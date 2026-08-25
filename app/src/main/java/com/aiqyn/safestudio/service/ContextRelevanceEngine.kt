package com.aiqyn.safestudio.service

import android.util.Log
import com.aiqyn.safestudio.data.ContextReminder
import com.aiqyn.safestudio.data.ContextMatch
import com.aiqyn.safestudio.data.RelevanceLevel
import java.util.Locale

/**
 * Context Relevance Engine
 * Analyzes detected speech/events against active context reminders to determine relevance.
 * Uses keyword matching and contextual factors to score relevance.
 */
object ContextRelevanceEngine {
    private const val TAG = "ContextRelevance"

    /**
     * Analyzes detected text against active reminders to find relevant matches.
     * @param detectedText The transcribed speech or detected text
     * @param reminders List of active context reminders
     * @return List of context matches sorted by relevance
     */
    fun analyzeRelevance(detectedText: String, reminders: List<ContextReminder>): List<ContextMatch> {
        if (reminders.isEmpty()) return emptyList()

        val normalizedText = normalizeText(detectedText)
        val matches = mutableListOf<ContextMatch>()

        for (reminder in reminders) {
            if (!reminder.enabled) continue

            val match = matchReminder(normalizedText, reminder)
            if (match != null) {
                matches.add(match)
            }
        }

        return matches.sortedByDescending { it.relevance.priority }
    }

    /**
     * Matches a single reminder against detected text.
     */
    private fun matchReminder(normalizedText: String, reminder: ContextReminder): ContextMatch? {
        val matchedKeywords = mutableSetOf<String>()
        var relevanceScore = 0

        // Check explicit keywords
        for (keyword in reminder.keywords) {
            val normalizedKeyword = normalizeText(keyword)
            if (normalizedText.contains(normalizedKeyword, ignoreCase = true)) {
                matchedKeywords.add(keyword)
                relevanceScore += 2
            }
        }

        // Check event name
        if (reminder.event.isNotEmpty()) {
            val normalizedEvent = normalizeText(reminder.event)
            if (normalizedText.contains(normalizedEvent, ignoreCase = true)) {
                matchedKeywords.add(reminder.event)
                relevanceScore += 3
            }
        }

        // Check location
        if (reminder.location.isNotEmpty()) {
            val normalizedLocation = normalizeText(reminder.location)
            if (normalizedText.contains(normalizedLocation, ignoreCase = true)) {
                matchedKeywords.add(reminder.location)
                relevanceScore += 2
            }
        }

        // Check destination
        if (reminder.destination.isNotEmpty()) {
            val normalizedDestination = normalizeText(reminder.destination)
            if (normalizedText.contains(normalizedDestination, ignoreCase = true)) {
                matchedKeywords.add(reminder.destination)
                relevanceScore += 2
            }
        }

        // Check description for important words
        if (reminder.description.isNotEmpty()) {
            val descriptionWords = reminder.description.split(" ").filter { it.length > 3 }
            for (word in descriptionWords) {
                val normalizedWord = normalizeText(word)
                if (normalizedText.contains(normalizedWord, ignoreCase = true)) {
                    matchedKeywords.add(word)
                    relevanceScore += 1
                }
            }
        }

        if (matchedKeywords.isEmpty()) return null

        // Determine relevance level based on score
        val relevance = when {
            relevanceScore >= 5 -> RelevanceLevel.HIGH
            relevanceScore >= 3 -> RelevanceLevel.MEDIUM
            else -> RelevanceLevel.LOW
        }

        // Calculate confidence based on keyword matches and text length
        val confidence = calculateConfidence(matchedKeywords, normalizedText)

        return ContextMatch(
            reminder = reminder,
            relevance = relevance,
            matchedKeywords = matchedKeywords,
            confidence = confidence
        )
    }

    /**
     * Normalizes text for comparison.
     */
    private fun normalizeText(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-zа-яёәғқңөұүіһ0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Calculates confidence score based on keyword matches.
     */
    private fun calculateConfidence(matchedKeywords: Set<String>, text: String): Float {
        if (matchedKeywords.isEmpty()) return 0f
        if (text.isEmpty()) return 0f

        // Base confidence on the ratio of matched keywords to total words in text
        val textWords = text.split(" ").size
        val matchRatio = matchedKeywords.size.toFloat() / textWords

        // Boost confidence if multiple keywords match
        val keywordBoost = when (matchedKeywords.size) {
            1 -> 0.5f
            2 -> 0.7f
            3 -> 0.85f
            else -> 0.95f
        }

        return (matchRatio + keywordBoost) / 2f
    }

    /**
     * Gets the highest relevance match from a list of matches.
     */
    fun getHighestRelevance(matches: List<ContextMatch>): ContextMatch? {
        return matches.maxByOrNull { it.relevance.priority }
    }

    /**
     * Formats a notification message based on context match and language.
     * Uses localized strings from the UI strings system.
     */
    fun formatContextMessage(match: ContextMatch, languageCode: String): String {
        val reminder = match.reminder
        val relevance = match.relevance

        return when (languageCode.lowercase(Locale.ROOT)) {
            "ru" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "Важно: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "Возможно связано: ${reminder.event}"
                    RelevanceLevel.LOW -> "Может быть связано: ${reminder.event}"
                }
            }
            "kk" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "Маңызды: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "Мүмкін байланысты: ${reminder.event}"
                    RelevanceLevel.LOW -> "Байланысты болуы мүмкін: ${reminder.event}"
                }
            }
            "de" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "Wichtig: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "Möglicherweise verbunden: ${reminder.event}"
                    RelevanceLevel.LOW -> "Könnte verbunden sein: ${reminder.event}"
                }
            }
            "es" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "Importante: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "Posiblemente relacionado: ${reminder.event}"
                    RelevanceLevel.LOW -> "Puede estar relacionado: ${reminder.event}"
                }
            }
            "ar" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "مهم: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "قد يكون مرتبطًا: ${reminder.event}"
                    RelevanceLevel.LOW -> "قد يكون مرتبطًا: ${reminder.event}"
                }
            }
            "zh" -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "重要：${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "可能相关：${reminder.event}"
                    RelevanceLevel.LOW -> "可能相关：${reminder.event}"
                }
            }
            else -> {
                when (relevance) {
                    RelevanceLevel.HIGH -> "Important: ${reminder.event} - ${reminder.description}"
                    RelevanceLevel.MEDIUM -> "Possibly related: ${reminder.event}"
                    RelevanceLevel.LOW -> "May be related: ${reminder.event}"
                }
            }
        }
    }
}
