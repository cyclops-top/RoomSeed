package top.cyclops.roomseed.sqlite

internal data class SqlStatement(val sql: String, val line: Int, val column: Int)

// The public driver prepares one statement at a time and does not return the unparsed suffix.
internal fun sqlStatements(sql: String): Sequence<SqlStatement> = sequence {
    var cursor = 0
    var line = 1
    var lineStart = 0
    var start = -1
    var startLine = 0
    var startColumn = 0
    var firstWord = true
    var createPrefix = false
    var trigger = false
    var triggerBoundary = false

    while (cursor < sql.length) {
        val current = sql[cursor]
        val next = sql.getOrNull(cursor + 1)
        val end =
            when {
                current == '-' && next == '-' ->
                    sql.indexOf('\n', cursor).let { if (it < 0) sql.length else it }
                current == '/' && next == '*' ->
                    sql.indexOf("*/", cursor + 2).let { if (it < 0) sql.length else it + 2 }
                current in " \t\r\n\u000c" -> cursor + 1
                current == ';' -> {
                    if (start >= 0 && !trigger) {
                        yield(
                            SqlStatement(sql.substring(start, cursor + 1), startLine, startColumn)
                        )
                        start = -1
                        firstWord = true
                        createPrefix = false
                    }
                    triggerBoundary = trigger
                    cursor + 1
                }
                else -> {
                    if (start < 0) {
                        start = cursor
                        startLine = line
                        startColumn = sql.codePointCount(lineStart, cursor) + 1
                    }
                    val tokenEnd =
                        when (current) {
                            '\'',
                            '"',
                            '`',
                            '[' -> quotedEnd(sql, cursor)
                            else -> {
                                var index = cursor
                                while (
                                    index < sql.length &&
                                        (sql[index].isLetterOrDigit() || sql[index] == '_')
                                ) index++
                                maxOf(index, cursor + 1)
                            }
                        }
                    val token = sql.substring(cursor, tokenEnd)
                    when {
                        firstWord -> createPrefix = token.equals("CREATE", ignoreCase = true)
                        createPrefix && token.equals("TRIGGER", ignoreCase = true) -> {
                            trigger = true
                            createPrefix = false
                        }
                        createPrefix ->
                            createPrefix =
                                token.equals("TEMP", true) || token.equals("TEMPORARY", true)
                    }
                    if (triggerBoundary && token.equals("END", ignoreCase = true)) trigger = false
                    triggerBoundary = false
                    firstWord = false
                    tokenEnd
                }
            }
        for (index in cursor until end) {
            if (sql[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        cursor = end
    }
    if (start >= 0) yield(SqlStatement(sql.substring(start), startLine, startColumn))
}

private fun quotedEnd(sql: String, start: Int): Int {
    val opening = sql[start]
    val closing = if (opening == '[') ']' else opening
    var cursor = start + 1
    while (cursor < sql.length) {
        if (sql[cursor++] == closing) {
            if (opening == '[' || sql.getOrNull(cursor) != closing) break
            cursor++
        }
    }
    return cursor
}
