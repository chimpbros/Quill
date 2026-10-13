package com.yugentech.quill.aira.chat.quickChat.prompt

sealed class QuickPrompt {
    data object CatchMeUp : QuickPrompt()
    data class ExplainConversation(val text: String) : QuickPrompt()
    data object IncludeMoreContext : QuickPrompt()
    data object WhoAreTheCharacters : QuickPrompt()
    data class WhatAreTheThemes(val chapterIndex: Int) : QuickPrompt()
    data class RecallCharacter(val name: String) : QuickPrompt()
    data class RecentRole(val name: String) : QuickPrompt()
    data class JourneySoFar(val name: String, val currentChapterIndex: Int) : QuickPrompt()
    data class WhatIsTheSignificance(val text: String, val chapterIndex: Int) : QuickPrompt()
    data class SummarizeChapter(val chapterIndex: Int, val locatorJson: String? = null) : QuickPrompt()
    data class DefineWord(val word: String) : QuickPrompt()
    data class WhatIsThis(val word: String, val locatorJson: String? = null) : QuickPrompt()
    data class ExplainThis(val text: String) : QuickPrompt()
    data class VisualizeThis(
        val text: String,
        val chapterIndex: Int,
        val locatorJson: String? = null
    ) : QuickPrompt()
    data class CustomQuestion(val selectedText: String, val query: String) : QuickPrompt()
}
