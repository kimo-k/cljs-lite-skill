# Lite-Mode Diagnostic Setup

`lite.bb` works against any existing shadow-cljs browser build. No changes to
`shadow-cljs.edn` are required — all diagnostic options are injected at build time
via `--config-merge`.

---

## Requirements

- **Babashka** — `bb` on PATH
- **clj-kondo** — on PATH (for source violation analysis)
- **brotli** — on PATH (for compressed size reporting)
- **shadow-cljs** — via npm (`npx shadow-cljs`) or clojure deps

---

## 1. Find a build to target

```bash
bb /path/to/lite.bb validate
```

Lists all `:browser` target builds in `shadow-cljs.edn`. Pick whichever represents
your app's main entry point. The tool will inject `:lite-mode`, `:elide-to-string`,
`:pseudo-names`, and `:source-map` on top of it.

---

## 2. Run

```bash
# Build into a labeled slot:
bb /path/to/lite.bb build latest <build-name> [cmd-prefix…]

# cmd-prefix is how to invoke shadow-cljs in this project.
# Default if omitted: npx shadow-cljs
# For clojure deps projects: clojure -M:<your-aliases>

# Examples:
bb /path/to/lite.bb build latest app clojure -M:demo:shadow
bb /path/to/lite.bb build latest app npx shadow-cljs

# Analyse:
bb /path/to/lite.bb check latest

# A/B comparison:
bb /path/to/lite.bb build before <build-name> [cmd-prefix…]
# ... make changes ...
bb /path/to/lite.bb build after  <build-name> [cmd-prefix…]
bb /path/to/lite.bb diff before after
```

---

## 3. Add target/ to .gitignore

```
/target/
```
