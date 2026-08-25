package org.cyuCBMclean.cyuclear.service

data class DecisionStep(
    val stage: String,
    val detail: String
)

data class DecisionTrace(
    val decision: CleanupFilter.FilterDecision,
    val steps: List<DecisionStep>
)

internal class DecisionTraceBuilder {
    private val steps = ArrayList<DecisionStep>(8)

    fun add(stage: String, detail: String) {
        steps += DecisionStep(stage, detail)
    }

    fun build(decision: CleanupFilter.FilterDecision): DecisionTrace {
        val isEn = org.cyuCBMclean.cyuclear.config.Language.isEnglish
        val stageName = if (isEn) "Final Verdict" else "最终结果"
        val actionText = if (decision.remove) (if (isEn) "Clean" else "清理") else (if (isEn) "Keep" else "保留")
        steps += DecisionStep(stageName, "${decision.reason} · $actionText")
        return DecisionTrace(decision, steps.toList())
    }
}
