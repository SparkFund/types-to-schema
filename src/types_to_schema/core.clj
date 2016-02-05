(ns types-to-schema.core
  "Converts Typed Clojure types to Prismatic Schemas for runtime checking"
  {:core.typed {:collect-only true}}
  (:require [clojure.tools.reader :as r]
            [clojure.core.typed :as t]
            [clojure.core.typed.ast-ops :as ops]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed.errors :as err]
            [clojure.core.typed.parse-ast :as ast]
            [schema.core :as s]))

(def wrappers-created
  "For coverage checking. A set of every wrapper that's been created, by function symbol."
  (atom #{}))
(def wrappers-called
  "For coverage checking. A set of every wrapper that's been called, by function symbol."
  (atom #{}))

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
              {a? b?})}
    (clojure.lang.Atom)
    (throw (ex-info "types-to-schema can't wrap Atom types"
                    {:type ::ast->schema}))))

(declare ast->schema)

(defn sequential-schema
  [t name-env]
  (let [{:keys [types drest rest]} t]
    (when drest
      (throw (ex-info  "Cannot generate predicate for dotted sequential form"
                       {:type ::ast->schema})))
    (vec (concat (map-indexed (fn [idx ti]
                                (s/one (ast->schema ti name-env)
                                       (str "idx " idx)))
                              types)
                 (when rest [(ast->schema rest name-env)])))))

(defmulti tapp-schema
  "Given a :TApp type, if the operator name matches the symbol, return a custom schema"
  (fn [t _] (-> t :rator :name)))

(defmethod tapp-schema `t/Option
  [t name-env]
  (assert (= 1 (count (:rands t))) "only single argument to t/Option allowed")
  (s/maybe (ast->schema (first (:rands t)) name-env)))

;; This samples the first element of a lazy seq and checks it against the
;; given seq type
(defmethod tapp-schema `t/NonEmptyLazySeq
  [t name-env]
  (assert (= 1 (count (:rands t))))
  (let [schema [(s/one (ast->schema (first (:rands t)) name-env) "idx0")]
        pred (fn [x]
               (let [lazy? (instance? clojure.lang.LazySeq x)
                     sentinel (when lazy? (take 1 x))]
                 (when (seq sentinel)
                   (nil? (s/check schema sentinel)))))]
    (s/pred pred "non-empty lazy seq")))

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
    (:Any) s/Any
    (:U) (apply s/either (mapv #(ast->schema % name-env) (:types t))) ;; TODO Could we generate a nice name from the type? Prismatic schema's Either isn't so informative
    (:I) (apply s/both (mapv #(ast->schema % name-env) (:types t)))
    (:HVec)
    (s/both (s/pred vector? 'vector?)
            (sequential-schema t name-env))
    (:HSequential)
    (s/both (s/pred sequential? 'sequential?)
            (sequential-schema t name-env))
    (:HSeq)
    (sequential-schema t name-env)
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
          (let [absent-keys (set (map :val (:absent-keys t)))
                pred (fn [m] (not (some absent-keys (keys m))))]
            (s/both base-scm (s/pred pred "absent-keys")))))
    (:Rec) (throw (ex-info  "Cannot generate predicate for recursive types" {:type ::ast->schema}))
    (throw (ex-info (str op " not supported in type->pred: " (:form t)) {:type ::ast->schema}))))

(defn wrap-single-arity-fn-with-validation
  "Wraps a single-method fn with arity checking."
  [f fn-sym {:keys [dom-schemas rest-schema rng-schema]}]
  (swap! wrappers-created conj fn-sym)
  (fn [& args]
    (swap! wrappers-called conj fn-sym)
    (when-not (if (some? rest-schema)
                (<= (count dom-schemas) (count args))
                (= (count dom-schemas) (count args)))
      (throw (ex-info (str "Supplied " (count args) " argument" (when (< 1 (count args)) "s")
                           " when " fn-sym " expected "
                           (if (some? rest-schema)
                             (str "at least " (count dom-schemas))
                             (count dom-schemas))
                           " argument" (when (< 1 (count dom-schemas)) "s"))
                      {:fn-sym fn-sym})))
    (let [r (apply f (map-indexed
                      (fn [idx [s arg]]
                        (try (s/validate s arg)
                             (catch java.lang.IllegalArgumentException e
                               (throw (ex-info (str "Bad schema for argument " idx " of " fn-sym)
                                               {:schema s
                                                :value arg
                                                :fn-sym fn-sym
                                                :which-schema [:argument idx]})))))
                      (map vector (concat dom-schemas (repeat rest-schema)) args)))]
      (try (s/validate rng-schema r)
           (catch java.lang.IllegalArgumentException e
             (throw (ex-info (str "Bad return schema for" fn-sym)
                             {:schema rng-schema
                              :value r
                              :fn-sym fn-sym
                              :which-schema :return-schema})))))))

(defn wrap-multi-arity-fn-with-validation
  [f fn-sym method-schemas]
  (let [[vararg fixed] ((juxt filter remove) #(some? (:rest-schema %)) method-schemas)
        [vararg-fixed-count vararg-wrapped]
        ,(do (when (< 1 (count vararg))
               (throw (ex-info "multiple varargs schemas defined"
                               {:fn-sym fn-sym
                                :method-schemas method-schemas})))
             (when (= 1 (count vararg))
               [(count (:dom-schemas (first vararg)))
                (wrap-single-arity-fn-with-validation f fn-sym
                                                      (first vararg))]))
        ;; {0 wrapped-method, 1 wrapped-method, ...}
        fixed-arity->wrapped (apply merge-with
                                    (fn [l r] (throw (ex-info "duplicated fixed arity:" {:l l :r r})))
                                    {}
                                    (for [s fixed]
                                      {(count (:dom-schemas s))
                                       (wrap-single-arity-fn-with-validation f fn-sym s)}))]
    (when (and vararg-wrapped
               (< vararg-fixed-count (apply max (keys fixed-arity->wrapped))))
      (throw (ex-info "Can't have fixed arity function with more params than variadic function"
                      {:fn-sym fn-sym
                       :vararg-wrapped vararg-wrapped
                       :fixed-arity->wrapped fixed-arity->wrapped
                       :method-schemas method-schemas})))
    (fn [& args]
      (if-let [fixed (get fixed-arity->wrapped (count args))]
        (apply fixed args)
        (if vararg-wrapped
          (apply vararg-wrapped args)
          (throw (ex-info "No matching arity"
                          {:arity (count args)
                           :fn-sym fn-sym
                           :method-schemas method-schemas})))))))

(defn fn-schemas
  "ns-qualified fn-sym. Looks up the fns type in the typed clojure impl/var-env
  returns nil if fn-sym doesn't have type annotation.

  only works for single-method fns for now."
  [fn-sym parse name-env]
  (let [meths (:arities parse)
        _ (def meths meths)
        ;; At least for toplevel annotations, it seems Typed Clojure
        ;; represents multiple arities always as a TApp of :Fn with :rands of
        ;; the various actual :Fn types for each arity. So each :arities only
        ;; ever seems to contain a single item.
        _ (when (not= 1 (count meths))
            (throw (ex-info "types-to-schema only expects a single arity per :Fn at this point."
                            {:type ::ast->schema})))
        meth (first meths)
        dom-schemas (impl/with-impl impl/clojure
                      (->> (:dom meth)
                           (mapv #(ast->schema % name-env))
                           (map-indexed (fn [i s] (s/named s [fn-sym :domain i])))
                           (doall)))
        rest-schema (when (:rest meth)
                      (impl/with-impl impl/clojure
                        (-> (:rest meth)
                            (ast->schema name-env)
                            (s/named [fn-sym :rest]))))
        rng-schema  (impl/with-impl impl/clojure
                      (-> (:rng meth)
                          (ast->schema name-env)
                          (s/named [fn-sym :range])))]
    {:dom-schemas dom-schemas
     :rest-schema rest-schema
     :rng-schema rng-schema}))

(defn wrap-fn-sym!
  "wraps validation to a ns-qualified fn, if it has a type annotation"
  [fn-sym name-env]
  (let [parse (when-let [p (get (impl/with-impl impl/clojure (impl/var-env)) fn-sym)] @p)
        wrap-fn (cond
                  (= :Fn (:op parse))
                  ,(fn [f] (wrap-single-arity-fn-with-validation f
                                                                 fn-sym
                                                                 (fn-schemas fn-sym parse name-env)))
                  (and (= :TApp (:op parse)) (every? #(= :Fn (:op %)) (:rands parse)))
                  ,(fn [f] (wrap-multi-arity-fn-with-validation f
                                                                fn-sym
                                                                (map #(fn-schemas fn-sym % name-env)
                                                                     (:rands parse))))
                  :else nil)]
    (if wrap-fn
      (let [vr (resolve fn-sym)
            prev (::before-validation-added (meta vr))]
        (do (if (some? prev)
              (alter-var-root vr (fn [_] (wrap-fn prev)))
              (do
                (alter-meta! vr assoc ::before-validation-added (deref vr))
                (alter-var-root vr wrap-fn)))
            :added))
      ;; o/w it's not a function and we should try to schema-validate the value itself - nothing needs to be wrapped.
      (do (swap! wrappers-created conj fn-sym)
          (swap! wrappers-called conj fn-sym)
          (let [schema (impl/with-impl impl/clojure (ast->schema parse name-env))]
            (try (s/validate schema @(resolve fn-sym))
                 (catch java.lang.IllegalArgumentException e
                   (throw (ex-info (str "Bad schema for" fn-sym)
                                   {:schema schema
                                    :value @(resolve fn-sym)
                                    :fn-sym fn-sym})))))
          :not-added))))

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
  `(type-syntax->schema (r/syntax-quote ~t) (atom {})))

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
                                              :qual-sym qs
                                              :original-message (.getMessage e)))))
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

;;; TODO Think about if we /want/ to leave some functions wrap-fn-sym!'d in
;;; production, and NOT unwrapped by the test fixture.
(defn wrap-namespaces-fixture
  "Wraps all functions it can in namespaces named by ns-syms and unwraps all of them.
  CAUTION: Will unwrap functions you've manually picked to be wrapped via wrap-fn-sym!"
  [ns-syms]
  (fn [f]
    (wrap-namespaces! (map find-ns ns-syms))
    (f)
    (unwrap-namespaces! (map find-ns ns-syms))))

#_
(do
  (require '[clojure.core.typed :as t])
  (require '[schema.core :as s])
  (require '[types-to-schema.core :as tts])
  (require '[clojure.core.typed.current-impl :as impl]))
