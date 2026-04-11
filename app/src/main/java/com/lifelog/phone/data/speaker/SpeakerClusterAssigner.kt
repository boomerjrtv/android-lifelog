package com.lifelog.phone.data.speaker

import com.lifelog.phone.data.local.PhoneLogEntity
import com.lifelog.phone.data.local.SpeakerProfileEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

data class ClusterDecision(
    val clusterId: String,
    val speakerId: String,
    val confidence: Double,
)

@Singleton
class SpeakerClusterAssigner @Inject constructor() {
    private val slotRegex = Regex("S[0-9]+")
    private val localTsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun assign(
        transcriptText: String,
        transcriptTs: String,
        activeSlot: String,
        recentTranscripts: List<PhoneLogEntity>,
        profiles: List<SpeakerProfileEntity> = emptyList(),
        embeddingSimilarity: ((SpeakerProfileEntity) -> Double)? = null,
        embeddingThresholdOverride: Double? = null,
    ): ClusterDecision {
        val slot = activeSlot.trim().uppercase()
        if (slot.matches(slotRegex)) {
            return ClusterDecision(
                clusterId = "slot_${slot.lowercase()}",
                speakerId = slot,
                confidence = 0.66,
            )
        }

        val cleanText = transcriptText.trim()
        if (cleanText.startsWith("agent:", ignoreCase = true)) {
            return ClusterDecision("legacy_agent", "agent", 0.9)
        }
        if (cleanText.startsWith("consumer:", ignoreCase = true)) {
            return ClusterDecision("legacy_consumer", "consumer", 0.9)
        }

        val targetTs = parseTsMillis(transcriptTs)
        val continuity = recentTranscripts.firstOrNull {
            val cluster = it.speakerClusterId.trim()
            cluster.isNotEmpty() && abs(parseTsMillis(it.ts) - targetTs) <= 45_000L
        }
        if (continuity != null) {
            return ClusterDecision(
                clusterId = continuity.speakerClusterId.trim(),
                speakerId = continuity.speakerId.trim(),
                confidence = continuity.speakerConfidence.coerceAtLeast(0.45),
            )
        }

        if (embeddingSimilarity != null && profiles.isNotEmpty()) {
            val dynamicThreshold = embeddingThresholdOverride ?: adaptiveEmbeddingThreshold(recentTranscripts, profiles)
            val best = profiles
                .map { it to embeddingSimilarity.invoke(it) }
                .maxByOrNull { it.second }
            if (best != null && best.second >= dynamicThreshold) {
                return ClusterDecision(
                    clusterId = best.first.clusterId,
                    speakerId = best.first.displayName,
                    confidence = best.second.coerceAtMost(0.98),
                )
            }
        }

        val counts = mutableMapOf<String, Int>()
        recentTranscripts.forEach {
            val id = it.speakerClusterId.trim()
            if (id.isNotEmpty()) counts[id] = (counts[id] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.takeIf { it.value >= 4 }
        if (dominant != null) {
            val id = dominant.key
            val label = recentTranscripts.firstOrNull { it.speakerClusterId.trim() == id }?.speakerId.orEmpty()
            return ClusterDecision(id, label, 0.42)
        }

        val minuteBucket = (targetTs / 60_000L).coerceAtLeast(0L)
        val suffix = Random.nextInt(100, 999)
        return ClusterDecision(
            clusterId = "auto_${minuteBucket}_$suffix",
            speakerId = "",
            confidence = 0.24,
        )
    }

    private fun adaptiveEmbeddingThreshold(
        recentTranscripts: List<PhoneLogEntity>,
        profiles: List<SpeakerProfileEntity>,
    ): Double {
        var threshold = 0.86
        val recent = recentTranscripts.take(40)
        val avgConfidence = recent.map { it.speakerConfidence }.average().takeIf { !it.isNaN() } ?: 0.5
        if (avgConfidence < 0.45) threshold += 0.03
        if (profiles.size >= 4) threshold += 0.02
        if (profiles.size >= 8) threshold += 0.02
        return threshold.coerceIn(0.82, 0.94)
    }

    private fun parseTsMillis(raw: String): Long {
        val clean = raw.trim()
        if (clean.isEmpty()) return 0L
        clean.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(clean).toEpochMilli()
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(clean, localTsFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            }
        }
    }
}
