(ns envoy.watcher
  (:require [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop >!! alt! chan close!]]
            [envoy.core :as core]
            [org.httpkit.client :as http]))

(defn- index-of [resp]
  (-> resp
      :headers
      :x-consul-index))

(defn- read-index
  ([path]
   (read-index path {}))
  ([path ops]
  (-> (http/get path (core/with-ops ops))
      index-of)))

(defn- start-watcher
  ([path fun stop?]
   (start-watcher path fun stop? {}))
  ([path fun stop? ops]
   (let [ch (chan)
         close-all-channels (fn []
                              (close! ch)
                              (close! stop?))]
     (go-loop [index   nil
               current (try
                         (core/get-all path ops)
                         (catch RuntimeException watch-error
                           (-> (format "[envoy watcher]: could not read latest changes from '%s' due to: %s" path watch-error)
                               prn)
                           (close-all-channels)))]
              (try
                (http/get (core/recurse path)
                          (merge (core/with-ops (merge ops
                                                       {:index (or index (read-index path ops))}))
                                 (core/with-auth ops))
                          #(>!! ch %))
                (catch Exception watch-error
                  (-> (format "[envoy watcher]: could not read latest changes from '%s' due to: %s" path watch-error)
                      prn)
                  (close-all-channels)))
              (alt!
                stop? ([_]
                       (prn "stopping" path "watcher"))
                ch ([resp]
                    (let [new-idx (index-of resp)
                          new-vs (core/read-values resp)]
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
   (watch-path path fun {:token (System/getenv "CONSUL_TOKEN")}))
  ([path fun ops]
  (let [stop-ch (chan)]
    (start-watcher path fun stop-ch ops)
    (Watcher. stop-ch))))
