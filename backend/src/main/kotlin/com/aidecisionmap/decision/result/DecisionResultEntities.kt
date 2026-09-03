package com.aidecisionmap.decision.result

import com.aidecisionmap.decision.domain.DecisionSessionEntity
import com.aidecisionmap.decision.domain.DecisionSourceType
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
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "decision_options",
    uniqueConstraints = [UniqueConstraint(name = "uk_decision_options_session_key", columnNames = ["session_id", "option_key"])],
)
class DecisionOptionEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    var session: DecisionSessionEntity,
    @Column(name = "option_key", nullable = false, length = 100)
    var optionKey: String,
    @Column(nullable = false)
    var name: String,
    @Column(columnDefinition = "TEXT")
    var summary: String? = null,
    @Column(precision = 5, scale = 2)
    var score: BigDecimal? = null,
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

@Entity
@Table(
    name = "decision_criteria",
    uniqueConstraints = [UniqueConstraint(name = "uk_decision_criteria_session_key", columnNames = ["session_id", "criterion_key"])],
)
class DecisionCriterionEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    var session: DecisionSessionEntity,
    @Column(name = "criterion_key", nullable = false, length = 100)
    var criterionKey: String,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false, precision = 8, scale = 7)
    var weight: BigDecimal,
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    var sourceType: DecisionSourceType,
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

@Entity
@Table(
    name = "criterion_option_scores",
    uniqueConstraints = [UniqueConstraint(name = "uk_criterion_option_scores_pair", columnNames = ["criterion_id", "option_id"])],
)
class CriterionOptionScoreEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criterion_id", nullable = false)
    var criterion: DecisionCriterionEntity,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    var option: DecisionOptionEntity,
    @Column(nullable = false, precision = 5, scale = 2)
    var score: BigDecimal,
    @Column(columnDefinition = "TEXT")
    var reason: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    var sourceType: DecisionSourceType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

@Entity
@Table(name = "decision_insights")
class DecisionInsightEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    var session: DecisionSessionEntity,
    @Enumerated(EnumType.STRING)
    @Column(name = "insight_type", nullable = false, length = 50)
    var insightType: InsightType,
    @Column(nullable = false)
    var title: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,
    @Column(nullable = false)
    var priority: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

enum class InsightType {
    KEY_DRIVER,
    TRADE_OFF,
    RISK,
    MISSING_INFORMATION,
}
