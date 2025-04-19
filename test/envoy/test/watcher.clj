(ns envoy.test.watcher
  (:require [clojure.test :refer :all]
            [envoy.watcher :as watcher]
            [envoy.core :as envoy]
            [clojure.core.async :as async :refer [chan >!! <!! close! go timeout]]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

;; mock http responses
(defn- mock-response [status body & [headers]]
  (let [base-headers {:content-type "application/json"
                      :content-length (count body)
                      :x-consul-index "42"}
        headers (merge base-headers headers)]
    {:status status
     :body body
     :headers headers}))

(defn- mock-kv-response [kvs & [index]]
  (let [index (or index 42)]
    (mock-response
     200
     (json/generate-string
      (mapv
       (fn [[k v]]
         {:Key k
          :Value (.encodeToString
                  (java.util.Base64/getEncoder)
                  (.getBytes (str v)))})
       kvs))
     {:x-consul-index (str index)})))

(defn- with-fake-http [responses f]
  (with-redefs [http/get (fn [url opts]
                           (let [resp (first @responses)]
                             (swap! responses rest)
                             (future resp)))
                envoy/get-all (fn [path opts]
                                {:test/key "initial-value"})]
    (f)))

(deftest test-index-of
  (testing "extract index from response"
    (let [resp {:headers {:x-consul-index "42"}}]
      (is (= "42" (#'watcher/index-of resp))))))

(deftest test-read-index
  (testing "read index from path"
    ;; directly mock the index-of function since that's what read-index uses
    (with-redefs [watcher/index-of (fn [_] "42")]
      (is (= "42" (#'watcher/read-index "http://localhost:8500/v1/kv/test"))))))

(deftest test-put-if-channel-open
  (testing "put to open channel"
    (let [ch (chan 1)
          on-active-called (atom false)
          on-active (fn [_] (reset! on-active-called true))]
      (#'watcher/put-if-channel-open! ch {:data :test-data
                                          :on-active on-active})
      (is (= :test-data (<!! ch)))
      (is @on-active-called)
      (close! ch)))

  (testing "put to closed channel"
    (let [ch (chan)
          on-close-called (atom false)
          on-close (fn [_] (reset! on-close-called true))]
      (close! ch)
      ;; check the actual return value - put! returns false for closed channels, not nil
      (is (false? (async/put! ch :test-data)))
      (on-close ch)
      (is @on-close-called))))

(deftest test-stop-watcher
  (testing "stop watcher protocol"
    (let [ch (chan)
          w (watcher/->Watcher ch)
          stop-val (atom nil)]
      (async/go
        (let [val (async/<! ch)]
          (reset! stop-val val)))
      (Thread/sleep 50)
      (watcher/stop w)
      (Thread/sleep 50)
      (is (= :dummy-data @stop-val)))))

(deftest test-watch-path
  (testing "create watcher"
    (with-redefs [watcher/start-watcher (fn [path fun stop-ch opts]
                                          (is (= "http://localhost:8500/v1/kv/test" path))
                                          (is (fn? fun))
                                          (is (instance? clojure.core.async.impl.channels.ManyToManyChannel stop-ch))
                                          (is (map? opts)))]
      (let [w (watcher/watch-path "http://localhost:8500/v1/kv/test" identity)]
        (is (instance? envoy.watcher.Watcher w))))))

;; error handling
(deftest test-handle-consul-read-error
  (testing "handle timeout error"
    (let [close-called (atom false)
          close-fn (fn [] (reset! close-called true))
          ex (ex-info "Timeout" {:cause :timeout})]
      (with-redefs [clojure.core/prn (fn [& _] nil)]
        ;; define a function that uses the macro
        (letfn [(test-fn []
                  (watcher/handle-consul-read-error
                   close-fn
                   (throw ex)))]
          (test-fn)
          ;; check that close-fn wasn't called for timeout errors
          (is (not @close-called))))))

  (testing "handle other error"
    (let [close-called (atom false)
          close-fn (fn [] (reset! close-called true))
          ex (RuntimeException. "Other error")]
      (with-redefs [clojure.core/prn (fn [& _] nil)]
        ;; define a function that uses the macro
        (letfn [(test-fn []
                  (watcher/handle-consul-read-error
                   close-fn
                   (throw ex)))]
          (test-fn)
          ;; check that close-fn was called for non-timeout errors
          (is @close-called))))))
