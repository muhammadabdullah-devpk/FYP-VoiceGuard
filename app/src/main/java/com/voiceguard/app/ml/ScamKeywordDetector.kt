package com.voiceguard.app.ml

import com.voiceguard.app.data.ScamKeyword

/**
 * Offline scam keyword matcher (second signal, proposal Table 4).
 *
 * Words are matched on word boundaries, so "pin" does not match "spinning" and
 * "case" does not match "showcase". Spelled-out letters ("o t p") are joined before matching.
 * The keyword list lives in the Room database and can be extended by the user.
 */
class ScamKeywordDetector {

    private class Compiled(val entity: ScamKeyword, val regex: Regex)

    @Volatile
    private var compiled: List<Compiled> = emptyList()

    fun reload(keywords: List<ScamKeyword>) {
        compiled = keywords
            .filter { it.keyword.isNotBlank() }
            .map { Compiled(it, buildRegex(it.keyword)) }
    }

    /** Returns every keyword found in [text] (each keyword once). */
    fun scan(text: String): List<ScamKeyword> {
        if (text.isBlank()) return emptyList()
        val normalized = normalize(text)
        return compiled.filter { it.regex.containsMatchIn(normalized) }.map { it.entity }
    }

    private fun buildRegex(keyword: String): Regex {
        val body = keyword.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{M}\\p{N}])" + body + "(?![\\p{L}\\p{M}\\p{N}])")
    }

    companion object {
        /** Lower-cases the text and appends joined single letters, e.g. "o t p" becomes "otp". */
        fun normalize(text: String): String {
            val lower = text.lowercase()
            val tokens = lower.split(Regex("[\\s,.;:!?\"'()\\-]+")).filter { it.isNotEmpty() }
            val result = StringBuilder(lower)
            val letters = StringBuilder()
            for (token in tokens) {
                if (token.length == 1 && token[0].isLetter()) {
                    letters.append(token)
                } else {
                    if (letters.length >= 2) result.append(' ').append(letters)
                    letters.setLength(0)
                }
            }
            if (letters.length >= 2) result.append(' ').append(letters)
            return result.toString()
        }
    }
}
