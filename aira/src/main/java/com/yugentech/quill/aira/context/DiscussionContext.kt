package com.yugentech.quill.aira.context

import com.yugentech.quill.database.entity.AiraDiscussionEntity
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

data class SourceExcerpt(val id: String, val title: String, val text: String, val locatorJson: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("text", text).put("locator", locatorJson)
    companion object {
        fun encode(sources: List<SourceExcerpt>) = JSONArray(sources.map { it.json() }).toString()
        fun decode(json: String?): List<SourceExcerpt> = runCatching {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                SourceExcerpt(it.getString("id"), it.getString("title"), it.getString("text"), it.getString("locator"))
            } }
        }.getOrDefault(emptyList())
    }
}

fun parseLocator(json: String?): Locator? = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

data class DiscussionContext(val topicId: String, val label: String, val selectionText: String?,
    val request: ReaderRequestContext, val mode: String, val lastResponse: String?, val sources: List<SourceExcerpt>) {
    companion object {
        fun from(entity: AiraDiscussionEntity) = DiscussionContext(entity.topicId, entity.label, entity.selectionText,
            ReaderRequestContext(parseLocator(entity.boundaryLocatorJson), parseLocator(entity.anchorLocatorJson),
                paragraphCount = entity.paragraphCount, discussionMode = entity.mode, topicId = entity.topicId),
            entity.mode, entity.lastResponse, SourceExcerpt.decode(entity.sourcesJson))
    }
}
