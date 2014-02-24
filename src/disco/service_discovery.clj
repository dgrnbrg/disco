(ns disco.service-discovery
  (:import [org.apache.curator.framework CuratorFrameworkFactory]
           [org.apache.curator.retry RetryNTimes]
           [org.apache.curator.x.discovery.details ServiceCacheListener]
           [org.apache.curator.x.discovery ServiceDiscoveryBuilder ServiceInstance ServiceDiscovery ServiceCache]))

(defn close
  [^java.io.Closeable c]
  (.close c))

(defn make-curator
  "Simple curator creation fn"
  [zkservers]
  (doto (CuratorFrameworkFactory/newClient zkservers 10000 60000 (RetryNTimes. 10 1000))
    (.start)))

(defn make-service-discovery
  "Returns a service discovery rooted at the given location"
  [curator root]
  (doto (.. (ServiceDiscoveryBuilder/builder Void)
            (client curator)
            (basePath root)
            (build))
    (.start)))

(defn register-service
  "Registers a service instance with ZK"
  [^ServiceDiscovery discovery service]
  (.registerService discovery service))

(defn unregister-service
  "Unregisters a service instance with ZK"
  [^ServiceDiscovery discovery service]
  (.unregisterService discovery service))

(defn service-members
  [^ServiceDiscovery discovery name]
  (mapv bean (.queryForInstances discovery name)))

(defmacro builder-helper
  "Takes  a list of fields and
   makes a function that calls the builder
   with all the given fields, if they're present."
  [arg in fields]
  (let [builder-sym (gensym "builder_")
        input-sym (gensym "input_")
        setters (->> fields
                     (map (fn [f]
                            (let [n (name f)
                                  kw (keyword n)]
                              `(if-let [x# (~kw ~input-sym)]
                                 (~(symbol (str \. n)) ~builder-sym x#)
                                 ~builder-sym)))))]
    `(let [~builder-sym ~arg
           ~input-sym ~in
           ~@(interleave (repeat builder-sym) setters)]
       (~'.build ~builder-sym))))

(defn service-instance
  "Creates a service instance object."
  [& {:as settings}]
  (builder-helper (ServiceInstance/builder) settings
                  [address
                   id
                   name
                   port
                   registrationTimeUTC
                   serviceType
                   sslPort
                   uriSpec]))

(defn service-cache
  "Creates a service cache"
  [^ServiceDiscovery discovery service-name]
  (doto (.. (.serviceCacheBuilder discovery)
            (name service-name)
            (build))
    (.start)))

(defn service-cache-members
  "Gets all instances from the service cache"
  [^ServiceCache cache]
  (mapv bean (.getInstances cache)))

(defn service-cache-listen
  "No arg function which is provided will be invoked
   whenever the contents of the cache change. To remove
   the listener, invoke the return value."
  [^ServiceCache cache f]
  (let [listener (reify ServiceCacheListener
                   (cacheChanged [_]
                     (f)))]
    (.addListener cache listener)
    (fn []
      (.removeListener cache listener))))
