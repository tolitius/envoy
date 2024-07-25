(ns envoy.watcher
  (:require [clojure.data :refer [diff]]
            [clojure.core.async :refer [go-loop >!! alt! chan close! put!]]
            [envoy.core :as core]
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
  (-> (http/get path (core/with-ops ops))
      index-of)))

(defn- data->channel
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
   (let [data-listener-channel  (chan)
         close-all-channels     (fn []
                                  (close! data-listener-channel)
                                  (data->channel stop? {:data LISTENER-STOP-VALUE
                                                        :on-open (fn [channel _]
                                                                   (close! channel))}))]
     (go-loop [index   nil
               current (handle-consul-read-error
                         close-all-channels
                         (core/get-all path ops))]
              (handle-consul-read-error
                close-all-channels
                (http/get (core/recurse path)
                          (merge (core/with-ops (merge ops
                                                       {:index (or index (read-index path ops))}))
                                 (core/with-auth ops))
                          #(>!! data-listener-channel %))) 
              (alt!
                stop?                 ([_]
                                       (prn "stopping" path "watcher"))
                data-listener-channel ([resp]
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
    (data->channel ch {:on-open #(>!! % LISTENER-STOP-VALUE)})))

(defn watch-path
  ([path fun]
   (watch-path path fun {:token (System/getenv "CONSUL_TOKEN")}))
  ([path fun ops]
  (let [stop-ch (chan)]
    (start-watcher path fun stop-ch ops)
    (Watcher. stop-ch))))
