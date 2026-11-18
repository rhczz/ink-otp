package io.github.rhczz.inkotp.platform

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.security.MessageDigest
import org.xmlpull.v1.XmlPullParser

internal enum class PrivacyBlockKind { TITLE, HEADING, META, PARAGRAPH, TERM, DETAIL, BULLET }

internal data class PrivacyLink(val start: Int, val end: Int, val url: String)

internal data class PrivacyBlock(
    val kind: PrivacyBlockKind,
    val text: String,
    val links: List<PrivacyLink>,
)

/** The website's canonical policy, compiled into native text from the APK asset. */
internal class PrivacyPolicy private constructor(
    val revision: String,
    val blocks: List<PrivacyBlock>,
) {
    companion object {
        fun load(context: Context): PrivacyPolicy =
            context.assets.open("privacy/index.html").bufferedReader().use { parse(it.readText()) }

        internal fun parse(source: String): PrivacyPolicy {
            val start = source.indexOf("<main ")
            val end = source.indexOf("</main>", start.coerceAtLeast(0))
            check(start >= 0 && end > start && source.lastIndexOf("<main ") == start) {
                "Bundled privacy policy must contain one main element"
            }
            val main = source.substring(start, end + "</main>".length)
            // Keep the byte-for-byte revision used by earlier versions for existing choices.
            val revision = MessageDigest.getInstance("SHA-256").digest(main.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return PrivacyPolicy(revision, parseBlocks(main))
        }

        private fun parseBlocks(main: String): List<PrivacyBlock> = buildList {
            val parser = Xml.newPullParser().apply { setInput(main.reader()) }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "main", "div", "article", "section", "dl", "ul" -> Unit
                        "nav" -> {
                            check(parser.getAttributeValue(null, "class") == "policy-toc")
                            val depth = parser.depth
                            do {
                                check(parser.next() != XmlPullParser.END_DOCUMENT)
                            } while (parser.eventType != XmlPullParser.END_TAG || parser.depth != depth)
                        }
                        "h1" -> add(readBlock(parser, PrivacyBlockKind.TITLE))
                        "h2" -> add(readBlock(parser, PrivacyBlockKind.HEADING))
                        "p" -> {
                            val kind = if (parser.getAttributeValue(null, "class") == "policy-meta") PrivacyBlockKind.META
                                else PrivacyBlockKind.PARAGRAPH
                            add(readBlock(parser, kind))
                        }
                        "dt" -> add(readBlock(parser, PrivacyBlockKind.TERM))
                        "dd" -> add(readBlock(parser, PrivacyBlockKind.DETAIL))
                        "li" -> add(readBlock(parser, PrivacyBlockKind.BULLET))
                        else -> error("Unsupported privacy policy element: ${parser.name}")
                    }
                    XmlPullParser.TEXT -> check(parser.isWhitespace) { "Unexpected text outside a privacy policy block" }
                }
            }
            check(isNotEmpty()) { "Bundled privacy policy is empty" }
        }

        private fun readBlock(parser: XmlPullParser, kind: PrivacyBlockKind): PrivacyBlock {
            val blockDepth = parser.depth
            val text = StringBuilder()
            val links = mutableListOf<PrivacyLink>()
            var linkStart = 0
            var linkUrl: String? = null
            var pendingSpace = false
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "a" -> {
                            linkStart = text.length
                            linkUrl = parser.getAttributeValue(null, "href")?.takeIf(::isSupportedLink)
                        }
                        "code", "em", "span", "strong", "time" -> Unit
                        else -> error("Unsupported inline privacy policy element: ${parser.name}")
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == blockDepth) break
                        if (parser.name == "a") {
                            // A space from either side of an inline boundary should not be clickable.
                            val start = if (text.getOrNull(linkStart) == ' ') linkStart + 1 else linkStart
                            val url = linkUrl
                            if (url != null && start < text.length) links += PrivacyLink(start, text.length, url)
                            linkUrl = null
                        }
                    }
                    XmlPullParser.TEXT -> parser.text.forEach { character ->
                        if (character in " \t\r\n\u000c") {
                            pendingSpace = text.isNotEmpty()
                        } else {
                            if (pendingSpace) text.append(' ')
                            text.append(character)
                            pendingSpace = false
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> error("Unclosed privacy policy block")
                }
            }
            return PrivacyBlock(kind, text.toString(), links.toList())
        }

        private fun isSupportedLink(href: String): Boolean {
            val uri = Uri.parse(href)
            return when (uri.scheme) {
                "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null
                "mailto" -> !uri.schemeSpecificPart.isNullOrBlank()
                else -> false
            }
        }
    }
}
