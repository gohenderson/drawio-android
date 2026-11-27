package io.github.gohenderson.drawio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URLEncoder
import java.util.zip.CRC32
import java.util.zip.Deflater

class DiagramFileTest {
    private val model = """<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>"""
    private val file = """<mxfile host="test"><diagram id="p" name="Page-1">$model</diagram></mxfile>"""

    private fun diagram(r: DiagramFile.Result) = (r as DiagramFile.Result.Diagram).xml

    @Test
    fun aDrawioFileIsShownAsItIs() {
        assertEquals(file, diagram(DiagramFile.parse(file.toByteArray())))
        assertEquals(model, diagram(DiagramFile.parse(model.toByteArray())))
    }

    @Test
    fun aByteOrderMarkAndSpaceAreIgnored() {
        assertEquals(file, diagram(DiagramFile.parse(("﻿\n  $file\n").toByteArray())))
    }

    @Test
    fun anythingElseIsAProblem() {
        assertTrue(DiagramFile.parse("just text".toByteArray()) is DiagramFile.Result.Problem)
        assertTrue(DiagramFile.parse("<html><body/></html>".toByteArray()) is DiagramFile.Result.Problem)
        assertTrue(DiagramFile.parse(ByteArray(0)) is DiagramFile.Result.Problem)
    }

    @Test
    fun aDoctypeIsRefused() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE mxfile [<!ENTITY x SYSTEM "file:///etc/passwd">]><mxfile>&x;</mxfile>"""
        assertTrue(DiagramFile.parse(xxe.toByteArray()) is DiagramFile.Result.Problem)
    }

    @Test
    fun aDrawioSvgShowsItsDiagram() {
        val content = file.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" content="$content" width="10" height="10"><g/></svg>"""
        assertEquals(file, diagram(DiagramFile.parse(svg.toByteArray())))
        val plain = """<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>"""
        assertTrue(DiagramFile.parse(plain.toByteArray()) is DiagramFile.Result.Problem)
    }

    @Test
    fun aDrawioPngShowsItsDiagram() {
        // As draw.io writes it: URI-encoded, in a zTXt chunk with a zlib stream
        assertEquals(file, diagram(DiagramFile.parse(png("zTXt", ztxt(file, raw = false)))))
        // Raw deflate, as the desktop CLI once wrote it [jgraph/drawio-desktop#2425]
        assertEquals(file, diagram(DiagramFile.parse(png("zTXt", ztxt(file, raw = true)))))
        // tEXt
        assertEquals(file, diagram(DiagramFile.parse(png("tEXt", "mxGraphModel".toByteArray() + 0 + encode(file).toByteArray()))))
    }

    @Test
    fun aPlainOrBrokenPngIsAProblem() {
        assertTrue(DiagramFile.parse(png("tEXt", "Comment\u0000hi".toByteArray())) is DiagramFile.Result.Problem)
        // A chunk that says it's longer than the file
        val broken = png("zTXt", ztxt(file, raw = false)).copyOf(40)
        assertTrue(DiagramFile.parse(broken) is DiagramFile.Result.Problem)
        // A huge length: unsigned, so it can't go backwards
        val huge = png("tEXt", ByteArray(4)).also { it[8] = -1; it[9] = -1; it[10] = -1; it[11] = -1 }
        assertTrue(DiagramFile.parse(huge) is DiagramFile.Result.Problem)
    }

    @Test
    fun aFileTooBigIsAProblem() {
        val big = object : java.io.InputStream() {
            var left = DiagramFile.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 'a'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        val r = DiagramFile.read(big)
        assertTrue(r is DiagramFile.Result.Problem && r.message.contains("MB"))
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun ztxt(xml: String, raw: Boolean): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
        d.setInput(encode(xml).toByteArray())
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return "mxGraphModel".toByteArray() + 0 + 0 + out.toByteArray()
    }

    private operator fun ByteArray.plus(b: Int) = this + byteArrayOf(b.toByte())

    private fun png(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(t: String, d: ByteArray) {
            val o = DataOutputStream(out)
            o.writeInt(d.size)
            o.write(t.toByteArray())
            o.write(d)
            o.writeInt(CRC32().apply { update(t.toByteArray()); update(d) }.value.toInt())
        }
        chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
        chunk(type, data)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
