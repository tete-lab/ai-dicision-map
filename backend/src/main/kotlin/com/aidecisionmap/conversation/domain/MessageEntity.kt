package com.aidecisionmap.conversation.domain

import com.aidecisionmap.decision.domain.DecisionSessionEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "messages")
class MessageEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    var session: DecisionSessionEntity,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: MessageRole,
    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
    }
}

enum class MessageRole {
    USER,
    ASSISTANT,
}
