package com.yugentech.quill.aira.intent.repository

import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.*
import kotlinx.serialization.json.*

object IntentParser {
    fun parse(raw: String, query: String): Intent = try {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = Json.parseToJsonElement(clean).jsonObject
        val flag = obj.getValue("isRAG").jsonPrimitive
        require(!flag.isString)
        fun strings(key: String) = obj.getValue(key).jsonArray.map {
            require(it.jsonPrimitive.isString); it.jsonPrimitive.content.trim()
        }.filter { it.isNotBlank() }.take(12)
        fun optional(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val variations = strings("queryVariations")
        val entities = strings("entities")
        val keywords = strings("keywords")
        val task = QueryIntent.valueOf(obj.getValue("queryIntent").jsonPrimitive.content.uppercase())
        val chapterScoped = obj["isChapterScoped"]?.jsonPrimitive?.booleanOrNull ?: false
        val scope = optional("scope")?.let { RequestedScope.valueOf(it) }
            ?: if (task == QueryIntent.CHAPTER_SUMMARY || chapterScoped) RequestedScope.CURRENT_CHAPTER else RequestedScope.RELEVANT_PASSAGES
        if (!flag.boolean) {
            require(scope in setOf(RequestedScope.GENERAL, RequestedScope.OVERVIEW, RequestedScope.RELEVANT_PASSAGES))
            Intent.General
        } else {
            require(variations.isNotEmpty() && scope !in setOf(RequestedScope.GENERAL, RequestedScope.OVERVIEW))
            Intent.BookRelated(variations, entities, keywords, task, optional("characterName"), chapterScoped,
                ContextPlan(scope, optional("targetName"), optional("term"),
                    if (scope == RequestedScope.PASSAGE) EvidenceSource.SELECTION else EvidenceSource.PUBLICATION,
                    explicitSpoilers = SpoilerPermission.explicit(query)))
        }
    } catch (_: Exception) {
        // Uncertainty remains grounded and cannot extend reading progress.
        Intent.BookRelated(listOf(query), emptyList(), emptyList(), QueryIntent.GENERAL)
    }
}
