package com.akashic.shell

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * 零依赖轻量 Markdown 渲染器（面向小屏流式回复）：
 * 支持标题、加粗、斜体、行内代码、围栏代码块、无序列表、引用与链接文本，
 * 未闭合的流式片段会安全回退为纯文本，不闪烁、不丢字。
 */
object MiniMarkdown {
    private val HEADING_RE = Regex("^(#{1,4})\\s+(.+)$")
    private val BULLET_RE = Regex("^(\\s*)[-*+]\\s+(.+)$")
    private val QUOTE_RE = Regex("^>\\s?(.*)$")

    fun render(
        source: String,
        codeBg: Int,
        mutedInk: Int,
        accentInk: Int,
    ): CharSequence {
        if (source.isEmpty()) return ""
        val out = SpannableStringBuilder()
        val lines = source.split('\n')
        var inCode = false
        val codeBuf = StringBuilder()

        fun flushCode() {
            if (codeBuf.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(codeBuf.toString().trimEnd('\n'))
            val end = out.length
            if (end > start) {
                out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.9f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            codeBuf.setLength(0)
        }

        for (rawLine in lines) {
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    flushCode()
                    inCode = false
                } else {
                    inCode = true
                }
                continue
            }
            if (inCode) {
                codeBuf.append(rawLine).append('\n')
                continue
            }

            if (out.isNotEmpty()) out.append('\n')

            val h = HEADING_RE.matchEntire(trimmed)
            if (h != null) {
                val level = h.groupValues[1].length
                val start = out.length
                appendInline(out, h.groupValues[2], codeBg, accentInk)
                val end = out.length
                val scale = when (level) {
                    1 -> 1.18f
                    2 -> 1.12f
                    else -> 1.06f
                }
                out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(scale), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val q = QUOTE_RE.matchEntire(trimmed)
            if (q != null) {
                val start = out.length
                appendInline(out, q.groupValues[1], codeBg, accentInk)
                val end = out.length
                if (end > start) {
                    out.setSpan(QuoteSpan(accentInk), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(mutedInk), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                continue
            }

            val b = BULLET_RE.matchEntire(rawLine)
            if (b != null) {
                val indent = if (b.groupValues[1].length >= 2) "  " else ""
                out.append(indent).append("• ")
                appendInline(out, b.groupValues[2], codeBg, accentInk)
                continue
            }

            appendInline(out, rawLine, codeBg, accentInk)
        }
        if (inCode) flushCode()
        return out
    }

    private fun appendInline(
        out: SpannableStringBuilder,
        text: String,
        codeBg: Int,
        accentInk: Int,
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            // 行内代码 `code`
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(text.substring(i + 1, close))
                    val end = out.length
                    out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.92f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }
            // 加粗 **bold**
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, close), codeBg, accentInk)
                    val end = out.length
                    out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }
            // 链接 [label](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf("](", i + 1)
                if (closeBracket > i + 1) {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = text.substring(i + 1, closeBracket)
                        val start = out.length
                        out.append(label)
                        val end = out.length
                        out.setSpan(ForegroundColorSpan(accentInk), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = closeParen + 1
                        continue
                    }
                }
            }
            // 斜体 *italic*
            if (text[i] == '*' && (i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*')) {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(text.substring(i + 1, close))
                    val end = out.length
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }
            out.append(text[i])
            i++
        }
    }
}
