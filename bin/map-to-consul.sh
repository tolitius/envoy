#!/usr/bin/env boot

(set-env! :dependencies '[[tolitius/envoy "0.1.5"]
                          [org.clojure/tools.cli "0.3.5" :exclusions [org.clojure/clojure]]])

(require '[clojure.edn :as edn]
         '[clojure.tools.cli :as cli]
         '[envoy.core :as envoy])

(defn -main [& args]
  (let [{:keys [consul-host
                consul-port
                edn-file]} (-> (cli/parse-opts args [["-f" "--edn-file FILEPATH" "edn file to use"]
                                                     ["-h" "--consul-host HOST" "consul host to populate"
                                                      :default "localhost"]
                                                     ["-p" "--consul-port PORT" "consul port"
                                                      :default 8500]])
                               :options)
        data (-> edn-file
                 slurp
                 edn/read-string)]
    (println (str "populating consul on \"" consul-host ":" consul-port "\" with data from \"" edn-file "\" file"))
    (envoy/map->consul (str "http://" consul-host ":" consul-port "/v1/kv") data)))
