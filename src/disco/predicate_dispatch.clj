(ns disco.predicate-dispatch
  (:require [clojure.tools.macro :as macro]))

(defn pred-search
  "Internal use only"
  [var args]
  (let [options (-> var meta :pred-table deref)
        result (some (fn [[p f]]
                       (when (try
                               (apply p args)
                               (catch clojure.lang.ArityException e
                                 false))
                         {::result (apply f args)}))
                     options)]
    (if-let [r (::result result)]
      r
      (throw (ex-info "Couldn't find matching predicate" {:var var :args args})))))

(defmacro defpred
  "Defines a function based on predicate disptach"
  [name-sym & args]
  (let [[name-sym args] (macro/name-with-attributes name-sym args)
        atom-sym (gensym "atom-holder")]
    (when (seq args)
      (throw (ex-info "Doesn't take arguments" {:name name-sym :args args})))
    `(do
       (defn ~(->> (meta name-sym)
                   (with-meta name-sym))
         [& args#]
         (pred-search (var ~name-sym) args#))
       (alter-meta! (var ~name-sym) assoc :pred-table (atom [])))))

(defn- fntail?
  [x]
  (and (seq? x)
       (vector? (first x))
       (next x)))

(defmacro defimpl
  "Defines an implementation of a predicate function.
   
   Bodies are fntails or lists of fntails.

   TODO: ensure that they don't re-add if already included
   "
  [pred test-body impl-body]
  (when-not (-> (resolve pred)
                meta
                :pred-table)
    (throw (ex-info "Not a predicate function" {:fn pred})))
  (let [test-body (if (fntail? test-body)
                    (list test-body)
                    test-body)
        impl-body (if (fntail? impl-body)
                    (list impl-body)
                    impl-body)]
    `(swap! (-> (var ~pred) meta :pred-table)
          conj
          [(fn ~@test-body)
           (fn ~@impl-body)])))
