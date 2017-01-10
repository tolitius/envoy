(ns envoy.core
  (:require [cheshire.core :as json]
            [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop go <! >! >!! alt! chan]]
            [org.httpkit.client :as http]
            [envoy.tools :refer [merge-maps map->props props->map remove-nils cpath->kpath]])
  (:import [javax.xml.bind DatatypeConverter]))

(defn- recurse [path]
  (str path "?recurse"))

(defn- index-of [resp]
  (-> resp
      :headers
      :x-consul-index))

(defn- with-ops [ops]
  {:query-params (remove-nils ops)})

(defn- read-index
  ([path]
   (read-index path {}))
  ([path ops]
  (-> (http/get path (with-ops ops))
      index-of)))

(defn- fromBase64 [s]
  (String. (DatatypeConverter/parseBase64Binary s)))

(defn- read-values 
  ([resp]
   (read-values resp true))
  ([{:keys [body]} to-keys?]
   ;; (println "body => " body)
   (into {}
         (for [{:keys [Key Value]} (json/parse-string body true)]
           [(if to-keys? (keyword Key) Key)
            (when Value (fromBase64 Value))]))))

(defn put 
  ([path v]
   (put path v {}))
  ([path v ops]
   ;; (println "@(http/put" path (merge {:body v} (with-ops ops)))
   @(http/put path (merge {:body v} (with-ops ops)))))

(defn delete
  ([path]
   (delete path {}))
  ([path ops]
   @(http/delete (recurse path)
                 (with-ops ops))))

(defn get-all
  ([path]
   (get-all path {}))
  ([path {:keys [keywordize?] :as ops
          :or {keywordize? true}}]
   (-> @(http/get (recurse path)
                  (with-ops (dissoc ops :keywordize?)))
       (read-values keywordize?))))

(defn- start-watcher
  ([path fun stop?]
   (start-watcher path fun stop? {}))
  ([path fun stop? ops]
   (let [ch (chan)]
     (go-loop [index nil current (get-all path)]
              (http/get path
                        (with-ops (merge ops
                                         {:index (or index (read-index path ops))}))
                        #(>!! ch %))
              (alt!
                stop? ([_]
                       (prn "stopping" path "watcher"))
                ch ([resp]
                    (let [new-idx (index-of resp)
                          new-vs (read-values resp)]
                      (when (and index (not= new-idx index))               ;; first time there is no index
                        (when-let [changes (first (diff new-vs current))]
                          (fun changes)))
                      (recur new-idx new-vs))))))))

(defprotocol Stoppable
  (stop [this]))

(deftype Watcher [ch]
  Stoppable
  (stop [_]
    (>!! ch :done)))

(defn watch-path
  ([path fun]
   (watch-path path fun {}))
  ([path fun ops]
  (let [stop-ch (chan)]
    (start-watcher (recurse path) fun stop-ch ops)
    (Watcher. stop-ch))))

(defn map->consul
  ([kv-path m]
   (map->consul kv-path m {}))
  ([kv-path m ops]
   (doseq [[k v] (map->props m)]
     (put (str kv-path "/" k) (str v) ops))))

(defn consul->map
  ([path]
   (consul->map path {}))
  ([path {:keys [offset] :as ops}]
   (-> (partial get-all path
                        (merge ops {:keywordize? false}))
       props->map
       (get-in (cpath->kpath offset)))))

(defn merge-with-consul
  ([m path]
   (merge-with-consul m path {}))
  ([m path ops]
   (if-let [consul (consul->map path ops)]
     (merge-maps m consul)
     m)))
