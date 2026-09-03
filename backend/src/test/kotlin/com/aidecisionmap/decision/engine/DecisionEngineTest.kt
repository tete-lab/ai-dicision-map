package com.aidecisionmap.decision.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val engine = DecisionEngine()

    @Test
    fun `normalizes criterion weights to one`() {
        val weights = engine.normalizeWeights(
            listOf(EngineCriterion("growth", 2.0), EngineCriterion("stability", 3.0)),
        )

        assertEquals(0.4, weights.getValue("growth").toDouble(), 0.000001)
        assertEquals(0.6, weights.getValue("stability").toDouble(), 0.000001)
        assertEquals(1.0, weights.values.sumOf { it.toDouble() }, 0.000001)
    }

    @Test
    fun `calculates deterministic weighted option scores`() {
        val result = engine.calculate(
            input(
                weights = listOf("growth" to 0.4, "stability" to 0.6),
                assessments = listOf(
                    assessment("stay", "growth", 80.0),
                    assessment("stay", "stability", 60.0),
                    assessment("move", "growth", 60.0),
                    assessment("move", "stability", 80.0),
                ),
            ),
        )

        val scores = result.optionScores.associateBy { it.optionId }
        assertEquals(68.0, scores.getValue("stay").score!!.toDouble(), 0.001)
        assertEquals(72.0, scores.getValue("move").score!!.toDouble(), 0.001)
        assertEquals(1, scores.getValue("move").rank)
        assertTrue(scores.getValue("move").tiedForLead)
    }

    @Test
    fun `assigns the same rank when option scores tie`() {
        val result = engine.calculate(
            input(
                weights = listOf("value" to 1.0),
                assessments = listOf(
                    assessment("stay", "value", 75.0),
                    assessment("move", "value", 75.0),
                ),
            ),
        )

        assertTrue(result.optionScores.all { it.rank == 1 })
        assertTrue(result.optionScores.all { it.tiedForLead })
    }

    @Test
    fun `keeps missing data out of scores and reports completeness separately`() {
        val result = engine.calculate(
            input(
                weights = listOf("growth" to 0.75, "stability" to 0.25),
                assessments = listOf(assessment("stay", "growth", 80.0)),
            ),
        )
        val scores = result.optionScores.associateBy { it.optionId }

        assertEquals(80.0, scores.getValue("stay").score!!.toDouble(), 0.001)
        assertNull(scores.getValue("move").score)
        assertEquals(25.0, result.completeness.scoreCoveragePercent.toDouble(), 0.001)
        assertEquals(37.5, result.completeness.weightedCoveragePercent.toDouble(), 0.001)
        assertEquals(3, result.completeness.missingScoreCount)
    }

    @Test
    fun `handles an extreme single criterion weight without score leakage`() {
        val result = engine.calculate(
            input(
                weights = listOf("critical" to 1.0, "ignored" to 0.0),
                assessments = listOf(
                    assessment("stay", "critical", 90.0),
                    assessment("stay", "ignored", 0.0),
                    assessment("move", "critical", 10.0),
                    assessment("move", "ignored", 100.0),
                ),
            ),
        )
        val scores = result.optionScores.associateBy { it.optionId }

        assertEquals(90.0, scores.getValue("stay").score!!.toDouble(), 0.001)
        assertEquals(10.0, scores.getValue("move").score!!.toDouble(), 0.001)
    }

    private fun input(
        weights: List<Pair<String, Double>>,
        assessments: List<EngineAssessment>,
    ) = DecisionEngineInput(
        options = listOf(EngineOption("stay"), EngineOption("move")),
        criteria = weights.map { EngineCriterion(it.first, it.second) },
        assessments = assessments,
    )

    private fun assessment(option: String, criterion: String, score: Double) =
        EngineAssessment(option, criterion, score)
}
