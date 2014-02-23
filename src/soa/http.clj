(ns soa.http
  (:require [ring.util.response :refer (response status)]
            [compojure.core :refer (GET)]
            [clj-http.client :as http]
            [clj-http.core]
            [soa.service-discovery :as sd]
            [clojure.core.async :as async]))

(def probe-route
  (GET "/probe" [] (-> (response "")
                       (status 204))))

(defn ring-probe-middleware
  "Adds a ring route for /probe, which is used to admit new requests"
  [h]
  (fn [req]
    (or (probe-route req) (h req))))

(defn probe-service
  "Asynchronously probes the given service"
  [{:keys [discovery max-concurrent-probes]
    :or {max-concurrent-probes 2}}
   service path]
  (let [terminate? (async/chan)
        members (shuffle (sd/service-members discovery service))
        members-chan (async/chan)
        result-uri (promise)]
    (async/go-loop [[h & t] members]
                   (when h
                     (async/alt!
                       terminate? nil
                       [[members-chan h]] (recur t)
                       :priority true))
                   (async/close! members-chan))
    (dotimes [i max-concurrent-probes]
      (async/go-loop []
                     (when-let [{:keys [address port]} (async/<! members-chan)]
                       (let [base (str "http://" address ":" port)]
                         (when (-> (str base "/probe")
                                   (clj-http.client/get)
                                   (:status)
                                   (= 204))
                           (async/close! terminate?)
                           (deliver result-uri (str base path))))
                       (recur))))
    @result-uri))

(defn clj-http-probe-middleware
  "Looks for requests to disco:// services. Uses the
   provided lookup service to find endpoints. Makes a
   limited number of probes at a time. The first one to
   respond gets the real request."
  [client]
  (fn [req]
    (if-let [[_ service path] (re-matches #"^disco://((?:\p{Alnum}|[-_.])+)(/.*)$" (:url req))]
      (let [chosen-url (probe-service req service path)]
        (assoc (client (assoc req :url chosen-url)) :chosen-url chosen-url))
      (client req))))

(defmacro with-probe-middleware
  "Runs the body with the probe middleware + standard http middleware"
  [& body]
  `(clj-http.client/with-middleware (conj clj-http.client/default-middleware clj-http-probe-middleware)
     ~@body))

(comment

  (keys (clj-http.client/get "http://google.com")) 

  (clj-http.client/with-middleware [clj-http-probe-middleware]
    (clj-http.client/get "disco://google.com"))

  (clj-http.client/with-middleware (conj clj-http.client/default-middleware clj-http-probe-middleware)
    (clj-http.client/get "disco://google.com"))
  )
