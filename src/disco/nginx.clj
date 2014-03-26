(ns disco.nginx
  (:require disco.http
            [disco.curator :as curator]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as sh]
            [clostache.parser :refer (render)]
            [disco.service-discovery :as sd]))

(defn create-caches-for-config
  [discovery config]
  (into {} (for [[k v] config]
             [k (assoc v :cache (sd/service-cache discovery (name k)))])))

(def default-template (slurp (io/resource "nginx-template.conf")))

#_(spit "/Users/dgrnbrg/disco/nginx.conf" (doto (render default-template
        {:service
         [{:name "frobulator"
           :path "/frob"
           :server [{:address "localhost" :port 2222}
                    {:address "localhost" :port 2223}]}]}) println))


(defn do-template
  "Takes the template and renders it"
  [template config+caches]
  (render template
          (reduce-kv (fn [c n {:keys [path cache]}]
                       (let [servers (mapv #(select-keys % [:address :port])
                                           (sd/service-cache-members cache))]
                         (update-in c [:service] conj {:name (name n)
                                                       :path path
                                                       :server servers})))
                     {:service []}
                     config+caches)))

(defn reload-nginx
  [nginx-config-file nginx-cmd]
  (let [{:keys [exit] :as result}
        (sh/sh nginx-cmd "-c" nginx-config-file "-s" "reload")]
    (when-not (zero? exit)
      (log/error "Failed to restaurt nginx:\n" (:out result) "\n" (:err result)))))

(defn run-nginx
  "Config is a map from service names to their ports"
  [discovery template config nginx-config-file nginx-cmd]
  (let [service-caches (create-caches-for-config discovery config)
        write-file #(do (spit nginx-config-file (do-template template service-caches))
                        (reload-nginx nginx-config-file nginx-cmd))
        _ (write-file)
        close-delay (delay
                      (doseq [[_ {:keys [cache]}] service-caches]
                        (curator/close cache)))]
    (doseq [[_ {:keys [cache]}] service-caches]
      (sd/service-cache-listen
        cache #(write-file)))
    (fn close [] (deref close-delay))))
