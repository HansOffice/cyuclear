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
        steps += DecisionStep("最终结果", "${decision.reason} · ${if (decision.remove) "清理" else "保留"}")
        return DecisionTrace(decision, steps.toList())
    }
}
