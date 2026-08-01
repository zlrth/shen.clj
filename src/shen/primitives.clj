(ns shen.primitives
  (:require [clojure.core :as c]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.walk :as w]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io Reader Writer InputStream OutputStream PrintWriter OutputStreamWriter]
           [java.util Arrays StringTokenizer]
           [clojure.lang Compiler$CompilerException ArityException])
  (:refer-clojure :exclude [set intern let pr type cond cons str number? string? defmacro
                            + - * / > < >= <= = and or])
  (:gen-class))


(c/defmacro ^:private pred-cond 
  "Checks each predicate against the item, returning the corresponding 
   result if it finds a match, otherwise returning nil.
   Assumes item to be a value, as it will get evaluated multiple times."
  [item pred result & preds+results]
  (c/cond (c/= pred :else ) result
        (not (seq preds+results)) `(if (~pred ~item) ~result nil) ;; last condition, but no :else in the form
        :else `(if (~pred ~item)
                 ~result
                 (pred-cond ~item ~@preds+results))))

(create-ns 'shen)
(create-ns 'shen.globals)

(def ^:const string? c/string?)
(def ^:const number? c/number?)

(c/defmacro assert-boolean
  ([x] `(assert-boolean ~x "%s is not a boolean"))
  ([x fmt]
     `(c/let [x# ~x]
        (if (instance? Boolean x#) x#
            (throw (IllegalArgumentException. (format ~fmt x#)))))))

(c/defmacro if-kl
  ([test] `(c/let [test# ~test] (fn [then# else#] (if-kl test# then# else#))))
  ([test then] `(partial (if-kl ~test) ~then))
  ([test then else] `(if (assert-boolean ~test "boolean expected: not %s") ~then ~else)))

(c/defmacro and
  ([x] `(fn [y#]
          (boolean (c/and (assert-boolean ~x) (assert-boolean y#)))))
  ([x & [y & xs]]
     `(if-let [x# (assert-boolean ~x)]
        ~(if xs `(and ~y ~@xs) `(assert-boolean ~y))
        false)))

(c/defmacro or
  ([x] `(fn [y#]
          (boolean (c/or (assert-boolean ~x) (assert-boolean y#)))))
  ([x & [y & xs]]
     `(if-let [x# (assert-boolean ~x)]
        true
        ~(if xs `(or ~y ~@xs) `(assert-boolean ~y)))))

(defn ^:private and-fn
  ([x] (and x))
  ([x y] (and x y)))

(defn ^:private or-fn
  ([x] (or x))
  ([x y] (or x y)))

(defn ^:private partials [name parameters]
  (for [p (map #(take % parameters) (range 1 (count parameters)))]
    `(~(vec p) (partial ~name ~@p))))

;; Shen 41 puts almost all of its internals in the `shen` package, so kernel
;; names look like `shen.walk` and `shen.*macros*`. Clojure will happily read
;; and even `def` such a symbol, but it can never resolve one: at any use site
;; the compiler sees the dot and tries to load a class instead. The dot has to
;; go, and `/` is unwelcome for the same reason it always was.
;;
;; So names are held munged inside Clojure and unmunged in strings -- `intern`
;; munges on the way in, `str` unmunges on the way out, which is what keeps
;; round trips like Shen's `concat` (str, cn, intern) honest and keeps the
;; printer showing `shen.walk` rather than the internal spelling.
(def ^:private ^:const dot-escape "-dot-")
(def ^:private ^:const slash-escape "-slash-")
;; `_` is escaped for a subtler reason than the other two. It is a perfectly
;; good Clojure symbol character, but AOT compilation names a function's class
;; after the munged symbol, and Clojure's own munging maps `-` to `_`. So
;; shen.initialise-environment and shen.initialise_environment -- distinct vars,
;; both real kernel functions -- would compile to one class file, the second
;; silently overwriting the first. Keeping `_` out of munged names makes that
;; mapping invertible, and so collision-free. The failure is invisible until you
;; AOT compile, where it presents as a function with the wrong body.
(def ^:private ^:const underscore-escape "-underscore-")

(defn ^:private munge-name [^String n]
  ;; `/` is Shen's division and `/.` its lambda; both are legal Clojure symbols
  ;; as they stand, and both are what the reader and printer expect to see.
  (c/case n
    "/" "/"
    "/." "/."
    (-> n
        (s/replace "." dot-escape)
        (s/replace "/" slash-escape)
        (s/replace "_" underscore-escape))))

(defn ^:private demunge-name [^String n]
  (-> n
      (s/replace dot-escape ".")
      (s/replace slash-escape "/")
      (s/replace underscore-escape "_")))

(defn munge-symbol
  "Rewrites a symbol read out of a KLambda file into one Clojure can resolve."
  [sym]
  (c/let [n (c/name sym)]
    (if (re-find #"[./_]" n) (symbol (munge-name n)) sym)))

(defn ^:private shen-internal-fn? [s]
  (c/and (symbol? s) (re-find #"shen-dot-" (c/name s))))

(defn ^:private may-cause-invalid-codesize? [s]
  (c/< 2000 (count (flatten s))))

(defn ^:private may-return-fn [name parameters body]
  (if-not (c/or (shen-internal-fn? name) (may-cause-invalid-codesize? body))
    `([~@parameters & extra#]
        (c/let [result# (eval-shen ~@body)]
          (if extra# (apply result# extra#)
              result#)))
    `([~@parameters & extra#]
        (throw (ArityException. (+ ~(count parameters) (count extra#)) (c/name name))))))

(declare eval-shen)

(c/defmacro defun [F X & Y]
  (c/let [F (if (seq? F) (eval F) F)]
    `(do
       (defn ^:dynamic ~F
         ~@(partials F X)
         (~(vec X) ~@Y)
         ~(may-return-fn F X Y))
       '~F)))

(def ^:private array-class (Class/forName "[Ljava.lang.Object;"))

(defn =
  ([X] (partial = X))
  ([X Y]
     (c/cond
       (c/and (identical? array-class (class X))
             (identical? array-class (class Y))) (Arrays/equals #^"[Ljava.lang.Object;" X
                                                                #^"[Ljava.lang.Object;" Y)
             (c/and (number? X) (number? Y)) (== X Y)
             :else (c/= X Y))))

(defn /
  ([X] (partial / X))
  ([X Y]
     (if (zero? Y) (throw (IllegalArgumentException. "division by zero"))
         (c/let [r (clojure.core// X Y)]
           (if (ratio? r) (double r) r)))))

(defn ^:private alias-op [op real-op]
  (eval `(defun ~op ~'[X Y] (~real-op ~'X ~'Y))))

(doseq [op '[+ - *]]
  (alias-op op (symbol "clojure.core" (c/str (name op) "'"))))

(doseq [op '[> < >= <=]]
  (alias-op op (symbol "clojure.core" (name op))))

(defn ^:private interned? [X]
  (c/and (seq? X) (= 'intern (first X))))

(def ^:private ^:const slash-dot (symbol "/."))

(defn ^:private recur?
  ([path] (partial recur? path))
  ([path fn]
     (c/or (= 'cond (last (drop-last path)))
           ;; `if-kl`, not `if`: the translator renames `if` before the path is
           ;; extended, so spelling it `if` here never matched anything. That
           ;; left a self-call in the tail of an `(if ...)` body compiling to
           ;; real recursion -- which only shows up once pattern factorisation
           ;; is enabled, since that is what makes the compiler emit `if` bodies
           ;; rather than `cond` ones.
           (set/superset? '#{defun cond if-kl do let}
                          (c/set path)))))

(declare set*)

(defn ^:private maybe-declare
  "Interns a placeholder so that a forward reference compiles. Resolution is
   explicitly against `shen`, the namespace the generated code runs in, and not
   against the ambient *ns*: translation is reachable from ordinary Clojure via
   eval-kl, and resolving `lambda` in, say, `user` finds nothing and would
   intern a nil placeholder straight over the `lambda` macro in `shen`."
  [kl]
  (when (and (symbol? kl)
             (= (name kl) (c/str kl))
             (not (ns-resolve 'shen kl)))
    (set* kl nil 'shen))
  kl)

;; Namespace-qualified on purpose. Shen 41's reader.kl defines `function`
;; itself, as `(fn V)` -- the currying lookup, which goes through arity, get,
;; the property vector, hash and map. Emitting a bare `function` here would
;; therefore route every higher-order application back through map, which calls
;; a local in head position, which emits `function` again: an infinite loop
;; before the REPL ever starts. What the translator wants is the port's own
;; primitive, and no kernel defun can shadow a qualified name.
(def ^:private ^:const apply-function `function)

(defn ^:private maybe-apply [kl path]
  (if (= 'cond (last path)) kl
      (list apply-function kl)))

(defn shen-kl-to-clj
  ([kl] (shen-kl-to-clj kl #{} [] :unknown))
  ([kl scope] (shen-kl-to-clj kl scope [] :no-recur))
  ([kl scope path fn]
     (pred-cond kl
                scope kl
                symbol? (c/case (name kl)
                           "true" true
                           "false" false
                           (list 'quote kl))
                seq? (c/let [[fst snd trd & rst] kl
                        ;; noted before fst is rewritten to if-kl below, so that
                        ;; the test still gets translated as a non-tail position
                        if? ('#{if} fst)
                        fn (if ('#{defun} fst)
                             snd
                             fn)
                        scope (c/cond
                                (get '#{defun} fst) (into scope trd)
                                (get '#{let lambda} fst) (conj scope snd)
                                :else scope)
                        fst (pred-cond fst
                              (every-pred
                               #{fn}
                               (recur? path)) 'recur
                              (some-fn
                               interned?
                               scope) (maybe-apply fst path)
                              seq? (maybe-apply (shen-kl-to-clj fst scope) path)
                              '#{if} 'if-kl
                              :else
                              (if (= 'cond (last path))
                                (shen-kl-to-clj fst scope)
                                (maybe-declare fst)))
                        path (conj path fst)
                        snd (c/cond
                              (get '#{defun let lambda} fst) snd
                              if? (shen-kl-to-clj snd scope)
                              :else (shen-kl-to-clj snd scope path fn))
                        trd (c/cond
                              (get '#{defun} fst) trd
                              (get '#{let} fst) (shen-kl-to-clj trd scope)
                              :else (shen-kl-to-clj trd scope path fn))]
                       (take-while (complement nil?)
                                   (concat [fst snd trd]
                                           (map #(shen-kl-to-clj % scope path fn) rst))))
      :else kl)))

(defn intern [String]
  (symbol (munge-name String)))

(c/alter-var-root #'intern c/memoize)

(c/defmacro cond [[test expr] & clauses]
  (list 'if-kl test expr
        (when clauses
          (c/cons 'cond clauses))))

(defn set* [X Y ns]
  @(c/intern (the-ns ns)
             (with-meta X {:dynamic true :declared true})
             Y))

(defn set
  ([X] (partial set X))
  ([X Y] (set* X Y 'shen.globals)))

(defn ^:private value* [X ns]
  ;; c/when, not c/and: a non-symbol X would leave `false` here, and `false` is
  ;; not nil, so the :else branch would try to deref it.
  (c/let [v (c/when (symbol? X) (ns-resolve ns X))]
    (c/cond
      (= X 'and) and-fn
      (= X 'or) or-fn
      (nil? v) (throw (IllegalArgumentException. (c/str "variable " X " has no value")))
      :else @v)))

(defn value [X] (value* X 'shen.globals))

(defn function [fn]
  (if (fn? fn) fn
      (value* fn 'shen)))

(defn simple-error [String]
  (throw (RuntimeException. ^String String)))

(defn clj-function
  "Resolves a namespace-qualified symbol -- `c/count`, say -- to the Clojure
   function it names. Shen 41 compiles an application whose head has no
   registered arity into a lambda-form lookup, so Shen code reaching into
   Clojure needs this as the fallback when that lookup comes up empty."
  [V]
  (c/let [v (c/when (c/and (symbol? V) (namespace V)) (ns-resolve 'shen V))
          f (c/when v @v)]
    (c/cond
      (c/and v (:macro (meta v)))
      (simple-error (c/str "fn: " V " is a Clojure macro; Shen can call Clojure"
                           " functions but not macros"))
      (fn? f) f
      :else (simple-error (c/str "fn: " V " is undefined")))))

(c/defmacro trap-error [X F]
  `(try
     ~X
     (catch Exception e#
       (~F e#))))

(defn error-to-string [E]
  (if (instance? Throwable E)
    (c/or (.getMessage ^Throwable E) (c/str E))
    (throw (IllegalArgumentException. ^String (c/str E " is not an exception")))))

(defn ^:private pair [X Y] [X Y])

(defn ^:private pair? [X]
  (c/and (vector? X) (= 2 (count X))))

(defn cons [X Y]
  (if (c/and (coll? Y)
             (not (pair? Y)))
    (c/cons X Y)
    (pair X Y)))

(defn hd [X] (first X))

(defn tl [X]
  (if (pair? X)
    (second X)
    (rest X)))

(defn fail! [] (assert false))

(defn cons? [X]
  (c/and (coll? X) (not (.isEmpty ^java.util.Collection X))))

(defn str [X]
  (c/cond
    (coll? X) (throw (IllegalArgumentException.
                      (c/str X " is not an atom; str cannot convert it to a string.")))
    ;; symbols read back out in their Shen spelling, not their munged one.
    ;; c/str rather than c/name: a symbol reaching Shen from embedded Clojure
    ;; may be namespace-qualified, and c/name would silently drop the alias.
    (symbol? X) (demunge-name (c/str X))
    :else (c/pr-str X)))

(def ^:private ^:const cons-bar (symbol "|"))

(defn ^:private seq-to-cons
  ([x] (seq-to-cons x false))
  ([[fst & rst] recursive?]
     (c/let [conv #(if (c/and recursive? (sequential? %))
                     (seq-to-cons % recursive?)
                     %)]
       (c/cond
         (nil? fst) ()
         ;; [X | Y] is a cons pair, not a three element list. Shen's own reader
         ;; gives `|` this meaning; Clojure's reads it as an ordinary symbol,
         ;; so embedded Shen -- (defprolog mem X [X | _] <--;) -- needs it here.
         (= cons-bar (first rst)) (list 'cons (conv fst) (conv (second rst)))
         :else (list 'cons (conv fst) (seq-to-cons rst recursive?))))))

(defn ^:private cleanup-clj [clj]
  (pred-cond clj
    vector? (recur (seq-to-cons clj))
    coll? (if ('#{clojure.core/deref} (first clj))
            (symbol (c/str "@" (second clj)))
            clj)
    '#{λ} slash-dot
    char? (intern clj)
    ;; Shen written inline in Clojure arrives via Clojure's reader, so it has
    ;; never been through `intern` and is unmunged -- `_` in (defprolog mem X
    ;; [X | _] <--;) would not match the kernel's own `_`. Munging is
    ;; idempotent, so KLambda coming back through here is unaffected.
    ;; Namespace-qualified symbols are Clojure interop and are left alone.
    (every-pred symbol? (complement namespace)) (munge-symbol clj)
    :else clj))

(defn eval-shen* [body]
  (c/let [body (w/postwalk cleanup-clj body)]
    (binding [*ns* (the-ns 'shen)]
      (->> body
           (map (function 'eval))
           last))))

(c/defmacro eval-shen [& body]
  `(c/let [env# (zipmap '~(keys &env) ~(vec (keys &env)))]
     (eval-shen* (w/postwalk-replace env# '~body))))

(c/defmacro 神 [& body]
  `(eval-shen ~@body))

(c/defmacro define [name & body]
  `(do
     (eval-shen (~'define ~name ~@body))
     (defn ~(with-meta name {:dynamic true})
       [& ~'args]
       ;; Looked up by name at call time. Shen 41's `define` hands back a
       ;; printable stand-in for the function -- it prints as `(fn foo)` -- and
       ;; not anything callable.
       (apply (function (intern ~(c/str name))) ~'args))))

(doseq [[name args] '{defmacro [name] defprolog [name] prolog? [] package [name exceptions]}]
  (eval
   `(c/defmacro ~name [~@args & ~'body]
      `(eval-shen ~(concat ['~name ~@args] ~'body)))))

(defn eval-kl [X]
  ;; Translation as well as evaluation: shen-kl-to-clj resolves symbols while
  ;; it works, and must do so as the `shen` namespace sees them.
  (binding [*ns* (the-ns 'shen)]
    (eval (shen-kl-to-clj (cleanup-clj X)))))

(c/defmacro lambda [X Y]
  `(fn [~X & XS#]
     (c/let [result# ~Y]
       (if XS# (apply result# XS#)
           result#))))

(c/defmacro λ [X Y]
  `(lambda ~X ~Y))

(c/defmacro let [X Y Z]
  (c/let [X-safe (if (seq? X) (gensym (eval X)) X)
          Z (if (seq? X)
              (w/postwalk #(if (= X %) X-safe %) Z)
              Z)]
    `(c/let [~X-safe ~Y]
       ~Z)))

(c/defmacro freeze [X]
 `(fn [] ~X))

;; Shen 41's (fail) is the symbol shen.fail!, so that is what an unwritten slot
;; should read back as.
(def ^:private shen-fail (intern "shen.fail!"))

(defn absvector [N]
  (doto (object-array (int N)) (Arrays/fill shen-fail)))

(defn absvector? [X]
  (identical? array-class (c/class X)))

(defn <-address [#^"[Ljava.lang.Object;" Vector N]
  (aget Vector (int N)))

(defn address-> [#^"[Ljava.lang.Object;" Vector N Value]
  (aset Vector (int N) Value)
  Vector)

(defn n->string [N]
  (c/str (char N)))

(defn string->n [S]
  (c/int (first S)))

(def byte->string n->string)

(defmulti pr (fn [_ S] (class S)))

(defmethod pr Reader [X ^Reader S]
  (if (= *in* S)
    (pr X *out*)
    (throw (IllegalArgumentException. (c/str S)))))

(defmethod pr OutputStream [X ^OutputStream S]
  (pr X (OutputStreamWriter. S)))

(defmethod pr Writer [X ^Writer S]
  (binding [*out* (if (= S (value '*stoutput*))
                    *out*
                    S)]
    (print X)
    (flush)
    X))

(defmulti read-byte class)

(defmethod read-byte InputStream [^InputStream S]
  (.read S))

(defmethod read-byte Reader [^Reader S]
  (.read S))

(defn ^:private ^Writer target-writer
  "Shen captures *stoutput* once, during initialisation. Rebinding Clojure's
   *out* afterwards -- with-out-str, say -- still has to capture Shen's output,
   so writes aimed at the captured stdout follow *out* instead of the stream
   object recorded back then."
  [S]
  (c/let [v (ns-resolve 'shen.globals '*stoutput*)]
    (if (c/and v (identical? @v S)) *out* S)))

;; Shen 41 defines `pr` in the kernel (writer.kl) rather than taking it as a
;; primitive, and drives it from write-byte plus the character-stream hooks
;; below. Reporting a character stream lets `pr` hand over a whole string at a
;; time instead of recursing a byte at a time.
(defmulti write-byte (fn [_ S] (class S)))

(defmethod write-byte OutputStream [B ^OutputStream S]
  (.write S (c/int B))
  B)

(defmethod write-byte Writer [B S]
  (c/let [w (target-writer S)]
    (.write w (c/int B))
    (.flush w)
    B))

(defn shen-dot-char-stoutput? [S]
  (instance? Writer S))

(defn shen-dot-write-string [String S]
  (c/let [w (target-writer S)]
    (.write w ^String String)
    (.flush w)
    String))

;; Input stays on the byte path: read-byte already reports EOF as -1, which is
;; what the kernel's shen.my-read-byte expects, and a character read has no way
;; to say the same thing through a one-character string.
(defn shen-dot-char-stinput? [S] false)

(defn shen-dot-read-unit-string [S]
  (c/let [b (read-byte S)]
    (if (neg? b) "" (c/str (char b)))))

;; Shen 41 dropped the leading stream-type argument: (open String Direction).
(defn open [String Direction]
  (c/let [Path (io/file (value '*home-directory*) String)]
    (c/cond
      (= 'in Direction) (io/input-stream Path)
      (= 'out Direction) (io/output-stream Path)
      :else (throw (IllegalArgumentException.
                    (c/str "invalid direction: " Direction))))))

;; A special form, not a function: `(type X T)` annotates X with the type
;; expression T and evaluates to X. T is data -- defcc emits things like
;; `(type (cons the ()) (list symbol))`, where `(list symbol)` is a type, not a
;; call -- so it must never be evaluated.
(c/defmacro type [X _MyType] X)

(defn close [^java.io.Closeable Stream]
  (.close Stream))

(defn pos [X N]
  (c/str (get X N)))

(defn tlstr [X]
  (subs X 1))

(defn cn
  ([Str1] (partial cn Str1))
  ([Str1 Str2]
     (c/let [strings (replace {() ""} [Str1 Str2])]
       (when-let [no-string (first (remove string? strings))]
         (throw (IllegalArgumentException. (c/str no-string " is not a string"))))
       (apply c/str strings))))

(def ^:private internal-start-time (System/currentTimeMillis))

(defn get-time [Time]
  (c/cond
    (= Time 'run) (/ (- (System/currentTimeMillis) internal-start-time) 1000)
    (= Time 'unix) (long (/ (System/currentTimeMillis) 1000))
    :else (throw (IllegalArgumentException.
                  (c/str "get-time does not understand the parameter " Time)))))

(defn system [Command]
  (:out (apply sh/sh (-> Command StringTokenizer. enumeration-seq))))


(defmethod print-method array-class [o ^Writer w]
  (print-method (vec o) w))

(defn parse-shen
  "Reads Shen source into a sequence of s-expressions."
  [s]
  ((function 'read-from-string) s))

(defn parse-and-eval-shen [s]
  (eval-shen* (parse-shen s)))

(defn reset-macros!
  "Drops user-defined macros, keeping the kernel's own."
  []
  ;; *macros* is an association list of (name . expander) pairs.
  (set '*macros* (filter #(shen-internal-fn? (hd %)) (value '*macros*))))

(defn exit
  ([] (exit 0))
  ([status] (System/exit status)))

; (load "ffi.shen") ; http://www.shenlanguage.org/library.html
; (ffi clj (@p shen->clj send-clj) (@p clj->shen receive-clj))

; (call-ffi clj *clojure-version*)
; [[:major | 1] [:minor | 4] [:incremental | 0] [:qualifier | nil]]

; (call-ffi clj (System/currentTimeMillis))
; 1336093159995


(defn shen->clj [x]
  (w/postwalk #(pred-cond %
                          #{(symbol "nil")} nil
                          symbol? (symbol (demunge-name (name %)))
                          :else %)
              x))

(defn send-clj [x]
  (binding [*ns* (the-ns 'clojure.core)]
    (eval x)))

(defn clj->shen [x]
  (pred-cond x
    nil? ()
    :else x))

(defn receive-clj [x] x)

(c/defmacro ^:dynamic call-ffi [foreign-language code]
  `(if-not ('~'#{clj clojure} ~foreign-language)
     (throw (IllegalArgumentException. (c/str "we don't know how to talk to " ~foreign-language)))
     (-> '~code shen->clj send-clj receive-clj clj->shen)))