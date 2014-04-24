# Disco

A Clojure framework for managing the complexitiies of Service Oriented Architectures, including scaling and deployment.

## Microservices

Disco's microservices are easy to use! You can define a microservice using `defservicefn`, which is almost
exactly like `defn`. The different is that a `defservicefn` requires an execution context and returns a
Listenable Future, which you can `deref` or add a listener to, via `disco.microservices/add-listener`. Let's
take a closer look.

### Local calls

```clojure
(require '[disco.microservices :as m])

;; Define a new servicefn. It implicitly returns a listenable future.
(m/defservicefn product
 [x y z]
 (* x y z))

;; Do a local call
(binding [m/*local* true]
  ;; Get a future with the result
  (let [result (product 1 2 3)]
    ;; Add a callback to be notified when finished
    (m/add-listener result #(println "Got a result!"))
    ;; Can use deref to get the result
    @result))

;; Alternatively, just use like a normal function call
(binding [m/*local* true]
  ;; Just get value
  @(product 1 2 3))
```

Here we can see how to define a servicefn (exactly like a regular `fn`), and how to call it. We can
tell it to use the `*local*` execution context via the `binding`. If we do not provide a context, it
will throw an exception.

The return value is always a listenable future, which can be `deref`ed and you can add listeners to.
These listeners are guaranteed to run if the result was delivered, either previously or in the future.
Compare this to `add-watch`, which only runs if the result has not been delivered yet.

### Automatic service discovery

Servicefns are very easy to call across JVMs with automatic load-balancing. Discovery is handled
by ZooKeeper, using the fantastic Curator library. Nippy provides fast and flexible serialization,
so that overhead is low and server-side exceptions are rethrown on the client-side. The
system has built-in retries, so that if one node is temporarily down, it'll automatically retry. Note
that this mean that all servicefns must be idempotent or purely querying.

To instantiate a `ServiceDiscovery` object, you may call `disco.service-discovery/make-service-discovery`.
This functions takes a `CuratorFramework` as an argument, which can be created by
`disco.curator/make-curator`. You'll need a ZooKeeper cluster for this.

```clojure
;; Create and register all the plumbing to export all servicefns in ns via service-discovery
(m/serve-ns service-discovery 'ns.with.services)

;; When calling a remote, the remote can be a Curator ServiceDiscovery
(binding [m/*remote* service-discovery]
  (let [result (product 1 2 3)]
    (m/add-listener result #(println "Got a result!"))
    @result))
```

This starts up an instance of http-kit on a random port, and register all the servicefns in
the namespace `ns.with.services` with the ZooKeeper cluster.

If you don't have a ZooKeeper cluster or have a different integration scheme in mind, many APIs
are exposed.

### Remote calls

What if you wanted to include the rpc routes as a ring middleware for your own instance of http-kit?
You can do this, as well as specify the exact uri for the RPC to target.

```clojure
;; Define a ring handler
(def app (-> (fn [req] {:status 500 :body ""})
             (m/make-ring-handler "/methods" [#'product])))

;; host #'app in a ring server

;; A remote can be a direct reference to a URL
(binding [m/*remote* "http://uri/to/app/methods/"]
  (let [result (product 1 2 3)]
    (m/add-listener result #(println "Got a result!"))
    @result))
```

`app` is a ring handler that is automatically generated just by passing a collection of servicefn vars.
In order to call functions remotely, just bind `*remote*` to the uri that the `app` is running on. 

Keep in mind that you still get the automatic retries, exception conveyance, and all those goodies,
so you can use your own routing infrastructure and load balancers instead of the built in option.

### Async servicefns

Servicefns can alos be asynchronous for improve scalability. All you need to do is to make them as `^:async`
or `^{:async true}`, and make
sure that they return a listenable future. You can create a listenable future than you can deliver
the result to (like a `promise`) by using `disco.microservices/make-listenable-future` and
`disco.microservices/deliver-result`. You can convert a core.async channel into a listenable
future by calling `disco.microservices/async-chan->listenable-future`. You can convert anything
that implements IDeref (such as `future`s and `promise`s) into a listenable future by
calling `disco.microservices/ideref->listenable-future`.

```clojure
;; Note that the channel returned by the go block must be converted to a listenable future
(m/defservicefn ^:async product
  [x y z]
  (-> (clojure.core.async/go (* x y z))
      (m/async-chan->listenable-future)))

;; or

(m/defservicefn ^:async product
  [x y z]
  (let [p (m/make-listenable-future)]
    (m/deliver-result p (* x y z))
    p))
```

By using async servicefns, you'll be able to achieve ever higher levels of scalability.

## Architecture

Disco has several interesting features so far:

* `disco.service-discovery` - a wrapper around curator-x-service-discovery, a powerful zookeeper library
* `disco.http` - a pair of ring & clj-http middleware that add a service discovery protocol, `disco://`, to your application. It uses a port of the [sparrow](http://people.csail.mit.edu/matei/papers/2013/sosp_sparrow.pdf) distributed scheduling algorithm to minimize latency without require a centralized queue or broker.
* `disco.nginx` - automatically keeps an nginx server's upstream servers matching exactly what is live in your cluster
* `disco.app-server` - runs all your apps, optionally using `disco.http`, on your cluster according to your desired configuration. Uses classloaders for true isolalation.


All the features include either unit tests or demos. You must install nginx and point disco to it yourself to get `disco.nginx` to work.


## License

Copyright © 2014 David Greenberg

Distributed under the Eclipse Public License either version 1.0.
