---
name: lite-mode
description: "Optimize ClojureScript builds for :lite-mode + :elide-to-string. Diagnose what leaked, fix banned functions, understand data structure mechanics. Entrypoint: bb ~/lite-mode-skill/lite.bb"
user-invocable: true
---

# Lite-Mode ClojureScript Optimization

This skill covers the full loop: build → diagnose → fix → verify. It is grounded in
the actual `cljs/core.cljs` source (read directly; see `types.md`).
Claims marked ⚠️ are from empirical measurement and may drift across CLJS versions.

When this skill is invoked, run `validate` immediately to check requirements and list
available shadow-cljs builds, then proceed to diagnose the build. Do not ask the user
what they want — diagnosing the codebase for lite-mode optimization is the task.

---

## When to Use

- The release build is larger than expected or larger than the standard build
- You see `Persistent*` or `Chunked*` names anywhere in the output
- You are adding new ClojureScript code to a namespace compiled with `:lite-mode true`
- You want to know if a function call is safe to use in a lite-mode build

---

## The Diagnostic Loop

### Step 1 — Build the diagnostic artifact

```bash
# First, see what builds are available:
bb ~/lite-mode-skill/lite.bb validate

# Build with pseudo-names + source-map for diagnosis:
bb ~/lite-mode-skill/lite.bb build-diagnostic latest <build-name> [cmd-prefix…]
# e.g.: bb ~/lite-mode-skill/lite.bb build-diagnostic latest app clojure -M:demo:shadow
```

Injects `:lite-mode`, `:elide-to-string`, `:pseudo-names`, `:source-map` via `--config-merge`.
Output goes to `target/lite-diag/latest/`. The `:pseudo-names` flag keeps mangled names
readable — e.g. `cljs$core$PersistentVector$$` instead of `a` — so leaks are visible.

Note: artifact is 5-10× larger than production because of `:pseudo-names`. Use
`build-measure` for true size numbers.

### Step 2 — Analyse

```bash
bb ~/lite-mode-skill/lite.bb check latest
```

Produces a report with four sections:

1. **Leaked types** — `Persistent*` / `ChunkedSeq` / `ChunkedCons` names found in the artifact.
   Any of these = dirty build. Zero = clean.

2. **Banned functions in artifact** — specific mangled names for known-bad functions
   (`cljs$core$set$$`, `cljs$core$doseq$$`, etc.) present in the artifact with suggested fixes.

3. **Source violations (clj-kondo)** — uses the source map to identify only actually-compiled
   project files, then runs clj-kondo on those files. No false positives from DCE'd namespaces.

4. **Name count** — total mangled-name count. Fewer names ≈ more DCE.

### Step 2b — A/B size comparison

```bash
bb ~/lite-mode-skill/lite.bb build-measure before <build-name> [cmd-prefix…]
# ... make changes ...
bb ~/lite-mode-skill/lite.bb build-measure after  <build-name> [cmd-prefix…]
bb ~/lite-mode-skill/lite.bb diff before after
```

`build-measure` injects only `:lite-mode` + `:elide-to-string` — no pseudo-names, so
brotli sizes reflect production reality.

The diff shows size delta, which leaked types were fixed/added, which banned functions
changed, and name count delta.

### Step 3 — Fix

Apply replacements from the tables below. Rebuild and re-check until the report shows
CLEAN with zero banned functions.

### Step 4 — Measure production size

```bash
bb measure-build
```

Reports actual brotli size of `main-lite.js`. Target: < 100k. Smaller = better.

---

## Banned & Safe Functions

Banned functions fall into three categories by leak mechanism — useful when reasoning
about functions not explicitly listed:

1. **Direct persistent type references** — the function body names `PersistentHashMap`,
   `PersistentHashSet`, etc. by symbol. One call anywhere in the transitive graph keeps
   the entire type alive. (`set`, `hash-map`, `array-map`, `sorted-*`, `zipmap`, etc.)

2. **`IPrintWithWriter` via `extend-type`** — pulls in the printer chain
   (`pr-writer-impl`, `StringBuffer`, `IWriter`). **`:elide-to-string` does NOT protect
   against this.** That flag only strips `Object toString` from `deftype` bodies; printer
   leaks through `extend-type` are a completely separate code path that survives it.
   (`ex-info`, `pr-str`, `prn`, `println`)

3. **Chunked seq macro expansion** — `doseq` and `for` expand to code that references
   `ChunkedSeq`/`ChunkedCons` even though `chunked-seq?` returns `false` at runtime.
   (`doseq`, `for`)

### ❌ Banned

| Call | Mechanism | Replacement |
|---|---|---|
| `(set coll)` | direct ref: `.createAsIfByAssoc PersistentHashSet` [core.cljs:9602] | `(reduce conj #{} coll)` |
| `(hash-map k v)` | direct ref: `(.-EMPTY PersistentHashMap)` [9052] | `{k v ...}` literal |
| `(array-map k v)` | direct ref: `.createAsIfByAssoc PersistentArrayMap` [9064] | `{k v ...}` literal |
| `(sorted-map ...)` | direct ref: `(.-EMPTY PersistentTreeMap)` [9087] | avoid; use plain map |
| `(sorted-map-by ...)` | direct ref: `(PersistentTreeMap. ...)` [9096] | avoid |
| `(sorted-set ...)` | direct ref: `(.-EMPTY PersistentTreeSet)` [9617] | avoid |
| `(sorted-set-by ...)` | direct ref: `(PersistentTreeSet. ...)` [9622] | avoid |
| `(zipmap ks vs)` | direct ref: `(transient {})` path [9689] | `(into {} (map vector ks vs))` |
| `(frequencies coll)` | direct ref: same pattern [10225] | `(reduce (fn [m x] (update m x (fnil inc 0))) {} coll)` |
| `(group-by f coll)` | direct ref: same pattern [11244] | `(reduce (fn [m x] (update m (f x) (fnil conj []) x)) {} coll)` |
| `(doseq [x coll] body)` | chunked seq expansion | `(run! (fn [x] body) coll)` |
| `(for [x coll] expr)` | chunked seq expansion | `(map (fn [x] expr) coll)` |
| `(ex-info msg data)` | IPrintWithWriter/extend-type — **not blocked by :elide-to-string** | `(js/Error. msg)` with `(set! (.-data e) data)` |
| `(pr-str x)` | IPrintWithWriter: calls `pr-writer-impl` directly [10597] | custom serializer |
| `(prn x)` | IPrintWithWriter: calls `pr-writer-impl` [10643] | `(js/console.log x)` |
| `(println x)` | IPrintWithWriter: calls `pr-writer-impl` [10629] | `(js/console.log (str x))` |
| `(vec coll)` | direct ref: `vec-lite`'s else-branch leaks | `(or (:key m) [])` or `[]` literal |

### ✅ Safe — protocol dispatch only, no persistent type references

These go through `IEditableCollection`, `IAssociative`, `ISeqable`, etc. The lite types
implement all these protocols in their own `deftype` bodies. Closure eliminates the
persistent-type implementations from the `extend-protocol` block since they're never dispatched to.

| Function | Notes |
|---|---|
| `into`, `mapv` | `IEditableCollection` dispatch; lite types stub transient with identity |
| `conj`, `conj!`, `assoc`, `assoc!`, `dissoc` | protocol dispatch |
| `get`, `get-in`, `find`, `contains?` | `ILookup` |
| `update`, `assoc-in`, `merge`, `select-keys` | `IAssociative` |
| `map`, `filter`, `reduce`, `run!` | `ISeq` / `ISeqable` |
| `first`, `rest`, `next`, `seq` | `ISeq` |
| `keys`, `vals`, `count`, `empty` | protocols |
| `vector?`, `map?`, `set?`, `coll?` | predicate protocols |
| `atom`, `reset!`, `swap!`, `add-watch`, `@` | verified safe — watches use lite maps |
| `volatile!`, `vreset!`, `vswap!` | safe; ~5-8k smaller than atom if no watches needed |
| `transient`, `persistent!` | identity no-ops on lite types — safe, zero perf benefit |
| `[]`, `{}`, `#{}` literals | emit-time dispatch → VectorLite / ObjMap / SetLite |
| `(vector ...)` | compiler pass rewrites to `vector-lite` |

---

## Compiler Rewrites (What the Compiler Does Automatically)

The analysis pass (`analyzer/passes/lite.cljc`) rewrites exactly two var references:
- `cljs.core/vector` → `cljs.core/vector-lite`
- `cljs.core/vec` → `cljs.core/vec-lite`

Emit-time dispatch handles literals:
- `[...]` → `new VectorLite(...)`
- `{:kw val}` / `{"str" val}` → `ObjMap.fromObject(...)`
- `{1 "one"}` (non-string/keyword keys) → `HashMapLite.fromArrays(...)`
- `#{...}` → `set_lite(...)`

Everything else — `hash-map`, `array-map`, `set`, `sorted-map` — is **not rewritten**.

---

## The `^boolean LITE_MODE` Guard

Use this for code that must behave differently at runtime between lite and standard builds.
Closure treats it as a compile-time constant and eliminates the unused branch — but only
when the conditional is at the **top level of a `let` binding or `if` expression**:

```clojure
;; ✅ Closure eliminates the unused branch
(let [result (if ^boolean LITE_MODE
               (lite-path args)
               (standard-path args))]
  result)

;; ❌ Nested in function args — Closure keeps BOTH branches alive
(let [result (some-fn (if ^boolean LITE_MODE lite-val standard-val) other-arg)]
  result)
```

Standard pattern for ex-info compatibility:

```clojure
(defn error [msg data]
  #?(:clj  (ex-info msg data)
     :cljs (if ^boolean cljs.core/LITE_MODE
             (let [e (js/Error. msg)]
               (set! (.-data e) data) e)
             (ex-info msg data))))

(defn error-data [e]
  #?(:clj  (ex-data e)
     :cljs (if ^boolean cljs.core/LITE_MODE (.-data e) (ex-data e))))
```

---

## Data Structure Mechanics (Quick Reference)

Full details in `types.md`. Key points for optimization:

### VectorLite
- Every `conj`/`assoc`/`pop` clones the entire JS array (`aclone`).
- `transient` / `persistent!` are identity — no mutation escape hatch.
- Building a 1000-element vector with `reduce conj` = ~500k element copies vs ~1k in standard.
- Prefer `into []` over sequential `conj` for large collections.

### ObjMap (keyword/string keys)
- Two structures: sorted key array + JS object. Each keyword key prefixed `"﷐'"` (3 extra bytes/key).
- Assigning a non-string/keyword key **silently promotes the whole map to HashMapLite**.
- Many small keyword-keyed maps can make the lite build **larger** than standard.

### HashMapLite (arbitrary keys)
- Bucket-per-hash scheme: `hashobj[hash(k)] = [k, v, k, v, ...]`.
- Every `assoc`/`dissoc` clones the bucket array and the bucket table.

### SetLite
- Backed by a `HashMapLite` (each element stored as both key and value).
- `-count` goes through `(-seq coll)` — it is **not O(1)**.

---

## Common Pitfalls

**`(str coll)` returns JS default** — with `:elide-to-string`, `toString` is stripped from
all lite types. `(str {:a 1})` returns `"[object Object]"`. Use `(pr-str coll)` if you need
Clojure-readable output (but note: `pr-str` pulls in the printer).

**`vec` still adds ~14k** — even though `vec` is rewritten to `vec-lite` by the compiler,
`vec-lite`'s else-branch calls `(into [] coll)` which can drag in infrastructure.
⚠️ Measured empirically on one build; may vary.

**Structural sharing is gone** — `(identical? (:a base) (:a (assoc base :c 2)))` is always
`false` in lite-mode. Don't rely on it for equality or caching.

**`sorted-map` / `sorted-set` have no lite equivalent** — they pull in `PersistentTreeMap`/
`PersistentTreeSet` with no compiler-provided fallback. Avoid entirely.

---

## File Locations

| File | Purpose |
|---|---|
| `~/lite-mode-skill/lite.bb` | Standalone diagnostic script |
| `~/lite-mode-skill/types.md` | Exact data structure implementation reference |
| `~/lite-mode-skill/guide.md` | Deep reference with core.cljs line numbers |
| `~/lite-mode-skill/setup.md` | How to add diagnostic build to a project |
| `shadow-cljs.edn` `:app-diag` | Diagnostic build config (all four options locked in) |
| `target/lite-diag/<label>/` | Build slot — JS + source map + brotli |
