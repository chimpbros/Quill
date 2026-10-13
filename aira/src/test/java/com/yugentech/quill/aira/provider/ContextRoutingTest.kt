package com.yugentech.quill.aira.provider

import com.yugentech.quill.aira.context.*
import com.yugentech.quill.aira.intent.model.*
import com.yugentech.quill.aira.intent.repository.IntentParser
import org.junit.Assert.*
import org.junit.Test

class ContextRoutingTest {
    private fun approved(name: String) = javaClass.getResourceAsStream("/approved-context-$name.txt")!!.bufferedReader().readText().trim()
    @Test fun productionInstructionsMatchReviewedCandidatesExactly() {
        assertEquals(approved("book").replace("${'$'}{title}", "Book").replace("${'$'}{author}", "Author"), ContextPrompts.book("Book", "Author"))
        assertEquals(approved("general").replace("${'$'}{title}", "Book").replace("${'$'}{author}", "Author"), PromptLibrary.general("Book", "Author"))
        assertEquals(approved("classifier"), PromptLibrary.classifier)
    }
    private fun route(scope: String, permission: Boolean = false, target: String = "chapter 3", term: String = "StarlAmp") = """
        {"isRAG":true,"queryIntent":"term_explanation","queryVariations":["StarlAmp"],"entities":[],"keywords":[],"scope":"$scope","targetName":"$target","term":"$term","allowSpoilers":$permission}
    """.trimIndent()
    @Test fun fictionalTermAndNamedTargetRemainLiteral() {
        val result = IntentParser.parse(route("NAMED_SECTION"), "Explain StarlAmp in chapter 3") as Intent.BookRelated
        assertEquals(RequestedScope.NAMED_SECTION, result.plan.scope)
        assertEquals("chapter 3", result.plan.targetName); assertEquals("StarlAmp", result.plan.term)
        assertEquals(QueryIntent.TERM_EXPLANATION, result.intent)
    }
    @Test fun classifierCannotGrantSpoilerPermission() {
        val result = IntentParser.parse(route("WHOLE_BOOK", true), "Recap the book") as Intent.BookRelated
        assertFalse(result.plan.explicitSpoilers); assertFalse(result.plan.permissionGranted)
        assertTrue((IntentParser.parse(route("WHOLE_BOOK"), "Recap the book with spoilers") as Intent.BookRelated).plan.explicitSpoilers)
        assertFalse(SpoilerPermission.explicit("without spoilers"))
        assertFalse(SpoilerPermission.explicit("no spoilers please"))
    }
    @Test fun invalidScopeDoesNotGrantBroaderAccess() {
        val result = IntentParser.parse(route("MADE_UP", true), "Who is Mara?") as Intent.BookRelated
        assertEquals(RequestedScope.RELEVANT_PASSAGES, result.plan.scope); assertFalse(result.plan.explicitSpoilers)
    }
    @Test fun ambiguousPartIsKeptForLocalClarification() {
        val result = IntentParser.parse(route("UNRESOLVED"), "Recap the first part") as Intent.BookRelated
        assertEquals(RequestedScope.UNRESOLVED, result.plan.scope)
    }
    @Test fun overviewIsGeneralKnowledgeRatherThanRetrieval() {
        assertEquals(Intent.General, IntentParser.parse("""{"isRAG":false,"queryIntent":"general","queryVariations":[],"entities":[],"keywords":[],"scope":"OVERVIEW"}""", "What is Blindsight about?"))
    }
}
