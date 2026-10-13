package com.yugentech.quill.personal

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun writeNavigationFixture(file: File) {
    val paragraphs = (1..18).joinToString("") { "<p>The lighthouse keeper walked along the shore, counting the boats and inspecting the lamp. This is ordinary scene detail for paragraph $it.</p>" }
    val files = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">navigation-test</dc:identifier><dc:title>The Lantern + The Bridge</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="three" href="three.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/><itemref idref="three"/></spine></package>""",
        "OEBPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">The Lantern</a><ol><li><a href="one.xhtml#a">Arrival</a></li><li><a href="one.xhtml#b">Night Watch</a></li><li><a href="two.xhtml#c">Homecoming</a></li></ol></li><li><a href="three.xhtml">The Bridge</a><ol><li><a href="three.xhtml#d">Arrival</a></li></ol></li></ol></nav></body></html>""",
        "OEBPS/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>The Lantern</title></head><body><h1 id="a">Arrival</h1><p>Mara inherited a lighthouse. Café lanterns glowed beside the harbor.</p>$paragraphs<h1 id="b">Night Watch</h1><p>StarlAmp painted the nighttime desert in blues and whites. Mara saw a moving shape.</p>$paragraphs</body></html>""",
        "OEBPS/two.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Homecoming</title></head><body><h1 id="c">Homecoming</h1><p>Mara and Ivo repaired the broken cable. The lamp guided a boat home. Mara chose to keep the lighthouse.</p></body></html>""",
        "OEBPS/three.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>The Bridge</title></head><body><h1 id="d">Arrival</h1><p>UNREAD-SECRET: Ivo crossed the bridge and found the missing map. StarlAmp scanned the tunnel.</p></body></html>"""
    )
    ZipOutputStream(file.outputStream()).use { zip -> for ((path, text) in files) { zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
}
