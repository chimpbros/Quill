package com.yugentech.quill.aira.message

data class AiraMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sources: List<com.yugentech.quill.aira.context.SourceExcerpt> = emptyList()
) {
    enum class Role {
        USER,
        AIRA
    }
}
