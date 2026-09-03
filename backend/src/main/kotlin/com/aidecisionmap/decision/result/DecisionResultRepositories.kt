package com.aidecisionmap.decision.result

import com.aidecisionmap.decision.domain.DecisionSessionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DecisionOptionRepository : JpaRepository<DecisionOptionEntity, Long> {
    fun findBySessionOrderByIdAsc(session: DecisionSessionEntity): List<DecisionOptionEntity>
}

interface DecisionCriterionRepository : JpaRepository<DecisionCriterionEntity, Long> {
    fun findBySessionOrderByIdAsc(session: DecisionSessionEntity): List<DecisionCriterionEntity>
}

interface CriterionOptionScoreRepository : JpaRepository<CriterionOptionScoreEntity, Long> {
    fun findByOptionSessionOrderByIdAsc(session: DecisionSessionEntity): List<CriterionOptionScoreEntity>
}

interface DecisionInsightRepository : JpaRepository<DecisionInsightEntity, Long> {
    fun findBySessionOrderByPriorityAscIdAsc(session: DecisionSessionEntity): List<DecisionInsightEntity>
}
