(ns envoy.test.core
  (:require [clojure.test :refer :all]
            [envoy.core :as envoy]
            [envoy.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as string]
            [org.httpkit.client :as http])
  (:import [java.util Base64]))


;; mock the http responses so we don't need an actual consul instance
(defn- mock-response [status body & [headers]]
  (let [base-headers {:content-type "application/json"
                      :content-length (count body)}]
    {:status status
     :body body
     :headers (merge base-headers headers)}))

(defn- mock-consul-kv [kvs & [index]]
  (let [index (or index 42)]
    (mock-response
     200
     (json/generate-string
      (mapv
       (fn [[k v]]
         {:Key k
          :Value (.encodeToString
                  (Base64/getEncoder)
                  (.getBytes (str v)))})
       kvs))
     {:x-consul-index (str index)})))

(defn- with-fake-http [responses f]
  (with-redefs [http/get (fn [url opts]
                           (let [resp (first @responses)]
                             (swap! responses rest)
                             (future resp)))
                http/put (fn [url opts]
                           (let [resp (first @responses)]
                             (swap! responses rest)
                             (future resp)))
                http/delete (fn [url opts]
                              (let [resp (first @responses)]
                                (swap! responses rest)
                                (future resp)))]
    (f)))

(deftest test-url-builder
  (testing "url builder with default params"
    (let [builder (envoy/url-builder {})]
      (is (= "http://localhost:8500/v1/kv" (builder)))
      (is (= "http://localhost:8500/v1/kv/test" (builder "test")))))

  (testing "url builder with custom params"
    (let [builder (envoy/url-builder {:hosts ["consul.example.com"]
                                      :port 8501
                                      :secure? true})]
      (is (= "https://consul.example.com:8501/v1/kv" (builder)))
      (is (= "https://consul.example.com:8501/v1/kv/test" (builder "test"))))))

(deftest test-consul-to-map
  (testing "consul->map basic functionality"
    (with-fake-http
      (atom [(mock-consul-kv {"hubble/store" "spacecraft://tape"
                              "hubble/camera/mode" "color"
                              "hubble/mission/target" "Horsehead Nebula"})])
      #(is (= {:hubble
               {:store "spacecraft://tape"
                :camera {:mode "color"}
                :mission {:target "Horsehead Nebula"}}}
              (envoy/consul->map "http://localhost:8500/v1/kv/hubble")))))

  (testing "consul->map with offset"
    (with-fake-http
      (atom [(mock-consul-kv {"hubble/mission/target" "Horsehead Nebula"})])
      #(is (= {:target "Horsehead Nebula"}
              (envoy/consul->map "http://localhost:8500/v1/kv"
                                 {:offset "hubble/mission"})))))

  (testing "consul->map with preserve-offset"
    (with-fake-http
      (atom [(mock-consul-kv {"hubble/mission/target" "Horsehead Nebula"})])
      #(is (= {:hubble {:mission {:target "Horsehead Nebula"}}}
              (envoy/consul->map "http://localhost:8500/v1/kv"
                                 {:offset "hubble/mission"
                                  :preserve-offset true}))))))

(deftest test-map-to-consul
  (testing "map->consul basic functionality"
    (let [data {:hubble
                {:store "spacecraft://tape"
                 :camera {:mode "color"}
                 :mission {:target "Horsehead Nebula"}}}
          put-calls (atom [])]
      (with-redefs [envoy/put (fn [path v & [opts]]
                                (swap! put-calls conj {:path path
                                                      :value v
                                                      :opts opts}))]
        (envoy/map->consul "http://localhost:8500/v1/kv" data)
        (is (= 3 (count @put-calls)))
        (is (some #(and (= (:path %) "http://localhost:8500/v1/kv/hubble/store")
                        (= (:value %) "spacecraft://tape"))
                 @put-calls))
        (is (some #(and (= (:path %) "http://localhost:8500/v1/kv/hubble/camera/mode")
                        (= (:value %) "color"))
                 @put-calls))
        (is (some #(and (= (:path %) "http://localhost:8500/v1/kv/hubble/mission/target")
                        (= (:value %) "Horsehead Nebula"))
                 @put-calls))))))

(deftest test-get-all
  (testing "get-all with keywordize"
    (with-fake-http
      (atom [(mock-consul-kv {"hubble/store" "spacecraft://tape"
                              "hubble/camera/mode" "color"})])
      #(is (= {:hubble/store "spacecraft://tape"
               :hubble/camera/mode "color"}
              (envoy/get-all "http://localhost:8500/v1/kv/hubble")))))

  (testing "get-all without keywordize"
    (with-fake-http
      (atom [(mock-consul-kv {"hubble/store" "spacecraft://tape"
                              "hubble/camera/mode" "color"})])
      #(is (= {"hubble/store" "spacecraft://tape"
               "hubble/camera/mode" "color"}
              (envoy/get-all "http://localhost:8500/v1/kv/hubble"
                             {:keywordize? false}))))))

(deftest test-put-delete
  (testing "put operation"
    (with-fake-http
      (atom [(mock-response 200 "true")])
      #(let [result (envoy/put "http://localhost:8500/v1/kv/test" "value")]
         (is (map? result))
         (is (= 200 (:status result)))
         (is (= "true" (:body result))))))

  (testing "put with options"
    (with-fake-http
      (atom [(mock-response 200 "true")])
      #(let [result (envoy/put "http://localhost:8500/v1/kv/test" "value" {:token "secret"})]
         (is (map? result))
         (is (= 200 (:status result)))
         (is (= "true" (:body result))))))

  (testing "delete operation"
    (with-fake-http
      (atom [(mock-response 200 "true")])
      #(is (map? (envoy/delete "http://localhost:8500/v1/kv/test")))))

  (testing "delete with options"
    (with-fake-http
      (atom [(mock-response 200 "true")])
      #(is (map? (envoy/delete "http://localhost:8500/v1/kv/test"
                               {:token "secret"}))))))

(deftest test-copy-move
  (testing "copy functionality"
    (let [consul-map-calls (atom [])
          map->consul-calls (atom [])]
      (with-redefs [envoy/consul->map (fn [path & [opts]]
                                        (swap! consul-map-calls conj {:path path :opts opts})
                                        {:config "value"}) ; Remove source nesting
                    envoy/map->consul (fn [path data & [opts]]
                                        (swap! map->consul-calls conj
                                               {:path path :data data :opts opts}))]
        (envoy/copy "http://localhost:8500/v1/kv" "/source" "/dest" {:token "secret"})

        (is (= 1 (count @consul-map-calls)))
        (is (= "http://localhost:8500/v1/kv" (:path (first @consul-map-calls))))
        (is (= "/source" (get-in (first @consul-map-calls) [:opts :offset])))

        (is (= 1 (count @map->consul-calls)))
        (is (= "http://localhost:8500/v1/kv" (:path (first @map->consul-calls))))
        (is (= {:dest {:config "value"}} (:data (first @map->consul-calls))))
        (is (= {:token "secret"} (:opts (first @map->consul-calls)))))))

  (testing "move functionality"
    (let [copy-calls (atom [])
          delete-calls (atom [])]
      (with-redefs [envoy/copy (fn [path from to opts]
                                 (swap! copy-calls conj {:path path
                                                        :from from
                                                        :to to
                                                        :opts opts}))
                    envoy/delete (fn [path opts]
                                   (swap! delete-calls conj {:path path :opts opts}))]
        (envoy/move "http://localhost:8500/v1/kv" "/source" "/dest" {:token "secret"})

        (is (= 1 (count @copy-calls)))
        (is (= "http://localhost:8500/v1/kv" (:path (first @copy-calls))))
        (is (= "/source" (:from (first @copy-calls))))
        (is (= "/dest" (:to (first @copy-calls))))

        (is (= 1 (count @delete-calls)))
        (is (= "http://localhost:8500/v1/kv/source/" (:path (first @delete-calls))))
        (is (= {:token "secret"} (:opts (first @delete-calls))))))))

(deftest test-merge-with-consul
  (testing "merge with non-empty consul data"
    (with-redefs [envoy/consul->map (fn [path opts]
                                      {:remote {:value "from-consul"}})]
      (let [local {:local {:value "local-value"}
                   :remote {:other "local-other"}}
            result (envoy/merge-with-consul local "http://localhost:8500/v1/kv" {:token "secret"})]
        (is (= {:local {:value "local-value"}
                :remote {:value "from-consul"
                         :other "local-other"}}
               result)))))

  (testing "merge with empty consul data"
    (with-redefs [envoy/consul->map (fn [path opts] nil)]
      (let [local {:local {:value "local-value"}}
            result (envoy/merge-with-consul local "http://localhost:8500/v1/kv" {:token "secret"})]
        (is (= local result))))))

(deftest test-read-values
  (testing "successful response parsing"
    (let [resp {:status 200
                :body "[{\"Key\":\"test/key\",\"Value\":\"dGVzdCB2YWx1ZQ==\"}]"
                :opts {:url "http://localhost:8500/v1/kv/test"}}
          result (#'envoy/read-values resp)]
      (is (= {:test/key "test value"} result))))

  (testing "empty value handling"
    (let [resp {:status 200
                :body "[{\"Key\":\"test/key\",\"Value\":null}]"
                :opts {:url "http://localhost:8500/v1/kv/test"}}
          result (#'envoy/read-values resp)]
      (is (= {:test/key nil} result))))

  (testing "404 error handling"
    (let [resp {:status 404
                :body "Not found"
                :opts {:url "http://localhost:8500/v1/kv/nonexistent"}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"could not find path in consul"
           (#'envoy/read-values resp)))))

  (testing "non-200 response handling"
    (let [resp {:status 500
                :body "Internal error"
                :opts {:url "http://localhost:8500/v1/kv/test"}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"failed to read from consul"
           (#'envoy/read-values resp))))))

(deftest test-strip-offset
  (testing "successful offset stripping"
    (let [data {:hubble {:mission {:target "Horsehead Nebula"}}}
          result (#'envoy/strip-offset data "hubble/mission")]
      (is (= {:target "Horsehead Nebula"} result))))

  (testing "error on invalid offset"
    (let [data {:hubble {:mission {:target "Horsehead Nebula"}}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"could not remove offset"
           (#'envoy/strip-offset data "invalid/path"))))))
