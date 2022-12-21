#!/usr/bin/env boot

(set-env! :dependencies '[[tolitius/envoy "0.1.26"]
                          [org.clojure/tools.cli "1.0.194" :exclusions [org.clojure/clojure]]])

(require '[clojure.edn :as edn]
         '[clojure.tools.cli :as cli]
         '[clojure.pprint :as pp]
         '[envoy.core :as envoy])

(defn exit!  [msg]
   (println msg)
   (System/exit 1))

(defn parse-args [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args [["-l" "--location LOCATION" "location to use: i.e. /hubble/mission"]
                                                               ["-f" "--edn-file EDN_FILE" "destination edn file where envoy would save a consul map"
                                                                :default "config.edn"]
                                                               ["-h" "--consul-host HOST" "consul host to populate"
                                                                :default "localhost"]
                                                               ["-p" "--consul-port PORT" "consul port"
                                                                :default 8500]
                                                               [nil "--pretty-print" "pretty print edn into a file"]
                                                               ["-o" "--options OPTIONS" "options: token, offset, etc."
                                                                :default "{}"]])]
    (cond
      errors                              (exit! (str errors "\n\n" summary))
      (not-every? options #{:location})   (exit! (str "missing required options\n\n" summary))
      :else options)))

;; i.e.
;; ./consul-to-map.sh -l /hubble/mission  \
;;                    -h consul-mars.com  \
;;                    -o '{:token "3c0d8be4-1cc3-4929-ce87-0a4425cb0171"}'

(defn -main [& args]
  (let [{:keys [consul-host
                consul-port
                edn-file
                pretty-print
                options
                location]} (parse-args args)
        consul-url (str consul-host "/v1/kv" location)
        options (edn/read-string options)]
    (println (str "copying from consul on \"" consul-url
                  "\" to \"" edn-file "\" file, with options: " options))
    (try
      (let [config (envoy/consul->map consul-url options)
            formatted (if-not pretty-print
                        config
                        (with-out-str (pp/pprint config)))]
        (spit edn-file formatted))
      (catch Exception e
        (exit! (.getMessage e))))))
