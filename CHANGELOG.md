# Changelog

All notable changes to this project will be documented in this file.

## v0.1.0 — 2026-05-19

Initial release.

### Added

- Kotlin K2 (FIR) compiler diagnostics with full type resolution via the Kotlin Analysis API
- IDE-level inspections loaded from the Kotlin Language Server plugin
- Four severity levels: `ERROR`, `WARNING`, `HINT`, `INFO`
- `--input` / `-i` — source directory or `.kt` / `.java` file to analyze (repeatable)
- `--classpath` / `-cp` — compile classpath for type resolution
- `--config` / `-c` — YAML config file for suppressing rules and overriding severities
- `--format` / `-f` — output as human-readable `text` or `sarif`
- `--output` / `-o` — write output to a file instead of stdout
- `--absolute-paths` — print absolute paths instead of paths relative to CWD
- `--common-checks` — restrict compiler diagnostics to common checkers and filter inspections to those enabled by default in the Kotlin Language Server
- `--fail-on-severity` — exit with code `1` when any finding meets or exceeds `WARNING` or `ERROR` (default: `ERROR`)
