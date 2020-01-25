(ns cloudnormity.api
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]))


(def default-tracking-attr :cloudnormity/conformed)


(defn tx!
  [conn tx-data]
  (d/transact conn {:tx-data tx-data}))

(defn has-attribute?
  "Returns true if a database has an attribute named attr-name"
  [conn attr-name]
  (-> (d/db conn)
      (d/pull '[:db/id] [:db/ident attr-name])
      :db/id
      boolean))

(defn ensure-cloudnormity-schema
  [conn tracking-attr]
  (when-not (has-attribute? conn tracking-attr)
    (let [tx-data [{:db/ident tracking-attr
                    :db/valueType :db.type/keyword
                    :db/cardinality :db.cardinality/one
                    :db/doc "Conformed norm name"}]]
      (tx! conn tx-data))))

(defn has-norm?
  "Returns true if a database has an attribute named attr-name"
  [conn tracking-attr norm-map]
  (seq (d/q '[:find ?e
              :in $ ?tracking-attr ?norm-name
              :where [?e ?tracking-attr ?norm-name]]
            (d/db conn)
            tracking-attr
            (:name norm-map))))

(defn read-resource
  "Reads and returns data from a resource containing edn text."
  [resource-name]
  (with-open [reader (->> (io/resource resource-name)
                          io/reader
                          (java.io.PushbackReader.))]
    (edn/read reader)))

(defn eval-tx-fn
  [conn {:keys [tx-fn] :as norm-map}]
  (try ((requiring-resolve tx-fn) conn)
       (catch Throwable t
         (throw (ex-info (str "Exception evaluating " tx-fn)
                         {:exception t})))))

(defn tx-data-for
  [conn {:keys [tx-data tx-fn tx-resource] :as norm-map}]
  (cond
    tx-data     tx-data
    tx-resource (read-resource tx-resource)
    tx-fn       (eval-tx-fn conn norm-map)))

(defn transact-norm
  [conn norm-map]
  (let [tx-data (tx-data-for conn norm-map)]
   (tx! conn tx-data)))

(defn conforms-to?
  ([conn norm-map]
   (conforms-to? conn default-tracking-attr norm-map))
  ([conn tracking-attr norm-map]
   (and (has-norm? conn tracking-attr norm-map)
        (:once norm-map))))

(defn ensure-norms
  [conn norm-maps]
  ;; TODO Something more useful here than `nil` return,
  ;; report of succeeded/failed norms?
  (doseq [norm-map norm-maps]
    (when-not (conforms-to? conn norm-map)
      (transact-norm conn norm-map))))

(defn ensure-conforms
  ([conn norm-maps]
   (ensure-conforms conn norm-maps (map :name norm-maps)))
  ([conn norm-maps norm-names]
   (ensure-conforms conn default-tracking-attr norm-maps norm-names))
  ([conn tracking-attr norm-maps norm-names]
   (ensure-cloudnormity-schema conn tracking-attr)
   (let [ensurable-norms (filter (comp (set norm-names) :name)
                                 norm-maps)]
     (ensure-norms conn ensurable-norms))))