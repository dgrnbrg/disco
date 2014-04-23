(ns disco.microservices
  (:require [clojure.tools.macro :as macro]
            [org.httpkit.client :as client]
            [org.httpkit.server :as server]
            [taoensso.nippy :as nippy]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import java.util.concurrent.atomic.AtomicBoolean
           java.util.ArrayList))

(def ^:dynamic *local* false)
(def ^:dynamic *remote* nil)

(defprotocol IListen
  (add-listener [this f] "Registers a listener to be called when the task is ready."))

(defprotocol IResult
  (deliver-result [this r] "Delivers a result to the target"))

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
        (l))))
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


(declare remote-call)

(defmacro defservicefn
  [name-sym & args]
  (let [[name-sym fntail] (macro/name-with-attributes name-sym args)
        meta-map (meta name-sym)
        meta-map (if-not (contains? meta-map :method)
                   (assoc meta-map :method :post)
                   meta-map)
        name-str (name name-sym)
        internal-name (gensym )
        name-sym (with-meta name-sym (assoc meta-map ::impl internal-name))]
    `(do
       (defn ~internal-name ~@fntail)
       (defn ~name-sym
       [& args#]
       (cond *remote*
             (remote-call (var ~(symbol (name (ns-name *ns*)) (name name-sym))) args#)
             *local*
             (MockFuture. (apply ~internal-name args#))
             :else
             (throw (ex-info "Please set *local* or *remote* to call a service fn" {:local *local* :remote *remote*})))))))

(defn remote-call
  [service-var args]
  (let [meta-map (meta service-var)]
    (if (= (:method meta-map) :get)
      (throw (ex-info "unimplemented: get" {:var service-var :args args}))
      (let [{:keys [ns name]} (meta service-var)
            content-type "application/edn"
            result (ListenablePromise. (promise) (ArrayList.) (AtomicBoolean.))]
        (client/post (str *remote* (ns-name ns) \/ (str name))
                     {:body ((get encoders content-type) args)
                      :headers  {"Content-Type" content-type
                                 "Accept" "application/x-nippy"}}
                     (fn [{:keys [body] {:keys [content-type]} :headers :as resp}]
                       (let [r ((get decoders content-type
                                     (fn [_] (throw (ex-info "failed to decode response" resp)))) body)]
                         (deliver-result result (if (::error r)
                                                  (assoc r
                                                         ::msg "RPC Error"
                                                         ::map resp)
                                                  r)))))
        result))))

(comment
  (def http-kit (server/run-server #'app {:port 8090}))
  (http-kit)

  (throw (nippy/thaw (nippy/freeze (try (throw (ex-info "lol" {})) (catch Exception e e)))))

  (def app (make-ring-handler (fn [h] {:body "oops" :status 500}) "/methods" [#'bar]))
  (try
    (binding [*remote* "http://localhost:8090/methods/"]
    (deref (bar 1 2 7) 1000 :fail)
    (let [r (bar 1 2 3)]
      (add-listener r (fn [] (println "Got an answer!" @r)))
      @r
      (add-listener r (fn [] (println "2nd instantly!" @r)))
      ))
    (catch Exception e
      (println (ex-data e))
      (clojure.stacktrace/print-cause-trace e)))
  )

(defn make-descriptor
  [service-var]
  (let [meta-map (meta service-var)
        method (get meta-map :method :post)

        ])
  )

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

(defn make-ring-handler
  [handler root descriptors]
  (let [regex (re-pattern (str root "/([^/]+)/(.+)"))]
    (fn [request]
      (let [[_ ns name] (re-matches regex (:uri request))]
        (if ns
          (try
            (if-let [match (some (fn [target]
                                   (let [{ns' :ns name' :name} (meta target)]
                                     (when (and (= ns (str (ns-name ns'))) (= name (str name')))
                                       target)))
                                 descriptors)]
              (if (= (:request-method request) (-> match meta :method))
                (let [args (if (= :get (-> match meta :method))
                             (decode-get-args request)
                             (decode-post-args request))
                      result (binding [*local* true]
                               (try
                                 @(apply match args)
                                 (catch Exception e
                                   {::error e})))]
                  (format-result request result))
                (throw (ex-info "Rpc method incorrect" {:expected (-> match meta :method)
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

(defn register-descriptors
  [descriptors port & {:keys [host]}]
  )

(defn make-ns-desciptors
  [ns]
  )

(defn serve-ns
  [ns]
  )

#_(clojure.pprint/pprint (clojure.walk/macroexpand-all
  '(defservicefn foo
  "Does cool stuff"
  [x y z]
  (println "bye bye")
  )))

(defservicefn ^{:method :get} foo
  "Does cool stuff"
  [x y z]
  (println "bye bye")
  (+ x y z)
  )

(defservicefn bar
  "Does cool stuff"
  [x y z]
  (println "bye bye")
  (throw (ex-info "lol side" {:remote *remote* :local *local*}))
  (+ x y z)
  )

;(binding [*local* true] (add-listener (foo 1 2 3) (fn [] (println "yay!"))))
