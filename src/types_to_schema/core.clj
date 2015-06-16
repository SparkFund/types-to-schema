(ns types-to-schema.core
  "Converts Typed Clojure types to Prismatic Schemas for runtime checking"
  {:core.typed {:collect-only true}}
  (:require [clojure.set :as set]
            [clojure.core.typed :as t]
            [clojure.core.typed.hole]
            [clojure.core.typed.parse-ast :as ast]
            [clojure.core.typed.errors :as err]
            [clojure.core.typed.ast-ops :as ops]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed.util-vars :as vs]
            [schema.core :as s]))

(def ^:dynamic *inside-rec* #{})

;;; This is probably more of general utility?
(defrecord SchemaMap [f schema desc]
  s/Schema
  (walker [this]
    (let [sub-walker (s/subschema-walker schema)]
      (clojure.core/fn [x]
        (sub-walker (f x)))))
  (explain [this] (list 'schema-map desc (s/explain schema))))

(defn schema-map
  "fmap for schemas (applies f to values before schema checking)"
  [f schema desc]
  (SchemaMap. f schema desc))

(defn rclass-preds [t]
  (case t
    (clojure.lang.Seqable)
    {:args #{1}
     :pred (fn [a?]
             (schema-map seq [a?] `seq))}
    (clojure.lang.IPersistentCollection
     clojure.lang.ISeq
     clojure.lang.IPersistentStack
     clojure.lang.IPersistentVector
     clojure.lang.APersistentVector
     clojure.lang.PersistentVector
     clojure.lang.ASeq
     java.lang.Iterable                 ; ASeq resolves to this?
     java.util.Collection               ; ASeq resolves to this too?
     java.util.List                     ; ASeq resolves to this too?
     clojure.lang.Cons
     clojure.lang.IPersistentList
     clojure.lang.PersistentList
     clojure.lang.LazySeq)
    {:args #{1}
     :pred (fn [a?]
             [a?])}
    (clojure.lang.IPersistentSet
     clojure.lang.APersistentSet
     clojure.lang.PersistentHashSet
     clojure.lang.PersistentTreeSet)
    {:args #{1}
     :pred (fn [a?]
             #{a?})}
    (clojure.lang.Associative)
    (throw "TODO") ;; Can be a int-lookup vector or a map, dispatch on runtime value.
    (clojure.lang.IMapEntry
     clojure.lang.AMapEntry
     clojure.lang.MapEntry)
    {:args #{2}
     :pred  (fn [a? b?]
              (s/map-entry a? b?))}
    (clojure.lang.Reduced)
    (throw "TODO") ;; Check if dereffing the runtime value is of the right type?
    (clojure.lang.IPersistentMap
     clojure.lang.APersistentMap
     clojure.lang.PersistentHashMap)
    {:args #{2}
     :pred  (fn [a? b?]
              {a? b?})}))

(declare ast->schema)

(defmulti tapp-schema
  "Given a :TApp type, if the operator name matches the symbol, return a custom schema"
  (fn [t _] (-> t :rator :name)))

(defmethod tapp-schema `t/Option
  [t name-env]
  (assert (= 1 (count (:rands t))) "only single argument to t/Option allowed")
  (s/maybe (ast->schema (first (:rands t)) name-env)))

(defmethod tapp-schema :default
  [t name-env]
  (let [{:keys [rator rands]} t]
    (cond
      ;; needs resolving
      (#{:Name} (:op rator))
      (ast->schema (update-in t [:rator] ops/resolve-Name) name-env)
      ;; polymorphic class
      (#{:Class} (:op rator))
      (let [{:keys [args pred] :as rcls} (rclass-preds (:name rator))
            _ (when-not rcls
                (err/int-error (str "Class does not take arguments: "
                                    (type (:name rator)))))
            _ (when-not (args (count rands)) ; FIXME This  never fails due to a Typed Clojure bug
                (err/int-error (str "Wrong number of arguments to "
                                    (:name rator) ", expected " args
                                    " actual " (count rands))))
                                        ;_ (prn "rands: " rands)
                                        ;_ (throw (ex-info "Nope" {}))
            rands-p (map (fn [ast]
                           (ast->schema ast name-env))
                         rands)]
        (s/both (eval (:name rator))
                (apply pred rands-p)))
      ;; substitute
      (#{:TFn} (:op rator))
      (ast->schema (ops/instantiate-TFn rator rands) name-env)
      :else
      (err/int-error (str "Don't know how to apply type: " (:form t))))))

(defn ast->schema
  "given type syntax, returns actual prismatic schema as data, not a macro

  This is conservative for Fn type -- only checks it's an IFn, doesn't wrap
  w/ higher order contract.

  Throws ex-info {:type ::ast->schema} when we don't know how to create a schema for the type."
  [{:keys [op] :as t} name-env]
  (case op
    (:F) (throw (ex-info "Cannot generate predicate for free variable" {:type ::ast->schema}))
    (:Poly) (throw (ex-info  "Cannot generate predicate for polymorphic type" {:type ::ast->schema}))
    (:PolyDots) (throw (ex-info "Cannot generate predicate for dotted polymorphic type" {:type ::ast->schema}))
    (:Fn) clojure.lang.IFn
    (:TApp) (tapp-schema t name-env)
    (:Class) (eval (:name t))
    (:Name)
    (impl/impl-case
     :clojure (if-let [myself (get @name-env (:name t))]
                (s/named (s/recursive myself) (:name t))
                (let [myself (atom nil)]
                  (swap! name-env assoc (:name t) myself) ;; crucially happens before the recursive call
                  (reset! myself (ast->schema (ops/resolve-Name t) name-env))
                  (s/named (s/recursive myself) (:name t))))
     :cljs (err/int-error (str "TODO CLJS Name")))
                                        ; (cond
                                        ;  (empty? (:poly? t)) `(instance? ~(:the-class t) ~arg)
                                        ;  :else (err/int-error (str "Cannot generate predicate for polymorphic Class")))
    (:Any) s/Any
    (:U) (apply s/either (mapv #(ast->schema % name-env) (:types t))) ;; TODO Could we generate a nice name from the type? Prismatic schema's Either isn't so informative
    (:I) (apply s/both (mapv #(ast->schema % name-env) (:types t)))
    (:HVec) (if (:drest t)
              (throw (ex-info  "Cannot generate predicate for dotted HVec" {:type ::ast->schema}))
              (vec (concat (map-indexed (fn [idx ti] (s/one (ast->schema ti name-env) (str "idx " idx))) (:types t))
                           (when (:rest t) [(ast->schema (:rest t) name-env)]))))
    (:CountRange) (s/both (s/either (s/eq nil)
                                    (s/pred coll? 'coll?))
                          (s/pred (if (:upper t)
                                    #(<= (:lower t) (count %) (:upper t))
                                    #(<= (:lower t) (count %)))
                                  (str "#(<= " (:lower t) " (count %) " (when (:upper t) (:upper t)) ")")))
    (:singleton) (s/eq (:val t))
    (:HMap)
    , (let [mand (apply hash-map (:mandatory t))
            opt  (apply hash-map (:optional t))
            base-scm (into
                      {}
                      (vec (concat
                            (->> mand
                                 (map (fn [[k v]] [(:val k)
                                                   (ast->schema v name-env)])))
                            (->> opt
                                 (map (fn [[k v]] [(s/optional-key (:val k))
                                                   (ast->schema v name-env)])))
                            (when (not (:complete? t))
                              [[s/Any s/Any]]))))]
        (if (empty? (:absent-keys t))
          base-scm
          (err/int-error (str "Cannot generate predicate for :absent-keys"))))
    (:Rec) (throw (ex-info  "Cannot generate predicate for recursive types" {:type ::ast->schema}))
    (throw (ex-info (str op " not supported in type->pred: " (:form t)) {:type ::ast->schema}))))

(defn wrap-with-validation
  "Only works for single-method fns at the moment."
  [f fn-sym dom-schemas rng-schema]
  (fn [& args]
    (let [r (apply f (map-indexed
                      (fn [idx [s arg]]
                        (try (s/validate s arg)
                             (catch java.lang.IllegalArgumentException e
                               (throw (ex-info (str "Bad schema for argument " idx " of " fn-sym)
                                               {:schema s
                                                :value arg
                                                :fn-sym fn-sym
                                                :which-schema [:argument idx]})))))
                      (map vector dom-schemas args)))]
      (try (s/validate rng-schema r)
           (catch java.lang.IllegalArgumentException e
             (throw (ex-info (str "Bad return schema for" fn-sym)
                             {:schema rng-schema
                              :value r
                              :fn-sym fn-sym
                              :which-schema :return-schema})))))))

(defn fn-schemas
  "ns-qualified fn-sym. Looks up the fns type in the typed clojure impl/var-env
  returns nil if fn-sym doesn't have type annotation.

  only works for single-method fns for now."
  [fn-sym name-env]
  (when-let [parse (get @impl/var-env fn-sym)]
    (let [meths (:arities @parse)
          _ (when (not= 1 (count meths)) (throw (ex-info "only single arity validation for now"
                                                         {:type ::ast->schema})))
          meth (first meths)
          dom-schemas (impl/with-impl impl/clojure
                        (->> (:dom meth)
                             (mapv #(ast->schema % name-env))
                             (map-indexed (fn [i s] (s/named s [fn-sym :domain i])))
                             (doall)))
          rng-schema  (impl/with-impl impl/clojure
                        (-> (:rng meth)
                            (ast->schema name-env)
                            (s/named [fn-sym :range])))]
      {:dom-schemas dom-schemas
       :rng-schema rng-schema})))

;;; Can we take just the var? Is there any way to go from var->symbol?
;;; TODO can we save the real unwrapped one in metadata so we don't re-wrap forever?
(defn wrap-fn-sym!
  "wraps validation to a ns-qualified fn, if it has a type annotation"
  [fn-sym name-env]
  (if-let [s (fn-schemas fn-sym name-env)]
    (let [vr (resolve fn-sym)
          prev (::before-validation-added (meta vr))]
      (do (if (some? prev)
            (alter-var-root vr (fn [_] (wrap-with-validation prev fn-sym (:dom-schemas s) (:rng-schema s))))
            (do
              (alter-meta! vr assoc ::before-validation-added (deref vr))
              (alter-var-root vr wrap-with-validation fn-sym (:dom-schemas s) (:rng-schema s))))
          :added))
    :not-added))

(defn unwrap-fn-sym!
  "unwraps validation from a ns-qualified fn.
  silently leaves non-wrapped fns as-is."
  [fn-sym]
  (let [vr (resolve fn-sym)
        prev (::before-validation-added (meta vr))]
    (if (some? prev)
      (do (alter-var-root vr (constantly prev))
          (alter-meta! vr dissoc ::before-validation-added)
          :removed)
      :not-removed)))

(defn type-syntax->schema [t name-env]
  (impl/with-impl impl/clojure
    (-> (ast/parse t)
        (ast->schema name-env))))

(defmacro schema
  "Generate a Prismatic Schema for type.

  The current type variable and dotted type variable scope is cleared before parsing.

  eg. (s/validate (schema Number) 1)
      ;=> 1"
  [t]
  `(type-syntax->schema (quote ~t) (atom {})))

(defn wrap-namespaces!
  "tries to wrap every fn in the namespaces with an annotation. Ignores fns that
  we can't create wrappers for (polymorphic types etc)

  Returns {:added (list of symbols wrapped by this call)
           :not-added (list of symbols not wrapped by this call)}

  This will unwrap and re-wrap symbols that have already been wrapped."
  [nses]
  (let [name-env (atom {})
        qual-syms (for [ns nses
                        [sym _var] (ns-interns ns)]
                    (symbol (str ns) (str sym)))]
    (apply merge-with concat
           (for [qs qual-syms]
             {(try (wrap-fn-sym! qs name-env)
                   (catch clojure.lang.ExceptionInfo e
                     (when (not= ::ast->schema (:type (ex-data e)))
                       (throw (ex-info (str "Error wrapping " qs)
                                       (assoc (ex-data e)
                                              :qual-sym qs))))
                     :not-added))
              [qs]}))))

(defn unwrap-namespaces!
  "tries to wrap every fn with an annotation. Ignores fns that we can't create
  wrappers for (polymorphic types etc)"
  [nses]
  (let [name-env (atom {})
        qual-syms (for [ns nses
                        [sym _var] (ns-interns ns)]
                    (symbol (str ns) (str sym)))]
    (doseq [qs qual-syms]
      (unwrap-fn-sym! qs))))

(defn wrap-namespaces-fixture [ns-syms]
  (fn [f]
    (wrap-namespaces! (map find-ns ns-syms))
    (f)
    (unwrap-namespaces! (map find-ns ns-syms))))

#_
(do
  (require '[clojure.core.typed :as t])
  (require '[schema.core :as s])
  (require '[spark.util.types-to-schema :as tts])
  (require '[clojure.core.typed.current-impl :as impl]))
#_
(do
  (tts/wrap-namespaces! [(find-ns 'spark.types)])
  (require '[spark.types :as st])
  ;; Keep error messages not so huge
  (type-syntax->schema `(t/Option String) (atom nil))
  (try (s/validate (type-syntax->schema 'Long (atom nil)) "ok")
       (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:value :error])))
  (tts/ast->schema @(get @impl/var-env 'spark.types/transaction) (atom nil))
  (let [var-env (atom nil)]
    (impl/with-impl impl/clojure
      (mapv #(ast->schema % var-env)
            (:dom (first (:arities @(get @impl/var-env `spark.logic.funding/remaining-principal-at-month-exact))))))))