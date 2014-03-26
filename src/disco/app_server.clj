(ns disco.app-server
  (:require [disco.service-discovery :as sd]
            [disco.curator :as curator]
            [clojure.set :as set]
            [classlojure.core :as classlojure])
  (:import org.apache.curator.framework.recipes.cache.ChildData
           java.net.URL))

;;; Design of services
;;;
;;; The app-server should have a root node which contains
;;; nada.
;;;
;;; Then, it should have a child node for each service.
;;; The service node should contain a clj map with the following keys:
;;; - :id - unique id for the service on the machine
;;; - :classpath - defines the jars in the service
;;; - :service-ns - defines the entry point ns of the service
;;; any other keys will get passed along to the service's init fn
;;;
;;; For now, we can coalesce all the services into the single root node
;;;
;;; A service ns should have several fns
;;; - `start` takes a single argument, the config map, and returns a map:
;;; - `stop` is a shutdown fn for the entire service/classloader.
;;; - `reload` is an optionally provided function that gets
;;; called when the config changes, but not the classpath
;;;
;;; When we get an update message, here's the converge alg
;;; 1) Compute 3 groups: services to add, services to remove, services that remained
;;; 2) Of the services that remained, check whether their config changed. If it did, check whether the classpath changed or they don't have a reload fn.
;;; 3) start up the services to add, shut down the services to remove, stop&start the services that had classpath changes or don't have reload fns, and reload the services that do have reload fns

(defn data->clj
  [^ChildData data]
  (read-string
    (String. (.getData data))))

(comment
  (do
    (def cp (mapv
              (fn [p]
                (if (.isDirectory (java.io.File. p))
                  (str "file:" p "/")
                  (str "file:" p)))
              (.split (System/getProperty "java.class.path") ":")))
    (def actual-state (atom {}))
    (def server
      (serve (:curator disco.core-test/services)
             "/test/path"
             actual-state)))

  (disco.curator/write-path
    (:curator disco.core-test/services)
    "/test/path"
    (.getBytes (pr-str {"test" {:classpath cp
                                :service-ns "disco.sample-service"
                                ;:garbage2 :tester     
                                :garbage :tester}})))

  )

(declare converge)

(defn serve
  [curator path actual-state]
  (let [desired-state (curator/node-cache curator path)
        ;actual-state (atom {})
        listener (curator/listen
                   desired-state (fn []

                                   (future
                                     (try
                                     (converge
                                       actual-state 
                                       (data->clj @desired-state))
                                       (catch Throwable t
                                         (.printStackTrace t)
                                         (flush)
                                         )
                                       ))))]
    ;(converge actual-state (data->clj @desired-state))
    (fn close []
      (listener)
      (desired-state)
      (doseq [service @actual-state]
        
        )
      )))

(defn prep-converge
  [desired-state actual-state]
  (let [actual-keys (set (keys actual-state))
        desired-keys (set (keys desired-state))
        added (set/difference desired-keys actual-keys)
        removed (set/difference actual-keys desired-keys)
        changed (->> (set/intersection actual-keys desired-keys)
                     (filter (fn [k]
                               (not= (get-in actual-state [k :config])
                                     (get desired-state k)))))]
    {:added added
     :removed removed
     :changed changed}))

(defn start-services
  [state services]
  (println "Starting" services)
  (doseq [[id {:keys [classpath service-ns] :as cfg}] services
          :let [cl (apply classlojure/classlojure classpath)]]
    (classlojure/eval-in
      cl
      `(do (require 'disco.sample-service)
           (require '~(symbol (name service-ns)))
           ((resolve '~(symbol (name service-ns) "start")) ~cfg)))
    (swap! state assoc id {:classloader cl
                           :config cfg})))

(defn stop-services
  [state services]
  (println "Stopping" services)
  (doseq [[id _] services
          :let [cl (get @state :classloader)
                service-ns (get-in @state [:config :service-ns])]]
    (classlojure/eval-in cl (list (symbol (name service-ns) "stop")))
    (swap! state dissoc id)))

(defn reload-services
  [state services]
  (println "Reloading" services)
  (doseq [[id cfg] services
          :let [cl (get-in @state [id :classloader])
                service-ns (get-in @state [id :config :service-ns])]]
    (if (= :stop
           (classlojure/eval-in
             cl
             `(if-let [f# (try
                            (resolve '~(symbol (name service-ns) "reload"))
                            (catch ClassNotFoundException ~'e
                              nil))]
                (do (f# ~cfg) :ok)
                (do (~(symbol (name service-ns) "stop")) :stop))))
      ; If we shut down due to lack of reload support, restart
      (start-services state {id cfg})
      (swap! state update-in [id :config] cfg))))

(defn converge
  [actual-state desired-state]
  (let [{:keys [added removed changed] :as x} (prep-converge
                                                desired-state
                                                @actual-state)]
    (println "got plan:" x)
    (start-services actual-state (select-keys desired-state added))
    (stop-services actual-state (select-keys desired-state removed))
    (reload-services actual-state (select-keys desired-state changed))))
