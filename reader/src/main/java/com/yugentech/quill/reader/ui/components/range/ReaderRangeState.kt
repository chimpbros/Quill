package com.yugentech.quill.reader.ui.components.range

import com.yugentech.quill.aira.rag.ResolvedTextRange
import org.readium.r2.shared.publication.Locator

data class ReaderRangeState(val start: Locator? = null, val end: Locator? = null,
    val range: ResolvedTextRange? = null, val adjustingStart: Boolean = false,
    val note: String = "", val colorInt: Int = 0xFFFFE082.toInt(), val saving: Boolean = false,
    val error: String? = null)
