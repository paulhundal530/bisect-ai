package com.bisectai.spec

/** A parsed investigation file split into its YAML front matter and Markdown body. */
data class FrontMatter(val yaml: String, val body: String)

/**
 * Splits an investigation Markdown file into leading YAML front matter and the Markdown body.
 *
 * The file must begin with a `---` fence, contain a closing `---` fence, and everything after
 * the closing fence is the body (the human-readable regression context).
 */
object FrontMatterSplitter {

    fun split(content: String): FrontMatter {
        val normalized = content.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        if (lines.isEmpty() || lines.first().trim() != "---") {
            throw IllegalArgumentException(
                "Investigation file must begin with a YAML front matter block delimited by '---'.",
            )
        }
        val closingIndex = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: throw IllegalArgumentException(
                "Investigation front matter is missing its closing '---' delimiter.",
            )
        val yaml = lines.subList(1, closingIndex).joinToString("\n")
        val body = lines.subList(closingIndex + 1, lines.size).joinToString("\n").trim()
        return FrontMatter(yaml, body)
    }
}
