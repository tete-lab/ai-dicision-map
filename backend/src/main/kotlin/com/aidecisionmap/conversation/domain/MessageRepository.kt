package com.aidecisionmap.conversation.domain

import com.aidecisionmap.decision.domain.DecisionSessionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface MessageRepository : JpaRepository<MessageEntity, Long> {
    fun findBySessionOrderByIdAsc(session: DecisionSessionEntity): List<MessageEntity>
}
