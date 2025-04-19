(ns envoy.test.session
  (:require [clojure.test :refer :all]
            [envoy.session :as session]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [camel-snake-kebab.core :as csk]))

;; mock the http responses so we don't need an actual consul instance
(defn- mock-response [status body & [headers]]
  (let [base-headers {:content-type "application/json"
                      :content-length (count body)}]
    {:status status
     :body body
     :headers (merge base-headers headers)}))

(defn- with-fake-http [responses f]
  (with-redefs [http/get (fn [url opts]
                           (let [resp (first @responses)]
                             (swap! responses rest)
                             (future resp)))
                http/put (fn [url opts]
                           (let [resp (first @responses)]
                             (swap! responses rest)
                             (future resp)))]
    (f)))

(deftest test-create-session
  (testing "create session basic functionality"
    (let [session-id "3f230917-90c9-6b5e-f579-43d854ba9cfe"
          response (mock-response 200 (json/generate-string {:ID session-id}))]
      (with-fake-http
        (atom [response])
        #(let [result (session/create-session "http://localhost:8500" {:name "test-session" :ttl "30s"})]
           (is (map? result))
           (is (= session-id (:id result)))))))

  (testing "create session with options"
    (let [session-id "3f230917-90c9-6b5e-f579-43d854ba9cfe"
          response (mock-response 200 (json/generate-string {:ID session-id}))]
      (with-fake-http
        (atom [response])
        #(let [result (session/create-session
                        "http://localhost:8500"
                        {:name "test-session" :ttl "30s"}
                        {:token "secret"})]
           (is (map? result))
           (is (= session-id (:id result))))))))

(deftest test-delete-session
  (testing "delete session"
    (let [response (mock-response 200 (json/generate-string true))]
      (with-fake-http
        (atom [response])
        #(is (true? (session/delete-session
                      "http://localhost:8500"
                      {:uuid "test-session-id"})))))))

(deftest test-renew-session
  (testing "renew session"
    (let [response (mock-response 200 (json/generate-string
                                        [{:ID "test-session-id"
                                          :Name "test-session"
                                          :TTL "30s"}]))]
      (with-fake-http
        (atom [response])
        #(let [result (session/renew-session
                        "http://localhost:8500"
                        {:uuid "test-session-id"})]
           (is (vector? result))
           (is (= 1 (count result)))
           (is (= "test-session-id" (:id (first result)))))))))

(deftest test-read-session
  (testing "read session"
    (let [response (mock-response 200 (json/generate-string
                                        [{:ID "test-session-id"
                                          :Name "test-session"
                                          :TTL "30s"}]))]
      (with-fake-http
        (atom [response])
        #(let [result (session/read-session
                        "http://localhost:8500"
                        {:uuid "test-session-id"})]
           (is (vector? result))
           (is (= 1 (count result)))
           (is (= "test-session-id" (:id (first result)))))))))

(deftest test-list-node-session
  (testing "list node sessions"
    (let [response (mock-response 200 (json/generate-string
                                        [{:ID "session-1" :Name "session-1" :Node "node-1"}
                                         {:ID "session-2" :Name "session-2" :Node "node-1"}]))]
      (with-fake-http
        (atom [response])
        #(let [result (session/list-node-session
                        "http://localhost:8500"
                        {:node "node-1"})]
           (is (vector? result))
           (is (= 2 (count result)))
           (is (= "node-1" (:node (first result)))))))))

(deftest test-list-sessions
  (testing "list all sessions"
    (let [response (mock-response 200 (json/generate-string
                                        [{:ID "session-1" :Name "session-1" :Node "node-1"}
                                         {:ID "session-2" :Name "session-2" :Node "node-2"}]))]
      (with-fake-http
        (atom [response])
        #(let [result (session/list-sessions "http://localhost:8500" {})]
           (is (vector? result))
           (is (= 2 (count result))))))))

(deftest test-acquire-lock
  (testing "acquire lock success"
    (let [response (mock-response 200 (json/generate-string true))]
      (with-fake-http
        (atom [response])
        #(is (true? (session/acquire-lock
                      "http://localhost:8500"
                      {:task "test-task" :session-id "test-session-id"}))))))

  (testing "acquire lock failure"
    (let [response (mock-response 200 (json/generate-string false))]
      (with-fake-http
        (atom [response])
        #(is (false? (session/acquire-lock
                       "http://localhost:8500"
                       {:task "test-task" :session-id "test-session-id"})))))))

(deftest test-release-lock
  (testing "release lock success"
    (let [response (mock-response 200 (json/generate-string true))]
      (with-fake-http
        (atom [response])
        #(is (true? (session/release-lock
                      "http://localhost:8500"
                      {:task "test-task" :session-id "test-session-id"}))))))

  (testing "release lock failure"
    (let [response (mock-response 200 (json/generate-string false))]
      (with-fake-http
        (atom [response])
        #(is (false? (session/release-lock
                       "http://localhost:8500"
                       {:task "test-task" :session-id "test-session-id"})))))))

(deftest test-with-version
  (testing "with version adds v1 to url"
    (is (= "http://localhost:8500/v1"
           (#'session/with-version "http://localhost:8500")))
    (is (= "http://localhost:8500/v1"
           (#'session/with-version "http://localhost:8500/")))))

(deftest test-error-message
  (testing "error message with exception"
    (is (= "test error"
           (session/error-message {:error (ex-info "test error" {})}))))

  (testing "error message with status"
    (is (= "response status was 404"
           (session/error-message {:status 404})))))
