package dev.walden.mygomusic.util

import dev.walden.mygomusic.Lyrics

/**
 * Turns whatever a plugin calls "lrc" into displayable lines.
 *
 * Plugins are inconsistent: most return LRC text, but some (the NetEase ones)
 * pack a JSON object like `{"t":0,"c":[{"tx":"作词: "},{"tx":"唐恬"}]}` into the
 * `lrc` field. Both shapes are normalised here, so the player never shows raw
 * JSON to the listener.
 */
fun plainLyricLines(lyrics: Lyrics): List<String> {
    if (lyrics.lines.isNotEmpty()) return emptyList() // timed lines are rendered separately
    val raw = lyrics.lrc?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    if (raw.startsWith("{") || raw.startsWith("[")) {
        val extracted = Regex("\"tx\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(raw)
            .map { unescapeJson(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()
        if (extracted.isNotEmpty()) return extracted
        // A JSON lyric without `tx` fields: fall back to any "text"/"line" keys.
        val other = Regex("\"(?:text|line|content)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(raw)
            .map { unescapeJson(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()
        if (other.isNotEmpty()) return other
        return emptyList()
    }
    return raw.lineSequence()
        .map { line -> line.replace(Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]"), "").trim() }
        .filter { it.isNotBlank() }
        .toList()
}

private fun unescapeJson(value: String): String =
    value.replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\r", "")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
