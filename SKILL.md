---
name: lite-mode
description: "Optimize ClojureScript builds for :lite-mode + :elide-to-string. Diagnose what leaked, fix banned functions, understand data structure mechanics. Entrypoint: bb lite.bb"
user-invocable: true
---

# Lite-Mode ClojureScript Optimization

This skill covers the full loop: build → diagnose → fix → verify. It is grounded in
the actual `cljs/core.cljs` source (read directly; see `types.md`).
Claims marked ⚠️ are from empirical measurement and may drift across CLJS versions.

When this skill is invoked, run `validate` immediately to check requirements and list
available shadow-cljs builds. Then run `clean` to remove any prior slots. Then proceed
to diagnose the build. Do not ask the user what they want — diagnosing the codebase for
lite-mode optimization is the task.

If the user implies source code has changed — even informally ("how about now?", "check
again, I changed something", "I fixed that", "try it again") — assume they want a full
rebuild before checking. Do not re-run `check` on stale artifacts.

---

## When to Use

- The release build is larger than expected or larger than the standard build
- You see `Persistent*` or `Chunked*` names anywhere in the output
- You are adding new ClojureScript code to a namespace compiled with `:lite-mode true`
- You want to know if a function call is safe to use in a lite-mode build

---

## The Diagnostic Loop

### Step 1 — Build

```bash
# First, see what builds are available:
bb lite.bb validate

# Build both measure + diagnostic slots in one command:
bb lite.bb build advanced <build-name> [cmd-prefix…]
# e.g.: bb lite.bb build advanced app clojure -M:demo:shadow
```

Each `build` call creates a new experiment (increments `:latest` in `latest.edn`) and
writes two slots:
- `.cljs-lite-skill/advanced-<n>/` — measure build: no pseudo-names, accurate production size
- `.cljs-lite-skill/diag-advanced-<n>/` — diagnostic build: pseudo-names + source-map, readable names

State is tracked in `.cljs-lite-skill/latest.edn`:
```edn
{:latest 1
 1 {:advanced {:measure "advanced-1" :diag "diag-advanced-1"}}}
```

Compiler options (`:lite-mode`, `:elide-to-string`, `:pseudo-names`, `:source-map`) are
injected via `--config-merge` at build time. **Do not tell users to add these to
`shadow-cljs.edn`** — the build target does not need them and `lite.bb` handles everything.

**Two slots, two purposes — never mix them:**
- **Size** → always from the measure slot (`<label>-<n>/`). The diag slot is 5-10× larger due to pseudo-names and means nothing.
- **Inspection** (what's in the build, leaked types, banned functions, reading names) → always from the diag slot (`diag-<label>-<n>/`). The measure slot has fully mangled names that are unreadable.

`check` and `diff` handle this automatically. If you ever read the artifact directly, use the diag slot.

### Step 2 — Analyse

```bash
bb lite.bb check advanced
# (default label is advanced, so bare `check` works too)
```

Looks up the label in the current experiment (`:latest`) from `latest.edn`, then analyzes the diag slot.

Produces a report with four sections:

1. **Leaked types** — `Persistent*` / `ChunkedSeq` / `ChunkedCons` names found in the artifact.
   Any of these = dirty build. Zero = clean.

2. **Banned functions in artifact** — specific mangled names for known-bad functions
   (`cljs$core$set$$`, `cljs$core$doseq$$`, etc.) present in the artifact with suggested fixes.

3. **Source violations (clj-kondo)** — uses the source map to identify only actually-compiled
   project files, then runs clj-kondo on those files. No false positives from DCE'd namespaces.
   Only report violations that match the banned functions list. Ignore all other kondo output —
   general linting warnings are not relevant here.

4. **Name count** — total mangled-name count. Fewer names ≈ more DCE.

### Step 2b — Multi-label experiments

The experiment system is not limited to A/B. You can group any number of labeled builds
into one experiment using `--exp <n>` — useful for comparing three approaches, isolating
individual fixes, or building a baseline alongside several variants. Pick label names that
describe what changed.

To compare two builds within the same experiment, pass `--exp <n>` to group them:

```bash
bb lite.bb build before <build-name> [cmd-prefix…]
# note the experiment number printed, e.g. [exp 3]
# ... make changes ...
bb lite.bb build after <build-name> --exp 3 [cmd-prefix…]
bb lite.bb diff before after
```

`diff` looks up both labels in the current experiment (`:latest`). `diff` uses:
- measure slots (`before-<n>`, `after-<n>`) for accurate brotli size delta
- diagnostic slots (`diag-before-<n>`, `diag-after-<n>`) for leaked types, banned functions, name count delta

### Step 3 — Fix

Apply replacements from the tables below. Rebuild and re-check until the report shows
CLEAN with zero banned functions.

### Step 2c — Per-source byte breakdown

```bash
bb lite.bb report advanced
# (default label is advanced, so bare `report` works too)
```

Parses the `report.html` written into the measure slot during build. Extracts the
`shadow/build-report` EDN embedded in a `<script>` tag and prints **post-DCE bytes per
source**, sorted descending. Zero-byte entries are filtered out.

Example output:
```
=== report: advanced-5 (post-DCE bytes) ===

source                                                        bytes  type
------------------------------------------------------------------------
cljs/core.cljs                                                47302  cljs
goog/string/stringbuffer.js                                     308  goog
nextjournal/offworld/demo/main.cljs                             258  cljs [src]
------------------------------------------------------------------------
TOTAL                                                         48346
```

The `type` column shows `cljs`, `goog`, or `?`, with a `[fs-root]` suffix for project
sources so you can distinguish your code from library code.

Use this when the build is CLEAN but still larger than expected — `check` tells you
what leaked or what's banned, but `report` tells you which namespace is dominating.

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
| `lite.bb` | Standalone diagnostic script |
| `types.md` | Exact data structure implementation reference |
| `guide.md` | Deep reference with core.cljs line numbers |
| `setup.md` | How to add diagnostic build to a project |
| `.cljs-lite-skill/<label>-<n>/` | Measure slot — production-equivalent size + `report.html` |
| `.cljs-lite-skill/diag-<label>-<n>/` | Diagnostic slot — pseudo-names, readable names |
| `.cljs-lite-skill/latest.edn` | State file: experiment history and current `:latest` |
