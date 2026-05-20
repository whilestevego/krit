# Changelog

All notable changes to this project will be documented in this file.

## v0.3.0 — 2026-05-20

### Added

- **`--error-log [FILE]`** — collect structured inspection errors instead of silently swallowing them. Omit the flag for no log; use `--error-log` alone to write `krit-errors.log`; supply a path to write elsewhere. Errors are deduplicated by `(inspectionId, exceptionClass)` so a single broken inspection doesn't flood the log.
- **Companion JARs** — 10 companion JARs from the Kotlin language-server tarball are now downloaded at build time and bundled alongside `language-server-plugins-kotlin.jar`. This lets IDE inspection classes resolve their base types at load time, fixing inspections that previously failed silently due to `ClassNotFoundException`.
- **`ServiceShims`** — no-op implementations of `ModCommandService`, `CodeStyleManager`, `PomModel`, and `IndentHelper` so inspections that call these services don't crash on startup.
- **Unit test suite** — 61 unit tests covering `Severity`, `ConfigLoader`, `TextReporter`, `SarifReporter`, `AnalysisRunner`, and `LineFilterStream`. Run with `./gradlew test` (fast, under 5 seconds, no K2 session).
- **Integration test suite** — `KritIntegrationTest` with 9 end-to-end tests covering type errors, suppression, severity overrides, and multi-file analysis. Run with `./gradlew integrationTest`.

### Fixed

- **Inspection execution order** — IDE inspections are now collected and run after the `analyze {}` block closes. Previously they were nested inside an outer K2 analysis session, which blocked `KotlinApplicableInspectionBase` from opening its own session and caused it to silently produce no findings.
- **Classloader conflicts** — source stubs for `ReferencesSearch` and IntelliJ container utilities resolve symbol conflicts between the bundled platform JARs and the companion JARs loaded by `InspectionRunner`.

## v0.2.0 — 2026-05-20

### Added

- **`bin/krit` launcher** — shell wrapper that applies the required JVM flags (`--enable-native-access`, `--add-opens`) automatically. Auto-discovers a Java 25+ installation from `JAVA_HOME`, Zed's bundled JBR, or `PATH`.
- **`classpath` / `classpath-file` in `krit.yml`** — specify the compile classpath from the config file instead of (or in addition to) `--classpath`. `shadowJar` now also writes `build/compile-classpath.txt`, so krit can analyze itself with no `--classpath` flag needed.
- **CI workflow** — GitHub Actions workflow that builds the fat JAR and runs self-analysis on every push.

### Changed

- The fat JAR is now fully self-contained: `language-server-plugins-kotlin.jar` (Kotlin IntelliJ plugin inspections) is bundled at build time and downloaded from JetBrains CDN rather than requiring a Zed installation at runtime.
- CLI help text improved; help is now shown when krit is invoked with no arguments.

## v0.1.0 — 2026-05-19

Initial release.

### Added

- Kotlin K2 (FIR) compiler diagnostics with full type resolution via the Kotlin Analysis API
- Kotlin IntelliJ plugin inspections alongside compiler diagnostics
- Four severity levels: `ERROR`, `WARNING`, `HINT`, `INFO`
- `--input` / `-i` — source directory or `.kt` / `.java` file to analyze (repeatable)
- `--classpath` / `-cp` — compile classpath for type resolution
- `--config` / `-c` — YAML config file for suppressing rules and overriding severities
- `--format` / `-f` — output as human-readable `text` or `sarif`
- `--output` / `-o` — write output to a file instead of stdout
- `--absolute-paths` — print absolute paths instead of paths relative to CWD
- `--common-checks` — restrict compiler diagnostics to common checkers and filter inspections to those enabled by default in the Kotlin IntelliJ plugin
- `--fail-on-severity` — exit with code `1` when any finding meets or exceeds `WARNING` or `ERROR` (default: `ERROR`)
