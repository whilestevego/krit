# krit

A Kotlin source code analyzer that uses the Kotlin compiler's native diagnostics (K2 Analysis API) and IDE-level inspections to report issues in your code.

## Features

- Runs Kotlin K2 compiler diagnostics with full type resolution
- Runs Kotlin IntelliJ plugin inspections alongside compiler diagnostics
- Outputs human-readable text or SARIF (for CI/CD integration)
- Configurable suppression and severity overrides via YAML
- Exit code based on finding severity — suitable for use in pre-commit hooks or CI pipelines

## Requirements

- Java 21+

## Build

```sh
./gradlew shadowJar
```

This produces `build/libs/krit.jar`.

## Usage

```sh
java -jar build/libs/krit.jar [OPTIONS]
```

### Options

| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--input` | `-i` | *(required)* | Source directory or `.kt`/`.java` file to analyze (repeatable) |
| `--classpath` | `-cp` | `""` | Compile classpath for type resolution (`:` or `;` separated) |
| `--config` | `-c` | `config/krit.yml` | Path to YAML config file |
| `--format` | `-f` | `text` | Output format: `text` or `sarif` |
| `--output` | `-o` | stdout | Write output to a file |
| `--absolute-paths` | | false | Print absolute paths instead of paths relative to CWD |
| `--common-checks` | | false | Restrict to common compiler checks and XML-enabled inspections only |
| `--fail-on-severity` | | `ERROR` | Exit with code `1` when any finding meets this severity or higher (`WARNING` or `ERROR`) |

### Examples

```sh
# Analyze a directory
java -jar build/libs/krit.jar -i src/main/kotlin

# Output SARIF to a file
java -jar build/libs/krit.jar -i src -f sarif -o report.json

# Fail on warnings (e.g. in CI)
java -jar build/libs/krit.jar -i src --fail-on-severity WARNING

# Analyze with an external classpath
java -jar build/libs/krit.jar -i src -cp "lib/foo.jar:lib/bar.jar"

# Run only common/conservative checks
java -jar build/libs/krit.jar -i src --common-checks
```

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | No findings at or above `--fail-on-severity` threshold |
| `1` | At least one finding at or above the threshold |

## Configuration

Create a YAML file (default path: `config/krit.yml`) to suppress rules or override their severity.

```yaml
suppress:
  - RULE_ID_TO_SUPPRESS

severity-overrides:
  SOME_RULE: WARNING
  ANOTHER_RULE: ERROR
  NOISY_RULE: HINT
```

**Severity levels** (low to high): `INFO`, `HINT`, `WARNING`, `ERROR`

If no config file exists, krit runs with no suppressions or overrides.

## Output Format

### Text

```
src/main/kotlin/Example.kt:10:5: error   [UNRESOLVED_REFERENCE] Unresolved reference: foo
src/main/kotlin/Example.kt:15:1: warning [UNUSED_VARIABLE] Variable 'bar' is never used

Found 2 finding(s): 1 error(s), 1 warning(s)
```

### SARIF

SARIF JSON, compatible with GitHub Code Scanning, Azure DevOps, and other SARIF consumers.
