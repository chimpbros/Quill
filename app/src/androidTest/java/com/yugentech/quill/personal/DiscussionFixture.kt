package com.yugentech.quill.personal

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun writeDiscussionFixture(file: File) {
    val paragraphs = (0..35).joinToString("") { index ->
        val text = if (index == 30) "Ivo said, ‘Keep your promise, Mara.’" else if (index > 30) "UNREAD-SCENE $index: Ivo reveals the ending."
            else "Mara and Ivo waited beside the harbor. Conversation line $index."
        "<p id=\"q$index\">$text</p>"
    }
    val files = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">discussion-test</dc:identifier><dc:title>The Promise</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
        "OEBPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="one.xhtml">The promise</a></li><li><a href="two.xhtml">The conversation</a></li></ol></nav></body></html>""",
        "OEBPS/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="promise">Mara promised Ivo she would repair the lamp before the boat returned.</p><p id="unicode">Café — 雨 🌙, said <em>Mara</em>.</p><p>***</p></body></html>""",
        "OEBPS/two.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>$paragraphs</body></html>"""
    )
    ZipOutputStream(file.outputStream()).use { zip -> for ((path, text) in files) {
        val complete = if (path.endsWith(".xhtml")) text.replace("The promise</a>", "The earlier promise</a>")
            .replace("<body>", "<head><title>The Promise</title></head><body>")
            .replace("<p>***</p>", "<p>***</p><hr/>") else text
        zip.putNextEntry(ZipEntry(path)); zip.write(complete.toByteArray()); zip.closeEntry()
    } }
}
