package com.aidecisionmap.decision.domain

import org.springframework.data.jpa.repository.JpaRepository

interface DecisionSessionRepository : JpaRepository<DecisionSessionEntity, Long> {
    fun findByPublicId(publicId: String): DecisionSessionEntity?
}
