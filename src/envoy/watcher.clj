(ns envoy.watcher
  (:require [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop >!! alt! chan close! put!]]
            [envoy.core :as envoy]
            [envoy.tools :as tools]
            [org.httpkit.client :as http])
  (:import (clojure.lang ExceptionInfo)))

(defonce ^:private LISTENER-STOP-VALUE
  :done)

(defn- index-of [resp]
  (-> resp
      :headers
      :x-consul-index))

(defn- read-index
  ([path]
   (read-index path {}))
  ([path ops]
  (-> (http/get path (tools/with-ops ops))
      index-of)))

(defn- put-if-channel-open!
  "push to channel _iff_ the channel is open and actively listening"
  [channel {:keys [on-active
                   on-close
                   data]
            :or   {on-close  (fn [_]
                               (prn "channel is closed"))
                   data      :dummy-data}}]
  (put! channel data
                (fn [result]
                  (if (nil? result)
                    (on-close channel)
                    (and on-active
                         (on-active channel))))))

(defmacro handle-consul-read-error
  "when reading from consul we might need to handle exceptions which is what i do"
  [close-all-channels & body]
  `(try
     ~@body
     (catch ExceptionInfo error-with-info#
       (if (-> error-with-info# ex-data :cause (= :timeout))
         (prn "timeout-issue" error-with-info#)
         (throw error-with-info#)))
     (catch RuntimeException watch-error#
       (-> (format "[envoy watcher]: could not read latest changes due to: %s" watch-error#)
           prn)
       (~close-all-channels))))

(defn- start-watcher
  ([path fun stop?]
   (start-watcher path fun stop? {}))
  ([path fun stop? ops]
   (let [watcher-channel    (chan)
         close-all-channels (fn []
                                  (close! watcher-channel)
                                  (put-if-channel-open! stop? {:data LISTENER-STOP-VALUE
                                                               :on-open (fn [channel _]
                                                                          (close! channel))}))]
     (println "starting watcher for" (tools/recurse path)
              "with raw options" ops
              "with options" (tools/with-ops (merge ops {:index (or nil (read-index path ops))})))
     (go-loop [index   nil
               current (handle-consul-read-error
                         close-all-channels
                         (envoy/get-all path ops))]
              (handle-consul-read-error
                close-all-channels
                (http/get (tools/recurse path)
                          (tools/with-ops (merge ops
                                                 {:index (or index (read-index path ops))}))
                          #(>!! watcher-channel %)))
              (alt!
                stop?           ([_]
                                 (prn "stopping" path "watcher"))
                watcher-channel ([resp]
                                       (let [new-idx (index-of resp)
                                             new-vs (envoy/read-values resp)]
                                         (when (and index (not= new-idx index))               ;; first time there is no index
                                           (when-let [changes (first (diff new-vs current))]
                                             (fun changes)))
                                         (recur new-idx new-vs))))))))

(defprotocol Stoppable
  (stop [this]))

(deftype Watcher [ch]
  Stoppable
  (stop [_]
    (put-if-channel-open! ch {:on-open #(>!! % LISTENER-STOP-VALUE)})))

(defn watch-path
  ([path fun]
   (watch-path path fun {:token (System/getenv "CONSUL_TOKEN")}))
  ([path fun ops]
  (let [stop-ch (chan)]
    (start-watcher path fun stop-ch ops)
    (Watcher. stop-ch))))
