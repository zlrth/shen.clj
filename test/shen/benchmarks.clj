(ns shen.benchmarks
  (:use [clojure.test]
        [shen.primitives :only (神)])
  (:require [shen.install]
            [shen]))

(defn benchmarks []
  ;; benchmarks.shen loads its parts as "benchmarks/<name>.shen", so the home
  ;; directory has to be the kernel root rather than the benchmarks folder.
  (神
   (cd "shen")
   (load "benchmarks/benchmarks.shen")
   (run-all-benchmarks stoutput-report)))

(defn -main []
  (benchmarks))
