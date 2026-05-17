package wile.tools.ktanalyzer.report

import wile.tools.ktanalyzer.api.LintFinding
import wile.tools.ktanalyzer.api.Severity
import java.io.PrintWriter

object SarifReporter : Reporter {
    override fun report(findings: List<LintFinding>, writer: PrintWriter) {
        val rules = findings.map { it.ruleId }.distinct().sorted()
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "${"$"}schema": "https://json.schemastore.org/sarif-2.1.0.json",""")
        sb.appendLine("""  "version": "2.1.0",""")
        sb.appendLine("""  "runs": [""")
        sb.appendLine("""    {""")
        sb.appendLine("""      "tool": {""")
        sb.appendLine("""        "driver": {""")
        sb.appendLine("""          "name": "kt-analyzer",""")
        sb.appendLine("""          "version": "1.0.0",""")
        sb.appendLine("""          "rules": [""")
        rules.forEachIndexed { i, ruleId ->
            val comma = if (i < rules.lastIndex) "," else ""
            sb.appendLine("""            { "id": ${json(ruleId)} }$comma""")
        }
        sb.appendLine("""          ]""")
        sb.appendLine("""        }""")
        sb.appendLine("""      },""")
        sb.appendLine("""      "results": [""")
        findings.forEachIndexed { i, f ->
            val comma = if (i < findings.lastIndex) "," else ""
            val level =
                when (f.severity) {
                    Severity.ERROR -> "error"
                    Severity.WARNING -> "warning"
                    Severity.INFO -> "note"
                }
            sb.appendLine("""        {""")
            sb.appendLine("""          "ruleId": ${json(f.ruleId)},""")
            sb.appendLine("""          "level": "$level",""")
            sb.appendLine("""          "message": { "text": ${json(f.message)} },""")
            sb.appendLine("""          "locations": [""")
            sb.appendLine("""            {""")
            sb.appendLine("""              "physicalLocation": {""")
            sb.appendLine("""                "artifactLocation": { "uri": ${json(f.filePath)} },""")
            sb.appendLine("""                "region": { "startLine": ${f.line}, "startColumn": ${f.column} }""")
            sb.appendLine("""              }""")
            sb.appendLine("""            }""")
            sb.appendLine("""          ]""")
            sb.append("""        }$comma""")
            sb.appendLine()
        }
        sb.appendLine("""      ]""")
        sb.appendLine("""    }""")
        sb.appendLine("""  ]""")
        sb.append("}")
        writer.print(sb)
        writer.flush()
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
