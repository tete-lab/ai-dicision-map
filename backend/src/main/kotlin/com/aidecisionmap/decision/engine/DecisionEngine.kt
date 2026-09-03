package com.aidecisionmap.decision.engine

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class DecisionEngine {
    fun calculate(input: DecisionEngineInput): DecisionEngineResult {
        require(input.options.isNotEmpty()) { "At least one option is required" }
        require(input.criteria.isNotEmpty()) { "At least one criterion is required" }
        requireUniqueIds(input)

        val normalizedWeights = normalizeWeights(input.criteria)
        val optionIds = input.options.map { it.id }.toSet()
        val criterionIds = input.criteria.map { it.id }.toSet()
        val assessmentByPair = linkedMapOf<AssessmentKey, EngineAssessment>()

        input.assessments.forEach { assessment ->
            require(assessment.optionId in optionIds) { "Unknown option '${assessment.optionId}'" }
            require(assessment.criterionId in criterionIds) { "Unknown criterion '${assessment.criterionId}'" }
            require(assessment.score.isFinite() && assessment.score in 0.0..100.0) {
                "Assessment scores must be between 0 and 100"
            }
            val key = AssessmentKey(assessment.optionId, assessment.criterionId)
            require(assessmentByPair.put(key, assessment) == null) {
                "Duplicate assessment for option '${assessment.optionId}' and criterion '${assessment.criterionId}'"
            }
        }

        val unrankedScores = input.options.map { option ->
            calculateOptionScore(option, input.criteria, normalizedWeights, assessmentByPair)
        }
        val rankedScores = rank(unrankedScores)
        val expectedScoreCount = input.options.size * input.criteria.size
        val providedScoreCount = assessmentByPair.size
        val weightedCoverage = input.options.map { option ->
            input.criteria
                .filter { AssessmentKey(option.id, it.id) in assessmentByPair }
                .fold(BigDecimal.ZERO) { total, criterion -> total + normalizedWeights.getValue(criterion.id) }
        }.fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(input.options.size.toLong()), CALCULATION_SCALE, RoundingMode.HALF_UP)
            .multiply(ONE_HUNDRED)
            .setScale(2, RoundingMode.HALF_UP)

        val missingPairs = input.options.flatMap { option ->
            input.criteria.mapNotNull { criterion ->
                AssessmentKey(option.id, criterion.id)
                    .takeUnless(assessmentByPair::containsKey)
                    ?.let { MissingAssessment(option.id, criterion.id) }
            }
        }

        return DecisionEngineResult(
            normalizedWeights = roundWeightsForStorage(input.criteria, normalizedWeights),
            optionScores = rankedScores,
            completeness = AnalysisCompleteness(
                expectedScoreCount = expectedScoreCount,
                providedScoreCount = providedScoreCount,
                missingScoreCount = expectedScoreCount - providedScoreCount,
                scoreCoveragePercent = BigDecimal.valueOf(providedScoreCount.toLong())
                    .multiply(ONE_HUNDRED)
                    .divide(BigDecimal.valueOf(expectedScoreCount.toLong()), 2, RoundingMode.HALF_UP),
                weightedCoveragePercent = weightedCoverage,
                missingAssessments = missingPairs,
            ),
        )
    }

    fun normalizeWeights(criteria: List<EngineCriterion>): Map<String, BigDecimal> {
        require(criteria.isNotEmpty()) { "At least one criterion is required" }
        criteria.forEach {
            require(it.weight.isFinite() && it.weight >= 0.0) { "Criterion weights must be finite and non-negative" }
        }

        val rawWeights = criteria.associate { it.id to BigDecimal.valueOf(it.weight) }
        val total = rawWeights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val divisor = if (total.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.valueOf(criteria.size.toLong())
        } else {
            total
        }
        val sourceWeights = if (total.compareTo(BigDecimal.ZERO) == 0) {
            criteria.associate { it.id to BigDecimal.ONE }
        } else {
            rawWeights
        }
        val normalized = linkedMapOf<String, BigDecimal>()
        var allocated = BigDecimal.ZERO
        criteria.forEachIndexed { index, criterion ->
            val weight = if (index == criteria.lastIndex) {
                BigDecimal.ONE.subtract(allocated)
            } else {
                sourceWeights.getValue(criterion.id)
                    .divide(divisor, CALCULATION_SCALE, RoundingMode.HALF_UP)
                    .also { allocated += it }
            }
            normalized[criterion.id] = weight
        }
        return normalized
    }

    private fun calculateOptionScore(
        option: EngineOption,
        criteria: List<EngineCriterion>,
        normalizedWeights: Map<String, BigDecimal>,
        assessments: Map<AssessmentKey, EngineAssessment>,
    ): EngineOptionScore {
        var weightedTotal = BigDecimal.ZERO
        var coveredWeight = BigDecimal.ZERO

        criteria.forEach { criterion ->
            val assessment = assessments[AssessmentKey(option.id, criterion.id)] ?: return@forEach
            val weight = normalizedWeights.getValue(criterion.id)
            weightedTotal += BigDecimal.valueOf(assessment.score).multiply(weight)
            coveredWeight += weight
        }

        val score = if (coveredWeight.compareTo(BigDecimal.ZERO) == 0) {
            null
        } else {
            weightedTotal.divide(coveredWeight, 2, RoundingMode.HALF_UP)
        }
        return EngineOptionScore(option.id, score, null, false)
    }

    private fun rank(scores: List<EngineOptionScore>): List<EngineOptionScore> {
        val scored = scores.filter { it.score != null }
            .sortedWith(compareByDescending<EngineOptionScore> { it.score }.thenBy { it.optionId })
        val leaderScore = scored.firstOrNull()?.score
        val ranks = mutableMapOf<String, Int>()
        var priorScore: BigDecimal? = null
        var priorRank = 0
        scored.forEachIndexed { index, item ->
            val rank = if (priorScore != null && item.score!!.compareTo(priorScore) == 0) priorRank else index + 1
            ranks[item.optionId] = rank
            priorScore = item.score
            priorRank = rank
        }

        return scores.map { item ->
            item.copy(
                rank = ranks[item.optionId],
                tiedForLead = leaderScore != null && item.score?.compareTo(leaderScore) == 0,
            )
        }
    }

    private fun requireUniqueIds(input: DecisionEngineInput) {
        require(input.options.map { it.id }.distinct().size == input.options.size) { "Option ids must be unique" }
        require(input.criteria.map { it.id }.distinct().size == input.criteria.size) { "Criterion ids must be unique" }
    }

    private fun roundWeightsForStorage(
        criteria: List<EngineCriterion>,
        normalizedWeights: Map<String, BigDecimal>,
    ): Map<String, BigDecimal> {
        val rounded = linkedMapOf<String, BigDecimal>()
        var allocated = BigDecimal.ZERO.setScale(STORAGE_SCALE)
        criteria.forEachIndexed { index, criterion ->
            val weight = if (index == criteria.lastIndex) {
                BigDecimal.ONE.setScale(STORAGE_SCALE).subtract(allocated)
            } else {
                normalizedWeights.getValue(criterion.id).setScale(STORAGE_SCALE, RoundingMode.HALF_UP)
                    .also { allocated += it }
            }
            rounded[criterion.id] = weight
        }
        return rounded
    }

    private data class AssessmentKey(val optionId: String, val criterionId: String)

    private companion object {
        const val CALCULATION_SCALE = 12
        const val STORAGE_SCALE = 7
        val ONE_HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    }
}

data class DecisionEngineInput(
    val options: List<EngineOption>,
    val criteria: List<EngineCriterion>,
    val assessments: List<EngineAssessment>,
)

data class EngineOption(val id: String)
data class EngineCriterion(val id: String, val weight: Double)
data class EngineAssessment(val optionId: String, val criterionId: String, val score: Double)

data class DecisionEngineResult(
    val normalizedWeights: Map<String, BigDecimal>,
    val optionScores: List<EngineOptionScore>,
    val completeness: AnalysisCompleteness,
)

data class EngineOptionScore(
    val optionId: String,
    val score: BigDecimal?,
    val rank: Int?,
    val tiedForLead: Boolean,
)

data class AnalysisCompleteness(
    val expectedScoreCount: Int,
    val providedScoreCount: Int,
    val missingScoreCount: Int,
    val scoreCoveragePercent: BigDecimal,
    val weightedCoveragePercent: BigDecimal,
    val missingAssessments: List<MissingAssessment>,
)

data class MissingAssessment(val optionId: String, val criterionId: String)
