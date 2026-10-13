package com.yugentech.quill.aira.context

import com.yugentech.quill.aira.rag.*
import org.readium.r2.shared.publication.Locator
import java.text.Normalizer

sealed interface ScopeResolution {
    data class Ready(val start: TextPoint, val end: TextPoint, val readingBoundary: TextPoint,
        val label: String, val requiresSpoilerPermission: Boolean) : ScopeResolution
    data class Clarify(val question: String, val choices: List<ScopeChoice>) : ScopeResolution
}

/** No model-generated index enters this resolver. Chapter numbers refer to TOC leaves. */
object ScopeResolver {
    fun resolve(structure: PublicationStructure, plan: ContextPlan, locator: Locator?, bookTitle: String,
        spoilerLock: Boolean): ScopeResolution {
        val boundary = structure.readingBoundary(locator)
        fun ready(start: TextPoint, end: TextPoint, label: String) = ScopeResolution.Ready(start, end, boundary,
            label, end > boundary)
        val safeChoice = ScopeChoice("read-so-far", "Only what I have read so far", plan.copy(
            scope = RequestedScope.READ_SO_FAR, resolvedSectionId = null, permissionGranted = false))
        fun sectionsQuestion(question: String, candidates: List<PublicationSection>): ScopeResolution.Clarify {
            val options = candidates.map { section ->
                val parent = structure.sections.firstOrNull { it.id == section.parentId }
                ScopeChoice(section.id, if (parent != null) "${parent.title} — ${section.title}" else section.title,
                    plan.copy(scope = RequestedScope.NAMED_SECTION, targetName = section.title, resolvedSectionId = section.id,
                        permissionGranted = false))
            }
            return ScopeResolution.Clarify(question, listOf(safeChoice) + options + ScopeChoice("whole-book",
                "Complete book", plan.copy(scope = RequestedScope.WHOLE_BOOK, resolvedSectionId = null, permissionGranted = false)))
        }
        val section = when (plan.scope) {
            RequestedScope.NAMED_SECTION -> {
                if (plan.resolvedSectionId != null) {
                    structure.sections.firstOrNull { it.id == plan.resolvedSectionId }
                        ?: return sectionsQuestion("That section is no longer available. Which section do you mean?", structure.sections)
                } else {
                    val target = normalized(plan.targetName.orEmpty())
                    if (target.isBlank()) return sectionsQuestion("Which section would you like to use?", structure.sections)
                    if (target == normalized(bookTitle)) null else {
                        val named = structure.sections.filter { normalized(it.title) == target }
                        val numbered = Regex("^(?:chapter|ch) (\\d+)$").matchEntire(target)?.groupValues?.get(1)?.toIntOrNull()
                        val leaves = structure.sections.filter { candidate -> structure.sections.none { it.parentId == candidate.id } }
                        val matches = if (named.isNotEmpty()) named else numbered?.let { leaves.getOrNull(it - 1)?.let(::listOf) }.orEmpty()
                        if (matches.size != 1) return sectionsQuestion(
                            if (matches.isEmpty()) "I could not resolve that section. Which one do you mean?" else "More than one section has that title. Which one do you mean?",
                            matches.ifEmpty { structure.sections })
                        matches.single()
                    }
                }
            }
            RequestedScope.CURRENT_CHAPTER -> {
                val active = structure.active(locator)
                    ?: return sectionsQuestion("Which chapter are you reading?", structure.sections)
                val children = structure.sections.filter { it.parentId == active.id }
                if (!structure.isPrecise(locator) && children.isNotEmpty()) return sectionsQuestion(
                    "Your saved location identifies a containing section. Which chapter do you mean?", children)
                active
            }
            RequestedScope.UNRESOLVED -> return sectionsQuestion("Which part do you mean?", structure.sections.filter { it.parentId == null })
            else -> null
        }
        val start = section?.start ?: structure.beginning
        val requestedEnd = when (plan.scope) {
            RequestedScope.NAMED_SECTION, RequestedScope.WHOLE_BOOK -> section?.end ?: structure.end
            RequestedScope.CURRENT_CHAPTER -> section!!.end
            else -> if (spoilerLock && !plan.explicitSpoilers && !plan.permissionGranted) boundary else structure.end
        }
        val label = when (plan.scope) {
            RequestedScope.CURRENT_CHAPTER, RequestedScope.NAMED_SECTION -> section?.title ?: bookTitle
            RequestedScope.WHOLE_BOOK -> bookTitle
            RequestedScope.READ_SO_FAR -> "Beginning through the reading location"
            RequestedScope.PASSAGE -> "Selected passage and relevant earlier evidence"
            else -> "Relevant passages within the permitted scope"
        }
        if (spoilerLock && requestedEnd > boundary && plan.scope != RequestedScope.CURRENT_CHAPTER &&
            !plan.explicitSpoilers && !plan.permissionGranted) {
            return ScopeResolution.Clarify("$label extends beyond your reading location. Include that material for this answer?",
                listOf(safeChoice, ScopeChoice("allow-requested", "Include $label for this answer",
                    plan.copy(permissionGranted = true))))
        }
        // READ_SO_FAR always retains its literal meaning, even with spoiler lock disabled.
        return ready(start, if (plan.scope == RequestedScope.READ_SO_FAR) boundary else requestedEnd, label)
    }

    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
