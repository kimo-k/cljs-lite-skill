# Lite-Mode Data Structures

Source: `/home/kk/clojurescript/src/main/cljs/cljs/core.cljs`

---

## VectorLite (line 12341)

**Fields:** `[meta array ^:mutable __hash]`
**Backing store:** plain JavaScript array (`array`)
**Iterator:** `VectorLiteIterator` (line 12332) — wraps the array with index `i`

### Key operations
- `-conj` (12401): `(aclone array)` + `.push` → new `VectorLite`
- `-pop` (12393): `(aclone array)` + `.pop` → new `VectorLite`
- `-assoc` / `-assoc-n` (12444): `(aclone array)` + `aset` → new `VectorLite`; throws on non-number key
- `-seq` (12417): `(prim-seq array)` — nil if empty
- `-count` (12422): `(alength array)`
- `-nth` (12425): `(aget array (int n))`; throws on out-of-bounds (2-arity returns `not-found`)
- `-reduce` (12465): delegates to `array-reduce`
- `-kv-reduce` (12471): manual loop over `array`
- `-drop` (12481): `(prim-seq array n)` — no copy

### Transient — identity no-op
- `-as-transient` (12501): returns `coll` itself
- `-conj!` (12505): calls `-conj` (copies!)
- `-persistent!` (12507): returns `coll` itself
- `-assoc!` / `-assoc-n!`: delegates to persistent `-assoc-n`
- **Every `conj!`/`assoc!` creates a full copy — no mutation, no perf benefit**

### Static members
- `VectorLite.EMPTY` (12532): `(VectorLite. nil (array) nil)`
- `VectorLite.fromArray` (12534): takes ownership of the array (no copy)

### Constructor functions
- `vector-lite` (12536): varargs → `VectorLite`
- `vec-lite` (12543): handles map-entry, vector (with-meta nil), array, else `(into [] coll)`

---

## ObjMap (line 12612) — replaces PersistentArrayMap for keyword/string keys

**Fields:** `[meta strkeys strobj ^:mutable __hash]`
**Backing store:**
- `strkeys` — plain JS array of encoded key strings (sorted by hash for iteration)
- `strobj` — plain JS object mapping encoded key → value

**Iterator:** `ObjMapIterator` (line 12603)

### Key encoding (lines 12585–12593)
- `keyword->obj-map-key`: prepends `"﷐'"` to `kw.fqn` — e.g. `:foo` → `"﷐'foo"`
- `obj-map-key->keyword`: reverses — strips prefix, calls `(keyword ...)`
- Non-keyword string keys are stored as-is (no prefix)
- Key sort order: `obj-map-compare-keys` (12565) sorts by `(hash k)`

### Key operations
- `-lookup` (12670): `scan-array` on `strkeys` + `unchecked-get strobj`
- `-assoc` (12680):
  - String/keyword key, key exists: clone `strobj` → new `ObjMap` (same `strkeys`)
  - String/keyword key, new key: clone both, `.push` new key
  - **Non-string/keyword key: promotes entire map to `HashMapLite`** (metadata preserved)
- `-dissoc` (12741): `aclone strkeys` + `.splice` + `obj-clone strobj` + `js-delete`
- `-count` (12668): `(alength strkeys)`
- `-seq` (12661): `.map` over sorted `strkeys`, returning `MapEntry` objects

### Transient — identity no-op (same as VectorLite)

### Static members
- `ObjMap.EMPTY` (12782): `(ObjMap. nil (array) (js-obj) empty-unordered-hash)`
- `ObjMap.fromObject` (12784): takes ownership of both arrays

---

## HashMapLite (line 12827) — replaces PersistentHashMap for arbitrary keys

**Fields:** `[meta count hashobj ^:mutable __hash]`
**Backing store:** `hashobj` — plain JS object used as hash-bucket table
- Keys of `hashobj` are `(hash k)` stringified
- Each bucket is `[k v k v ...]` flat array for collision resolution
- `count` is maintained explicitly

### Key operations
- `-lookup` (12895): `(unchecked-get hashobj (hash k))` → `scan-array-equiv 2 k bucket`
- `-assoc` (12903):
  - Bucket exists, key found: `aclone bucket` + `gobject/clone hashobj` (with `identical?` short-circuit)
  - Bucket exists, key absent: `aclone bucket` + `.push k v`
  - No bucket: `gobject/clone hashobj` + `unchecked-set h (array k v)`
- `-dissoc` (12935): clones `hashobj`; if bucket ≤2 entries `js-delete` it, else `aclone + .splice`
- `-seq` (12873): sorts `(js-keys hashobj)`, pushes `MapEntry` into flat array → `prim-seq`
- `-count` (12891): field `count` directly (O(1))

### Transient — identity no-op

### Static members
- `HashMapLite.EMPTY` (13007): `(HashMapLite. nil 0 (js-obj) empty-unordered-hash)`
- `HashMapLite.fromArrays` (13009): loops `assoc` over parallel key/value arrays into `EMPTY`

### Helper
- `scan-array-equiv` (12814): linear scan using `=` (not `identical?`) — used for bucket key lookup

---

## SetLite (line 13024) — replaces PersistentHashSet

**Fields:** `[meta hash-map ^:mutable __hash]`
**Backing store:** a `HashMapLite` where each element maps to itself

### Key operations
- `-conj` (13055): `(assoc hash-map o o)` — `identical?` short-circuit → new `SetLite`
- `-disjoin` (13097): `(-dissoc hash-map v)` → new `SetLite`
- `-lookup` (13089): `(-contains-key? hash-map v)` → `(-lookup hash-map v)`
- `-seq` (13076): `(-seq hash-map)` → `.map arr (fn [kv] (-key kv))`
- **`-count` (13081): `(-count (-seq coll))` — O(n), not O(1)**

### Transient — identity no-op

### Static members
- `SetLite.EMPTY` (13135): `(SetLite. nil (. HashMapLite -EMPTY) empty-unordered-hash)`

---

## Subtle gotchas

- **`-count` on SetLite** goes through `(-seq coll)` — O(n), not O(1)
- **ObjMap promotes to HashMapLite** on non-string/keyword `assoc`, losing sorted-array structure but *preserving metadata*
- **`scan-array` uses `identical?`** (ObjMap, encoded string keys) vs **`scan-array-equiv` uses `=`** (HashMapLite, arbitrary keys)
- **All four types embed `IPrintWithWriter` in their `deftype` body** (not `extend-protocol`), so the printer's `extend-protocol` block for persistent types is never dispatched to → DCE-safe
- **Transients are identity no-ops** — `(transient [])` returns the same vector, every `conj!` still copies
- **`vec-lite`'s else-branch** calls `(into [] coll)` which can drag in extra infrastructure; avoid `vec` if size-critical
- **ObjMap keyword prefix** is `"﷐'"` (3 bytes per keyword key) — many small maps can regress vs standard mode
