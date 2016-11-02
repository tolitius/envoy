## consul basic ops

```clojure
$ boot repl
boot.user=> (require '[envoy.core :as envoy :refer [stop]])
nil
```

### Adding to Consul

```clojure
boot.user=> (envoy/put "http://localhost:8500/v1/kv/hubble/mission" "Horsehead Nebula")
{:opts {:body "Horsehead Nebula", :method :put, :url "http://localhost:8500/v1/kv/hubble/mission"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:57:40 GMT"}, :status 200}

boot.user=> (envoy/put "http://localhost:8500/v1/kv/hubble/store" "spacecraft")
{:opts {:body "spacecraft", :method :put, :url "http://localhost:8500/v1/kv/hubble/store"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:58:13 GMT"}, :status 200}

boot.user=> (envoy/put "http://localhost:8500/v1/kv/hubble/camera/mode" "color")
{:opts {:body "color", :method :put, :url "http://localhost:8500/v1/kv/hubble/camera/mode"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:58:36 GMT"}, :status 200}
```

### Reading from Consul

```clojure
boot.user=> (envoy/get-all "http://localhost:8500/v1/kv/hubble")
{:hubble/ nil, :hubble/camera/ nil, :hubble/camera/mode "color", :hubble/mission "Horsehead Nebula", :hubble/store "spacecraft"}

boot.user=> (envoy/get-all "http://localhost:8500/v1/kv/hubble/store")
{:hubble/store "spacecraft"}
```

### Deleting from Consul

```clojure
boot.user=> (envoy/delete "http://localhost:8500/v1/kv/hubble/camera")
{:opts {:method :delete, :url "http://localhost:8500/v1/kv/hubble/camera?recurse"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:59:26 GMT"}, :status 200}

boot.user=> (envoy/get-all "http://localhost:8500/v1/kv/hubble")
{:hubble/ nil, :hubble/mission "Horsehead Nebula", :hubble/store "spacecraft"}
```

### Watch for key/value changes

Adding a watcher is simple: `envoy/watch-path path fun`

`fun` is going to be called with a new value each time the `path`'s value is changed.

```clojure
boot.user=> (def store-watcher (envoy/watch-path "http://localhost:8500/v1/kv/hubble/store"
                                                 #(println "watcher says:" %)))
```

creates a `envoy.core.Watcher` and echos back the current value:

```clojure
#'boot.user/store-watcher
watcher says: {:hubble/store spacecraft}
```

it is an `envoy.core.Watcher`:

```clojure
boot.user=> store-watcher
#object[envoy.core.Watcher 0x72a190f0 "envoy.core.Watcher@72a190f0"]
```

that would print to REPL, since that's the function provided `#(println "watcher says:" %)`, every time the key `hubble/store` changes.

let's change it to "Earth":
<p align="center"><img src="doc/img/store-update.png"></p>

once the "UPDATE" button is clicked REPL will notify us with a new value:

```clojure
watcher says: {:hubble/store Earth}
```

same thing if it's changed with `envoy/put`:

```clojure
boot.user=> (envoy/put "http://localhost:8500/v1/kv/hubble/store" "spacecraft tape")
watcher says: {:hubble/store spacecraft tape}
{:opts {:body "spacecraft tape", :method :put, :url "http://localhost:8500/v1/kv/hubble/store"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 03:22:41 GMT"}, :status 200}
```

`envoy.core.Watcher` is stoppable:

```clojure
boot.user=> (stop store-watcher)
true
"stopping" "http://localhost:8500/v1/kv/hubble/store" "watcher"
```

## License

Copyright © 2016 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
