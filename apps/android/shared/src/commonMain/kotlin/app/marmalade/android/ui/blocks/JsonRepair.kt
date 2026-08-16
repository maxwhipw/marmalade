package app.marmalade.android.ui.blocks

// Adapted from Kai (github.com/SimonSchubert/Kai, Apache-2.0) —
// ui/dynamicui/KaiUiParser.kt syntax-repair stages (fixJsonSyntax /
// sanitizeJson / trimTrailingIncomplete). See CREDITS.md.
//
// Repairs the JSON damage LLMs actually produce inside ```marmalade-ui
// fences — truncated output (mid-string, mid-key), `"key=[` for `"key":[`,
// extra/mismatched closers, an object in an array missing its `}` before
// the next `,{` — so a partial tree still renders with field defaults
// instead of degrading to a raw code block. This matters most for
// smaller/local models.

object JsonRepair {

    /** Fix common LLM JSON syntax errors like `"key=[` instead of `"key":[`. */
    private val brokenKeySyntax = Regex(""""(\w+)=([{\[])""")

    fun fixJsonSyntax(raw: String): String =
        brokenKeySyntax.replace(raw) { "\"${it.groupValues[1]}\":${it.groupValues[2]}" }

    /**
     * Repair JSON with extra/mismatched closing braces/brackets using
     * stack-based matching; trim + close truncated structures. Returns the
     * input unchanged when it doesn't start with `{`/`[`.
     */
    fun sanitizeJson(raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw[0] != '{' && raw[0] != '[') return raw

        val stack = mutableListOf<Char>()
        val result = StringBuilder()
        var inString = false
        var escaped = false
        // Last structural char emitted outside strings — detects `,{` inside
        // an object whose parent is an array (a forgotten `}` between array
        // elements).
        var lastSig = ' '

        for (c in raw) {
            if (escaped) {
                escaped = false
                result.append(c)
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                result.append(c)
                continue
            }
            if (c == '"') {
                inString = !inString
                result.append(c)
                lastSig = c
                continue
            }
            if (inString) {
                result.append(c)
                continue
            }
            if (c.isWhitespace()) {
                result.append(c)
                continue
            }
            when (c) {
                '{', '[' -> {
                    if (lastSig == ',' &&
                        stack.lastOrNull() == '{' &&
                        stack.getOrNull(stack.size - 2) == '['
                    ) {
                        val commaIdx = result.lastIndexOf(",")
                        if (commaIdx >= 0) {
                            result.insert(commaIdx, '}')
                            stack.removeAt(stack.lastIndex)
                        }
                    }
                    stack.add(c)
                    result.append(c)
                    lastSig = c
                }

                '}' -> if (stack.isNotEmpty() && stack.last() == '{') {
                    stack.removeAt(stack.lastIndex)
                    result.append(c)
                    lastSig = c
                }

                ']' -> if (stack.isNotEmpty() && stack.last() == '[') {
                    stack.removeAt(stack.lastIndex)
                    result.append(c)
                    lastSig = c
                }

                else -> {
                    result.append(c)
                    lastSig = c
                }
            }
            if (stack.isEmpty()) return result.toString()
        }

        // Unclosed JSON — trim trailing incomplete content, then close what's open.
        val trimmed = trimTrailingIncomplete(result.toString(), inString)
        return buildString {
            append(trimmed)
            for (i in stack.indices.reversed()) {
                append(if (stack[i] == '{') '}' else ']')
            }
        }
    }

    /**
     * Trim trailing incomplete content from truncated JSON (half-open
     * strings, trailing commas/colons, orphaned keys) so appending closers
     * produces valid JSON.
     */
    private fun trimTrailingIncomplete(json: String, inString: Boolean): String {
        var s = json
        if (inString) {
            val lastQuote = s.lastIndexOf('"')
            if (lastQuote >= 0) s = s.substring(0, lastQuote)
        }
        s = s.trimEnd()
        while (s.isNotEmpty()) {
            val last = s.last()
            if (last == ',' || last == ':') {
                s = s.dropLast(1).trimEnd()
                continue
            }
            if (last != '"') break
            val openQuote = s.lastIndexOf('"', s.lastIndex - 1)
            if (openQuote < 0) break
            val before = s.substring(0, openQuote).trimEnd()
            if (before.isEmpty() || before.last() in setOf(',', '{', '[')) {
                s = before
            } else {
                break
            }
        }
        return s
    }
}
