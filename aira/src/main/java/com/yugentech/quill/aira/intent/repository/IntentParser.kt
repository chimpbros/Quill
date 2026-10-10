package com.yugentech.quill.aira.intent.repository
import com.yugentech.quill.aira.intent.model.*
import kotlinx.serialization.json.*
object IntentParser {
 fun parse(raw:String,query:String):Intent {
  return try {
   val clean=raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
   val obj=Json.parseToJsonElement(clean).jsonObject
   val flag=obj.getValue("isRAG").jsonPrimitive
   require(!flag.isString)
   val rag=flag.boolean
   fun strings(key:String)=obj.getValue(key).jsonArray.map { require(it.jsonPrimitive.isString);it.jsonPrimitive.content.trim() }.filter { it.isNotBlank() }
   val variations=strings("queryVariations");val entities=strings("entities");val keywords=strings("keywords")
   val intent=QueryIntent.valueOf(obj.getValue("queryIntent").jsonPrimitive.content.uppercase())
   if(!rag) Intent.General else {
    require(variations.isNotEmpty())
    Intent.BookRelated(variations,entities,keywords,intent,
     obj["characterName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
     obj["isChapterScoped"]?.jsonPrimitive?.booleanOrNull ?: false)
   }
  } catch(_:Exception) { Intent.BookRelated(listOf(query),emptyList(),emptyList(),QueryIntent.GENERAL) }
 }
}
