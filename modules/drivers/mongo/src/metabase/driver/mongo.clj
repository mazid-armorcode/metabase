(ns metabase.driver.mongo
  "MongoDB Driver."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.common.table-rows-sample :as table-rows-sample]
   [metabase.driver.mongo.connection :as mongo.connection]
   [metabase.driver.mongo.conversion :as mongo.conversion]
   [metabase.driver.mongo.database :as mongo.db]
   [metabase.driver.mongo.execute :as mongo.execute]
   [metabase.driver.mongo.json]
   [metabase.driver.mongo.parameters :as mongo.params]
   [metabase.driver.mongo.query-processor :as mongo.qp]
   [metabase.driver.mongo.util :as mongo.util]
   [metabase.driver.util :as driver.u]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [taoensso.nippy :as nippy])
  (:import
   (com.mongodb.client MongoClient MongoDatabase)
   (org.bson.types Binary ObjectId)))

(set! *warn-on-reflection* true)

(comment metabase.driver.mongo.json/keep-me)

;; JSON Encoding (etc.)

;; Encode BSON undefined like `nil`
(json/add-encoder org.bson.BsonUndefined json/generate-nil)

(nippy/extend-freeze ObjectId :mongodb/ObjectId
  [^ObjectId oid data-output]
  (.writeUTF data-output (.toHexString oid)))

(nippy/extend-thaw :mongodb/ObjectId
  [data-input]
  (ObjectId. (.readUTF data-input)))

(driver/register! :mongo)

(defmethod driver/can-connect? :mongo
  [_ db-details]
  (mongo.connection/with-mongo-client [^MongoClient c db-details]
    (let [db-names (mongo.util/list-database-names c)
          db-name (mongo.db/db-name db-details)
          db (mongo.util/database c db-name)
          db-stats (mongo.util/run-command db {:dbStats 1} :keywordize true)]
      (and
       ;; 1. check db.dbStats command completes successfully
       (= (float (:ok db-stats))
          1.0)
       ;; 2. check the database is actually on the server
       ;; (this is required because (1) is true even if the database doesn't exist)
       (boolean (some #(= % db-name) db-names))))))

(defmethod driver/humanize-connection-error-message
  :mongo
  [_ message]
  (condp re-matches message
    #"^Timed out after \d+ ms while waiting for a server .*$"
    :cannot-connect-check-host-and-port

    #"^host and port should be specified in host:port format$"
    :invalid-hostname

    #"^Password can not be null when the authentication mechanism is unspecified$"
    :password-required

    #"^org.apache.sshd.common.SshException: No more authentication methods available$"
    :ssh-tunnel-auth-fail

    #"^java.net.ConnectException: Connection refused$"
    :ssh-tunnel-connection-fail

    #".*javax.net.ssl.SSLHandshakeException: PKIX path building failed.*"
    :certificate-not-trusted

    #".*MongoSocketReadException: Prematurely reached end of stream.*"
    :requires-ssl

    #".* KeyFactory not available"
    :unsupported-ssl-key-type

    #"java.security.InvalidKeyException: invalid key format"
    :invalid-key-format

    message))

;;; ### Syncing

(defmethod driver/sync-in-context :mongo
  [_ database do-sync-fn]
  (mongo.connection/with-mongo-client [_ database]
    (do-sync-fn)))

(defmethod driver/dbms-version :mongo
  [_driver database]
  (mongo.connection/with-mongo-database [db database]
    (let [build-info (mongo.util/run-command db {:buildInfo 1})
          version-array (get build-info "versionArray")
          sanitized-version-array (into [] (take-while nat-int?) version-array)]
      (when (not= (take 3 version-array) (take 3 sanitized-version-array))
        (log/warnf "sanitizing versionArray %s results in %s, losing information"
                   version-array sanitized-version-array))
      {:version (get build-info "version")
       :semantic-version sanitized-version-array})))

(defmethod driver/describe-database :mongo
  [_ database]
  (mongo.connection/with-mongo-database [^MongoDatabase db database]
    {:tables (set (for [collection (mongo.util/list-collection-names db)
                        :when (not (contains? #{"system.indexes" "system.views"} collection))]
                    {:schema nil, :name collection}))}))

(defmethod driver/describe-table-indexes :mongo
  [_ database table]
  (mongo.connection/with-mongo-database [^MongoDatabase db database]
    (let [collection (mongo.util/collection db (:name table))]
      (try
        (->> (mongo.util/list-indexes collection)
             (map (fn [index]
                    ;; for text indexes, column names are specified in the weights
                    (if (contains? index "textIndexVersion")
                      (get index "weights")
                      (get index "key"))))
             (map (comp name first keys))
             ;; mongo support multi key index, aka nested fields index, so we need to split the keys
             ;; and represent it as a list of field names
             (map #(if (str/includes? % ".")
                     {:type  :nested-column-index
                      :value (str/split % #"\.")}
                     {:type  :normal-column-index
                      :value %}))
             set)
        (catch com.mongodb.MongoCommandException e
          ;; com.mongodb.MongoCommandException: Command failed with error 166 (CommandNotSupportedOnView): 'Namespace test-data.orders_m is a view, not a collection' on server bee:27017. The full response is {"ok": 0.0, "errmsg": "Namespace test-data.orders_m is a view, not a collection", "code": 166, "codeName": "CommandNotSupportedOnView"}
          (if (= (.getErrorCode e) 166)
            #{}
            (throw e)))))))

(defn- describe-table-query-step
  "A single reduction step in the [[describe-table-query]] pipeline.
  At the end of each step the output is a combination of 'result' and 'item' objects. There is one 'result' for each
  path which has the most common type for that path. 'item' objects have yet to be aggregated into 'result' objects.
  Each object has the following keys:
  - result: true means the object represents a 'result', false means it represents a 'item' to be further processed.
  - path:   The path to the field in the document.
  - type:   If 'item', the type of the field's value. If 'result', the most common type for the field.
  - index:  If 'item', the index of the field in the parent object. If 'result', it is the minimum of such indices.
  - object: If 'item', the value of the field if it's an object. If 'result', it is null."
  [max-depth depth]
  [{"$facet"
    (cond-> {"results"    [{"$match" {"result" true}}]
             "newResults" [{"$match" {"result" false}}
                           {"$group" {"_id"   {"type"       "$type"
                                               "path"       "$path"}
                                      ;; count is zero if type is "null" so we only select "null" as the type if there
                                      ;; is no other type for the path
                                      "count" {"$sum" {"$cond" {"if"   {"$eq" ["$type" "null"]}
                                                                "then" 0
                                                                "else" 1}}}
                                      "index" {"$min" "$index"}}}
                           {"$sort" {"count" -1}}
                           {"$group" {"_id"        "$_id.path"
                                      "type"       {"$first" "$_id.type"}
                                      "index"      {"$min" "$index"}}}
                           {"$project" {"path"      "$_id"
                                        "type"       1
                                        "result"     {"$literal" true}
                                        "object"     nil
                                        "index"      1}}]}
      (not= depth max-depth)
      (assoc "nextItems" [{"$match" {"result" false, "object" {"$ne" nil}}}
                          {"$project" {"path" 1
                                       "kvs"  {"$map" {"input" {"$objectToArray" "$object"}
                                                       "as"    "item"
                                                       "in"    {"k"          "$$item.k"
                                                                ;; we only need v in the next step it's an object
                                                                "object"     {"$cond" {"if"   {"$eq" [{"$type" "$$item.v"} "object"]}
                                                                                       "then" "$$item.v"
                                                                                       "else" nil}}
                                                                "type"       {"$type" "$$item.v"}}}}}}
                          {"$unwind" {"path" "$kvs", "includeArrayIndex" "index"}}
                          {"$project" {"path"       {"$concat" ["$path" "." "$kvs.k"]}
                                       "type"       "$kvs.type"
                                       "result"     {"$literal" false}
                                       "index"      1
                                       "object"     "$kvs.object"}}]))}
   {"$project" {"acc" {"$concatArrays" (cond-> ["$results" "$newResults"]
                                         (not= depth max-depth)
                                         (conj "$nextItems"))}}}
   {"$unwind" "$acc"}
   {"$replaceRoot" {"newRoot" "$acc"}}])

(defn- describe-table-query
  "To understand how this works, see the comment block below for a rough translation of this query into Clojure."
  [& {:keys [collection-name sample-size max-depth]}]
  (let [start-n       (quot sample-size 2)
        end-n         (- sample-size start-n)
        sample        [{"$sort" {"_id" 1}}
                       {"$limit" start-n}
                       {"$unionWith"
                        {"coll" collection-name
                         "pipeline" [{"$sort" {"_id" -1}}
                                     {"$limit" end-n}]}}]
        initial-items [{"$project" {"path" "$ROOT"
                                    "kvs" {"$map" {"input" {"$objectToArray" "$$ROOT"}
                                                   "as"    "item"
                                                   "in"    {"k"          "$$item.k"
                                                            "object"     {"$cond" {"if"   {"$eq" [{"$type" "$$item.v"} "object"]}
                                                                                   "then" "$$item.v"
                                                                                   "else" nil}}
                                                            "type"       {"$type" "$$item.v"}}}}}}
                       {"$unwind" {"path" "$kvs", "includeArrayIndex" "index"}}
                       {"$project" {"path"       "$kvs.k"
                                    "result"     {"$literal" false}
                                    "type"       "$kvs.type"
                                    "index"      1
                                    "object"     "$kvs.object"}}]]
    (concat sample
            initial-items
            (mapcat #(describe-table-query-step max-depth %) (range (inc max-depth)))
            [{"$project" {"_id" 0, "path" "$path", "type" "$type", "index" "$index"}}])))

(comment
  ;; `describe-table-clojure` is a reference implementation for [[describe-table-query]] in Clojure.
  ;; It is almost logically equivalent, excluding minor details like how the sample is taken, and dealing with null
  ;; values. It is arguably easier to understand the Clojure version and translate it into MongoDB query language
  ;; than to understand the MongoDB query version directly.
  (defn describe-table-clojure [sample-data max-depth]
    (let [;; initial items is a list of maps, each map a field in a document in the sample
          initial-items (mapcat (fn [x]
                                  (for [[i [k v]] (map vector (range) x)]
                                    {:path   (name k)
                                     :object (if (map? v) v nil)
                                     :index  i
                                     :type   (type v)}))
                                sample-data)
          most-common (fn [xs]
                        (key (apply max-key val (frequencies xs))))]
      (:results (reduce
                 (fn [{:keys [results next-items]} depth]
                   {:results    (concat results
                                        (for [[path group] (group-by :path next-items)]
                                          {:path  path
                                           :type  (most-common (map :type group))
                                           :index (apply min (map :index group))}))
                    :next-items (when (not= depth max-depth)
                                  (->> (keep :object next-items)
                                       (mapcat (fn [x]
                                                 (for [[i [k v]] (map vector (range) x)]
                                                   {:path   (str (:path x) "." (name k))
                                                    :object (if (map? v) v nil)
                                                    :index  i
                                                    :type   (type v)})))))})
                 {:results [], :next-items initial-items}
                 (range (inc max-depth))))))
  ;; Example:
  (def sample-data
    [{:a 1 :b {:c "hello" :d [1 2 3]}}
     {:a 2 :b {:c "world"}}])
  (describe-table-clojure sample-data 0)
  ;; => ({:path "a", :type java.lang.Long, :index 0}
  ;;     {:path "b", :type clojure.lang.PersistentArrayMap, :index 1})
  (describe-table-clojure sample-data 1)
  ;; => ({:path "a", :type java.lang.Long, :index 0}
  ;;     {:path "b", :type clojure.lang.PersistentArrayMap, :index 1}
  ;;     {:path ".c", :type java.lang.String, :index 0}
  ;;     {:path ".d", :type clojure.lang.PersistentVector, :index 1})
  )

(def describe-table-query-depth
  "The depth of nested objects that [[describe-table-query]] will execute to. If set to 0, the query will only return the
  fields at the root level of the document. If set to K, the query will return fields at K levels of nesting beyond that.
  Setting its value involves a trade-off: the lower it is, the faster describe-table-query executes, but the more queries we might
  have to execute."
  ;; Cal 2024-09-15: I chose 100 as the limit because it's a pretty safe bet it won't be exceeded (the documents we've
  ;; seen on cloud are all <20 levels deep)
  ;; Case 2024-10-04: Sharded clusters seem to run into exponentially more work the bigger this is. Over 20 and this
  ;; risks never finishing.
  ;; From arakaki:
  ;;  > I think we can pick a max-depth that works well. I know that some other related tools set limits of 7 nested levels.
  ;;  > And that would be definitely ok for most.
  ;;  > If people have problems with that, I think we can make it configurable.
  7)

(mu/defn- describe-table :- [:sequential
                             [:map {:closed true}
                              [:path  driver-api/schema.common.non-blank-string]
                              [:type  driver-api/schema.common.non-blank-string]
                              [:index :int]]]
  "Queries the database, returning a list of maps with metadata for each field in the table (aka collection).
  Like `driver/describe-table` but the data is directly from the [[describe-table-query]] and needs further processing."
  [db table]
  (let [query (describe-table-query {:collection-name (:name table)
                                     :sample-size     (* table-rows-sample/nested-field-sample-limit 2)
                                     :max-depth       describe-table-query-depth})
        data  (:data (driver-api/process-query {:database (:id db)
                                                :type     "native"
                                                :native   {:collection (:name table)
                                                           :query      (json/encode query)}}))
        cols  (map (comp keyword :name) (:cols data))]
    (map #(zipmap cols %) (:rows data))))

(defn- db-type->base-type [db-type]
  ;; Mongo types from $type aggregation operation
  ;; https://www.mongodb.com/docs/manual/reference/operator/aggregation/type/#available-types
  (get {"double"     :type/Float
        "string"     :type/Text
        "object"     :type/Dictionary
        "array"      :type/Array
        "binData"    :type/MongoBinData
        ;; "uuid" is not a database type like the rest here
        ;; it's determined by the subtype of binData fields in describe-table
        "uuid"       :type/UUID
        "objectId"   :type/MongoBSONID
        "bool"       :type/Boolean
        ;; Mongo's date type is actually a date time so we can't map it to :type/Date
        ;; See documentation: https://www.mongodb.com/docs/manual/reference/bson-types/#std-label-document-bson-type-date
        "date"       :type/Instant
        "null"       :type/*
        "regex"      :type/*
        "dbPointer"  :type/*
        "javascript" :type/*
        "symbol"     :type/Text
        "int"        :type/Integer
        "timestamp"  :type/Instant
        "long"       :type/Integer
        "decimal"    :type/Decimal}
       db-type :type/*))

(defn- add-database-position
  "Adds :database-position to all fields. It starts at 0 and is ordered by a depth-first traversal of nested fields."
  [fields i]
  (->> fields
       ;; Previously database-position was set with Clojure according to the logic in this imperative pseudocode:
       ;; i = 0
       ;; for each row in sample:
       ;;   for each k,v in row:
       ;;     field.database-position = i
       ;;     i = i + 1
       ;;     for each k,v in field.nested-fields:
       ;;       field.database-position = i
       ;;       i = i + 1
       ;;       etc.
       ;; We can't match this logic exactly with a MongoDB query. We can get close though: index is the minimum index
       ;; of the key in the object over all documents in the sample. however, there can be more than one key that has
       ;; the same index. so name is used to keep the order stable.
       (sort-by (juxt :index :name))
       (reduce (fn [[fields i] field]
                 (let [field             (assoc field :database-position i)
                       i                 (inc i)
                       nested-fields     (:nested-fields field)
                       [nested-fields i] (if nested-fields
                                           (add-database-position nested-fields i)
                                           [nested-fields i])
                       field             (-> field
                                             (m/assoc-some :nested-fields nested-fields)
                                             (dissoc :index))]
                   [(conj fields field) i]))
               [#{} i])))

(defmethod driver/describe-table :mongo
  [_driver database table]
  (let [fields (->> (describe-table database table)
                    (map (fn [field]
                           (let [path (str/split (:path field) #"\.")
                                 name (last path)]
                             (cond-> {:name              name
                                      :database-type     (:type field)
                                      :base-type         (db-type->base-type (:type field))
                                      ; index is used by `set-database-position`, and not present in final result
                                      :index             (:index field)
                                      ; path is used to nest fields, and not present in final result
                                      :path              path}
                               (= name "_id")
                               (assoc :pk? true))))))
        ;; convert the flat list of fields into deeply-nested map.
        ;; `fields` and `:nested-fields` values are maps from name to field
        fields (reduce
                (fn [acc field]
                  (assoc-in acc (interpose :nested-fields (:path field)) (dissoc field :path)))
                {}
                fields)
        ;; replace maps from name to field with sets of fields and set visibility-type for parent fields
        fields (walk/postwalk (fn [x]
                                (cond-> x
                                  (and (map? x) (:nested-fields x))
                                  (assoc :visibility-type :details-only)

                                  (map? x)
                                  (m/update-existing :nested-fields #(set (vals %)))))
                              (set (vals fields)))
        [fields _] (add-database-position fields 0)]
    {:schema nil
     :name   (:name table)
     :fields fields}))

(doseq [[feature supported?] {:basic-aggregations              true
                              :expression-aggregations         true
                              :inner-join                      true
                              :left-join                       true
                              :nested-fields                   true
                              :native-parameter-card-reference false
                              :native-parameters               true
                              :nested-queries                  true
                              :set-timezone                    true
                              :standard-deviation-aggregations true
                              :test/jvm-timezone-setting       false
                              :identifiers-with-spaces         true
                              :saved-question-sandboxing       false
                              :expressions/date                true
                              :expressions/text                true
                              :expressions/datetime            true
                              ;; Index sync is turned off across the application as it is not used ATM.
                              :index-info                      false}]
  (defmethod driver/database-supports? [:mongo feature] [_driver _feature _db] supported?))

(defmethod driver/database-supports? [:mongo :schemas] [_driver _feat _db] false)

(defmethod driver/database-supports? [:mongo :window-functions/cumulative]
  [_driver _feat db]
  (-> ((some-fn :dbms-version :dbms_version) db)
      :semantic-version
      (driver.u/semantic-version-gte [5])))

(defmethod driver/database-supports? [:mongo :expressions]
  [_driver _feature db]
  (-> ((some-fn :dbms-version :dbms_version) db)
      :semantic-version
      (driver.u/semantic-version-gte [4 2])))

(defmethod driver/database-supports? [:mongo :date-arithmetics]
  [_driver _feature db]
  (-> ((some-fn :dbms-version :dbms_version) db)
      :semantic-version
      (driver.u/semantic-version-gte [5])))

(defmethod driver/database-supports? [:mongo :datetime-diff]
  [_driver _feature db]
  (-> ((some-fn :dbms-version :dbms_version) db)
      :semantic-version
      (driver.u/semantic-version-gte [5])))

(defmethod driver/database-supports? [:mongo :now]
  ;; The $$NOW aggregation expression was introduced in version 4.2.
  [_driver _feature db]
  (-> ((some-fn :dbms-version :dbms_version) db)
      :semantic-version
      (driver.u/semantic-version-gte [4 2])))

(defmethod driver/database-supports? [:mongo :native-requires-specified-collection]
  [_driver _feature _db]
  true)

;; We say Mongo supports foreign keys so that the front end can use implicit
;; joins. In reality, Mongo doesn't support foreign keys.
;; Only define an implementation for `:foreign-keys` if none exists already.
;; In test extensions we define an alternate implementation, and we don't want
;; to stomp over that if it was loaded already.
(when-not (get (methods driver/database-supports?) [:mongo :foreign-keys])
  (defmethod driver/database-supports? [:mongo :foreign-keys] [_driver _feature _db] true))

(defmethod driver/mbql->native :mongo
  [_ query]
  (mongo.qp/mbql->native query))

(defmethod driver/execute-reducible-query :mongo
  [_driver query _context respond]
  (assert (string? (get-in query [:native :collection])) "Cannot execute MongoDB query without a :collection name")
  (mongo.connection/with-mongo-client [_ (driver-api/database (driver-api/metadata-provider))]
    (mongo.execute/execute-reducible-query query respond)))

(defmethod driver/substitute-native-parameters :mongo
  [driver inner-query]
  (mongo.params/substitute-native-parameters driver inner-query))

(defmethod driver/db-start-of-week :mongo
  [_]
  :sunday)

(defn- get-id-field-id [table]
  (some (fn [field]
          (when (= (:name field) "_id")
            (:id field)))
        (driver-api/fields (driver-api/metadata-provider) (u/the-id table))))

(defmethod driver/table-rows-sample :mongo
  [_driver table fields rff opts]
  (driver-api/with-metadata-provider (:db_id table)
    (let [mongo-opts {:limit    table-rows-sample/nested-field-sample-limit
                      :order-by [[:desc [:field (get-id-field-id table) nil]]]}]
      (table-rows-sample/table-rows-sample table fields rff (merge mongo-opts opts)))))

(defn- encode-mongo
  "Converts a Clojure representation of a Mongo aggregation pipeline to a formatted JSON-like string"
  ([mgo] (encode-mongo mgo 0))
  ([mgo indent-level]
   (let [indent (apply str (repeat indent-level "  "))
         next-indent (str indent "  ")]
     (letfn [(encode-map [m next-indent]
               (if (empty? m) "{}"
                   (str "{\n"
                        (->> m
                             (map (fn [[k v]] (str next-indent "\"" (name k) "\": " (encode-mongo v (inc indent-level)))))
                             (str/join ",\n"))
                        "\n" indent "}")))
             (encode-vector [v next-indent]
               (if (empty? v) "[]"
                   (str "[\n"
                        (->> v
                             (map #(str next-indent (encode-mongo % (inc indent-level))))
                             (str/join ",\n"))
                        "\n" indent "]")))
             (encode-binary [bin]
               (if (= (.getType ^Binary bin) mongo.conversion/bson-uuid-type)
                 (str "UUID(\"" (mongo.conversion/bsonuuid->uuid bin) "\")")
                 (json/encode bin)))
             (encode-object-id [oid] (str "ObjectId(\"" (.toString ^ObjectId oid) "\")"))]
       (cond
         (map? mgo) (encode-map mgo next-indent)
         (vector? mgo) (encode-vector mgo next-indent)
         (instance? ObjectId mgo) (encode-object-id mgo)
         (instance? Binary mgo) (encode-binary mgo)
         :else (json/encode mgo))))))

(defmethod driver/prettify-native-form :mongo
  [_driver native-form]
  (try
    (encode-mongo native-form)
    (catch Throwable e
      (log/errorf "Unexpected error while encoding Mongo BSON query: %s" (ex-message e))
      (log/debugf e "Query:\n%s" native-form)
      native-form)))

;; Following code is using monger. Leaving it here for a reference as it could be transformed when there is need
;; for ssl experiments.
#_(comment
    (require '[clojure.java.io :as io]
             '[monger.credentials :as mcred])
    (import javax.net.ssl.SSLSocketFactory)

  ;; The following forms help experimenting with the behaviour of Mongo
  ;; servers with different configurations. They can be used to check if
  ;; the environment has been set up correctly (or at least according to
  ;; the expectations), as well as the exceptions thrown in various
  ;; constellations.

  ;; Test connection to Mongo with client and server SSL authentication.
    (let [ssl-socket-factory
          (driver.u/ssl-socket-factory
           :private-key (-> "ssl/mongo/metabase.key" io/resource slurp)
           :password "passw"
           :own-cert (-> "ssl/mongo/metabase.crt" io/resource slurp)
           :trust-cert (-> "ssl/mongo/metaca.crt" io/resource slurp))
          connection-options
          (mg/mongo-options {:ssl-enabled true
                             :ssl-invalid-host-name-allowed false
                             :socket-factory ssl-socket-factory})
          credentials
          (mcred/create "metabase" "admin" "metasample123")]
      (with-open [connection (mg/connect (mg/server-address "127.0.0.1")
                                         connection-options
                                         credentials)]
        (mg/get-db-names connection)))

  ;; Test what happens if the client only support server authentication.
    (let [server-auth-ssl-socket-factory
          (driver.u/ssl-socket-factory
           :trust-cert (-> "ssl/mongo/metaca.crt" io/resource slurp))
          server-auth-connection-options
          (mg/mongo-options {:ssl-enabled true
                             :ssl-invalid-host-name-allowed false
                             :socket-factory server-auth-ssl-socket-factory
                             :server-selection-timeout 200})
          credentials
          (mcred/create "metabase" "admin" "metasample123")]
      (with-open [server-auth-connection
                  (mg/connect (mg/server-address "127.0.0.1")
                              server-auth-connection-options
                              credentials)]
        (mg/get-db-names server-auth-connection)))

  ;; Test what happens if the client support only server authentication
  ;; with well known (default) CAs.
    (let [unauthenticated-connection-options
          (mg/mongo-options {:ssl-enabled true
                             :ssl-invalid-host-name-allowed false
                             :socket-factory (SSLSocketFactory/getDefault)
                             :server-selection-timeout 200})
          credentials
          (mcred/create "metabase" "admin" "metasample123")]
      (with-open [unauthenticated-connection
                  (mg/connect (mg/server-address "127.0.0.1")
                              unauthenticated-connection-options
                              credentials)]
        (mg/get-db-names unauthenticated-connection)))
    :.)
