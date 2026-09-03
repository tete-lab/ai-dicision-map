package com.aidecisionmap.decision.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "decision_sessions")
class DecisionSessionEntity(
    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    var publicId: String,
    @Column(nullable = false)
    var title: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var stage: DecisionStage = DecisionStage.STARTED,
    @Column(nullable = false)
    var progress: Int = 0,
    @Column(columnDefinition = "TEXT")
    var summary: String? = null,
    @Column(name = "state_json", columnDefinition = "LONGTEXT")
    var stateJson: String? = null,
    @Column(name = "prompt_version", nullable = false, length = 50)
    var promptVersion: String,
    @Column(name = "result_narrative_json", columnDefinition = "LONGTEXT")
    var resultNarrativeJson: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
