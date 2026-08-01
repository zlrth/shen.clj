(ns shen.test
  (:use [clojure.test]
        [shen.primitives :only (value set shen-kl-to-clj λ 神 define defmacro defprolog prolog?
                                      reset-macros! package parse-shen parse-and-eval-shen)])
  (:refer-clojure :exclude [eval defmacro set for filter])
  ;; shen.install renders the kernel from KLambda if that hasn't happened yet,
  ;; so it has to load before shen.
  (:require [shen.install]
            [shen]))

(define super
  [Value Succ End] Action Combine Zero ->
  (if (End Value)
    Zero
    (Combine (Action Value)
             (super [(Succ Value) Succ End]
                    Action Combine Zero))))

(define for
  Stream Action -> (super Stream Action do 0))


(define filter
  Stream Condition ->
  (super Stream
         (λ Val (if (Condition Val) [Val] []))
         append
         []))

(deftest shenlanguage.org
  ;; Shen 41 compiles an application whose head has no registered arity into a
  ;; lambda-form lookup, and the port resolves namespace-qualified names from
  ;; there -- which reaches Clojure functions but not Clojure macros, since a
  ;; macro's var holds a two-extra-argument expander rather than the callable
  ;; the lookup wants. So `for` is captured on the Clojure side here.
  (is (= "0123456789"
         (with-out-str (神 (for [0 (+ 1) (= 10)] print)))))

  (are [shen out] (= out (with-out-str (shen/print shen)))

       ;; Shen calling a Clojure function.
       (神
        (c/count "0123456789"))
       "10"

       (神
        (filter [0 (+ 1) (= 100)]
                (λ X (integer? (/ X 3)))))
       ;; shen.iter-list stops at *maximum-print-sequence-size* elements and
       ;; appends "... etc"; Shen 8 used to run one element into the ellipsis.
       "[0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57 ... etc]"

       ))

(deftest interop
  (defprolog mem
    X [X | _] <--\;
    X [Y | Z] <-- (mem X Z)\;)

  (define factorial
    0 -> 1
    X -> (* X (factorial (- X 1))))

  (are [shen out] (= out (with-out-str (shen/print shen)))

       (filter [0 (partial + 1) (partial = 100)]
               #(integer? (/ % 3)))
       ;; shen.iter-list stops at *maximum-print-sequence-size* elements and
       ;; appends "... etc"; Shen 8 used to run one element into the ellipsis.
       "[0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57 ... etc]"

       (prolog? (mem 1 [X | 2]) (return X))
       "1"

       (factorial 5)
       "120"

       (let [n 5]
         (神
          (factorial n)))
       "120"

       ))

(deftest shen-defmacro
  (are [shen out] (re-find (re-pattern out) (with-out-str (shen/print shen)))

       (神
        (clj-exec (/ 8 2)))
       (str
        "run time: .+ secs" "\n"
        "4")

       (神
        (parsed-exec (/ 2 0)))
       "failed"

       ))

(deftest partials
  (define foo
    X -> (λ Y Y))

  (are [shen result] ((if (fn? result) result #{result}) shen)

       (神
        ((λ X Y (/ X Y)) 10 5))
       2

       (神
        ((λ X Y (+ X Y)) 2))
       fn?

       (神
        ((λ X (integer? (/ X 3))) 3))
       true

       (神
        (foo 1))
       fn?

       (神
        (foo 1 2))
       2

       ))

(deftest packages
  (are [shen out] (= out (with-out-str (-> shen parse-and-eval-shen)))

       "(package null () (print 1) (print 2))"
       "12"

       ))

(deftest cons-pair
  (are [shen result] ((if (fn? result) result #{result}) shen)

       (神
        (cons 1 2))
       [1 2]

       (神
        (cons 1 (cons 2 ())))
       '(1 2)

       (神
        [1 2])
       '(1 2)

       ))

(deftest printer
  (are [shen out] (= out (with-out-str (shen/print shen)))

       (神
        ())
       "[]"

       (神
        (cons 1 (cons 2 ())))
       "[1 2]"

       (神
        (cons 1 2))
       "[1 | 2]"

       (神
        (cons (cons 1 2) 3))
       "[[1 | 2] | 3]"

       (神
        (cons 1 (cons 2 3)))
       "[1 2 | 3]"

       ;; A raw absvector is not a `vector?` -- slot 0 holds (fail) rather than
       ;; a length -- so the printer renders it as a bare array.
       (神
        (absvector 1))
       "<<...>>"

       (神
        (vector 1))
       "<...>"

       (神
        (vector 0))
       "<>"

       (神
        (@p 1 2))
       "(@p 1 2)"

       ))

(deftest eval
  (are [shen result] ((if (fn? result) result #{result})
                      (-> shen parse-and-eval-shen))

       "((/. X (+ X 2)) 1)"
       3

       "((/. X Y (+ X Y)) 2 2)"
       4

       "((/. X Y (+ X Y)) 2)"
       fn?

       "(filter [0 (+ 1) (= 100)] (/. X (integer? (/ X 3))))"
       seq?

       ;; Shen 41 returns a printable stand-in for the function, not its name.
       "(defprolog f a <--;)"
       #(= "(fn f)" (with-out-str (shen/print %)))

       "(cond (true \"/\"))"
       "/"

       "(= 1.0 1)"
       true?

       ))

(deftest dual-namespace
  (set 'dual-namespace true)
  (is (true? @(resolve 'shen.globals/dual-namespace)))
  (is (true? (value 'dual-namespace)))
  (is (nil? (resolve 'shen/dual-namespace)))

  (set 'element? nil)
  (is (nil? (value 'element?)))
  (is (fn? shen/element?)))

(deftest parser
  (are [kl-str clj] (= clj (-> kl-str parse-shen first
                               shen-kl-to-clj))
       "1"
       1

       "1.0"
       1.0

       "symbol"
       ''symbol

       ""
       nil

       "nil"
       `'~(symbol "nil")

       "true"
       true

       "false"
       false

       "\"String\""
       "String"

       "()"
       ()

       "(+ 1 1)"
       '(+ 1 1)

       ))

;; Shen 41 wants a genuine variable as the lambda parameter -- `_` is a pattern
;; wildcard and shen.process-lambda rejects it -- and it rejects a bare `E` in a
;; macro body as a free variable. (protect E) satisfies both.
(use-fixtures :once (fn [suite]
                      (defmacro clj-exec-macro
                        [clj-exec Expr] -> [trap-error [time Expr] [λ (protect E) failed]])
                      (parse-and-eval-shen "(defmacro parsed-exec-macro [parsed-exec Expr] -> [trap-error [time Expr] [/. (protect E) failed]])")

                      (suite)

                      (reset-macros!)))

(defn toggle-trace [tfn]
  (require 'clojure.tools.trace)
  (doseq [ns '[shen shen.primitives]]
    ((ns-resolve 'clojure.tools.trace tfn) ns)))


;; CLisp

;; passed ... 146
;; failed ...0
;; pass rate ...100%

;; ok
;; 0

;; run time: 25.129999235272408 secs
;; loaded


(defn test-programs []
  (神
   (cd "shen/tests")
   (load "runme.shen")))

;; The extension suite is not reached by shen/tests/runme.shen, and its own
;; runme loads by paths relative to the kernel root rather than shen/tests.
(defn extension-tests []
  (神
   (cd "shen")
   (load "tests/extensions/runme.shen")))

(defn ^:private tee
  "A writer that forwards to `out` and also accumulates into `sb`.

   Both arities have to be given: proxy replaces every overload of a method
   name at once, so supplying only write(char[], int, int) would leave
   write(int) and write(String) dispatching to it with the wrong arity rather
   than delegating through Writer as they normally would. Either arity may be
   handed a char[] or a String."
  [^java.io.Writer out ^StringBuilder sb]
  (letfn [(emit [^String s] (.append sb s) (.write out s))]
    (proxy [java.io.Writer] []
      (write
        ([x] (emit (cond (integer? x) (str (char x))
                         (string? x) x
                         :else (String. ^chars x))))
        ([x off len] (emit (if (string? x)
                             (subs x off (+ off len))
                             (String. ^chars x off len)))))
      (flush [] (.flush out))
      (close [] (.flush out)))))

(def ^:private results-line #"(?m)^(passed|failed) \.\.\. (\d+)")

(defn failures
  "Runs `f` and returns the number of failures it reported.

   The harness in shen/tests/harness.shen only ever prints its tally, and
   kerneltests.shen calls (reset) once it is done, zeroing the counters -- so
   the printed report is the only surviving record of what happened. Counts are
   cumulative across a run, hence the max rather than the sum. A run that dies
   before reporting anything counts as a failure rather than a pass."
  [f]
  (let [sb (StringBuilder.)]
    (binding [*out* (tee *out* sb)]
      (f)
      (flush))
    (let [tally (->> (re-seq results-line (str sb))
                     (reduce (fn [m [_ k v]] (update m k (fnil max 0) (parse-long v))) {}))]
      (if (contains? tally "passed")
        (get tally "failed" 0)
        (do (println "No test results were reported.") 1)))))

(defn -main [& args]
  (let [failed (failures (if (some #{"extensions"} args) extension-tests test-programs))]
    (when-not (zero? failed)
      (println failed "test(s) failed."))
    (shutdown-agents)
    (System/exit (if (zero? failed) 0 1))))
