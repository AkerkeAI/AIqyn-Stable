package com.aiqyn.safestudio.data

/**
 * Context Reminder data class
 * Represents a user-defined event with keywords for context-aware notifications
 */
data class ContextReminder(
    val id: String,
    val event: String,
    val time: String = "",
    val location: String = "",
    val destination: String = "",
    val keywords: Set<String> = emptySet(),
    val description: String = "",
    val enabled: Boolean = true
)

/**
 * Relevance Level enum
 * Indicates how strongly a detected speech matches a context reminder
 */
enum class RelevanceLevel(val priority: Int) {
    HIGH(3),
    MEDIUM(2),
    LOW(1)
}

/**
 * Context Match data class
 * Represents a match between detected text and a context reminder
 */
data class ContextMatch(
    val reminder: ContextReminder,
    val relevance: RelevanceLevel,
    val matchedKeywords: Set<String>,
    val confidence: Float
)
