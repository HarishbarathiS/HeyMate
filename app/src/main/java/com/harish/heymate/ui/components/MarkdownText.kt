package com.harish.heymate.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders a subset of Markdown the AI commonly emits, so users never see raw
 * `**asterisks**` or `#` marks. Handles, per line:
 *   - # / ## / ### headers
 *   - `- ` / `* ` bullets   (and 1. numbered lists)
 *   - inline **bold**, *italic* / _italic_, `code`, ~~strike~~
 *
 * Deliberately small and dependency-free — not a full CommonMark parser. Tables,
 * links, and fenced code blocks fall through as styled inline text.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val base = LocalTextStyle.current
    Column(modifier) {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        lines.forEachIndexed { i, raw ->
            if (i > 0) Spacer(Modifier.height(2.dp))
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))

                line.startsWith("### ") -> Text(
                    parseInline(line.removePrefix("### ")),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                line.startsWith("## ") -> Text(
                    parseInline(line.removePrefix("## ")),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                line.startsWith("# ") -> Text(
                    parseInline(line.removePrefix("# ")),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )

                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> BulletRow(
                    marker = "•",
                    content = parseInline(line.trimStart().drop(2)),
                    style = base,
                )

                Regex("^\\d+\\. ").containsMatchIn(line.trimStart()) -> {
                    val trimmed = line.trimStart()
                    val dot = trimmed.indexOf(". ")
                    BulletRow(
                        marker = trimmed.substring(0, dot + 1),
                        content = parseInline(trimmed.substring(dot + 2)),
                        style = base,
                    )
                }

                else -> Text(parseInline(line), style = base)
            }
        }
    }
}

@Composable
private fun BulletRow(marker: String, content: AnnotatedString, style: androidx.compose.ui.text.TextStyle) {
    Row {
        Text(marker, style = style)
        Spacer(Modifier.width(8.dp))
        Text(content, style = style)
    }
}

/**
 * Parse inline emphasis into an AnnotatedString. Supports **bold**, *italic*,
 * _italic_, `code`, and ~~strikethrough~~. Unmatched markers are left as literal
 * text so nothing silently disappears.
 */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // **bold**
            c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            // *italic*
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // _italic_
            c == '_' -> {
                val end = text.indexOf('_', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // `code`
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // ~~strikethrough~~
            c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
