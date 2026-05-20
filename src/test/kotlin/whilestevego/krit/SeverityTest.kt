package whilestevego.krit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import whilestevego.krit.api.Severity

class SeverityTest : FunSpec({

    test("ordinal order is INFO < HINT < WARNING < ERROR") {
        Severity.INFO.ordinal shouldBe 0
        Severity.HINT.ordinal shouldBe 1
        Severity.WARNING.ordinal shouldBe 2
        Severity.ERROR.ordinal shouldBe 3
    }

    test("ERROR >= WARNING is true") {
        (Severity.ERROR >= Severity.WARNING) shouldBe true
    }

    test("WARNING >= ERROR is false") {
        (Severity.WARNING >= Severity.ERROR) shouldBe false
    }

    test("WARNING >= WARNING is true (at-threshold counts)") {
        (Severity.WARNING >= Severity.WARNING) shouldBe true
    }

    test("HINT >= WARNING is false") {
        (Severity.HINT >= Severity.WARNING) shouldBe false
    }

    test("valueOf succeeds for uppercase string") {
        Severity.valueOf("error".uppercase()) shouldBe Severity.ERROR
    }
})
