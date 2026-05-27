# ClojureScript Lite-Mode Programming Guide

> _Evidence-based guide to shrinking ClojureScript builds under `:lite-mode true` + `:elide-to-string true`._
> _Every claim traced to a specific `core.cljs` line number. Verified via VLQ source map analysis._

---

## Quickstart for Agents

Given a ClojureScript repo with `:lite-mode true :elide-to-string true`, follow this workflow:

### Phase 1: Build & Diagnose

```clojure
;; Production build options — measure actual size
{:lite-mode true :elide-to-string true}

;; Debug/analysis build options — add these for source map tracing:
{:lite-mode       true
 :elide-to-string true
 :source-map      true
 :pseudo-names    true}  ;; readable names, but 5-10× larger artifact
```

Build with the debug options, then analyze the source map (§Appendix B).
Rebuild with production options to measure the real artifact size.
- **Zero** `Persistent*` names allowed (PersistentVector, PersistentArrayMap,
  PersistentHashMap, PersistentHashSet)
- **Zero** `Transient*`, `ChunkedSeq`, `ChunkedCons` names allowed
- `StringBuffer` (goog.string) is always present — not a printer leak
- If any `Persistent*` names exist, trace them to source files using VLQ decoder

### Phase 2: Replace Banned Functions

Search the repo for these patterns (all verified with `core.cljs` line numbers):

| Search for | Replace with | Why (core.cljs) |
|---|---|---|
| `set` | `(reduce conj #{} coll)` | `.createAsIfByAssoc PersistentHashSet` [9602] — **direct named ref** |
| `hash-map`, `array-map` | `{k v ...}` literal | direct persistent ctors [9052,9064] |
| `sorted-map`, `sorted-set` | avoid | `PersistentTreeMap/Set` [9087,9617] |
| `ex-info` | `js/Error.` + custom prop | `IPrintWithWriter` [11787] |
| `println`, `prn`, `pr-str`, `pr` | `js/console.log` | `pr-writer-impl` [10444] |
| `doseq` (single binding) | `run!` | chunked seqs |
| `for` | `map` | chunked seqs |
| `zipmap`, `frequencies`, `group-by` | inline with `reduce` | `transient` [9689,10225,11244] |
| `vec` | `(or (:key m) [])` | adds 14K empirically (vs 1K for `into` alone) |
| `clojure.walk` (any fn) | inline tree walker | transitively may pull in `into` etc. |
| `^boolean LITE_MODE` nested in `merge` | lift to top-level `if` | Closure won't eliminate |

**Functions confirmed safe (protocol-only, &lt;2K impact each):** `into`, `mapv`, `conj!`, `assoc!`, `transient`, `persistent!` — these dispatch through protocols the lite types stub out with identity no-ops. No direct persistent type references.

### Phase 3: Verify with Source Maps

Rebuild and re-run the source map scan. Repeat until zero persistent type names remain.

**Expected result:** A nexus-sized interceptor library went from 136K → 80K (41% reduction).

---

## 1. What is Lite-Mode?

> **David Nolen (CLJS author) on when lite-mode makes sense:** *"lite-mode is
> really kind of a tactical thing if you already know what you are doing. I think
> as you want to use more and more of the standard library it's going to be hard
> to take advantage of :lite-mode — diminishing returns. Regular ClojureScript if
> you're not using external deps is not big, ~20K gzipped. The mental overhead of
> :lite-mode just isn't worth the savings. Only if you're doing something very
> specific, static websites, trivial scripting."*
>
> He also notes that one promising use case is *"low level CLJS libs you would
> otherwise write in JS"* — light DOM web components, macros-without-deps, etc.
> At work he has Light DOM web components compiling to ~8-9K brotli.

Lite-mode is a ClojureScript compiler flag (`:lite-mode true`) introduced as an experimental feature
[commit `f9a6856d`]. It replaces the full persistent/structural-sharing collection types with
lightweight **copy-on-write** data structures backed by plain JavaScript arrays and objects. Combined
with `:elide-to-string true`, it can cut emitted code size **by half or more** for simple programs.

**Size comparison (advanced compilation, brotli compression):**

| Program | Standard CLJS | Lite-Mode + `:elide-to-string` |
|---|---|---|
| `{}` hash-map literal | ~17K | ~6K |
| `(->> (map inc (range 10)) ...)` pipeline | ~19K | ~6K |

**How to enable:**

```clojure
;; build config (see resources/lite_test.edn for a working example)
{:optimizations  :advanced
 :lite-mode       true                         ;; [closure.clj:214, 2532-2534]
 :elide-to-string true}                        ;; [closure.clj:215]
```

The `:lite-mode` key flows through the compiler as:

1. **recognised option** — `closure.clj:214` adds it to the allowlist of build keys
2. **Closure define** — `closure.clj:2532-2534` sets `cljs.core.LITE_MODE` as a closure define
3. **analyzer check** — `analyzer.cljc:497-498` defines `(lite-mode?)` → reads `:options :lite-mode`
4. **code generation** — `compiler.cljc:570,599,627` branches on `(ana/lite-mode?)` at emit time

---

## 2. The Lite Data Structures

All lite-type definitions live in `src/main/cljs/cljs/core.cljs`, starting at line 12330.
They replace ClojureScript's persistent trie-based types with simple JS-backed alternatives.

### 2.1 `VectorLite` — replaces `PersistentVector`

**Defined at:** `core.cljs:12341-12530`

- Backed by a **plain JavaScript array** (constructed with `(array)` not `(aclone)`) — line 12532
- On `assoc`: clones the **entire array** (`aclone array`), mutates the slot — line 12448
- On `conj` / `pop`: clones the array + `.push` / `.pop` — lines 12397, 12404
- No trie-structure, no tail, no structural sharing
- `empty` returns a shared singleton (`VectorLite.EMPTY`) — line 12407, defined at 12532

**Emitters:**
| Source | Standard | Lite-Mode |
|---|---|---|
| `[1 2 3]` | `PersistentVector.fromArray(...)` | `new cljs.core.VectorLite(null, [1,2,3], null)` |
| `(vector 1 2 3)` | `cljs.core/vector` | `cljs.core/vector-lite` (compiler pass rewrites) |
| `(vec xs)` | `cljs.core/vec` | `cljs.core/vec-lite` (compiler pass rewrites) |

- `emit* :vector` dispatch: `compiler.cljc:596-601`
- `emit-lite-vector` implementation: `compiler.cljc:591-594`
- `vector-lite` definition: `core.cljs:12536-12541`
- `vec-lite` definition: `core.cljs:12543-12557`

### 2.2 `ObjMap` — replaces `PersistentArrayMap` (keyword/string keys)

**Defined at:** `core.cljs:12612-12780`

- Backed by a **JavaScript object** (`js-obj`) for values (`strobj`) and a **sorted array** (`strkeys`) for key order
- Only supports **string and keyword keys**. Non-string/keyword `assoc` **promotes** to `HashMapLite` — lines 12696-12703
- Keywords are encoded with a `"\uFDD0'"` prefix via `keyword->obj-map-key` — lines 12585-12588
- Iteration returns `MapEntry` with decoded keywords via `obj-map-key->keyword` — lines 12589-12592
- On `assoc` / `dissoc`: clones the object + arrays (`obj-clone`, `aclone`), mutates — lines 12686-12695, 12742-12750
- `empty` returns `ObjMap.EMPTY` — line 12652, defined at 12782

**Emitters:**
| Source | Standard | Lite-Mode (all string/keyword keys) | Lite-Mode (mixed keys) |
|---|---|---|---|
| `{:a 1 :b 2}` | `PersistentArrayMap.fromArray(...)` | `ObjMap.fromObject(["\uFDD0'a","\uFDD0'b"],{...})` | n/a |
| `{:a 1 2 :b}` | `PersistentArrayMap.fromArray(...)` | n/a | `HashMapLite.fromArrays(...)` |
| destructuring | `PersistentArrayMap` | `ObjMap` | `ObjMap` |

- `emit* :map` dispatch: `compiler.cljc:567-575`
- `emit-obj-map` implementation: `compiler.cljc:534-539`
- `obj-map-key` encoding helper: `compiler.cljc:525-531`
- `(--destructure-map)` lite-mode branch: `core.cljs:4153-4161`
- `seq-to-map-for-destructuring` lite-mode branch: `core.cljs:9075-9082`

⚠️ **Cache invalidation:** Flipping `:lite-mode` on or off does **not** bust
the compiler's analysis cache. Delete the output directory (`target/`, `public/js/`,
etc.) before rebuilding to see accurate results. Comparing stale artifacts across
mode changes is a common source of confusion.

⚠️ **Per-map overhead vs PersistentArrayMap:** ObjMap uses two structures (a sorted
string-key array `strkeys` + a values object `strobj`) plus a `"\uFDD0'"` prefix
on every keyword key. PersistentArrayMap uses a single flat array `[k,v,k,v,...]`.
For a 2-key map like `{:a 1 :b 2}`:

| | PersistentArrayMap emit | ObjMap emit |
|---|---|---|
| Keys | `"a","b"` in flat array | `"\uFDD0'a","\uFDD0'b"` in sorted array (+3 bytes/key) |
| Values | same flat array | separate `js-obj` literal |
| Total structures | 1 | 2 (sorted key array + value object) |

For programs that create **many small keyword-keyed maps** (data architecture
libraries, interceptor chains, context maps, dispatch tables), the per-map
overhead can accumulate and cause the lite-mode build to be **larger** than the
standard build — counteracting the size savings on collection implementation code.

### 2.3 `HashMapLite` — replaces `PersistentHashMap` (arbitrary keys)

**Defined at:** `core.cljs:12827-13015`

- Backed by a **JavaScript object** used as hash buckets — `hashobj` is a JS object where keys are hashes
- Each bucket is an array of `[key, val, key, val, ...]` for collision resolution — `scan-array-equiv` at line 12813
- On `assoc` / `dissoc`: clones `hashobj` and the affected bucket, mutates — lines 12886-12928, 12944-12956
- `identical?` check on `assoc` when value unchanged — line 12917-12919 (returns `coll` itself)
- `empty` returns `HashMapLite.EMPTY` — line 12865, defined at 13007

**Emitters:**
| Source | Standard | Lite-Mode |
|---|---|---|
| `{1 "one" 2 "two"}` | `PersistentArrayMap.fromArray(...)` | `HashMapLite.fromArrays([1,2],["one","two"])` |
| `(hash-map ...)` | `PersistentHashMap` | **No pass rewrite** (see §4.1) |

- `emit-lite-map` implementation: `compiler.cljc:541-544`
- `hash-map-lite` constructor: `core.cljs:13016-13021`
- `HashMapLite.fromArrays`: `core.cljs:13009-13014`

### 2.4 `SetLite` — replaces `PersistentHashSet`

**Defined at:** `core.cljs:13024-13133`

- Backed by a `HashMapLite` (stores value as both key and val) — `hash-map` field
- On `conj` / `disj`: delegates to map `assoc` / `dissoc` — lines 13058-13059, 13098-13101
- `empty` returns `SetLite.EMPTY` — line 13062, defined at 13135
- `equiv` checks `set?` and compares counts/elements — lines 13067-13072

**Emitters:**
| Source | Standard | Lite-Mode |
|---|---|---|
| `#{1 2 3}` | `PersistentHashSet.createAsIfByAssoc(...)` | `cljs.core.set_lite([1,2,3])` |

- `emit* :set` dispatch: `compiler.cljc:624-629`
- `emit-lite-set` implementation: `compiler.cljc:619-622`
- `set-lite` function: `core.cljs:13137-13148`

---

## 3. What the Compiler Rewrites

### 3.1 Analysis pass: `cljs.analyzer.passes.lite/use-lite-types`

**Full source:** `src/main/cljs/cljs/analyzer/passes/lite.cljc` (32 lines)

The compiler appends this pass when `:lite-mode` is true — `analyzer.cljc:4608`:

```clojure
;; analyzer.cljc:4606-4608
(let [passes (cond-> (or *passes* default-passes)
               (lite-mode?) (conj lite/use-lite-types))
```

The pass itself rewrites only two var references — `lite.cljc:15-17`:

```clojure
(def ctor->ctor-lite
  '{cljs.core/vector cljs.core/vector-lite
    cljs.core/vec    cljs.core/vec-lite})
```

It detects `:op :var` AST nodes (`replace-var?`, line 25-27) and rewrites `:name` and `:info :name`
(`update-var`, lines 19-23).

**Additionally:** `cljs.core/vector` is special-cased in `get-var` — `analyzer.cljc:4226`:

```clojure
;; analyzer.cljc:4226-4228 — prevents macro resolution of `vector`
(when-not (and (lite-mode?) (= 'vector sym))
  (.findInternedVar ...))
```

| Original form | Rewritten to | Mechanism |
|---|---|---|
| `cljs.core/vector` | `cljs.core/vector-lite` | Pass rewrite — `lite.cljc:16` |
| `cljs.core/vec` | `cljs.core/vec-lite` | Pass rewrite — `lite.cljc:17` |
| `vector` (resolved) | `vector-lite` | Pass rewrite + `get-var` guard — `analyzer.cljc:4226` |
| `vec` (resolved) | `vec-lite` | Pass rewrite |

### 3.2 Emit-time dispatch (`cljs.compiler`)

Literal forms (analyzed as `:vector`, `:map`, `:set` AST nodes) are dispatched at code generation
based on `(ana/lite-mode?)`:

| AST node | Source | Emitter used |
|---|---|---|
| `:vector` | `compiler.cljc:596-601` | `emit-lite-vector` → `new cljs.core.VectorLite(...)` |
| `:map` (all keyword/string keys) | `compiler.cljc:567-575` | `emit-obj-map` → `cljs.core.ObjMap.fromObject(...)` |
| `:map` (mixed key types) | `compiler.cljc:567-575` | `emit-lite-map` → `cljs.core.HashMapLite.fromArrays(...)` |
| `:set` | `compiler.cljc:624-629` | `emit-lite-set` → `cljs.core.set_lite(...)` |

### 3.3 Core library guards

Several functions in `cljs.core` branch on the `LITE_MODE` compile-time constant — defined at
`core.cljs:57-58`:

```clojure
^{:doc "Boolean flag for LITE_MODE"}
LITE_MODE false)
```

| Function | Guard | Source |
|---|---|---|
| `chunked-seq?` | always returns `false` | `core.cljs:2275-2279` |
| `(--destructure-map)` | uses `ObjMap` instead of `PersistentArrayMap` | `core.cljs:4153-4161` |
| `seq-to-map-for-destructuring` | uses `ObjMap` instead of `PersistentArrayMap` | `core.cljs:9075-9082` |
| `(-empty (List. ...))` | discards metadata on lazy seq `empty` | `core.cljs:3639-3645` |

---

## 4. The Printing Problem & `:elide-to-string`

This is the other big code-size lever alongside `:lite-mode`. Understanding it
requires tracing a four-hop call chain through cljs.core.

### 4.1 The Call Chain That Pulls In the Printer

When you write `(str {:a 1})`, here is exactly what happens:

```
(str {:a 1})                          ;; core.cljs:3128-3138
  → (.toString {:a 1})                ;; core.cljs:3135 — (if (nil? x) "" (.toString x))
    → (pr-str* this)                  ;; core.cljs:12615 — Object (toString [coll] (pr-str* coll))
      → (-pr-writer obj writer nil)   ;; core.cljs:939 — IPrintWithWriter protocol dispatch
```

At this point `-pr-writer` dispatches to the type-specific printer. For ObjMap:

```
        → (print-map coll pr-writer writer opts)   ;; core.cljs:12778
          → (print-prefix-map nil m pr-writer ...)  ;; core.cljs:10684-10692
            → (pr-sequential-writer writer pr-writer "{" ", " "}" ...)  ;; core.cljs:10675
              → (pr-writer (first coll) writer opts)  ;; recursive call into:
                → (pr-writer-impl obj writer opts)     ;; core.cljs:10444 — the big dispatch
```

`pr-writer-impl` [core.cljs:10444-10540] is the heart of the printer — a ~100-line `cond`
that handles **every** ClojureScript value type:

| Type handled | Lines | What it does |
|---|---|---|
| `nil` | 10446 | writes `"nil"` |
| print-meta check | 10448-10451 | `^` + meta print |
| CLJS ctors | 10454-10455 | `.cljs$lang$ctorPrWriter` |
| `IPrintWithWriter` | 10457-10458 | protocol dispatch |
| booleans | 10460-10461 | `(str_ obj)` |
| numbers (NaN, Inf, -0) | 10463-10470 | special formatting |
| JS objects | 10472-10483 | `#js {...}` with `print-map` |
| arrays | 10485-10490 | `#js [...]` with `pr-sequential-writer` |
| strings + readably | 10492-10494 | `quote-string` with char escapes |
| JS functions | 10496-10503 | `#object[Function ...]` |
| Dates | 10505-10517 | `#inst "..."` ISO 8601 formatting |
| regexes | 10519 | `#"..."` |
| JS symbols | 10521 | `#object[...]` |
| fallback objects | 10523-10540 | `#object[Constructor ...]` |

This function alone is ~100 lines, but it **pulls in** as dependencies:

```
pr-writer-impl pulls in:
  ├── print-meta?          → core.cljs:10433-10436
  ├── print-map            → core.cljs:10684-10692
  │   ├── lift-ns          → namespace lifting for #:ns{...} syntax
  │   └── print-prefix-map → core.cljs:10675-10682
  │       └── pr-sequential-writer → core.cljs:10380-10405 (~25 lines)
  │           └── *print-level* binding, *print-length* (pr-opts-len)
  ├── pr-sequential-writer → (for arrays, sets, lists)
  ├── quote-string         → core.cljs:10424-10431
  │   └── char-escapes     → core.cljs:10418-10423 (JS object with escape mappings)
  ├── write-all            → core.cljs:10407-10410
  └── (str_ obj)           → for booleans/numbers (pulls in str_)
```

Plus all the **`IPrintWithWriter` implementations** [core.cljs:10694-10793] — ~100 lines
covering ~25 types (LazySeq, IndexedSeq, RSeq, Cons, List, PersistentVector,
PersistentQueue, PersistentArrayMap, PersistentHashMap, PersistentTreeMap,
PersistentHashSet, PersistentTreeSet, Subvec, Range, ChunkedCons, etc.).

Plus the **writer infrastructure**:

```
IWriter protocol           → core.cljs:798-802
StringBufferWriter         → core.cljs:928-931
StringBuffer (goog.string) → imported at core.cljs:16
```

**Total printing infrastructure: ~400-500 lines of cljs.core** (out of ~13,000).
After Closure advanced compilation, this still represents a significant fraction
of the output — which is why the commit message says eliding toString "cuts emitted
code in half or more for simpler programs."

### 4.2 How `:elide-to-string` Breaks the Chain

The mechanism is simple but surgical. In `core.cljc:1522-1531`:

```clojure
(core/defn- add-obj-methods [type type-sym sigs]
  (core/->> sigs
    ;; Elide all toString methods in :lite-mode
    (remove
      (core/fn [[f]]
        (core/and (ana/elide-to-string?) (core/= 'toString f))))
    (map ...)))
```

This function is called for **every** `deftype`'s `Object` protocol methods
[core.cljc:1582-1583]. When `:elide-to-string` is true, any `(toString [this] ...)`
method is simply **not generated** in the emitted JavaScript.

This breaks the call chain at **step 2** (see §4.1):

```
(str {:a 1})
  → (.toString {:a 1})
    → ❌ NO OVERRIDE EXISTS → falls through to Object.prototype.toString
      → returns "[object Object]"  (JavaScript's default)
```

Since `pr-str*` is now unreachable from any `toString`, and `pr-writer-impl` is
only reachable from `pr-str*` (or from explicit `pr-str`/`prn` calls), the
**entire printing infrastructure becomes dead code** for programs that only use
`str` for string concatenation.

### 4.3 What Breaks vs. What Still Works

| Expression | Without `:elide-to-string` | With `:elide-to-string` |
|---|---|---|
| `(str "hello " 42)` | `"hello 42"` | `"hello 42"` ✅ numbers still work |
| `(str "hello " nil)` | `"hello "` | `"hello "` ✅ nil returns `""` |
| `(str {:a 1})` | `"{:a 1}"` | `"[object Object]"` ❌ JS default |
| `(str [1 2 3])` | `"[1 2 3]"` | `"1,2,3"` ❌ JS Array.toString |
| `(str #{:a :b})` | `"#{:a :b}"` | `"[object Object]"` ❌ JS default |
| `(str (list 1 2))` | `"(1 2)"` | `"[object Object]"` ❌ JS default |
| `(pr-str {:a 1})` | `"{:a 1}"` | `"{:a 1}"` ✅ still works! |
| `(prn {:a 1})` | prints `{:a 1}` | prints `{:a 1}` ✅ still works! |

**Key insight:** `pr-str` and `prn` still work because they call `pr-writer-impl`
directly, not through `.toString`. If your code never calls `pr-str`/`prn` either,
then even those functions are dead-code-eliminated.

### 4.4 Interaction with `:lite-mode`

The lite types all implement `toString` via `pr-str*`:

| Type | toString impl | Source |
|---|---|---|
| `VectorLite` | `(pr-str* coll)` | `core.cljs:12343-12344` |
| `ObjMap` | `(pr-str* coll)` | `core.cljs:12614-12615` |
| `HashMapLite` | `(pr-str* coll)` | `core.cljs:12829-12830` |
| `SetLite` | `(pr-str* coll)` | `core.cljs:13026-13027` |

Without `:elide-to-string`, these `toString` methods would pull in the **same** printing
machinery as the persistent types — defeating much of the purpose of lite-mode.

With both flags enabled (`:lite-mode true` + `:elide-to-string true`):

1. **`:lite-mode`** replaces the collection implementations → cuts data structure code
2. **`:elide-to-string`** strips `toString` → cuts the printing infrastructure

Together they remove the two largest subsystems in cljs.core. For a simple program,
you're left with just the seq abstraction, protocol infrastructure, and basic
operations — hence the size numbers from the commit message (~6K brotli).

### 4.5 When NOT to Use `:elide-to-string`

Don't use it if:

- You call `(str coll)` and expect Clojure-readable output
- You use `(js/console.log coll)` for debugging (use `(prn coll)` or
  `(js/console.log (pr-str coll))` instead)
- You `.toString` on ClojureScript collections for interop with JS libraries
  that expect formatted output
- You pass ClojureScript collections to template literals: `` `${my-map}` ``

### 4.6 Linting for `:elide-to-string`

A linter should flag:

- **Warning:** `(str coll)` where `coll` is a collection (map, vector, set, seq)
  — will produce JS default string, not Clojure-readable
- **Warning:** `(.toString coll)` on collections — same reason
- **Info:** `(.log js/console coll)` — suggest `(prn coll)` or explicit `pr-str`
- **Info:** template literal `` `${coll}` `` where `coll` is a collection

### 4.7 Hidden Printer Entry Points

`:elide-to-string` strips `toString` from `deftype` Object methods, but it does
**not** affect protocol extensions. Several commonly-used functions route through
`IPrintWithWriter` — bypassing the `toString` elision entirely and pulling in the
printer infrastructure:

**`ex-info` / `ExceptionInfo`:** `ExceptionInfo` implements `IPrintWithWriter`
via `extend-type` (not an Object `toString`). Calling `ex-info` pulls in
`ExceptionInfo` → its `IPrintWithWriter` impl (`pr-writer-ex-info` [11756]) →
`pr-writer` → `pr-writer-impl` [10444]. **Empirically verified (55KB impact):**
this leaks the printer protocol infrastructure (`IPrintWithWriter`, `pr_writer`,
`StringBuffer`) but does **not** pull in persistent types (PersistentVector,
PersistentArrayMap, etc.). The persistent types' `IPrintWithWriter`
implementations live in an `extend-protocol` block [10692] separate from the
lite types' implementations (which are embedded in each lite `deftype` body
[12527,12776,13001,13130]). Since all collection instances in lite-mode are lite
types, the persistent type protocol entries are never dispatched to → dead code
eliminated by Closure.

```clojure
;; ❌ Pulls in the full printer even with :elide-to-string
(ex-info "Something went wrong" {:reason :timeout})

;; ✅ Lite-safe alternative (avoid ex-info in lite-mode error paths)
(js/Error. "Something went wrong")
```

**`pr-str` / `prn` / `println`:** These call `pr-writer-impl` directly, not
through `.toString`. With `:elide-to-string`, `str` on collections falls through
to `Object.prototype.toString`, but `pr-str` still works — and still brings the
printer. If you use `prn` for debug logging, the printer is included.

**`ExceptionInfo.toString`:** Even though `:elide-to-string` strips `toString`
from user-defined `deftype`s, `ExceptionInfo.toString` is defined in `cljs.core`
and compiled once. Its `toString` calls `pr-str*`, which calls `pr-writer-impl`.

**Diagnosing with source maps:** Enable `:source-maps true` in the release build
(shadow-cljs: `:release {:source-maps true}`). The resulting `.js.map` file
contains a `names` array mapping every minified symbol back to its original
`cljs.core` name. Filtering for `PersistentVector`, `pr-writer-impl`, etc.
immediately reveals whether persistent types are leaking into the lite-mode build.

**Bottom line:** `:elide-to-string` only cuts the `toString` → `pr-str*` chain.
Any code path that reaches `pr-writer-impl` through protocols (`IPrintWithWriter`,
`pr-str`, `prn`, `println`, `ex-info`) keeps the **printer infrastructure** alive
(`IPrintWithWriter`, `pr_writer`, `StringBuffer`, `IWriter`). However, the
persistent type implementations within the printer's `extend-protocol` block are
never dispatched to in lite-mode (all instances are lite types with their own
`IPrintWithWriter` impls) and are eliminated by Closure. The printer infrastructure
alone costs ~10KB over baseline (55KB vs 46KB in empirical testing).

---

## 5. Breaking Changes & Lintable Patterns

### 🔴 Category A: Type References — Will fail at runtime

These reference types that **do not exist** in a lite-mode build (eliminated by Closure dead-code removal
since no constructors for them are ever emitted).

#### A1. `instance?` checks on persistent types

```clojure
;; ❌ BREAKS in lite-mode — types don't exist
(instance? cljs.core/PersistentVector [1 2 3])    ;; VectorLite not PersistentVector
(instance? cljs.core/PersistentArrayMap {:a 1})   ;; ObjMap or HashMapLite
(instance? cljs.core/PersistentHashMap {1 :a})    ;; HashMapLite
(instance? cljs.core/PersistentHashSet #{1 2})    ;; SetLite
```

```clojure
;; ✅ Lite-safe alternatives — use protocol-based predicates
(vector? [1 2 3])
(map? {:a 1})
(set? #{1 2})
(satisfies? ICounted coll)
```

#### A2. Direct constructor calls

```clojure
;; ❌ BREAKS in lite-mode — constructors don't exist
(PersistentVector. nil 0 5 ...)          ;; cf. core.cljs:5919
(PersistentArrayMap. nil ...)            ;; cf. core.cljs:6957
(PersistentHashMap. nil 0 nil false nil ...)  ;; cf. core.cljs:8165
(PersistentHashSet. nil ...)             ;; cf. core.cljs:9358
```

```clojure
;; ✅ Lite-safe alternatives
[]                   ;; literal vector → VectorLite
{}                   ;; literal map → ObjMap or HashMapLite
#{}                  ;; literal set → SetLite
(vector 1 2 3)       ;; compiler pass rewrites to vector-lite → VectorLite
```

#### A3. `.-EMPTY` static fields on persistent types

```clojure
;; ❌ BREAKS in lite-mode
(.-EMPTY PersistentVector)       ;; cf. lite version at core.cljs:12532
(.-EMPTY PersistentArrayMap)     ;; cf. lite version at core.cljs:12782
(.-EMPTY PersistentHashMap)      ;; cf. lite version at core.cljs:13007
(.-EMPTY PersistentHashSet)      ;; cf. lite version at core.cljs:13135
```

```clojure
;; ✅ Lite-safe alternatives
[]
{}
#{}
```

#### A4. `.-fromArray` / `.-fromArrays` / `.createAsIfByAssoc` static methods

```clojure
;; ❌ BREAKS in lite-mode
(.fromArray PersistentVector some-array true)
(.fromArray PersistentArrayMap some-array true false)
(.createAsIfByAssoc PersistentArrayMap some-array)
```

```clojure
;; ✅ Lite-safe alternatives
(reduce conj [] some-array)
(reduce #(apply assoc %1 %2) {} (partition 2 some-array))
```

---

### 🟠 Category B: Behavioral Differences — Code compiles but behaves differently

#### B1. Chunked seqs are silently absent

**Source:** `core.cljs:2275-2279` — `chunked-seq?` hardcoded to `false` in lite-mode.

```clojure
;; ❌ In lite-mode, this ALWAYS returns false
(chunked-seq? (range 0 10 1))
;; → true in standard, false in lite-mode

;; ❌ These don't exist at all in lite-mode
(instance? ChunkedSeq s)
(instance? ChunkedCons s)
(array-chunk arr)
```

Chunked seq checks are scattered through core — see `core.cljs:3956,3965,4568,4595,4808,4920,5385`.

```clojure
;; ✅ Lite-safe: don't depend on chunking
;; Standard seq operations work fine:
(map inc (range 10))
(filter even? (range 100))
```

#### B2. Transient is a no-op

**Source:** `core.cljs:12383,12856,13052` — `-as-transient` returns `coll` itself.
`core.cljs:12404,12865,13062` — `-persistent!` also returns `coll`.

```clojure
;; ❌ In lite-mode, transient mutations return new copies each time
;; (no mutation guard, no shared mutable state)
(let [t (transient [])]
  (conj! t 1)     ;; returns new VectorLite
  (conj! t 2)     ;; returns ANOTHER new VectorLite — t is unchanged!
  (persistent! t)) ;; returns empty vector!
```

```clojure
;; ❌ Calling get on a persisted! transient throws in standard CLJS,
;;    but works fine (returns value) in lite-mode
;; (Test guarded behind LITE_MODE: collections_test.cljs:1127-1132)
(let [t (assoc! (transient []) 0 1)]
  (persistent! t)
  (get t :a :not-found))   ;; Error in standard, OK in lite-mode
```

```clojure
;; ✅ Lite-safe pattern for transient:
;; Use threading. Every conj!/assoc! returns a new collection.
(-> (transient [])
    (conj! 1)
    (conj! 2)
    (persistent!))
;; But honestly: just use persistent operations directly.
;; There's no performance benefit to transients in lite-mode.
```

⚠️ **Key insight:** Transients in lite-mode are literally identity functions.
`-as-transient` at `core.cljs:12383` (VectorLite), `12856` (HashMapLite), `13052` (SetLite)
all return `coll`. They exist only for API compatibility.

#### B3. Structural sharing is gone

Because every `assoc` copies the entire backing array/object:

- `VectorLite`: `core.cljs:12448` — `aclone array` then `aset`
- `ObjMap`: `core.cljs:12686` — `obj-clone strobj strkeys`, `12689-12690` — or full clone + push
- `HashMapLite`: `core.cljs:12898-12900` — `aclone bucket` + `gobject/clone hashobj`

```clojure
;; ❌ identical? checks on nested collections will never match:
(let [base {:a {:b 1}}]
  (identical? (:a base) (:a (assoc base :c 2))))
;; → true in standard (structure shared), false in lite-mode (full copy)
```

```clojure
;; ✅ Lite-safe: don't rely on structural sharing or identical?
;; Use = for equality.
(= (:a base) (:a (assoc base :c 2)))  ;; always true
```

⚠️ **Key performance implication:** The lack of structural sharing means every
`conj`/`assoc`/`dissoc` clones the **entire** backing structure. Building a
collection by sequentially adding elements goes from O(n) to **O(n²)**:

```clojure
;; Standard mode: ~1,000 element copies (amortized O(1) per conj)
;; Lite-mode:     ~500,000 element copies (O(n) per conj)
(reduce conj [] (range 1000))
```

- **Small collections (<100 elements):** negligible difference
- **Large collections (>1000 elements):** 50-100× slower for sequential
  construction. This hits any workload that builds collections incrementally —
  `reduce`-based construction, `into`, data ingestion loops, `for`
  comprehensions with `:into`.
- **Read-heavy / write-once workloads:** lite-mode is fine (serialization,
  lookups, iteration are comparable)
- **Transients don't help:** since `-as-transient` returns `coll` itself
  (see §5 B2), `(transient [])` followed by `conj!` does exactly the same
  full copies — there is no mutable escape hatch in lite-mode.

**Source:** `VectorLite` `assoc` at `core.cljs:12448` (`aclone array`), `conj` at
`core.cljs:12397`; `ObjMap` `assoc` at `core.cljs:12686`; `HashMapLite` `assoc`
at `core.cljs:12898-12900`.

#### B4. Lazy seq metadata on `empty`

**Source:** `core.cljs:3639-3645` — the `(-empty List)` special case.

```clojure
;; ❌ Different behavior:
(meta (empty (with-meta (lazy-seq ...) {:b :c})))
;; → {:b :c} in standard CLJS (preserves meta)
;; → nil in lite-mode (discards meta)

;; Same for lists:
(meta (empty '^{:b :c} (1 2 3)))
;; → nil in both modes (this was always nil)
```

#### B5. `.equiv` on maps and sets may differ

The `.equiv` method is implemented as a protocol method (`IEquiv`) on each type.
While the lite types implement it (`core.cljs:12413` for VectorLite, `12656` for ObjMap,
`12869` for HashMapLite, `13067` for SetLite), the behavior may differ from persistent
types in edge cases. The commit notes `.equiv` is "not a standard thing — primarily for
interop in transit-js".

```clojure
;; 🟡 .equiv behavior may differ in edge cases
(.equiv {:foo 1 :bar 2} {:foo 1 :bar 2})

;; ✅ Lite-safe: use = for equality
(= {:foo 1} {:foo 1})
```

#### B6. Non-string/keyword keys in ObjMap cause a silent promotion

**Source:** `core.cljs:12696-12703` — when a non-string key is assoc'ed into ObjMap,
the entire map is converted to a `hash-map-lite` (HashMapLite), **losing metadata**.

```clojure
;; ❌ When you assoc a non-string/non-keyword key into an ObjMap:
(assoc {:a 1} 5 "five")
;; Returns a HashMapLite — metadata from the original map is LOST
;; In standard CLJS: metadata is preserved
```

---

### 🟡 Category C: Size Regressions — Code compiles but pulls in more

#### C1. Calling `hash-map` explicitly vs `{}`

The analysis pass only rewrites `vector` and `vec`. `hash-map` is NOT rewritten,
so it pulls in `PersistentHashMap`:

```clojure
;; ❌ These are NOT rewritten by the analysis pass:
(hash-map :a 1 :b 2)    ;; pulls in PersistentHashMap code
(array-map :a 1 :b 2)   ;; pulls in PersistentArrayMap code
(sorted-map :a 1)       ;; pulls in PersistentTreeMap code
```

```clojure
;; ✅ Use literal syntax — the emit phase handles it
{:a 1 :b 2}              ;; emits ObjMap — compiler.cljc:573
{1 "one" 2 "two"}        ;; emits HashMapLite — compiler.cljc:574
```

#### C2. Calling `set` function vs `#{}`

`set` is NOT rewritten by the pass. It directly references
`.createAsIfByAssoc PersistentHashSet` at [9602]. The literal `#{}`
uses emit-time dispatch (`compiler.cljc:624-629`).

```clojure
;; ❌ Not rewritten by pass — dual persistent type leak:
(set [1 2 3])

;; ✅ Static: use literal syntax:
#{1 2 3}

;; ✅ Dynamic: use reduce conj:
(reduce conj #{} [1 2 3])
```

#### C3. `toString` elision (`:elide-to-string true`)
	
> **Full details in §4.** Only a summary is repeated here.

**Source:** `core.cljc:1522-1531` — `add-obj-methods` filters out `toString`
methods when `:elide-to-string` is true (`analyzer.cljc:500-501`).

```clojure
;; ❌ With :elide-to-string, str on collections returns JS defaults:
(str {:a 1})                    ;; "[object Object]" instead of "{:a 1}"
(.toString [1 2 3])            ;; "1,2,3" instead of "[1 2 3]"

;; ✅ pr-str/prn still work:
(pr-str {:a 1})                 ;; "{:a 1}"
(prn {:a 1})                    ;; prints {:a 1}
```

#### C4. Core functions that internally create persistent types

These functions may pull in persistent type code through their implementation:

```clojure
;; ❌ These may pull in persistent type code through internal usage:
(zipmap ks vs)          ;; uses PersistentHashMap internally
(group-by f coll)       ;; uses PersistentHashMap internally
(frequencies coll)      ;; uses PersistentHashMap internally
```

⚠️ **This depends on whether these functions reference persistent types directly
in their implementation or go through protocol-based paths. Check your build output.**

#### C5. Many small keyword-keyed maps can regress in size

**Source:** ObjMap stores keyword keys with a `"\uFDD0'"` prefix and uses a
**sorted key array** separate from the values **object** (§2.2). PersistentArrayMap
stores everything in a single flat array `[k,v,k,v,...]`.

For programs that create **many small keyword-keyed maps** — interceptor chains,
context maps, dispatch tables, data architecture libraries — the per-map ObjMap
overhead accumulates. A 2-key map like `{:queue b :stack M}` emits as:

```js
// Standard: single flat array literal
new PersistentArrayMap(null, 2, [queue_kw, b, stack_kw, M], null)
// Lite-mode: sorted key array + values object + \uFDD0' prefix per key
ObjMap.fromObject(["\uFDD0'queue", "\uFDD0'stack"],
                  {"\uFDD0'queue": b, "\uFDD0'stack": M})
```

The `\uFDD0'` prefix adds 3 bytes per keyword key. With hundreds of small maps
this overhead can outweigh the lite-mode code-size savings on collection
implementations, making the build **larger** than standard mode.

```clojure
;; ❌ Size regression risk: many small keyword-keyed maps
(def interceptors [{:phase :expand :before f}
                   {:phase :execute :before g}
                   {:phase :error :after h}])

;; ✅ Less affected: few large maps, vector-heavy data, non-keyword keys
[1 2 3 4 5]                                    ;; VectorLite is compact
{:users [{:id 1 :name "a"} {:id 2 :name "b"}]}  ;; one ObjMap at top level
```

---


```clojure
;; ✅ All safe in lite-mode — no persistent types pulled in
(def state (atom {}))
(reset! state {:x 1})
(swap! state assoc :y 2)
(add-watch state :log (fn [_ _ old new] (js/console.log old new)))
(js/console.log @state)

;; ℹ️ volatile! is still fine if you want slightly smaller builds
(def state (volatile! {}))  ;; ~5-8KB smaller, no watch support
```

#### C6. `doseq` and `for` are code size pitfalls

Both `doseq` and `for` know about chunked seqs, which pulls in `ChunkedCons`,
`IChunkedSeq`, and related infrastructure. Even with lite-mode's chunked-seq
guard (`chunked-seq?` always returns false), the code paths that reference
chunking types survive dead-code elimination.

**Recommendation:** For single-binding iteration, use `run!` instead of `doseq`:

```clojure
;; ❌ Pulls in chunked seq infrastructure
(doseq [x coll] (do-something x))

;; ✅ Preferred in lite-mode
(run! do-something coll)
```

#### C7. `seq` alone brings in significant code

Just calling `(seq coll)` pulls in the `ISeqable` protocol dispatch, `LazySeq`,
and iteration infrastructure. In lite-mode, the concrete seq types may differ but
the dispatch machinery is still included. For trivial scripting, prefer direct JS
interop or `run!`/`doseq` alternatives.

---

### 🟢 Category D: Patterns That Work Fine in Lite-Mode

These are 100% safe and should be preferred. The lite types implement all the same protocols
(`ICounted`, `ISeqable`, `ILookup`, `IAssociative`, `IMap`, `IVector`, `ISet`, `IEquiv`,
`IHash`, `IMeta`, `IWithMeta`, `ICloneable`, `IReduce`, `IKVReduce`, `IFn`, `IIterable`,
`IPrintWithWriter`, `IEmptyableCollection`, `ICollection`, plus transient interfaces as no-ops).

```clojure
;; Literals — emit-time dispatch handles these
[1 2 3]              ;; → VectorLite (compiler.cljc:600)
{:a 1 :b 2}          ;; → ObjMap (compiler.cljc:573)
{1 :a 2 :b}          ;; → HashMapLite (compiler.cljc:574)
#{:a :b :c}          ;; → SetLite (compiler.cljc:628)

;; Seq abstraction (works identically via ISeqable protocol)
(map inc [1 2 3])
(filter even? (range 100))
(reduce + 0 [1 2 3])
(first coll)
(rest coll)
(seq coll)

;; Collection predicates (use satisfies?/protocols, not type checks)
(vector? x)
(map? x)
(set? x)
(seq? x)
(coll? x)
(sequential? x)
(associative? x)
(counted? x)

;; Collection operations (via protocols — work on all implementations)
(get m k)
(assoc m k v)
(dissoc m k)
(conj c x)
(pop v)
(peek v)
(contains? m k)
(find m k)
(keys m)
(vals m)
(get-in m path)
(assoc-in m path v)
(update m k f)
(merge m1 m2)
(select-keys m ks)

;; Equality (uses protocols, works everywhere)
(= a b)
(not= a b)
(identical? a b)     ;; works, just don't rely on structural sharing

;; Metadata — all lite types have meta/__hash fields
;; VectorLite: core.cljs:12341, ObjMap: core.cljs:12612,
;; HashMapLite: core.cljs:12827, SetLite: core.cljs:13024
(with-meta coll m)
(meta coll)
(vary-meta coll f)

;; ES6 iteration — all lite types register via (es6-iterable ...)
;; VectorLite: core.cljs:12530, ObjMap: core.cljs:12780,
;; HashMapLite: core.cljs:12997, SetLite: core.cljs:13133

;; Invoke (IFn) — all lite types implement (-invoke coll k)
;; VectorLite: core.cljs:12453, ObjMap: core.cljs:12754,
;; HashMapLite: core.cljs:12960, SetLite: core.cljs:13111
(:a {:a 1})
([1 2 3] 1)
(#{:x} :x)
```

---

## 6. Detecting Lite-Mode at Compile Time

The compiler sets a Closure define — source: `closure.clj:2532-2534`:

```clojure
;; closure.clj:2532-2534
(:lite-mode opts)
(assoc-in [:closure-defines (str (comp/munge 'cljs.core/LITE_MODE))]
  (:lite-mode opts))
```

This becomes the constant at `core.cljs:57-58`:

```clojure
^{:doc "Boolean flag for LITE_MODE"}
LITE_MODE false)
```

In user code, use it with the `^boolean` type hint for dead-code elimination:

```clojure
;; ✅ Use LITE_MODE for conditional compilation
(when-not ^boolean LITE_MODE
  ;; code that only makes sense with persistent types
  (is (instance? RangedIterator (-iterator (subvec [0 1 2 3] 1 3)))))
```

The test suite uses this extensively — see:
- `collections_test.cljs:198,1022,1130` — guards on `^boolean LITE_MODE`
- `interop_test.cljs:18,26` — guards `.equiv` tests
- `seqs_test.cljs:43,210` — guards metadata and chunked seq tests

---

## 7. Evidence-Based Function Reference

Every entry below is traced to a specific `core.cljs` line number. Functions are
organized by the **protocol mechanism** that leaks persistent types into the
lite-mode build, verified via VLQ source map tracing (§Appendix B).

### Mechanism: Direct Persistent Type References

Functions that reference persistent type constructors, static fields, or static methods
**by name**. These are the primary leaks — a single call pulls in the referenced type
and its dependencies. Empirically confirmed: removing `set` alone dropped the build
from 124K→80K.

In contrast, functions like `into`/`mapv` that dispatch through `IEditableCollection`
(via protocol property access — no named type references) add &lt;2K each. The lite
types stub out the transient protocol with identity no-ops.

**❌ Banned (direct named refs):**

| Function | Line | Proof |
|---|---|---|
| `set` | 9586 | `.createAsIfByAssoc PersistentHashSet` [9602] |
| `hash-map` | 9052 | `(transient (.-EMPTY PersistentHashMap))` — references `PersistentHashMap.EMPTY` |
| `array-map` | 9064 | `(.createAsIfByAssoc PersistentArrayMap arr)` — direct static method |
| `sorted-map` | 9087 | `(.-EMPTY PersistentTreeMap)` — direct static field |
| `sorted-map-by` | 9096 | `(PersistentTreeMap. ...)` — direct constructor |
| `sorted-set` | 9617 | `(.-EMPTY PersistentTreeSet)` — direct static field |
| `sorted-set-by` | 9622 | `(PersistentTreeSet. ...)` — direct constructor |
| `zipmap` | 9689 | `(transient {})` — unverified leak impact |
| `frequencies` | 10225 | Same pattern; unverified |
| `group-by` | 11244 | Same pattern; unverified |

**✅ Safe replacements:**

| Pattern | Replacement |
|---|---|
| `(set coll)` | `(reduce conj #{} coll)` |
| `(hash-map k v ...)` | `{k v ...}` (literal emit → ObjMap/HashMapLite) |
| `(array-map k v ...)` | `{k v ...}` |

**✅ Confirmed safe (protocol-only, no direct persistent refs):**

| Function | Adds | Evidence |
|---|---|---|
| `into` | ~1K | Pure `IEditableCollection` protocol dispatch; lite types stub it out |
| `mapv` | ~1K | Same path as `into` via `transient` |
| `conj!`, `assoc!`, `transient`, `persistent!` | &lt;1K | Pure protocol dispatch |
| `vec` | ~14K | Rewritten to `vec-lite` [lite.cljc:17]; avoid if size-critical |
| `clojure.walk` | varies | Uses `into`; inlining tree walker removes cleanly |

### Mechanism: `IPrintWithWriter` Protocol (Printer)

`ExceptionInfo` extends `IPrintWithWriter` via `extend-type` [11787], calling
`pr-writer-ex-info` [11756] → `pr-writer` → `pr-writer-impl` [10444].

**Empirically verified:** This pulls in the printer infrastructure (~10KB over
baseline: `IPrintWithWriter`, `pr_writer`, `StringBuffer`, `IWriter`) but does
**not** pull in persistent types. The persistent types' `IPrintWithWriter`
implementations live in an `extend-protocol` block [10692] that is never
dispatched to in lite-mode (all collection instances are lite types with their
own `IPrintWithWriter` impls embedded in their `deftype` bodies
[12527,12776,13001,13130]). Closure eliminates the dead block.

```
ex-info → ExceptionInfo. → IPrintWithWriter [11787] → pr-writer-ex-info [11756]
  → pr-writer → pr-writer-impl [10444]
  → dispatch to lite type's IPrintWithWriter (never persistent types)
  → StringBuffer, quote-string, print-map, lift-ns (printer infra only)
```

**❌ Banned:**

| Function | Line | Proof |
|---|---|---|
| `ex-info` | 11796 | Creates `ExceptionInfo` which extends `IPrintWithWriter` at 11787 |
| `pr-str` | 10597 | Calls `pr-str-with-opts` → `pr-writer-impl` at 10444 |
| `prn` | 10643 | Calls `pr-with-opts` → `pr-writer-impl` at 10444 |
| `println` | 10629 | Calls `pr-with-opts` → `pr-writer-impl` at 10444 |
| `pr` | 10607 | Calls `pr-with-opts` → `pr-writer-impl` at 10444 |

**✅ Safe replacements:**

| Pattern | Replacement |
|---|---|
| `(ex-info msg data)` | `js/Error.` + custom property (see §B nexus case study) |
| `(pr-str x)` | JSON.stringify or custom serializer |
| `(prn x)` | `(js/console.log x)` |
| `(println x)` | `(js/console.log (str x))` |

### Mechanism: `IWatchable` / `Atom` — ✅ SAFE

`Atom` stores watches in a map, but in lite-mode it's always a lite type.
`-add-watch` calls `(assoc nil k v)` → literal `{k v}` → emit-time dispatch
produces `ObjMap`/`HashMapLite`. The `doseq` in `-notify-watches` compiles
to plain JS `for` loop. `IWatchable` protocol dispatch DCE'd when no watches
are used.

**Empirically verified:** Three independent builds (shadow-cljs ×2, cljs.main)
all show zero persistent type names.

**Verdict:** Safe. No replacement needed. `volatile!` is still a fine option
if you want ~5-8KB smaller builds and don't need watches or `swap!`.

### Mechanism: Chunked Seqs

`doseq` and `for` reference `ChunkedSeq`/`ChunkedCons`/`IChunkedSeq` even though
`chunked-seq?` returns `false` in lite-mode [2275-2279].

**❌ Banned:**

| Function | Line | Proof |
|---|---|---|
| `doseq` | macro | Expands to chunk-aware iteration |
| `for` | macro | Expands to chunk-aware iteration |
| `chunked-seq` | 6068 | `(ChunkedSeq. vec ...)` — direct constructor call |
| `chunk-cons` | 3794 | References `ChunkedCons` |
| `chunk`, `chunk-append`, `chunk-first`, `chunk-rest`, `chunk-next` | 3799–3811 | Chunk infrastructure |

**✅ Safe replacements:**

| Pattern | Replacement |
|---|---|
| `(doseq [x coll] body)` | `(run! (fn [x] body) coll)` |
| `(for [x coll] body)` | `(map (fn [x] body) coll)` |

### Mechanism: Direct Persistent Type Constructors
	
These create persistent type instances by name, bypassing lite-mode's emit-time dispatch.

**❌ Banned:**

| Function | Line | Proof |
|---|---|---|
| `hash-map` | 9052 | `(transient (.-EMPTY PersistentHashMap))` — both transient + direct type ref |
| `array-map` | 9064 | `(.createAsIfByAssoc PersistentArrayMap arr)` — direct static method |
| `set` | 9586 | `.createAsIfByAssoc PersistentHashSet` [9602] |
| `sorted-map` | 9087 | `(.-EMPTY PersistentTreeMap)` — direct static field access |
| `sorted-map-by` | 9096 | `(PersistentTreeMap. ...)` — direct constructor call |
| `sorted-set` | 9617 | `(.-EMPTY PersistentTreeSet)` — direct static field access |
| `sorted-set-by` | 9622 | `(PersistentTreeSet. ...)` — direct constructor call |

**✅ Safe replacements:**

| Pattern | Replacement |
|---|---|
| `(hash-map k v ...)` | `{k v ...}` (literal emit → ObjMap/HashMapLite) |
| `(array-map k v ...)` | `{k v ...}` |
| `(set coll)` | `(reduce conj #{} coll)` or `#{}` literal |

### Mechanism: `goog-define` Conditionals Must Be Top-Level

`^boolean LITE_MODE` conditionals nested inside function arguments are NOT eliminated
by Closure. They must be at the **top level of a `let` binding or `if` branch**.

**❌ Banned:**
```clojure
(let [x (merge (if ^boolean LITE_MODE lite persistent) common)]
  ...)
```

**✅ Safe:**
```clojure
(let [x (if ^boolean LITE_MODE
          (merge lite common)
          (merge persistent common))]
  ...)
```

### ✅ Functions Verified SAFE

These use non-leaking protocols (`ICollection`, `IAssociative`, `ILookup`, `ISeq`, `IKVReduce`, `IVolatile`).

| Function | Protocol | Core line |
|---|---|---|
| `conj` | `ICollection` | — |
| `reduce` + `conj` | `ICollection` (via conj) | — |
| `merge` | `ICollection` (via conj on maps) | — |
| `assoc`, `dissoc` | `IAssociative` / `IMap` | — |
| `get`, `get-in` | `ILookup` | — |
| `update`, `assoc-in` | `IAssociative` | — |
| `map`, `filter`, `reduce` | `ISeq` / `ISeqable` | — |
| `first`, `rest`, `next`, `seq` | `ISeq` | — |
| `keys`, `vals` | `ISeqable` | — |
| `count` | `ICounted` | — |
| `empty` | `IEmptyableCollection` | — |
| `volatile!`, `vreset!` | `IVolatile` | — |
| `into-array` | JS Array `.push` | 559 |
| `reduce-kv` | `IKVReduce` | — |
| `select-keys` | `ILookup` + `IAssociative` | — |
| `contains?`, `find` | `ILookup` | — |
| `vector` (lite-mode) | rewritten to `vector-lite` | lite.cljc:16 |
| `[]`, `{}`, `#{}` literals | emit-time lite dispatch | compiler.cljc:567-629 |

---


## Appendix A: Key Source Files

| File | Relevant Lines | What |
|---|---|---|
| `src/main/cljs/cljs/core.cljs` | 57-58 | `LITE_MODE` constant |
| | 2275-2279 | `chunked-seq?` guard |
| | 3639-3645 | `(-empty List)` metadata guard |
| | 4153-4161 | `(--destructure-map)` lite branch |
| | 9075-9082 | `seq-to-map-for-destructuring` lite branch |
| | 12330-12530 | `VectorLiteIterator`, `VectorLite` |
| | 12536-12557 | `vector-lite`, `vec-lite` |
| | 12583-12592 | `obj-map-compare-keys`, `keyword->obj-map-key`, `obj-map-key->keyword` |
| | 12603-12780 | `ObjMapIterator`, `ObjMap` |
| | 12786-12811 | `obj-map`, `ObjMap.createAsIfByAssoc`, `scan-array-equiv` |
| | 12827-13015 | `HashMapLite` |
| | 13016-13021 | `hash-map-lite` |
| | 13024-13133 | `SetLite` |
| | 13137-13148 | `set-lite` |
| `src/main/cljs/cljs/analyzer/passes/lite.cljc` | 1-32 | Full pass — `ctor->ctor-lite`, `use-lite-types` |
| `src/main/clojure/cljs/analyzer.cljc` | 497-498 | `lite-mode?` predicate |
| | 500-501 | `elide-to-string?` predicate |
| | 4226-4228 | `get-var` guard for `vector` |
| | 4606-4608 | Pass pipeline — appends `lite/use-lite-types` |
| `src/main/clojure/cljs/compiler.cljc` | 525-531 | `obj-map-key` helper |
| | 534-539 | `emit-obj-map` |
| | 541-544 | `emit-lite-map` |
| | 567-575 | `emit* :map` dispatch |
| | 591-594 | `emit-lite-vector` |
| | 596-601 | `emit* :vector` dispatch |
| | 619-622 | `emit-lite-set` |
| | 624-629 | `emit* :set` dispatch |
| `src/main/clojure/cljs/closure.clj` | 214-215 | Option allowlist (`:lite-mode`, `:elide-to-string`) |
| | 2529-2534 | Closure define for `LITE_MODE` |
| `src/main/clojure/cljs/core.cljc` | 1522-1531 | `add-obj-methods` — `toString` elision |
| `resources/lite_test.edn` | 1-28 | Working lite-mode build config example |
| `src/test/cljs/cljs/lite_collections_test.cljs` | 1-32 | Dedicated lite-mode test namespace |
| `src/test/cljs/lite_test_runner.cljs` | 1-127 | Full test suite under lite-mode |


## Appendix B: Empirical Build Analysis (Source Map Diagnosis)

### Methodology: VLQ Source Map Tracing

The `.js.map` file from a release build (`:source-maps true`) contains a `names` array
mapping every minified symbol back to its original `cljs.core` name, and VLQ-encoded
`mappings` that link each name to its source file and line. This allows **exact causal
tracing** of what keeps persistent types alive.

**Setup (shadow-cljs):**

Use two builds — one for size measurement (production), one for analysis (debug):

```clojure
{:builds {:app-lite {:target :browser
                     :modules {:main-lite {:entries [myapp]}}
                     ;; Production: measure actual size
                     :release {:compiler-options {:lite-mode       true
                                                  :elide-to-string true}}
                     ;; Debug: readable names for source map analysis (5-10× larger)
                     :dev {:compiler-options {:lite-mode       true
                                              :elide-to-string true
                                              :source-map      true
                                              :pseudo-names    true}}}}}}
```

`:pseudo-names true` keeps readable function names (`cljs$core$into`) instead
of single-letter minified names, making it trivial to trace what's in the build.
But it inflates artifact size 5-10× — use only for analysis, not production.

**Source map analysis (binary clean/dirty check):**
```python
import json

with open('public/js/main-lite.js.map') as f:
    m = json.load(f)

# shadow-cljs uses sectioned maps; vanilla cljs.main uses flat maps
if 'sections' in m:
    names = m['sections'][0]['map']['names']
else:
    names = m['names']

# Binary check: any Persistent* or Chunked* names = dirty build
pers = [n for n in names if 'Persistent' in n or 'Chunked' in n]
if pers:
    print(f'DIRTY: {len(pers)} persistent/chunked names present')
    for p in pers:
        print(f'  {p}')
else:
    print('CLEAN: zero persistent/chunked names')
```

**What to look for:**
1. **`Persistent*` names** — PersistentVector, PersistentArrayMap, PersistentHashMap,
   PersistentHashSet. Any of these = dirty build. Trace with VLQ decoder.
2. **`Chunked*` names** — ChunkedSeq, ChunkedCons. Should be absent.
3. **`Transient*` names** — appear alongside Persistent* when `set`/`hash-map` leak.
   They're a consequence of the persistent types being pulled in, not a root cause.
4. **`StringBuffer`** — always present from goog base, not a printer leak.
   Ignore it.
5. **`IPrintWithWriter`, `pr_writer`, `ExceptionInfo`** — printer infrastructure.
   Present only if `ex-info`, `pr-str`, `prn`, or `println` are used.
   Without `:elide-to-string` these appear for every build.

**With `:pseudo-names true`** you can grep for specific banned functions:
`cljs$core$set` → `set` was called somewhere. No VLQ decoding needed.

### Why Protocol Dispatch Does NOT Leak Persistent Types

The lite types implement `IEditableCollection` (and all
other protocols) in their own `deftype` bodies [core.cljs:12383,12856,13052].
The persistent types' implementations live in a separate `extend-protocol`
block [core.cljs:10692] that is never dispatched to in lite-mode (all
collection instances are lite types). Closure eliminates the dead block.

This is why `into`, `mapv`, `conj!`, and `transient` are safe (~1K each):
the protocol dispatch hits the lite type's identity-no-op implementation,
and the persistent type implementations are dead code.


### Finding: Goog-Define Conditionals Must Be Top-Level

`^boolean LITE_MODE` conditionals nested inside expressions are NOT eliminated
by Closure's dead-code elimination. They must be at the **top level of a `let`
binding or `if` branch**.

```clojure
;; ❌ Nested — Closure keeps BOTH branches alive
(let [handlers (merge
                 (if ^boolean LITE_MODE
                   {VectorLite handler}    ;; lite branch
                   {PersistentVector handler})  ;; persistent branch — NOT eliminated!
                 common)]
  ...)

;; ✅ Top-level — Closure eliminates the unused branch
(let [handlers (if ^boolean LITE_MODE
                 (merge {VectorLite handler} common)
                 (merge {PersistentVector handler} common))]
  ...)
```

This applies to all `goog-define` constants, not just `LITE_MODE`. The compiler
macro-expands them to `goog.get` calls, which Closure treats as constants — but
only at the statement/expression level, not inside arguments to other calls.


### Finding: `atom` Is Safe in Lite-Mode

**Source:** `clojure.core/atom` initializes watches to `nil`. The first
`add-watch` calls `(assoc nil k v)`, which hits the literal `{k v}` path —
emitted as `ObjMap` or `HashMapLite` in lite-mode. Subsequent `assoc`/`dissoc`
calls dispatch to the lite type's protocol implementation. No persistent types
are ever involved.

The `doseq` in `-notify-watches` compiles to a plain JS `for` loop, not chunked
seq infrastructure.

**Empirically verified (CLJS 1.11.60–1.12.x, three independent builds):**
`(atom ...)` with `reset!`, `swap!`, `@`, and `add-watch` shows **zero
persistent type names** in source maps.


```clojure
;; ✅ OK in lite-mode — no persistent types pulled in
(def state (atom {}))
(reset! state {:x 1})
(swap! state assoc :y 2)
(js/console.log @state)

;; ℹ️ volatile! is still fine if you don't need watches (slightly smaller)
(def state (volatile! {}))
```


### Finding: `ex-info` Pulls In the Printer Chain

**Source:** `ExceptionInfo` implements `IPrintWithWriter` via `extend-type`
(not Object `toString`), so `:elide-to-string` doesn't help. The `IPrintWithWriter`
protocol dispatch pulls in `pr-writer-impl` and the printer infrastructure
(`StringBuffer`, `IWriter`, `pr_writer`, `print-map`). **Empirically, this does
NOT pull in persistent types** — the persistent types' `IPrintWithWriter` impls
live in a separate `extend-protocol` block [10692] that is never dispatched to
in lite-mode (all instances are lite types). The printer infrastructure alone
costs ~10KB.

**Lite-safe pattern:**
```clojure
(defn error [message data]
  #?(:clj  (ex-info message data)
     :cljs (if ^boolean LITE_MODE
             (let [e (js/Error. message)]
               (set! (.-errorData e) data) e)
             (ex-info message data))))

(defn error-data [e]
  #?(:clj  (ex-data e)
     :cljs (if ^boolean LITE_MODE (.-errorData e) (ex-data e))))
```


### Case Study: nexus (interceptor library)

An interceptor/effect-dispatch library was analyzed to identify what prevented
the lite-mode build from shrinking below standard mode. Each change was verified
by VLQ source map tracing (§B.1) before and after.

**Build:**
| Stage | Size | Names | Persistent names |
|---|---|---|---|
| Standard advanced | 135K | — | — |
| Lite-mode + elide-to-string (initial) | 136K | 1649 | 62 |
| — `atom` → `volatile!` | 132K | — | 62 |
| — `mapv`, `into`, `clojure.walk`, `vec`, `set` | 80K | 1191 | 4 |

**What was fixed:**
1. `ex-info` → `js/Error.` with `LITE_MODE` guard
2. `vec` → `(or (:nexus/interceptors nexus) [])` (3 sites) — 14K saving
3. **`set` → `(reduce conj #{})`** — **THE primary fix. `set` directly references `PersistentHashSet.createAsIfByAssoc` [9602]. This single call kept all persistent types alive.**

**Key finding:** Protocol-dispatch functions (`into`, `mapv`) are safe (&lt;2K each).
The real leaks are functions with **direct named references** to persistent type
constructors/fields (`set`, `hash-map`, `array-map`, `sorted-map`, etc.) and
printer-triggered functions (`ex-info`, `println`).

**Result:** 41% size reduction (136K → 80K). All persistent types eliminated.

## Appendix C: Previous Case Studies

### reagami

Commit: https://github.com/borkdude/reagami/commit/8ff85aece7fe39dc764ef0896f8d837f0dd8375c

**Key change:** All `::auto-resolved-keywords` used as JavaScript object property names
(via `aset`/`aget`) were replaced with `^:private` string constants:

```clojure
;; Before (broken in lite-mode):
(aset node ::on-render v)
(aget node ::vnode)

;; After (lite-mode safe):
(def ^:private on-render-key "reagami.core/on-render")
(def ^:private vnode-key    "reagami.core/vnode")
(aset node on-render-key v)
(aget node vnode-key)
```

**Why it matters:** In lite-mode with `:advanced` compilation, Closure's property
renaming can't unify keyword-based property access with other accesses to the same
logical property name. The `::kw` auto-resolution happens at read time, but the
resulting string flows through code paths that differ between standard and lite-mode,
causing Closure to assign different minified names. Using a single `^:private`
string constant guarantees Closure sees the same value everywhere and renames it
consistently.

**Also fixed:** `(subs (str tag) 1)` → `(name tag)` — `name` is the canonical way
to get a keyword's string representation; `str` on keywords can behave differently
when `:elide-to-string` is enabled.
