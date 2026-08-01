(ns build
  "Build tasks for shen.clj. Run with `clojure -T:build <task>`."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'shen.clj/shen.clj)
(def version (str/trim (slurp "VERSION")))
(def class-dir "target/classes")
(def generated-dir "target/generated")
(def uber-file (format "target/shen.clj-%s-standalone.jar" version))

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn- java-run
  "Runs a Clojure expression in a fresh JVM off the project basis. The kernel
   has to be rendered in a different process from the one that loads it: the
   classloader fixes its view of the classpath at startup, and rendering also
   leaves placeholder vars behind in the `shen` namespace."
  [what expr]
  (println what)
  (let [{:keys [exit]} (b/process (b/java-command {:basis @basis
                                                   :java-opts ["-Xss16m"]
                                                   :main 'clojure.main
                                                   :main-args ["-e" (pr-str expr)]}))]
    (when-not (zero? exit)
      (throw (ex-info (str what " failed") {:exit exit})))))

(defn clean
  "Removes all build output."
  [_]
  (b/delete {:path "target"}))

(defn kl
  "Renders shen/klambda/*.kl into target/generated/shen.clj."
  [_]
  ;; target/classes comes before target/generated on the classpath, so leftover
  ;; AOT output would satisfy `(require 'shen)` and the render would quietly
  ;; no-op. It is stale the moment we re-render anyway.
  (b/delete {:path class-dir})
  (b/delete {:path generated-dir})
  (java-run "Rendering KLambda kernel..." '(require 'shen.install)))

(defn compile
  "Renders the kernel, then AOT-compiles it into target/classes."
  [opts]
  (kl opts)
  (println "Compiling...")
  (b/compile-clj {:basis @basis
                  :class-dir class-dir
                  :src-dirs ["src" generated-dir]
                  :ns-compile '[shen.primitives shen.install shen]
                  :java-opts ["-Xss16m"]})
  opts)

(defn uber
  "Builds a standalone jar at target/shen.clj-<version>-standalone.jar."
  [opts]
  (compile opts)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'shen.install})
  (println "Built" uber-file)
  opts)
