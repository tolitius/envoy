## Diplomatic rank

_[source](https://en.wikipedia.org/wiki/Diplomatic_rank#Historical_ranks.2C_1815-1961):_

> _The rank of Envoy was short for "Envoy Extraordinary and Minister Plenipotentiary", and was more commonly known as Minister. For example, the Envoy Extraordinary and Minister Plenipotentiary of the United States to the French Empire was known as the "United States Minister to France" and addressed as "Monsieur le Ministre."_

<hr/>

[![Clojars Project](https://clojars.org/tolitius/envoy/latest-version.svg)](http://clojars.org/tolitius/envoy)

- [How to play](#how-to-play)
- [Map to Consul](#map-to-consul)
- [Consul to Map](#consul-to-map)
  - [Reading with an offset](#reading-with-an-offset)
- [Watch for key/value changes](#watch-for-keyvalue-changes)
  - [Watch nested keys](#watch-nested-keys)
  - [Watching the Watcher](#watching-the-watcher)
- [Consul CRUD](#consul-crud)
  - [Adding to Consul](#adding-to-consul)
  - [Reading from Consul](#reading-from-consul)
  - [Deleting from Consul](#deleting-from-consul)
- [Clone and Teleport](#clone-and-teleport)
  - [Copy](#copy)
  - [Move](#move)
- [Merging Configurations](#merging-configurations)
- [Sessions and Locks](#sessions-and-locks)
- [Options](#options)
  - [Serializer](#serializer)
- [License](#license)

## How to play

In order to follow all the docs below, bring envoy in:

```clojure
$ make repl
dev=> (require '[envoy.core :as envoy :refer [stop]])
nil
```

## Map to Consul

Since most Clojure configs are EDN maps, you can simply push the map to Consul with preserving the hierarchy:

```clojure
dev=> (def m {:hubble
                    {:store "spacecraft://tape"
                     :camera
                      {:mode "color"}
                     :mission
                      {:target "Horsehead Nebula"}}})

dev=> (envoy/map->consul "http://localhost:8500/v1/kv" m)
nil
```

done.

you should see Consul logs confirming it happened:

```bash
2016/11/02 02:04:13 [DEBUG] http: Request PUT /v1/kv/hubble/mission/target? (337.69µs) from=127.0.0.1:39372
2016/11/02 02:04:13 [DEBUG] http: Request GET /v1/kv/hubble?recurse&index=2114 (4m41.723665304s) from=127.0.0.1:39366
2016/11/02 02:04:13 [DEBUG] http: Request PUT /v1/kv/hubble/camera/mode? (373.246µs) from=127.0.0.1:39372
2016/11/02 02:04:13 [DEBUG] http: Request PUT /v1/kv/hubble/store? (1.607247ms) from=127.0.0.1:39372
```

and a visual:

<p align="center"><img src="doc/img/map-to-consul.png"></p>

## Consul to Map

In case a Clojure map with config read from Consul is needed it is just `consul->map` away:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv/hubble")
{:hubble
 {:camera {:mode "color"},
  :mission {:target "Horsehead Nebula"},
  :store "spacecraft://tape"}}
```

you may notice it comes directly from "the source" by looking at Consul logs:

```bash
2016/11/02 02:04:32 [DEBUG] http: Request GET /v1/kv/hubble?recurse (76.386µs) from=127.0.0.1:54167
```

### Reading with an offset

You may also read from consul at a certain `:offset` by specifying it in options.

Let's say we need to get everything that lives under the `hubble/mission`:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv" {:offset "hubble/mission"})
{:target "Horsehead Nebula"}
```

Specifying an offset is really useful for multiple environments or teams living in the same consul / acl.

(!) One thing to note is that an `offset` should start from the root and should be used with no data prefix in URL:

i.e. this is good:
```clojure
(envoy/consul->map "http://localhost:8500/v1/kv" {:offset "/hubble/mission"})
```

but this is not:
```clojure
(envoy/consul->map "http://localhost:8500/v1/kv/hubble" {:offset "mission"})
```

this has to do with the fact that Consul always returns data with a prefix e.g.: `{:hubble {:mission {:target "Horsehead Nebula"}}}`, hence just "mission" would not be enough to strip it, but "hubble/mission" would.

## Watch for key/value changes

Watching for kv changes with envoy _does not require_ to run a separate Consul Agent client or Consul Template and boils down to a simple function:

```clojure
(watch-path path fun)
```

`fun` is going to be called with a new value each time the `path`'s value is changed.

```clojure
dev=> (def store-watcher (envoy/watch-path "http://localhost:8500/v1/kv/hubble/store"
                                                 #(println "watcher says:" %)))
```

creates a `envoy.core.Watcher` and echos back the current value:

```clojure
#'dev/store-watcher
watcher says: {:hubble/store spacecraft}
```

it is an `envoy.core.Watcher`:

```clojure
dev=> store-watcher
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
dev=> (envoy/put "http://localhost:8500/v1/kv/hubble/store" "spacecraft tape")
watcher says: {:hubble/store spacecraft tape}
{:opts {:body "spacecraft tape", :method :put, :url "http://localhost:8500/v1/kv/hubble/store"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 03:22:41 GMT"}, :status 200}
```

`envoy.core.Watcher` is stoppable:

```clojure
dev=> (stop store-watcher)
"stopping" "http://localhost:8500/v1/kv/hubble/store" "watcher"
true
```

### Watch Nested Keys

In case you need to watch a hierarchy of keys (with all the nested keys), you can set a watcher on a local root key:

```clojure
dev=> (def hw (envoy/watch-path "http://localhost:8500/v1/kv/hubble"
                                      #(println "watcher says:" %)))
```

notice this watcher is on the top most / root `/hubble` key.

In this case _only the nested keys which values are changed_ will trigger notifications.

Let's say we went to `hubble/mission` and changed it from "Horsehead Nebula" to "Butterfly Nebula":

```clojure
watcher says: {:hubble/mission Butterfly Nebula}
```

It can be stopped as any other watcher:

```clojure
dev=> (stop hw)
"stopping" "http://localhost:8500/v1/kv/hubble?recurse" "watcher"
true
```

### Watching the Watcher

There is a [more visual example](https://github.com/tolitius/hubble) of envoy watchers that propagate notifications all the way to the browser:

<img src="doc/img/hubble-mission.jpg" width="100%">

Notification listner is just a function really, hence it can get propagated anywhere intergalactic computer system can reach.

## Consul CRUD

### Adding to Consul

The map from above can be done manually by "puts" of course:

```clojure
dev=> (envoy/put "http://localhost:8500/v1/kv/hubble/mission" "Horsehead Nebula")
{:opts {:body "Horsehead Nebula", :method :put, :url "http://localhost:8500/v1/kv/hubble/mission"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:57:40 GMT"}, :status 200}

dev=> (envoy/put "http://localhost:8500/v1/kv/hubble/store" "spacecraft")
{:opts {:body "spacecraft", :method :put, :url "http://localhost:8500/v1/kv/hubble/store"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:58:13 GMT"}, :status 200}

dev=> (envoy/put "http://localhost:8500/v1/kv/hubble/camera/mode" "color")
{:opts {:body "color", :method :put, :url "http://localhost:8500/v1/kv/hubble/camera/mode"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:58:36 GMT"}, :status 200}
```

### Reading from Consul

```clojure
dev=> (envoy/get-all "http://localhost:8500/v1/kv/hubble")
{:hubble/camera/mode "color",
 :hubble/mission "Horsehead Nebula",
 :hubble/store "spacecraft://tape"}

dev=> (envoy/get-all "http://localhost:8500/v1/kv/hubble/store")
{:hubble/store "spacecraft"}
```

in case there is no need to convert keys to keywords, it can be disabled:

```clojure
dev=> (envoy/get-all "http://localhost:8500/v1/kv/" {:keywordize? false})
{"hubble/camera/mode" "color",
 "hubble/mission" "Horsehead Nebula",
 "hubble/store" "spacecraft://tape"}
```

### Deleting from Consul

```clojure
dev=> (envoy/delete "http://localhost:8500/v1/kv/hubble/camera")
{:opts {:method :delete, :url "http://localhost:8500/v1/kv/hubble/camera?recurse"}, :body "true", :headers {:content-length "4", :content-type "application/json", :date "Wed, 02 Nov 2016 02:59:26 GMT"}, :status 200}

dev=> (envoy/get-all "http://localhost:8500/v1/kv/hubble")
{:hubble/mission "Horsehead Nebula", :hubble/store "spacecraft://tape"}
```

## Clone and Teleport

It is often the case when configuration trees need to be copied or moved from one place to another, under a new root or a new nested path. `envoy` can do it with `copy` and `move` commands.

### Copy

Copying configuration from one place to another is done with a `copy` command:

> => _```(envoy/copy kv-path from to)```_

Let's say we need to copy Hubble's mission (i.e. a "sub" config) under a new root "dev", so it lives under "/dev/hubble/mission" instead:

```clojure
dev=> (envoy/copy "http://localhost:8500/v1/kv" "/hubble/mission" "/dev/hubble/mission")
```

done. Let's read from this new "dev" root to make sure the mission is there:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv/dev")
{:dev {:hubble {:mission {:target "Horsehead Foo"}}}}
```

great.

We can of course copy the whole "hubble"'s config under "dev":

```clojure
dev=> (envoy/copy "http://localhost:8500/v1/kv" "/hubble" "/dev/hubble")
```

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv/dev")
{:dev {:hubble {:camera {:mode "color"}, :mission {:target "Horsehead Nebula"}, :store "spacecraft tape"}}}
```

`copy` is really handy when you need to copy configurations between environments, or just need to copy some nested portion of the config.

### Move

A `move` command is exactly the same as the `copy`, but, as you would expect, it deletes the source after the copy is done.

> => _```(envoy/move kv-path from to)```_

The Hubble's development work is finished, and we are switching to work on the Kepler telescope. Let's say most of the configuration may be reused, so we'll just move Hubble's config to Kepler:

```clojure
dev=> (envoy/move "http://localhost:8500/v1/kv" "/hubble" "/kepler")
```

done.

Oh, but we'll need "dev" and "qa" environments for Kepler's development. Let's move it again to live under "dev" root:

```clojure
dev=> (envoy/move "http://localhost:8500/v1/kv" "/kepler" "/dev/kepler")
```

and "copy" this config to "qa" before editing it:

```clojure
dev=> (envoy/copy "http://localhost:8500/v1/kv" "/dev/kepler" "/qa/kepler")
```

Let's look at Kepler's Consul universe:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv")

{:dev
 {:kepler
  {:mission {:target "Horsehead Nebula"},
   :camera {:mode "color"},
   :store "spacecraft tape"}},
 :qa
 {:kepler
  {:mission {:target "Horsehead Nebula"},
   :camera {:mode "color"},
   :store "spacecraft tape"}}}
```

Niice, universe awaits...

## Merging Configurations

Often there is an internal configuration some parts of which need to be overridden with values from Consul. Envoy has `merge-with-consul` function that does just that:

```clojure
(envoy/merge-with-consul config "http://localhost:8500/v1/kv/hubble")
```

will deep merge (with nested kvs) config with a map it'll read from Consul.

In case a Consul space is protected by a token, or any other options need to be passed to Consul to read the overrides, they can be added in an optional map:

```clojure
(envoy/merge-with-consul config
                         "http://localhost:8500/v1/kv/hubble"
                         {:token "7a0f3b39-8871-e16e-2101-c1b30a911883"})
```

## Sessions and Locks

Consul provides a [session mechanism](https://www.consul.io/docs/dynamic-app-config/sessions) which can be used to build distributed locks.

Sessions can be created, deleted, listed, listed for a node, etc. Once the session is created locks can be acquired for these sessions.

Consul [session API](https://www.consul.io/api-docs/session) have multiple parameters, do read the Consul docs in case you need to deviate from defaults.

An example we'll look at here will:

* create two sessions with TTL 45 seconds (`moon` and `mars`)
* acquire a `start-stage-one-booster` lock for the `moon` session
* try to acquire the same lock with `mars` _(fail)_
* wait until the first, `moon` session expires
* renew the `mars` session to make sure we can still use it after the `moon` expires
* try to acquire the same lock with the `mars` session again _(succeed)_

```clojure
(require '[envoy.session :as es])
```

```clojure
=> (def moon (es/create-session "http://localhost:8500" {:name "fly-me-to-the-moon" :ttl "45s"}))
#'user/moon

=> (def mars (es/create-session "http://localhost:8500" {:name "fly-me-to-the-mars" :ttl "45s"}))
#'user/mars

=> moon
{:id "3f230917-90c9-6b5e-f579-43d854ba9cfe"}

=> mars
{:id "371445e6-d74f-9524-de06-8622fa4344cd"}
```

sessions are created and Consul returned session ids to refer to these sessions by.

let's list these sessions:

```clojure
=> (es/list-sessions "http://localhost:8500")
[{:service-checks nil,
  :modify-index 18157665,
  :name "fly-me-to-the-mars",
  :behavior "release",
  :node "pluto",
  :ttl "45s",
  :id "371445e6-d74f-9524-de06-8622fa4344cd",
  :create-index 18157665,
  :lock-delay 15000000000,
  :node-checks ["serfHealth"]}
 {:service-checks nil,
  :modify-index 18157655,
  :name "fly-me-to-the-moon",
  :behavior "release",
  :node "pluto",
  :ttl "45s",
  :id "3f230917-90c9-6b5e-f579-43d854ba9cfe",
  :create-index 18157655,
  :lock-delay 15000000000,
  :node-checks ["serfHealth"]}]
```

now let's acquire a `start-stage-one-booster` lock with the `moon` session:

```clojure
=> (es/acquire-lock "http://localhost:8500" {:task "start-stage-one-booster"
                                             :session-id (moon :id)})
true
```

"true" means Consul said the lock was successfully acquired.

now let's try to acquire the same lock with the `mars` session:

```clojure
=> (es/acquire-lock "http://localhost:8500" {:task "start-stage-one-booster"
                                             :session-id (mars :id)})
false
```

ooops, can't touch this: since this lock is already acquired by another session.

while we are waiting on the `moon` session to expire (that "45s" TTL), let's renew the `mars` session so it outlives the `moon` one:

```clojure
=> (es/renew-session "http://localhost:8500" {:uuid (mars :id)})
[{:service-checks nil,
  :modify-index 18157665,
  :name "fly-me-to-the-mars",
  :behavior "release",
  :node "pluto",
  :ttl "45s",
  :id "371445e6-d74f-9524-de06-8622fa4344cd",
  :create-index 18157665,
  :lock-delay 15000000000,
  :node-checks ["serfHealth"]}]
```

after waiting for over 45 seconds, let's try to acquire the same lock with the `mars` session again:

```clojure
=> (es/acquire-lock "http://localhost:8500" {:task "start-stage-one-booster"
                                             :session-id (mars :id)})
true
```

great success!

by the way we also get a visual:

<p align="center"><img src="doc/img/mars-lock.png"></p>

all the params and options can still be used with session api.

for example to pass a "data center" and an "ACL token":

```clojure
=> (es/create-session "http://localhost:8500" {:dc "asteroid-belt" :name "fly-me-to-the-moon" :ttl "45s"}
                                              {:token "73e5a965-40af-9c70-a817-b065b6ef82db"})
```

the reason they are in two maps is because a `dc` is part of create session API parameters, whereas a token is a general Consul param.

## Options

All commands take an optional map of parameters. These parameters will get converted into Consul [KV Store Endpoints](https://www.consul.io/api/kv.html#parameters) params. Thus making all of the KV Store Endpoint params supported.

For example, in case keys are protected by ACL, you can provide a token:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv"
                               {:token "4c308bb2-16a3-4061-b678-357de559624a"})

{:hubble {:mission "Butterfly Nebula", :store "spacecraft://ssd"}}
```

or a _token_ and a _datacenter_:

```clojure
dev=> (envoy/consul->map "http://localhost:8500/v1/kv"
                               {:token "63aaa731-b124-40ef-9425-978aba612a1d"
                                :dc "phloston"})

{:hubble {:mission "Ghost of Jupiter", :store "spacecraft://tape"}}
```

or any other Consul supported parameters.

### Serializer

By default envoy will serialize and deserialize data in EDN format. Which usually is quite transparent, since EDN map gets written and read from Consul as a nested key value structure which is just that: a map.

There are cases where values are sequences: i.e. `{:hosts ["foo1.com", "foo2.com"]}` in which case they will still be serialized and deserialized as EDN by default, however it might be harder to consume these EDN sequences from languages other than Clojure which do not speak EDN natively.

While there are libraries for other languages that support EDN

* Java: https://github.com/danboykis/trava
* Go: https://github.com/go-edn/edn
* Ruby: https://github.com/relevance/edn-ruby
* Python: https://github.com/swaroopch/edn_format
* etc.

envoy would allow to specify other serializers via a `:serializer` option:

```clojure
dev=> (def config {:system {:hosts ["foo1.com", "foo2.com", {:a 42}]}})
#'dev/config

dev=> (envoy/map->consul "http://localhost:8500/v1/kv" config {:serializer :json})
nil

dev=> (envoy/consul->map "http://localhost:8500/v1/kv/system" {:serializer :json})
{:system {:hosts ["foo1.com" "foo2.com" {:a 42}]}}
```

If `{:serializer :json}` option is provided sequence values will be stored in Consul as JSON:

<p align="center"><img src="doc/img/json-serialization.png"></p>

which can be consumed from other languages without the need to know about EDN.

## License

Copyright © 2023 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
