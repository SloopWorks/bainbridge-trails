package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.schedule.DayPlan
import kotlinx.datetime.LocalDate

/**
 * Live sensing during a recording (product §3.6.1, PF5): feed every fix of the walk with the current island day's
 * plan; [onFix] returns the items that fix newly sensed (the find moment, labelled provisional). It applies exactly
 * the [ClaimEvaluator] rules, to the raw fixes; Finish is the truth ([ClaimEvaluator.evaluate] over the kept fixes, with
 * [sensed] for the unclaimed list). One tracker per walk; [found] = keys already claimed. Not thread-safe.
 */
class SensingTracker(
    evaluator: ClaimEvaluator,
    found: Set<String> = emptySet(),
    hints: Map<String, Int> = emptyMap(),
) {
    private val walk = evaluator.Walk(found, hints)
    private val dates = HashSet<LocalDate>()
    private val keys = LinkedHashSet<String>()

    /** Keys sensed so far, in order. */
    val sensed: Set<String> get() = keys

    fun onFix(fix: GatedFix, plan: DayPlan): List<Claim> {
        if (dates.add(plan.date)) walk.addPlan(plan)
        val new = walk.add(fix)
        for (c in new) keys += c.key
        return new
    }
}
