package com.yugentech.quill.aira.response

sealed class AiraResponse {
    data class Preparing(val message: String) : AiraResponse()
    data class Partial(val text: String) : AiraResponse()
    data class Success(
        val text: String,
        val requiresSpoilerPermission: Boolean = false,
        val sources: List<com.yugentech.quill.aira.context.SourceExcerpt> = emptyList()
    ) : AiraResponse()

    data class ImageSuccess(
        val imagePath: String
    ) : AiraResponse()

    data class Error(val message: String) : AiraResponse()
    data class Clarification(val question: String, val choices: List<com.yugentech.quill.aira.context.ScopeChoice>,
        val requestId: String = "") : AiraResponse()
}
