package io.github.rhczz.inkotp.platform

import android.content.Context
import java.security.MessageDigest

/** A copy of the website policy, packaged at build time and read from the APK. */
internal class PrivacyPolicy private constructor(
    val revision: String,
    val document: String,
) {
    companion object {
        fun load(context: Context): PrivacyPolicy {
            val source = context.assets.open("privacy/index.html").bufferedReader().use { it.readText() }
            val styles = context.assets.open("privacy/site.css").bufferedReader().use { it.readText() }
            val start = source.indexOf("<main ")
            val end = source.indexOf("</main>", start.coerceAtLeast(0))
            check(start >= 0 && end > start && source.lastIndexOf("<main ") == start) {
                "Bundled privacy policy must contain one main element"
            }
            val policy = source.substring(start, end + "</main>".length)
            val revision = MessageDigest.getInstance("SHA-256").digest(policy.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val document = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
                  <meta name="referrer" content="no-referrer">
                  <title>隐私声明</title>
                  <style>
                $styles
                  .policy-shell { padding-block-start: 1.25rem; }
                  @media (prefers-color-scheme: dark) {
                    :root {
                      color-scheme: dark;
                      --paper: #151923;
                      --ink: #f4f5fa;
                      --muted: #c1c7d3;
                      --line: #444c5c;
                      --accent: #aab7ff;
                      --accent-hover: #aab7ff;
                    }
                    .policy-note { background: #27314a; }
                  }
                  </style>
                </head>
                <body>
                $policy
                </body>
                </html>
            """.trimIndent()
            return PrivacyPolicy(revision, document)
        }
    }
}
