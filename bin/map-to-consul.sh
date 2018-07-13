#!/usr/bin/env boot

(set-env! :dependencies '[[tolitius/envoy "0.1.9-SNAPSHOT"]
                          [org.clojure/tools.cli "0.3.5" :exclusions [org.clojure/clojure]]])

(require '[clojure.edn :as edn]
         '[clojure.tools.cli :as cli]
         '[envoy.core :as envoy])

(defn exit!  [msg]
   (println msg)
   (System/exit 1))

(defn parse-args [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args [["-f" "--edn-file FILEPATH" "edn file to use"]
                                                               ["-h" "--consul-host HOST" "consul host to populate"
                                                                :default "localhost"]
                                                               ["-p" "--consul-port PORT" "consul port"
                                                                :default 8500]
                                                               ["-o" "--options OPTIONS" "options: token, offset, etc."
                                                                :default "{}"]])]
    (cond
      errors                              (exit! (str errors "\n\n" summary))
      (not-every? options #{:edn-file})   (exit! (str "missing required options\n\n" summary))
      :else options)))

;; i.e.
;; ./map-to-consul.sh -f rover.edn        \
;;                    -h consul-mars.com  \
;;                    -o '{:token "3c0d8be4-1cc3-4929-ce87-0a4425cb0171"}'

(defn -main [& args]
  (let [{:keys [consul-host consul-port edn-file options]} (parse-args args)
        consult-url (str "http://" consul-host ":" consul-port "/v1/kv")
        data (-> edn-file
                 slurp
                 edn/read-string)
        options (edn/read-string options)]
    (println (str "populating consul on \"" consult-url
                  "\" with data from \"" edn-file "\" file, with options: " options))
    (try
      (envoy/map->consul consult-url data options)
      (catch Exception e
        (exit! (.getMessage e))))))
