# 神.clj | Shen for Clojure

http://shenlanguage.org/

Shen is a portable functional programming language by [Mark Tarver](http://www.lambdassociates.org/) that offers

* pattern matching,
* λ calculus consistency,
* macros,
* optional lazy evaluation,
* static type checking,
* an integrated fully functional Prolog,
* and an inbuilt compiler-compiler.

See also: [Shen.java](https://github.com/hraberg/Shen.java)


## This Clojure Port

`[shen.clj "0.2.0-SNAPSHOT"]`

Runs the [Shen 41.2](https://github.com/Shen-Language/shen-sources) kernel on
Clojure 1.12, and passes the Shen kernel test suite.

Built with [deps.edn](https://clojure.org/guides/deps_and_cli) and
[tools.build](https://clojure.org/guides/tools_build); no Leiningen required.

### To run the REPL

    ./bin/shen

    # or directly
    clojure -M:repl

The first run renders `shen/klambda/*.kl` into `target/generated/shen.clj` and
loads it from source, which takes a while. Building once makes startup fast:

    clojure -T:build compile        # render + AOT compile into target/classes
    clojure -T:build uber           # ...and a standalone jar
    clojure -T:build kl             # re-render only
    clojure -T:build clean

    java -Xss16m -jar target/shen.clj-0.2.0-SNAPSHOT-standalone.jar

`bin/shen` prefers the standalone jar if one has been built, and uses `rlwrap`
for readline support when it is installed.

---

    Shen, www.shenlanguage.org, copyright (C) 2010-2024, Mark Tarver
    version: S41.2, language: Clojure, platform: Clojure 1.12.5 [jvm 23.0.1]
    port 0.2.0-SNAPSHOT, ported by Håkan Råberg


    (0-) (define super
           [Value Succ End] Action Combine Zero ->
             (if (End Value)
                 Zero
                 (Combine (Action Value)
                          (super [(Succ Value) Succ End]
                                 Action Combine Zero))))
    (fn super)

    (1-) (define for
           Stream Action -> (super Stream Action do 0))
    (fn for)

    (2-) (define filter
           Stream Condition ->
             (super Stream
                    (/. Val (if (Condition Val) [Val] []))
                    append
                    []))
    (fn filter)

    (3-) (for [0 (+ 1) (= 10)] print)
    01234567890

    (4-) (filter [0 (+ 1) (= 100)]
                 (/. X (integer? (/ X 3))))
    [0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57 ... etc]


### Tests

The Shen kernel test suite, from `shen/tests`:

    yes | clojure -M:shen-test

    passed ... 134
    failed ... 0
    pass rate ... 100%

The Clojure-side tests:

    clojure -M:test

    Ran 11 tests containing 50 assertions.
    0 failures, 0 errors.

The benchmarks:

    clojure -M:benchmarks


### 神, define, prolog? and defprolog macros

Instead of using Shen's reader, you can embed Shen directly in Clojure using
these macros. For simplicity, all Shen code lives and is evaluated in the `shen`
namespace for now (this will likely change).

```clojure
; shen.test/shenlanguage.org
(define for
  Stream Action -> (super Stream Action do 0))

; shen.test/printer
(神
 (cons 1 2))
"[1 | 2]"

(神
 (@p 1 2))
"(@p 1 2)"

; shen.test/partials
(神
 ((λ X Y (+ X Y)) 2))
fn?
```

As can be seen `λ` stands in for `/.` in Shen to avoid Clojure reader macros.
`@p`, `@s` and `@v` are converted from Clojure deref to their Shen symbols.
Characters, like `\;`, will also be converted to symbols.

Note that `[]` in Shen are lists, and not Clojure vectors. `|` keeps its Shen
meaning inside these forms, so `[X | Y]` is a cons pair rather than a three
element list. A Clojure vector with a count of 2 is used to represent a cons
pair internally.

Shen 41 rejects `_` as a lambda parameter — it is a pattern wildcard — and
rejects a bare variable in a macro body as free, so write `(protect E)`:

```clojure
(defmacro clj-exec-macro
  [clj-exec Expr] -> [trap-error [time Expr] [λ (protect E) failed]])
```

See [`shen.test`](https://github.com/hraberg/shen.clj/blob/master/test/shen/test.clj) for more examples.


#### Shen calling Clojure

Shen code can access `clojure.core`, which is required as `c`:

```clojure
(神
  (c/count "0123456789"))
10
```

Clojure **functions** only. Shen 41 compiles an application whose head has no
registered arity into a lambda-form lookup, and the port resolves
namespace-qualified names from there — which cannot reach a macro, since a
macro's var holds a two-extra-argument expander rather than a callable.


### Name mangling

Shen 41 puts nearly all of its internals in the `shen` package, so kernel names
look like `shen.walk`. Clojure can read and even `def` such a symbol but can
never resolve one — at a use site the compiler sees the dot and looks for a
class — so names are rewritten on the way in and restored on the way out:

| Shen | Clojure |
| --- | --- |
| `.` | `-dot-` |
| `/` | `-slash-` |
| `_` | `-underscore-` |

`intern` mangles, `str` restores, so round trips through Shen's own `concat`
stay honest and the printer shows `shen.walk` rather than the internal spelling.
`_` is escaped because AOT names a function's class after the mangled symbol and
Clojure's own munging maps `-` to `_`; without it `shen.initialise-environment`
and `shen.initialise_environment` would compile to a single class file, the
second silently replacing the first.


### Known limitations

* `shen/klambda/stlib.kl` — the optional standard library, built separately by
  upstream's `make-stlib.shen` — is not loaded. Its initialisers are far past
  the JVM's 64K limit on the size of a single method. Nothing in the kernel or
  the kernel test suite refers to it.
* Clojure macros cannot be called from Shen; see above.
* Tail calls become `recur` by a heuristic rather than a real tail-position
  analysis. 62 of the benchmarks run; `match list (multiple clauses, not
  matching)` still overflows the stack.
* Performance is not a goal for 0.x, but some tuning has been made to ease
  development.


## Roadmap

This port, while aiming to conform closely (and hopefully fully) to the [Shen specification](http://shenlanguage.org/Documentation/shendoc.htm), has its primary goal to enable Shen's power in real world Clojure code.

* Shen / Clojure interop:
  * Shen packages as namespaces?
  * Hiding Shen internal names.
  * Bringing smaller parts of Shen goodness back into Clojure: predicate dispatch, pattern matching, prolog. Maybe even the type system.
  * Ensuring Shen can call Clojure/Java properly.
* Future / Questions
  * Real tail-position analysis instead of the current `recur` heuristic.
  * Loading `stlib.kl` by splitting oversized methods.
  * Making Shen as lazy as its host?
  * Existing Shen libraries and portability?
  * ClojureScript.
  * overwrite.clj - rewriting more parts of Shen into Clojure if interop or performance requires it.


#### The other port, Shen to Clojure

http://code.google.com/p/shen-to-clojure/

## License

http://shenlanguage.org/license.html

Shen, Copyright © 2010-2024 Mark Tarver

shen.clj, Copyright © 2012 Håkan Råberg
