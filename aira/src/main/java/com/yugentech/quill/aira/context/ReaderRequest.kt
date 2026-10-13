package com.yugentech.quill.aira.context

import org.readium.r2.shared.publication.Locator

enum class RequestedScope { PASSAGE, CURRENT_CHAPTER, NAMED_SECTION, READ_SO_FAR, WHOLE_BOOK, RELEVANT_PASSAGES, OVERVIEW, GENERAL, UNRESOLVED, RECENT_SCENE }
enum class EvidenceSource { GENERAL_KNOWLEDGE, SELECTION, PUBLICATION }

/** The classifier names a target; only the publication resolver may assign its boundaries. */
data class ContextPlan(
    val scope: RequestedScope = RequestedScope.RELEVANT_PASSAGES,
    val targetName: String? = null,
    val term: String? = null,
    val source: EvidenceSource = EvidenceSource.PUBLICATION,
    val explicitSpoilers: Boolean = false,
    val resolvedSectionId: String? = null,
    val permissionGranted: Boolean = false
)

data class ReaderRequestContext(val currentLocator: Locator? = null, val selectionLocator: Locator? = null,
    val freshTopic: Boolean = false, val paragraphCount: Int = 24, val discussionMode: String? = null,
    val topicId: String? = null, val endLocator: Locator? = null)
data class ScopeChoice(val id: String, val label: String, val plan: ContextPlan)

object SpoilerPermission {
    // A classifier flag alone cannot authorize a wider boundary.
    fun explicit(query: String): Boolean {
        if (Regex("(?i)\\b(?:no|without|avoid) (?:any |the )?spoilers?\\b|\\b(?:do not|don't|dont|never) (?:include|tell|explain|reveal|spoil)[^.!?]{0,35}\\b(?:ending|spoilers?)\\b").containsMatchIn(query)) return false
        return Regex(
        "(?i)\\b(spoilers? (?:are )?(?:ok(?:ay)?|allowed|welcome)|allow spoilers?|include (?:the )?(?:spoilers?|ending)|with spoilers?|spoil (?:it|the (?:book|ending))|tell me (?:the |about the )?ending|explain (?:the )?ending|ending explained)\\b"
        ).containsMatchIn(query)
    }
}
