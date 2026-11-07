package dev.walden.mygomusic.util

import dev.walden.mygomusic.Lyrics
import dev.walden.mygomusic.pluginJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns whatever a plugin calls "lrc" into displayable lines.
 *
 * Plugins are inconsistent: most return LRC text, but the NetEase ones pack a
 * JSON object like `{"t":0,"c":[{"tx":"作词: "},{"tx":"唐恬"}]}` into the same
 * field. Both shapes are normalised here.
 *
 * Two traps this used to fall into (BUGS.md H5 / M12):
 *  - every LRC string starts with '[', so a `startsWith("[")` JSON test sent all
 *    plain LRC down the JSON path, found no "tx" keys and returned nothing —
 *    the player then claimed "这首歌暂无歌词" for perfectly good lyrics;
 *  - hand-rolled unescaping applied `\\` last and never handled `\uXXXX`, so it
 *    corrupted escaped backslashes and left unicode escapes as literal garbage.
 *
 * Now JSON is only used when it actually parses *and* yields lyric text, and
 * unescaping is delegated to the JSON parser (which is correct by construction).
 */
fun plainLyricLines(lyrics: Lyrics): List<String> {
    // Timed lines are rendered by the synced-lyrics view instead.
    if (lyrics.lines.isNotEmpty()) return emptyList()
    val raw = lyrics.lrc?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()

    if (raw.startsWith("{") || raw.startsWith("[")) {
        val fromJson = extractJsonLyricText(raw)
        if (fromJson.isNotEmpty()) return fromJson
        // Not JSON after all (i.e. ordinary LRC) — fall through.
    }
    return parseLrcText(raw)
}

/** Keys that carry lyric text. Anything else (image links, orpheus URIs, ids) is ignored. */
private val TEXT_KEYS = listOf("tx", "text", "line", "content", "lyric", "lrc")

private fun extractJsonLyricText(raw: String): List<String> {
    val root = runCatching { pluginJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<String>()
    collectText(root, out)
    return out.flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun collectText(element: JsonElement, out: MutableList<String>) {
    when (element) {
        is JsonObject -> {
            val direct = TEXT_KEYS.firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            if (direct != null) {
                out += direct
            } else {
                element.values.forEach { collectText(it, out) }
            }
        }

        is JsonArray -> element.forEach { collectText(it, out) }
        // Bare primitives are deliberately ignored: sibling fields such as
        // "li":"http://…jpg" or "or":"orpheus://…" are not lyrics.
        else -> Unit
    }
}

private val LRC_TIMESTAMP = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

/** Whole-line metadata tags (`[ti:…]`, `[ar:…]`, `[offset:…]`, …) carry no lyrics. */
private val LRC_METADATA = Regex(
    """^\s*\[(?:ti|ar|al|by|offset|re|ve|length|au|hash|sign|qq|total|kana|encoded)\s*:[^\]]*]\s*$""",
    RegexOption.IGNORE_CASE,
)

private fun parseLrcText(raw: String): List<String> =
    raw.lineSequence()
        .filterNot { LRC_METADATA.matches(it) }
        .map { it.replace(LRC_TIMESTAMP, "").trim() }
        .filter { it.isNotEmpty() }
        .toList()
