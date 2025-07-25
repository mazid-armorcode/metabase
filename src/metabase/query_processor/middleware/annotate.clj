(ns metabase.query-processor.middleware.annotate
  "Middleware for annotating (adding type information to) the results of a query, under the `:cols` column."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [medley.core :as m]
   [metabase.analyze.core :as analyze]
   [metabase.config.core :as config]
   [metabase.driver.common :as driver.common]
   [metabase.legacy-mbql.schema :as mbql.s]
   [metabase.legacy-mbql.util :as mbql.u]
   [metabase.lib.binning :as lib.binning]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.lib.temporal-bucket :as lib.temporal-bucket]
   [metabase.lib.util.match :as lib.util.match]
   [metabase.models.humanization :as humanization]
   [metabase.query-processor.debug :as qp.debug]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.query-processor.middleware.annotate.legacy-helper-fns :as annotate.legacy-helper-fns]
   [metabase.query-processor.middleware.escape-join-aliases :as escape-join-aliases]
   [metabase.query-processor.reducible :as qp.reducible]
   [metabase.query-processor.store :as qp.store]
   [metabase.query-processor.util :as qp.util]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]))

(def ^:private Col
  "Schema for a valid map of column info as found in the `:cols` key of the results after this namespace has ran."
  ;; name and display name can be blank because some wacko DBMSes like SQL Server return blank column names for
  ;; unaliased aggregations like COUNT(*) (this only applies to native queries, since we determine our own names for
  ;; MBQL.)
  [:map
   [:name         :string]
   [:display_name :string]
   ;; type of the Field. For Native queries we look at the values in the first 100 rows to make an educated guess
   [:base_type    ::lib.schema.common/base-type]
   ;; effective_type, coercion, etc don't go here. probably best to rename base_type to effective type in the return
   ;; from the metadata but that's for another day
   ;; where this column came from in the original query.
   [:source       [:enum :aggregation :fields :breakout :native]]
   ;; a field clause that can be used to refer to this Field if this query is subsequently used as a source query.
   ;; Added by this middleware as one of the last steps.
   [:field_ref {:optional true} mbql.s/Reference]])

;; TODO - I think we should change the signature of this to `(column-info query cols rows)`
(defmulti column-info
  "Determine the `:cols` info that should be returned in the query results, which is a sequence of maps containing
  information about the columns in the results. Dispatches on query type. `results` is a map with keys `:cols` and,
  optionally, `:rows`, if available."
  {:arglists '([query results])}
  (fn [query _]
    (:type query)))

(defmethod column-info :default
  [{query-type :type, :as query} _]
  (throw (ex-info (tru "Unknown query type {0}" (pr-str query-type))
                  {:type  qp.error-type/invalid-query
                   :query query})))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      Adding :cols info for native queries                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(mu/defn- check-driver-native-columns
  "Double-check that the *driver* returned the correct number of `columns` for native query results."
  [cols :- [:maybe [:sequential [:map-of :any :any]]] rows]
  (when (seq rows)
    (let [expected-count (count cols)
          actual-count   (count (first rows))]
      (when-not (= expected-count actual-count)
        (throw (ex-info (str (tru "Query processor error: number of columns returned by driver does not match results.")
                             "\n"
                             (tru "Expected {0} columns, but first row of resuls has {1} columns."
                                  expected-count actual-count))
                        {:expected-columns (map :name cols)
                         :first-row        (first rows)
                         :type             qp.error-type/qp}))))))

(defn annotate-native-cols
  "Given metadata columns and the `entity_id` of the card, annotate the metadata with full details.

  Do not call this from other namespaces; it is exposed only to support
  [[metabase.query-processor.test-util/metadata->native-form]]."
  [cols card-entity-id]
  (let [unique-name-fn (mbql.u/unique-name-generator)]
    (mapv (fn [{col-name :name :as driver-col-metadata}]
            (let [col-name (name col-name)]
              (merge
               {:display_name (u/qualified-name col-name)
                :source       :native}
               ;; It is perfectly legal for a driver to return a column with a blank name; for example, SQL Server does
               ;; this for aggregations like `count(*)` if no alias is used. However, it is *not* legal to use blank
               ;; names in MBQL `:field` clauses, because `SELECT ""` doesn't make any sense. So if we can't return a
               ;; valid `:field`, omit the `:field_ref`.
               (when-not (str/blank? col-name)
                 (let [unique-col-name (unique-name-fn col-name)]
                   {:field_ref [:field unique-col-name {:base-type (or (:base_type driver-col-metadata)
                                                                       (:base-type driver-col-metadata))}]
                    :ident     (lib/native-ident unique-col-name card-entity-id)}))
               driver-col-metadata)))
          cols)))

(defmethod column-info :native
  [{{:keys [card-entity-id]} :info :as _query} {:keys [cols rows] :as _results}]
  (check-driver-native-columns cols rows)
  (annotate-native-cols cols card-entity-id))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       Adding :cols info for MBQL queries                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(mu/defn- join-with-alias :- [:maybe mbql.s/Join]
  [{:keys [joins source-query]} :- :map
   join-alias                   :- ::lib.schema.common/non-blank-string]
  (or (some
       (fn [{:keys [alias], :as join}]
         (when (= alias join-alias)
           join))
       joins)
      (when source-query
        (join-with-alias source-query join-alias))))

;;; --------------------------------------------------- Field Info ---------------------------------------------------

(defn- display-name-for-joined-field
  "Return an appropriate display name for a joined field. For *explicitly* joined Fields, the qualifier is the join
  alias; for implicitly joined fields, it is the display name of the foreign key used to create the join."
  [field-display-name {:keys [fk-field-id], join-alias :alias}]
  (let [qualifier (if fk-field-id
                    ;; strip off trailing ` id` from FK display name
                    (str/replace (:display-name (lib.metadata/field (qp.store/metadata-provider) fk-field-id))
                                 #"(?i)\sid$"
                                 "")
                    join-alias)]
    (format "%s → %s" qualifier field-display-name)))

(defn- datetime-arithmetics?
  "Helper for [[infer-expression-type]]. Returns true if a given clause returns a :type/DateTime type."
  [clause]
  (lib.util.match/match-one clause
    #{:datetime-add :datetime-subtract :relative-datetime}
    true

    [:field _ (_ :guard :temporal-unit)]
    true
    [:expression _ (_ :guard :temporal-unit)]
    true

    :+
    (some (partial mbql.u/is-clause? :interval) (rest clause))

    _ false))

(declare col-info-for-field-clause)

(def type-info-columns
  "Columns to select from a field to get its type information without getting information that is specific to that
  column."
  [:base_type :effective_type :coercion_strategy :semantic_type])

(defn infer-expression-type
  "Infer base-type/semantic-type information about an `expression` clause."
  [expression]
  (cond
    (string? expression)
    {:base_type :type/Text}

    (number? expression)
    {:base_type :type/Number}

    (boolean? expression)
    {:base_type :type/Boolean}

    (mbql.u/is-clause? :value expression)
    (let [[_ value options] expression]
      (or (not-empty (select-keys options type-info-columns))
          (select-keys (infer-expression-type value) type-info-columns)))

    (mbql.u/is-clause? :field expression)
    (col-info-for-field-clause {} expression)

    (mbql.u/is-clause? :coalesce expression)
    (select-keys (infer-expression-type (second expression)) type-info-columns)

    (mbql.u/is-clause? :length expression)
    {:base_type :type/BigInteger}

    (mbql.u/is-clause? :case expression)
    (let [[_ clauses] expression]
      (some
       (fn [[_ expression]]
         ;; get the first non-nil val
         (when (and (not= expression nil)
                    (or (not (mbql.u/is-clause? :value expression))
                        (let [[_ value] expression]
                          (not= value nil))))
           (select-keys (infer-expression-type expression) type-info-columns)))
       clauses))

    (mbql.u/is-clause? :convert-timezone expression)
    {:converted_timezone (nth expression 2)
     :base_type          :type/DateTime}

    (datetime-arithmetics? expression)
    ;; make sure converted_timezone survived if we do nested datetime operations
    ;; FIXME: this does not preverse converted_timezone for cases nested expressions
    ;; i.e:
    ;; {"expression" {"converted-exp" [:convert-timezone "created-at" "Asia/Ho_Chi_Minh"]
    ;;                "date-add-exp"  [:datetime-add [:expression "converted-exp"] 2 :month]}}
    ;; The converted_timezone metadata added for "converted-exp" will not be brought over
    ;; to ["date-add-exp"].
    ;; maybe this `infer-expression-type` should takes an `inner-query` and look up the
    ;; source expresison as well?
    (merge (select-keys (infer-expression-type (second expression)) [:converted_timezone])
           {:base_type :type/DateTime})

    (mbql.u/is-clause? mbql.s/string-functions expression)
    {:base_type :type/Text}

    (mbql.u/is-clause? mbql.s/numeric-functions expression)
    {:base_type :type/Float}

    (mbql.u/is-clause? mbql.s/boolean-functions expression)
    {:base_type :type/Boolean}

    :else
    {:base_type :type/*}))

(defn- fe-friendly-expression-ref
  "Apparently the FE viz code breaks for pivot queries if `field_ref` comes back with extra 'non-traditional' MLv2
  info (`:base-type` or `:effective-type` in `:expression`), so we better just strip this info out to be sure. If you
  don't believe me remove this and run `e2e/test/scenarios/visualizations-tabular/pivot_tables.cy.spec.js` and you
  will see."
  [a-ref]
  (let [a-ref (mbql.u/remove-namespaced-options a-ref)]
    (lib.util.match/replace a-ref
      [:expression expression-name (opts :guard (some-fn :base-type :effective-type))]
      (let [fe-friendly-opts (dissoc opts :base-type :effective-type)]
        (if (seq fe-friendly-opts)
          [:expression expression-name fe-friendly-opts]
          [:expression expression-name])))))

(defn- col-info-for-expression
  [inner-query [_expression expression-name {:keys [temporal-unit] :as _opts} :as clause]]
  (merge
   (infer-expression-type (mbql.u/expression-with-name inner-query expression-name))
   {:name            expression-name
    :display_name    expression-name
    :ident           (get-in inner-query [:expression-idents expression-name])
    :field_ref       (fe-friendly-expression-ref clause)}
   (when temporal-unit
     {:unit temporal-unit})))

(mu/defn- col-info-for-field-clause*
  [{:keys [source-metadata], :as inner-query} [_ id-or-name opts :as clause] :- mbql.s/field]
  (let [stage-is-from-source-card? (:qp/stage-had-source-card inner-query)
        join                       (when (:join-alias opts)
                                     (join-with-alias inner-query (:join-alias opts)))
        join-is-at-current-level?  (some #(= (:alias %) (:join-alias opts)) (:joins inner-query))
        ;; record additional information that may have been added by middleware. Sometimes pre-processing middleware
        ;; needs to add extra info to track things that it did (e.g. the
        ;; [[metabase.query-processor.middleware.add-dimension-projections]] pre-processing middleware adds keys to
        ;; track which Fields it adds or needs to remap, and then the post-processing middleware does the actual
        ;; remapping based on that info)
        namespaced-options         (not-empty (into {}
                                                    (filter (fn [[k _v]]
                                                              (and (keyword? k) (namespace k))))
                                                    opts))]
    ;; TODO -- I think we actually need two `:field_ref` columns -- one for referring to the Field at the SAME
    ;; level, and one for referring to the Field from the PARENT level.
    (cond-> {:field_ref (-> clause
                            mbql.u/remove-namespaced-options
                            (mbql.u/update-field-options dissoc :ident))}
      (:base-type opts)
      (assoc :base_type (:base-type opts))

      namespaced-options
      (assoc :options namespaced-options)

      (string? id-or-name)
      (merge (or (some-> (some #(when (= (:name %) id-or-name) %) source-metadata)
                         (dissoc :field_ref))
                 (merge
                  {:name         id-or-name
                   :display_name (humanization/name->human-readable-name id-or-name)}
                  (when-let [ident (some-> (some #(when (= (:name %) id-or-name) %) (:source-metadata join))
                                           :ident)]
                    {:ident ident}))))

      (integer? id-or-name)
      (merge (let [{:keys [parent-id], :as field} (lib.metadata/field (qp.store/metadata-provider) id-or-name)]
               #_{:clj-kondo/ignore [:deprecated-var]}
               (if-not parent-id
                 (qp.store/->legacy-metadata field)
                 (let [parent (col-info-for-field-clause inner-query [:field parent-id nil])]
                   (-> (update field :name #(str (:name parent) \. %))
                       qp.store/->legacy-metadata)))))

      (:binning opts)
      (-> (assoc :binning_info (-> (:binning opts)
                                   (set/rename-keys {:strategy :binning-strategy})
                                   u/snake-keys))
          (assoc :was_binned true))

      (:temporal-unit opts)
      (assoc :unit (:temporal-unit opts))

      (or (:join-alias opts) (:alias join))
      (assoc :source_alias (or (:join-alias opts) (:alias join)))

      join
      (update :display_name display-name-for-joined-field join)

      ;; When a new join is introduced, wrap all the input :idents with that join.
      ;; If the clause is a number, then it needs re-joining. If it's a string, then any join clause should already
      ;; be included.
      (and join (or (integer? id-or-name)
                    join-is-at-current-level?))
      (update :ident lib/explicitly-joined-ident (:ident join))

      ;; Sometimes we see an implicitly joined ref with `:source-field`, but no corresponding join clause.
      ;; In that case, look up the FK's ident and use it to adjust this column's ident.
      (and (:source-field opts)
           (not join))
      (update :ident lib/implicitly-joined-ident
              (:ident (lib.metadata/field (qp.store/metadata-provider) (:source-field opts))))

      ;; Join with fk-field-id => IMPLICIT JOIN
      ;; Join w/o fk-field-id  => EXPLICIT JOIN
      (:fk-field-id join)
      (assoc :fk_field_id (:fk-field-id join))

      ;; For IMPLICIT joins, remove `:join-alias` in the resulting Field ref -- it got added there during
      ;; preprocessing by us, and wasn't there originally. Make sure the ref has `:source-field`.
      (:fk-field-id join)
      (update :field_ref mbql.u/update-field-options (fn [opts]
                                                       (-> opts
                                                           (dissoc :join-alias)
                                                           (assoc :source-field (:fk-field-id join)))))

      ;; If source Field (for an IMPLICIT join) is specified in either the field ref or matching join, make sure we
      ;; return it as `fk_field_id`. (Not sure what situations it would actually be present in one but not the other
      ;; -- but it's in the tests :confused:)
      (or (:source-field opts)
          (:fk-field-id join))
      (assoc :fk_field_id (or (:source-field opts)
                              (:fk-field-id join)))

      ;; If the source query is from a saved question, remove the join alias as the caller should not be aware of joins
      ;; happening inside the saved question. The `not join-is-at-current-level?` check is to ensure that we are not
      ;; removing `:join-alias` from fields from the right side of the join.
      (and stage-is-from-source-card?
           (not join-is-at-current-level?))
      (update :field_ref mbql.u/update-field-options dissoc :join-alias))))

(mu/defn- col-info-for-field-clause :- [:map
                                        [:field_ref mbql.s/Field]]
  "Return results column metadata for a `:field` or `:expression` clause, in the format that gets returned by QP results"
  [inner-query :- :map
   clause      :- mbql.s/Field]
  (lib.util.match/match-one clause
    :expression
    (col-info-for-expression inner-query &match)

    :field
    (col-info-for-field-clause* inner-query &match)

    ;; we should never reach this if our patterns are written right so this is more to catch code mistakes than
    ;; something the user should expect to see
    _
    (throw (ex-info (tru "Don''t know how to get information about Field: {0}" &match)
                    {:field &match}))))

(mu/defn- col-info-for-aggregation-clauses
  "Return appropriate (legacy) column metadata for the `:aggregation` clauses of this legacy inner query, in order."
  [inner-query]
  ;; for some mystery reason it seems like the annotate code uses `:long` style display names when something appears
  ;; inside an aggregation clause, e.g.
  ;;
  ;;    Distinct values of Category → Name
  ;;
  ;; but `:default` style names when they appear on their own or in breakouts, e.g.
  ;;
  ;;    Name
  ;;
  ;; why is this the case? Who knows! But that's the old pre-MLv2 behavior. I think we should try to fix it, but it's
  ;; probably going to involve updating a ton of tests that encode the old behavior.
  (when (seq (:aggregation inner-query))
    (let [query (annotate.legacy-helper-fns/legacy-inner-query->mlv2-query inner-query)]
      (binding [lib.metadata.calculation/*display-name-style* :long]
        (for [agg-metadata (lib/aggregations-metadata query -1)]
          (-> (m/remove-keys #(= "lib" (namespace %)) agg-metadata)
              (update-keys u/->snake_case_en)))))))

(def ^:private LegacyInnerQuery
  [:and
   :map
   [:fn
    {:error/message "legacy inner-query with :source-table or :source-query"}
    (some-fn :source-table :source-query)]])

;; TODO: This function is janky, and converts isolated legacy aggregations and expressions into pMBQL.
;; The right approach here is to adjust the annotate middleware to ensure that all aggregations and expressions
;; already have the right column names present in their options. Then this function can be deprecated, and replaced
;; with another helper (probably in the driver or MBQL utils?) that grabs the :name for a clause from its options.
(mu/defn aggregation-name :- ::lib.schema.common/non-blank-string
  "Return an appropriate aggregation name/alias *used inside a query* for an `:aggregation` subclause (an aggregation
  or expression). Takes an options map as schema won't support passing keypairs directly as a varargs.

  These names are also used directly in queries, e.g. in the equivalent of a SQL `AS` clause."
  [inner-query :- LegacyInnerQuery
   ag-clause]
  (lib/column-name (annotate.legacy-helper-fns/legacy-inner-query->mlv2-query inner-query) (lib/->pMBQL ag-clause)))

;;; ----------------------------------------- Putting it all together (MBQL) -----------------------------------------

(defn- check-correct-number-of-columns-returned [returned-mbql-columns results]
  (let [expected-count (count returned-mbql-columns)
        actual-count   (count (:cols results))]
    (when (seq (:rows results))
      (when-not (= expected-count actual-count)
        (throw
         (ex-info (str (tru "Query processor error: mismatched number of columns in query and results.")
                       " "
                       (tru "Expected {0} fields, got {1}" expected-count actual-count)
                       "\n"
                       (tru "Expected: {0}" (mapv :name returned-mbql-columns))
                       "\n"
                       (tru "Actual: {0}" (vec (:columns results))))
                  {:expected returned-mbql-columns
                   :actual   (:cols results)}))))))

(mu/defn- cols-for-fields
  [{:keys [fields], :as inner-query} :- :map]
  (for [field fields]
    (assoc (col-info-for-field-clause inner-query field)
           :source :fields)))

(mu/defn- cols-for-ags-and-breakouts
  [{breakouts :breakout, breakout-idents :breakout-idents, :as inner-query} :- :map]
  (concat
   (map-indexed (fn [i breakout]
                  (assoc (col-info-for-field-clause inner-query breakout)
                         :source :breakout
                         :ident  (get breakout-idents i)))
                breakouts)
   (for [[i aggregation] (m/indexed (col-info-for-aggregation-clauses inner-query))]
     (assoc aggregation
            :source            :aggregation
            :field_ref         [:aggregation i]
            :aggregation_index i))))

(mu/defn cols-for-mbql-query
  "Return results metadata about the expected columns in an 'inner' MBQL query."
  [inner-query :- :map]
  (concat
   (cols-for-ags-and-breakouts inner-query)
   (cols-for-fields inner-query)))

(mu/defn- merge-source-metadata-col :- [:maybe :map]
  [source-metadata-col :- [:maybe :map]
   col                 :- [:maybe :map]]
  (merge
   {} ; ensure the type is a plain map rather than a Toucan 2 instance or whatever
   (when-let [field-id (:id source-metadata-col)]
     (-> (lib.metadata/field (qp.store/metadata-provider) field-id)
         #_{:clj-kondo/ignore [:deprecated-var]}
         qp.store/->legacy-metadata))
   source-metadata-col
   col
   ;; pass along the unit from the source query metadata if the top-level metadata has unit `:default`. This way the
   ;; frontend will display the results correctly if bucketing was applied in the nested query, e.g. it will format
   ;; temporal values in results using that unit
   (when (= (:unit col) :default)
     (select-keys source-metadata-col [:unit]))

   ;; Idents are sometimes preserved from the source, and sometimes replaced.
   ;; TODO: Expressions that simply wrap a field ref keep their IDs and might also need special handling here?
   (when-let [ident (or ;; If the new col is an aggregation or breakout, use its ident for sure.
                     (when (#{:aggregation :breakout} (:source col))
                       (:ident col))
                        ;; Otherwise prefer the ident of the source, if any.
                        ;; If col is not an aggregation or breakout, then its ident probably comes from a naive re-read
                        ;; by Field ID, and we don't want to overwrite the ident of a breakout or joined field from an
                        ;; earlier stage.
                     (:ident source-metadata-col)
                        ;; If the source doesn't have an ident, keep col's ident.
                     (:ident col))]
     {:ident ident})))

(defn- maybe-merge-source-metadata
  "Merge information from `source-metadata` into the returned `cols` for queries that return the columns of a source
  query as-is (i.e., the parent query does not have breakouts, aggregations, or an explicit`:fields` clause --
  excluding the one added automatically by `add-source-metadata`)."
  [source-metadata cols]
  (if (= (count cols) (count source-metadata))
    (map merge-source-metadata-col source-metadata cols)
    cols))

(defn- flow-field-metadata
  "Merge information about fields from `source-metadata` into the returned `cols`."
  [source-metadata cols model?]
  (let [by-key (m/index-by (comp qp.util/field-ref->key :field_ref) source-metadata)]
    (for [{:keys [field_ref source] :as col} cols]
     ;; aggregation fields are not from the source-metadata and their field_ref
     ;; are not unique for a nested query. So do not merge them otherwise the metadata will be messed up.
     ;; TODO: I think the best option here is to introduce a parent_field_ref so that
     ;; we could preserve metadata such as :sematic_type or :unit from the source field.
      (if-let [source-metadata-for-field (and (not= :aggregation source)
                                              (get by-key (qp.util/field-ref->key field_ref)))]
        (let [col' (merge col
                          (when model?
                            (select-keys source-metadata-for-field qp.util/preserved-keys)))]
          (merge-source-metadata-col source-metadata-for-field col'))
        col))))

(declare mbql-cols)

(defn- cols-for-source-query
  [{:keys [source-metadata source-query/entity-id]
    {native-source-query :native, :as source-query} :source-query}
   {{:keys [card-entity-id]} :info :as outer-query}
   results]
  (let [columns (if native-source-query
                  (maybe-merge-source-metadata
                   source-metadata (column-info {:type :native
                                                 ;; If the native stage came from a source card, we use its entity_id.
                                                 ;; But if that isn't set, then this native stage is an inner stage of
                                                 ;; a single card, and we use its entity_id for native idents.
                                                 :info {:card-entity-id (or entity-id card-entity-id)}}
                                                results))
                  (mbql-cols source-query outer-query results))]
    (qp.util/combine-metadata columns source-metadata)))

(defn- idents-for-model
  [cols card-entity-id]
  ;; Safety checks during dev and test.
  (when-not config/is-prod?
    (when-not (every? :ident cols)
      (throw (ex-info "idents-for-model with missing idents" {:cols           cols
                                                              :card-entity-id card-entity-id})))
    (when-not (string? card-entity-id)
      (throw (ex-info "idents-for-model with blank card-entity-id" {:cols cols}))))

  (for [col cols]
    (cond-> col
      ;; Check that the ident isn't already set for the source model, to avoid "double-bagging".
      ;; That only applies to `:source :fields` columns though - not expressions, aggregations, etc.
      (not (lib/valid-model-ident? col card-entity-id))
      (lib/add-model-ident card-entity-id))))

(defn mbql-cols
  "Return the `:cols` result metadata for an 'inner' MBQL query based on the fields/breakouts/aggregations in the
  query."
  [{:keys [source-metadata source-query fields]
    :source-query/keys [entity-id model?]
    :as inner-query}
   outer-query
   results]
  (let [cols (cols-for-mbql-query inner-query)]
    (cond
      source-query
      (cond-> (cols-for-source-query inner-query outer-query results)
        true       (u/prog1 #_sq-cols (qp.debug/debug> [`cols-for-source-query <>]))
        model?     ((fn [sq-cols]
                      (u/prog1 (idents-for-model sq-cols entity-id)
                        (qp.debug/debug> [`idents-for-model entity-id sq-cols '=>
                                          ^{:portal.viewer/default :portal.viewer/diff}
                                          [(vec sq-cols) (vec <>)]]))))
        (seq cols) ((fn [sq-cols]
                      (u/prog1 (flow-field-metadata sq-cols cols model?)
                        (qp.debug/debug> [`flow-field-metadata 'sq-cols sq-cols 'cols cols 'model? model? '=>
                                          ^{:portal.viewer/default :portal.viewer/diff}
                                          [(vec sq-cols) (vec <>)]])))))

      (every? #(lib.util.match/match-one % [:field (field-name :guard string?) _] field-name) fields)
      (maybe-merge-source-metadata source-metadata cols)

      :else
      cols)))

(defn- restore-cumulative-aggregations
  [{aggregations :aggregation breakouts :breakout :as inner-query} replaced-indexes]
  (let [offset   (count breakouts)
        restored (reduce (fn [aggregations index]
                           (lib.util.match/replace-in aggregations [(- index offset)]
                             [:count]       [:cum-count]
                             [:count field] [:cum-count field]
                             [:sum field]   [:cum-sum field]))
                         (vec aggregations)
                         replaced-indexes)]
    (assoc inner-query :aggregation restored)))

(defmethod column-info :query
  [{inner-query :query,
    replaced-indexes :metabase.query-processor.middleware.cumulative-aggregations/replaced-indexes
    :as outer-query}
   results]
  (u/prog1 (mbql-cols (cond-> inner-query
                        replaced-indexes (restore-cumulative-aggregations replaced-indexes))
                      outer-query
                      results)
    (check-correct-number-of-columns-returned <> results)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Deduplicating names                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ColsWithUniqueNames
  [:and
   [:maybe [:sequential Col]]
   [:fn
    {:error/message ":cols with unique names"}
    (fn [cols]
      (u/empty-or-distinct? (map :name cols)))]])

(mu/defn- deduplicate-cols-names :- ColsWithUniqueNames
  [cols :- [:sequential Col]]
  (map (fn [col unique-name]
         (assoc col :name unique-name))
       cols
       (mbql.u/uniquify-names (map :name cols))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           add-column-info middleware                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- merge-col-metadata
  "Merge a map from `:cols` returned by the driver with the column metadata determined by the logic above."
  [our-col-metadata driver-col-metadata]
  ;; 1. Prefer our `:name` if it's something different that what's returned by the driver
  ;;    (e.g. for named aggregations)
  ;; 2. Prefer our inferred base type if the driver returned `:type/*` and ours is more specific
  ;; 3. Then, prefer any non-nil keys returned by the driver
  ;; 4. Finally, merge in any of our other keys
  (let [non-nil-driver-col-metadata (m/filter-vals some? driver-col-metadata)
        our-base-type               (when (= (:base_type driver-col-metadata) :type/*)
                                      (u/select-non-nil-keys our-col-metadata [:base_type]))
        ;; whatever type comes back from the query is by definition the effective type, fallback to our effective
        ;; type, fallback to the base_type
        effective-type              (when-let [db-base (or (:base_type driver-col-metadata)
                                                           (:effective_type our-col-metadata)
                                                           (:base_type our-col-metadata))]
                                      {:effective_type db-base})
        our-name                    (u/select-non-nil-keys our-col-metadata [:name])]
    (merge our-col-metadata
           non-nil-driver-col-metadata
           our-base-type
           our-name
           effective-type)))

(defn- merge-cols-returned-by-driver
  "Merge our column metadata (`:cols`) derived from logic above with the column metadata returned by the driver. We'll
  prefer the values in theirs to ours. This is important for wacky drivers like GA that use things like native
  metrics, which we have no information about.

  It's the responsibility of the driver to make sure the `:cols` are returned in the correct number and order."
  [our-cols cols-returned-by-driver]
  (if (seq cols-returned-by-driver)
    (mapv merge-col-metadata our-cols cols-returned-by-driver)
    our-cols))

(mu/defn merged-column-info :- ColsWithUniqueNames
  "Returns deduplicated and merged column metadata (`:cols`) for query results by combining (a) the initial results
  metadata returned by the driver's impl of `execute-reducible-query` and (b) column metadata inferred by logic in
  this namespace."
  [query {cols-returned-by-driver :cols, :as result} :- [:maybe :map]]
  (->> (merge-cols-returned-by-driver (column-info query result) cols-returned-by-driver)
       (deduplicate-cols-names)
       (map lib.temporal-bucket/ensure-temporal-unit-in-display-name)
       (map lib.binning/ensure-binning-in-display-name)))

(defn base-type-inferer
  "Native queries don't have the type information from the original `Field` objects used in the query.
  If the driver returned a base type more specific than :type/*, use that; otherwise look at the sample
  of rows and infer the base type based on the classes of the values"
  [{:keys [cols]}]
  (apply analyze/col-wise
         (for [{driver-base-type :base_type} cols]
           (if (contains? #{nil :type/*} driver-base-type)
             (driver.common/values->base-type)
             (analyze/constant-fingerprinter driver-base-type)))))

;; TODO: Start recording the :ident of the inner column under a different key.
;; TODO: Use `:ident`s for matching up model metadata!
(defn- merge-model-metadata
  [query-metadata model-metadata _card-entity-id]
  (qp.util/combine-metadata query-metadata model-metadata)
  ;; FIXME: Breadcrumbs during development.
  #_(qp.util/combine-metadata
     (for [{:keys [source name display_name] :as col} query-metadata]
       (cond-> col
         (and (= source :native)
              (= name display_name)) (assoc :display_name (humanization/name->human-readable-name name))))
     model-metadata))

(defn- add-column-info-xform
  [query metadata rf]
  (qp.reducible/combine-additional-reducing-fns
   rf
   [(base-type-inferer metadata)
    ((take 1) conj)]
   (fn combine [result base-types truncated-rows]
     (let [metadata (update metadata :cols
                            (comp #(annotate-native-cols % (-> query :info :card-entity-id))
                                  (fn [cols]
                                    (map (fn [col base-type]
                                           (-> col
                                               (assoc :base_type base-type)
                                               ;; annotate will add a field ref with type info
                                               (dissoc :field_ref)))
                                         cols
                                         base-types))))]
       (rf (cond-> result
             (map? result)
             (assoc-in [:data :cols]
                       (merged-column-info
                        query
                        (assoc metadata :rows truncated-rows)))))))))

(defn add-column-info
  "Middleware for adding type information about the columns in the query results (the `:cols` key)."
  [{query-type :type, :as query
    {:keys [card-entity-id metadata/model-metadata alias/escaped->original]} :info} rff]
  (fn add-column-info-rff* [metadata]
    (qp.debug/debug> (list `add-column-info query metadata))
    (if (and (= query-type :query)
             ;; we should have type metadata eiter in the query fields
             ;; or in the result metadata for the following code to work
             (or (->> query :query keys (some #{:aggregation :breakout :fields}))
                 (every? :base_type (:cols metadata))))
      (let [query    (cond-> query
                       (seq escaped->original) ;; if we replaced aliases, restore them
                       (escape-join-aliases/restore-aliases escaped->original))
            metadata (cond-> (assoc metadata :cols (merged-column-info query metadata))
                       (seq model-metadata)
                       (update :cols merge-model-metadata model-metadata card-entity-id))]
        (qp.debug/debug> (list `add-column-info:mbql query model-metadata '=> metadata))
        (rff metadata))
      ;; rows sampling is only needed for native queries! TODO ­ not sure we really even need to do for native
      ;; queries...
      (let [metadata (cond-> (update metadata :cols annotate-native-cols card-entity-id)
                       ;; annotate-native-cols ensures that column refs are present which we need to match metadata
                       (seq model-metadata)
                       (update :cols merge-model-metadata model-metadata card-entity-id)
                       ;; but we want those column refs removed since they have type info which we don't know yet
                       :always
                       (update :cols (fn [cols] (map #(dissoc % :field_ref) cols))))]
        (qp.debug/debug> (list `add-column-info:native '=> metadata))
        (add-column-info-xform query metadata (rff metadata))))))
