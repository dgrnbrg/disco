(ns disco.microservices
  (:require [clojure.tools.macro :as macro]
            [org.httpkit.client :as client]
            [clojure.core.async :as async]
            [disco.predicate-dispatch :as pd]
            [org.httpkit.server :as server]
            [taoensso.nippy :as nippy]
            [disco.service-discovery :as sd]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import java.util.concurrent.atomic.AtomicBoolean
           org.apache.curator.x.discovery.ServiceDiscovery
           java.util.ArrayList))

(def ^:dynamic *local* false)
(def ^:dynamic *remote* nil)

(defprotocol IListen
  (add-listener [this f] "Registers a no-arg listener f to be called when the task is ready."))

(defprotocol IResult
  (deliver-result [pending result] "Delivers a result to the given pending container"))

(deftype MockFuture [value]
  clojure.lang.IBlockingDeref
  (deref [_ _ _] value)
  clojure.lang.IDeref
  (deref [_] value)
  IListen
  (add-listener [_ f] (f)))

(deftype ListenablePromise [p ^ArrayList listeners ^AtomicBoolean delivered?]
  clojure.lang.IBlockingDeref
  (deref [_ ms v] (let [r (deref p ms v)]
                    (when-let [e (::error r)]
                      (throw (ex-info (::msg r) (::map r) e)))
                    r))
  clojure.lang.IDeref
  (deref [_] (let [r (deref p)]
                    (when-let [e (::error r)]
                      (throw (ex-info (::msg r) (::map r) e)))
                    r))
  IResult
  (deliver-result [_ r]
    (locking delivered?
      (.lazySet delivered? true)
      (deliver p r)  
      (doseq [l listeners]
        (l)))
    (.clear listeners))
  IListen
  (add-listener [_ f]
    (locking delivered?
      (if (.get delivered?)
        (f)
        (.add listeners f)))))

(def decoders
  {"application/edn" (fn [input-stream] (edn/read (java.io.PushbackReader. (io/reader input-stream))))
   "application/x-nippy" (fn [input-stream] (nippy/thaw-from-in! (java.io.DataInputStream. input-stream)))})

(def encoders
  {"application/edn" pr-str
   "application/x-nippy" (fn [obj] (let [baos (java.io.ByteArrayOutputStream.)] 
                                     (nippy/freeze-to-out! (java.io.DataOutputStream. baos) obj)
                                     (java.io.ByteArrayInputStream. (.toByteArray baos))))})


(pd/defpred to-listenable-future
  "Converts its argument to a listenable future")

(defn make-listenable-future
  []
  (ListenablePromise. (promise) (ArrayList.) (AtomicBoolean.)))

;; TODO: these functions that are converting things
;; to listenable futures should be included into a single
;; predicate dispatch function, so that all conversions can
;; be done automatically
(pd/defimpl to-listenable-future
  ([chan]
   (instance? clojure.core.async.impl.channels.ManyToManyChannel chan))
  ([chan]
   (let [f (make-listenable-future)]
     (async/go (deliver-result f (async/<! chan)))
     f)))

(pd/defimpl to-listenable-future
  ([ideref]
   (or (instance? clojure.lang.IDeref ideref)
       ;;TODO should java.util.concurrent.Future be here?
       (instance? clojure.lang.IBlockingDeref ideref)))
  ([ideref]
   (let [f (make-listenable-future)]
     (future (deliver-result f @ideref))
     f)))

(pd/defimpl to-listenable-future
  ([mf]
   (instance? MockFuture mf))
  ([mf]
   mf))

(defn deferred-exception
  "Wraps an exception into a special sentinal object"
  [ex]
  {::error ex})

(declare remote-call)

(defmacro defservicefn
  [name-sym & args]
  (let [[name-sym fntail] (macro/name-with-attributes name-sym args)
        meta-map (meta name-sym)
        name-str (name name-sym)
        internal-name (gensym name-str)
        name-sym (with-meta name-sym (assoc meta-map
                                            ::impl internal-name
                                            :method (get meta-map
                                                         :method :post)
                                            ::service-fn true))
        args-sym (gensym "args_")
        local-invocation (if (or (:async meta-map)
                                 (= :async (:tag meta-map)))
                           `(apply ~internal-name ~args-sym)
                           `(MockFuture. (apply ~internal-name ~args-sym)))]
    `(do
       (defn ~internal-name ~@fntail)
       (defn ~name-sym
         [& ~args-sym]
         (cond *remote*
               (remote-call (var ~(symbol (name (ns-name *ns*)) (name name-sym))) ~args-sym)
               *local*
               ~local-invocation
               :else
               (throw (ex-info "Please set *local* or *remote* to call a service fn" {:local *local* :remote *remote*})))))))

(defn get-uri-from-remote
  [ns name]
  (let [string-path (str (ns-name ns) \/ name)
        remote *remote*]
    (cond (string? remote) (str remote string-path)
          (instance? ServiceDiscovery remote)
          (let [members (sd/service-members remote string-path)
                _ (when (empty? members)
                    (throw (ex-info "There are no service members"
                                    {:path string-path
                                     :remote remote})))
                {:keys [address port]} (rand-nth members)]
            (str "http://" address \: port "/methods/" string-path)))))

(defn remote-call
  [service-var args]
  (let [meta-map (meta service-var)]
    (if (= (:method meta-map) :get)
      (throw (ex-info "unimplemented: get" {:var service-var :args args}))
      (let [{:keys [ns name]} (meta service-var)
            content-type "application/edn"
            result (ListenablePromise. (promise) (ArrayList.) (AtomicBoolean.))
            uri #(get-uri-from-remote ns name)
            post-opts {:body ((get encoders content-type) args)
                       ::retries 10
                       :headers  {"Content-Type" content-type
                                  "Accept" "application/x-nippy"}}
            handler
            (fn handler [{:keys [body error status]
                          {retries ::retries} :opts
                          {:keys [content-type]} :headers
                          :as resp}]
              (if (and (or error
                           (not (<= 200 status 299)))
                       (not (zero? retries)))
                (do (println "retry" retries)
                    (client/post (uri)
                                 (assoc post-opts
                                        ::retries (dec retries))
                                 handler
                                 ))
                (let [r ((get decoders content-type
                              (fn [_]
                                (throw
                                  (ex-info "failed to decode response"
                                           resp))))
                         body)]
                  (deliver-result result (if (::error r)
                                           (assoc r
                                                  ::msg "RPC Error"
                                                  ::map resp)
                                           r)))))]
        (client/post (uri) post-opts handler)
        result))))

(defn decode-post-args
  [request]
  ((get decoders (:content-type request)
        (fn [_] (throw (ex-info "Unknown Content-Type" request))))
   (:body request)))

(defn decode-get-args
  [request]
  (throw (ex-info "implment decode-get-args") {})
  )

(defn format-result
  [request result]
  ;;TODO parse accept
  (let [accept (get-in request [:headers "accept"])]
    {:body ((get encoders accept (fn [_] (throw (ex-info "Unmatched accept encoding" request)))) result)
     :status 200
     :headers {"Content-Type" accept}}))

(defn search-vars
  "Does linear scan of the given service vars to see"
  [service-vars ns name]
  (some (fn [target]
          (let [{ns' :ns name' :name} (meta target)]
            (when (and (= ns (str (ns-name ns'))) (= name (str name')))
              target)))
        service-vars))

(defn decode-args
  [request]
  (if (= :get (:request-method request))
    (decode-get-args request)
    (decode-post-args request)))

(defn apply-local-service-var
  [var args]
  (binding [*local* true]
    (try
      (apply var args)
      (catch Exception e
        (MockFuture. (deferred-exception e))))))

(defn listenable-future->http-kit-async-result
  [request result]
  (server/with-channel request channel
    (add-listener
      result
      (fn []
        (server/send!
          channel
          (format-result request @result))))))

(defn make-ring-handler
  "Takes a next-level handler, a mount point for the microservices
   (usually /methods), the service vars to expose"
  [handler root service-vars]
  (let [regex (re-pattern (str root "/([^/]+)/(.+)"))]
    (fn [request]
      (let [[_ ns name] (re-matches regex (:uri request))]
        (if ns
          (try
            (if-let [match (search-vars service-vars ns name)]
              (if (= (:request-method request) (-> match meta :method))
                (let [args (decode-args request)
                      result (-> (apply-local-service-var match args)
                                 (to-listenable-future))]
                  (listenable-future->http-kit-async-result request result))
                (throw (ex-info "Rpc method incorrect"
                                {:expected (-> match meta :method)
                                 :got (:request-method request)})))
              (throw (ex-info "No matching rpc"
                              {:ns ns
                               :name name
                               :uri (:uri request)})))
            (catch Exception e
              {:body (doto (with-out-str (pr-str (ex-data e)) \newline (clojure.stacktrace/print-cause-trace e))
                       println
                       )
               :headers {"Content-Type" "text/plain"}
               :status 500}))
          (handler request))))))

(defn service-var-to-service
  "This converts a service var into a Service, for use with curator service discovery.
   
   See disco.service-discovery/service-instance for more service-args"
  [service-var & {:as service-args}]
  (let [m (meta service-var)
        ns (str (ns-name (:ns m)))
        n (str (:name m))]
    (when (:name service-args)
      (throw (ex-info "Service args shouldn't have a :name"
                      {:name service-args})))
    (apply sd/service-instance :name (str ns \/ n)
           (apply concat service-args))))

(defn serve-ns
  [service-discovery ns-symbol]
  (let [vars (->> (ns-map (find-ns ns-symbol))
                  (filter (fn [kv] (and (-> kv val var?)
                                        (-> kv val meta ::service-fn))))
                  (map val))
        _ (when (empty? vars)
            (throw (ex-info "There are no service fns in the namespace"
                            {:ns-symbol ns-symbol
                             :ns (find-ns ns-symbol)})))
        ;; TODO retry server until port found
        port (+ 10000 (rand-int 5000))
        server (server/run-server (make-ring-handler
                                    (fn [r]
                                      {:body "error - no defined handler"
                                       :status 404})
                                    "/methods"
                                    vars)
                                  {:port port})
        host "localhost" #_(.getHostName (java.net.InetAddress/getLocalHost))
        services (for [v vars] (service-var-to-service v
                                                       :address host
                                                       :port port))]
    (doseq [s services]
      (sd/register-service service-discovery s))
    (fn close []
      (doseq [s services]
        ;;TODO unregister doesn't work
        (sd/unregister-service service-discovery s))
      (server))))
