# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```sh
# Build the fat JAR (required before running)
./gradlew shadowJar

# Run krit against itself
bin/krit --input src/main/kotlin --common-checks --fail-on-severity ERROR

# Run unit tests (fast, <5s; no K2 session)
./gradlew test

# Run integration tests (slow, ~15–60s; initializes the K2 session)
./gradlew integrationTest

# Run all tests
./gradlew test integrationTest
```

`bin/krit` is required to run the tool — it applies the necessary JVM flags (`--enable-native-access=ALL-UNNAMED`, `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`) that the fat JAR needs. Invoking the JAR directly without these flags will crash.

There are no linters configured; CI runs krit on itself as the quality gate.

## Architecture

Krit is a CLI tool that combines two analysis sources — the Kotlin K2 compiler's native diagnostics and IntelliJ IDE inspections — and reports the results as `text` or SARIF.

### Pipeline

```
Main.kt (CLI args, file collection)
  └─ ConfigLoader       → AnalyzerConfig (suppress list, severity overrides, classpath)
  └─ PsiEngine          → DiagnosticMessage[], InspectionFinding[]
       ├─ K2 Analysis API (compiler diagnostics)
       └─ InspectionRunner (IDE inspections from bundled JAR)
  └─ AnalysisRunner     → LintFinding[] (applies suppression, severity overrides, sorting)
  └─ TextReporter / SarifReporter  → stdout or file
```

### Key constraints

**Fat JAR is the only supported runtime.** `PsiEngine` has two behaviors that only work when executing from the fat JAR:
- `stdlibJar()` extracts kotlin-stdlib from the fat JAR into a temp file and passes it to the K2 module. Without it, type resolution is degraded.
- `patchDefaultPathResolver()` pre-caches all XML from the fat JAR before the K2 session starts, working around `IOException`s that occur when the standalone session invalidates JAR URL connections during `xi:include` resolution. When running from the Gradle classpath (e.g. in integration tests), the cache is empty but the `DataLoader` can still resolve files normally.

**Java 25+ is required.** IntelliJ platform classes bundled in `kotlin-compiler` are incompatible with Java 21 at runtime. `Unsafe.java` (a project source file) shadows `com.intellij.util.containers.Unsafe` from `kotlin-compiler` to replace the deprecated `sun.misc.Unsafe` call with `jdk.internal.misc.Unsafe`.

**IntelliJ platform must be unshaded.** The `kotlin-compiler` (non-embeddable) artifact is used instead of `kotlin-compiler-embeddable` because the embeddable shades `com.intellij.*` to `org.jetbrains.kotlin.com.intellij.*`, which breaks the Analysis API.

**IDE inspections load via a nested JAR.** `language-server-plugins-kotlin.jar` is downloaded at build time from JetBrains CDN and embedded as a classpath resource inside the fat JAR. At runtime `InspectionRunner.Loader` extracts it to a temp file and loads inspection classes via `URLClassLoader`. It cannot be flat-merged into the fat JAR because its module-descriptor XMLs would cause IntelliJ's plugin loader to fail startup.

### InspectionRunner blacklist

Some inspections are excluded from the loader:
- **Diagnostic-based inspections** (those extending `KotlinKtDiagnosticBasedInspectionBase` / `KotlinPsiDiagnosticBasedInspectionBase`) — they duplicate findings already produced by the K2 compiler diagnostics path.
- **Specific noisy inspections** (e.g. `UnusedVariableInspection`, `UnusedSymbolInspection`) — listed by FQN in `BLACKLISTED_FQNS`.

### Severity mapping

Compiler diagnostics (`KaSeverity`) and IDE inspections (`ProblemHighlightType`) each map to krit's four-level `Severity` enum. `AnalysisRunner` owns this mapping and applies config-level overrides on top. The enum ordinal order `INFO < HINT < WARNING < ERROR` is load-bearing — it drives the `--fail-on-severity` exit-code check in `Main.kt`.

### Release process

1. Update `version` in `build.gradle` and add an entry to `CHANGELOG.md`.
2. Merge to `main`.
3. Push a `vX.Y.Z` tag — the release workflow builds the fat JAR and creates a GitHub release using the matching changelog section.
