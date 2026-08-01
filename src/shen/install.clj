(ns shen.install
  (:use [clojure.java.io :only (file reader writer)]
        [clojure.pprint :only (pprint)])
  (:require [clojure.string :as s]
            [clojure.walk :as w]
            [shen.primitives])
  (:import [java.io StringReader PushbackReader FileNotFoundException]
           [java.util.regex Pattern])
  (:gen-class))

;; Since Shen 22.0 every top-level form lives in shen.initialise, so load order
;; no longer matters -- all the defuns just have to exist before initialisation.
;;
;; stlib.kl is deliberately absent: it is the optional standard library (built
;; separately by make-stlib.shen), nothing in the kernel or the kernel test
;; suite refers to it, and its initialisers are far past the JVM's 64K limit on
;; the size of a single method.
(def shen-namespaces '[core declarations dict init load macros prolog reader
                       sequent sys t-star toplevel track types writer yacc])

(def kl-dir (->> ["../../K Lambda" "shen/klambda"]
                 (map file) (filter #(.exists %)) first))

;; Where the Clojure rendered from the KLambda kernel is written. Must be on
;; the classpath -- see :paths in deps.edn.
(def generated-dir "target/generated")

;; KLambda atoms that Clojure's reader either rejects or silently mis-parses.
;; Each gets wrapped in an (intern "...") call, which the translator turns into
;; a runtime symbol lookup. Kept longest-first so that e.g. shen.@ch is matched
;; before shen.@c.
(def unwelcome-symbols
  (->> [":" ";" "{" "}" ":=" "~" "/." "@p" "@s" "@v" "c/" "n/" "r/"
        "shen.@c" "shen.@ch" "shen.@v-help"
        "shen.macro-@c" "shen.macro-@ch" "shen.process-@s"]
       (sort-by (comp - count))))

;; Lookarounds rather than capture groups, so that adjacent unwelcome symbols
;; don't lose their separator to the previous match.
(def cleanup-symbols-pattern
  (re-pattern (str "(?<=[\\s(])("
                   (s/join "|" (map #(Pattern/quote %) unwelcome-symbols))
                   ")(?=[\\s)])")))

;; KLambda has no string escapes at all, so a literal runs from one quote to
;; the next -- which is also what makes `"\"` (a lone backslash) a valid KL
;; string even though Clojure would read the backslash as an escape.
(def ^:private string-literal #"\"[^\"]*\"")

(defn ^:private escape-backslashes
  "Doubles backslashes inside a KLambda string literal so that Clojure's reader
   sees the characters KLambda meant, rather than an escape sequence."
  [literal]
  (s/replace literal "\\" "\\\\"))

(defn cleanup-symbols
  "Wraps unwelcome symbols in (intern \"...\") calls, leaving string literals
   alone -- Shen 41 ships strings containing `{`, `}` and `:`, and rewriting
   inside them corrupts the source."
  [kl]
  (let [chunks (s/split kl string-literal -1)
        literals (concat (map escape-backslashes (re-seq string-literal kl))
                         (repeat ""))]
    (apply str
           (interleave (map #(s/replace % cleanup-symbols-pattern "(intern \"$1\")")
                            chunks)
                       literals))))

(defn read-kl [kl]
  (with-open [r (PushbackReader. (StringReader. (cleanup-symbols kl)))]
    (doall
     (map #(w/postwalk (fn [x] (if (symbol? x) (shen.primitives/munge-symbol x) x)) %)
          (take-while (complement nil?)
                      (repeatedly #(read r false nil)))))))

(defn read-kl-file [file]
  (try
    (cons `(c/comment ~(str file)) (read-kl (slurp file)))
    (catch Exception e
      ;; Swallowing this would drop a whole kernel file from the render and
      ;; only surface much later as an unresolvable symbol.
      (throw (ex-info (str "Could not read " file ": " (.getMessage e))
                      {:file (str file)} e)))))

(defn header [ns]
  `(~'ns ~ns
     (:use [shen.primitives])
     (:require [clojure.core :as ~'c])
     (:refer-clojure :only [])
     (:gen-class)))

(def missing-declarations '#{shen-kl-to-lisp FORMAT READ-CHAR})

(defn declarations [clj]
  (into missing-declarations
        (map second (filter #(= 'defun (first %)) clj))))

(defn ^:private assert-no-class-collisions!
  "AOT compilation names a function's class after its munged symbol, and
   Clojure's munging maps `-` to `_`. Two kernel functions whose names differ
   only there would compile to a single class file, the second silently
   replacing the first -- a function with the wrong body, and no error. The
   escaping in shen.primitives/munge-name is what prevents this; this checks it
   actually held."
  [names]
  (let [collisions (->> names
                        (filter symbol?)
                        distinct
                        (group-by #(clojure.lang.Compiler/munge (str %)))
                        (filter #(next (val %)))
                        (into {} (map (juxt key (comp vec val)))))]
    (when (seq collisions)
      (throw (ex-info (str "KLambda names collide after Clojure class munging: "
                           (pr-str collisions))
                      {:collisions collisions})))))

(defn write-clj-file [dir name forms]
  (with-open [w (writer (file dir (str name ".clj")))]
    (binding [*out* w]
      (doseq [f forms]
        (pprint f)
        (println)))))

(defn project-version []
  (try
    (s/trim (slurp "VERSION"))
    (catch Exception _ "unknown")))

(defn kl-to-clj
  ([] (kl-to-clj kl-dir generated-dir))
  ([{:keys [kl-dir out-dir]}] (kl-to-clj (or kl-dir shen.install/kl-dir)
                                         (or out-dir generated-dir)))
  ([dir to-dir]
     (.mkdirs (file to-dir))
     (let [shen (mapcat read-kl-file
                        (map #(file dir (str % ".kl")) shen-namespaces))
           dcl (declarations shen)]
       (assert-no-class-collisions! dcl)
       (write-clj-file to-dir "shen"
                       (concat [(header 'shen)]
                               [`(c/declare ~@(filter symbol? dcl))]
                               ['(c/intern 'shen.globals (c/with-meta '*language* {:dynamic true}) "Clojure")]
                               [(concat '(c/intern 'shen.globals (c/with-meta '*port* {:dynamic true}))
                                        [(project-version)])]
                               (map #(shen.primitives/shen-kl-to-clj %)
                                    (remove string? shen))
                               ['(c/load "shen/overwrite")]
                               ;; shen.shen was renamed to shen.repl in 22.0
                               [(list 'c/defn '-main []
                                      (list (shen.primitives/munge-symbol 'shen.repl)))])))
     ;; Translation interns a placeholder var into `shen` for every name it
     ;; can't resolve yet. Those placeholders would lose the generated file a
     ;; race: loading it evaluates `(ns shen (:use [shen.primitives]))`, and
     ;; referring a primitive over an already-interned var of the same name is
     ;; REJECTED -- leaving `defun` and friends bound to nil. Drop them so a
     ;; load in this same JVM starts from a clean namespace.
     (remove-ns 'shen)
     (create-ns 'shen)
     nil))

(defn ^:private load-generated!
  "Loads the rendered kernel by path rather than by `require`. The classloader
   fixes its view of the classpath at JVM start, so a target/generated created
   during this run is invisible to `require` -- but it is still perfectly
   loadable by file. Registering the lib afterwards keeps a later
   `(require 'shen)` from going back to the classpath and failing."
  []
  (load-file (str (file generated-dir "shen.clj")))
  (dosync (commute @#'clojure.core/*loaded-libs* conj 'shen)))

(defn install
  "Loads the Shen kernel, rendering it from KLambda first if that hasn't
   happened yet. Idempotent."
  []
  (try
    (require 'shen)
    (catch FileNotFoundException _
      (when-not (.exists (file generated-dir "shen.clj"))
        (println "Rendering the Shen kernel into" (str (file generated-dir "shen.clj")))
        (kl-to-clj))
      (load-generated!))))

(defn -main []
  (install)
  (binding [*ns* (the-ns 'shen)
            shen.primitives/*exit-on-console-eof* true]
    ((resolve 'shen/-main))))

;; Requiring this namespace is enough to bring up a working Shen environment,
;; so that `(require 'shen)` succeeds for callers that don't know about the
;; KLambda rendering step. Set -Dshen.install.skip=true to get at the
;; translator without booting the kernel, which is what you want when working
;; on the translator itself or running tooling over this namespace.
(when-not (Boolean/getBoolean "shen.install.skip")
  (install))
