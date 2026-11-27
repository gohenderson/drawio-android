package io.github.gohenderson.drawio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a diagram file into the XML the viewer shows: a .drawio / .xml file as it is, a
 * .drawio.png's embedded diagram (like the desktop's png-xml.js) or a .drawio.svg's.
 */
object DiagramFile {
    /** Bigger than any real diagram; stops a huge file from taking all the memory. */
    const val MAX_BYTES = 50L * 1024 * 1024

    sealed class Result {
        data class Diagram(val xml: String) : Result()
        data class Problem(val message: String) : Result()
    }

    fun read(input: InputStream): Result {
        val bytes = try {
            readLimited(input)
        } catch (e: TooBig) {
            return Result.Problem("The file is bigger than ${MAX_BYTES / 1024 / 1024} MB.")
        }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): Result {
        if (isPng(bytes)) {
            return pngXml(bytes)?.let { Result.Diagram(it) }
                ?: Result.Problem("This PNG has no draw.io diagram in it.")
        }
        val text = String(bytes, StandardCharsets.UTF_8).removePrefix("﻿").trim()
        val root = rootElement(text) ?: return Result.Problem("This isn't a draw.io diagram (it isn't XML).")
        return when (root.first) {
            "mxfile", "mxGraphModel" -> Result.Diagram(text)
            "svg" -> root.second?.takeIf { isDiagram(it) }?.let { Result.Diagram(it) }
                ?: Result.Problem("This SVG has no draw.io diagram in it.")
            else -> Result.Problem("This isn't a draw.io diagram.")
        }
    }

    private class TooBig : Exception()

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw TooBig()
        }
        return out.toByteArray()
    }

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private fun isPng(b: ByteArray) = b.size >= 8 && PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }

    /**
     * The diagram in a PNG's tEXt or zTXt chunk named mxGraphModel, or null. Chunk
     * lengths are unsigned and a chunk running past the end ends the scan, so a malformed
     * file can't keep it going (as in the desktop's png-xml.js).
     */
    fun pngXml(b: ByteArray): String? {
        var offset = 8
        while (offset + 8 <= b.size) {
            val length = ((b[offset].toLong() and 0xff) shl 24) or ((b[offset + 1].toLong() and 0xff) shl 16) or
                ((b[offset + 2].toLong() and 0xff) shl 8) or (b[offset + 3].toLong() and 0xff)
            val type = String(b, offset + 4, 4, StandardCharsets.US_ASCII)
            val start = offset + 8
            if (start + length > b.size) return null
            val end = start + length.toInt()
            if (type == "tEXt" || type == "zTXt") {
                var keyEnd = start
                while (keyEnd < end && b[keyEnd] != 0.toByte()) keyEnd++
                if (String(b, start, keyEnd - start, StandardCharsets.US_ASCII) == "mxGraphModel") {
                    val text = if (type == "zTXt") {
                        // Skip the null and the compression method byte
                        val data = b.copyOfRange(minOf(keyEnd + 2, end), end)
                        String(inflate(data, raw = false) ?: inflate(data, raw = true) ?: return null, StandardCharsets.UTF_8)
                    } else {
                        String(b, keyEnd + 1, end - keyEnd - 1, StandardCharsets.UTF_8)
                    }
                    return decode(text)
                }
            }
            if (type == "IEND") return null
            offset = end + 4 // data + CRC
        }
        return null
    }

    /** The text as stored is URI-encoded (%3Cmxfile…); older files may not be. */
    private fun decode(text: String): String =
        if (text.startsWith("%3C", ignoreCase = true) || text.startsWith("%3c")) {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } else text

    private fun inflate(data: ByteArray, raw: Boolean): ByteArray? {
        val inflater = Inflater(raw)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            if (inflater.finished()) out.toByteArray() else null
        } catch (e: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun isDiagram(xml: String) = rootElement(xml)?.first.let { it == "mxfile" || it == "mxGraphModel" }

    /**
     * The root element's name and, for an SVG, its content attribute (where draw.io keeps
     * the diagram); null if it isn't well-formed XML. External entities and DOCTYPEs are
     * refused: a file can't make this read anything else.
     */
    private fun rootElement(xml: String): Pair<String, String?>? = try {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        val doc = f.newDocumentBuilder().apply { setErrorHandler(null) }.parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val root = doc.documentElement
        val name = root.nodeName.substringAfter(':')
        name to (if (name == "svg") root.getAttribute("content").takeIf { it.isNotEmpty() } else null)
    } catch (e: Exception) {
        null
    }
}
