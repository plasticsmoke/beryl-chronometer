package com.plasticsmoke.beryl.expr

/**
 * Tokenizer for C-like expressions used in Chronometer watch XML files.
 *
 * Faithful Kotlin port of chronometer-web `src/expr/tokenizer.ts`, itself derived
 * from the lex specification in `Parser/c.l` of the original Chronometer source.
 * Produces a flat list of tokens from a source string.
 */

enum class TokenType {
    Integer,
    Double,
    DoubleE,
    Identifier,

    // Punctuation / single-char operators
    LParen,
    RParen,
    Comma,
    Colon,
    Question,
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Ampersand,
    Pipe,
    Caret,
    Tilde,
    Bang,
    LessThan,
    GreaterThan,
    Equals,

    // Multi-char operators
    LeftShift,
    RightShift,
    LessEqual,
    GreaterEqual,
    EqualEqual,
    BangEqual,
    AmpAmp,
    PipePipe,
    PlusEquals,
    MinusEquals,
    StarEquals,
    SlashEquals,

    // End of input
    EOF,
}

data class Token(val type: TokenType, val value: String, val position: Int)

class TokenizerError(message: String, val position: Int) : RuntimeException(message)

private val TWO_CHAR_OPS: Map<String, TokenType> = mapOf(
    "<<" to TokenType.LeftShift,
    ">>" to TokenType.RightShift,
    "<=" to TokenType.LessEqual,
    ">=" to TokenType.GreaterEqual,
    "==" to TokenType.EqualEqual,
    "!=" to TokenType.BangEqual,
    "&&" to TokenType.AmpAmp,
    "||" to TokenType.PipePipe,
    "+=" to TokenType.PlusEquals,
    "-=" to TokenType.MinusEquals,
    "*=" to TokenType.StarEquals,
    "/=" to TokenType.SlashEquals,
)

private val ONE_CHAR_OPS: Map<Char, TokenType> = mapOf(
    '(' to TokenType.LParen,
    ')' to TokenType.RParen,
    ',' to TokenType.Comma,
    ':' to TokenType.Colon,
    '?' to TokenType.Question,
    '+' to TokenType.Plus,
    '-' to TokenType.Minus,
    '*' to TokenType.Star,
    '/' to TokenType.Slash,
    '%' to TokenType.Percent,
    '&' to TokenType.Ampersand,
    '|' to TokenType.Pipe,
    '^' to TokenType.Caret,
    '~' to TokenType.Tilde,
    '!' to TokenType.Bang,
    '<' to TokenType.LessThan,
    '>' to TokenType.GreaterThan,
    '=' to TokenType.Equals,
)

// Char? helpers mirror JS where `source[pos]` past the end yields `undefined`
// (all classification predicates then return false).
private fun isWhitespace(ch: Char?): Boolean =
    ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C' || ch == '\u000B'

private fun isDigit(ch: Char?): Boolean = ch != null && ch in '0'..'9'

private fun isHexDigit(ch: Char?): Boolean =
    ch != null && (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F')

private fun isIdentStart(ch: Char?): Boolean =
    ch != null && (ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_')

private fun isIdentChar(ch: Char?): Boolean = isIdentStart(ch) || isDigit(ch)

/** Tokenize a C-expression string into a list of [Token]s. */
fun tokenize(source: String): List<Token> {
    val tokens = ArrayList<Token>()
    var pos = 0

    while (pos < source.length) {
        // Skip whitespace
        if (isWhitespace(source[pos])) {
            pos++
            continue
        }

        // Skip block comments /* ... */
        if (source[pos] == '/' && pos + 1 < source.length && source[pos + 1] == '*') {
            pos += 2
            while (pos + 1 < source.length && !(source[pos] == '*' && source[pos + 1] == '/')) {
                pos++
            }
            if (pos + 1 >= source.length) {
                throw TokenizerError("Unterminated comment", pos)
            }
            pos += 2 // skip */
            continue
        }

        val start = pos

        // Numbers: hex, octal, decimal integers, doubles, scientific notation
        if (isDigit(source[pos]) ||
            (source[pos] == '.' && pos + 1 < source.length && isDigit(source[pos + 1]))
        ) {
            val tok = readNumber(source, pos)
            tokens.add(tok)
            pos = start + tok.value.length
            continue
        }

        // Identifiers: [a-zA-Z_][a-zA-Z0-9_]*
        if (isIdentStart(source[pos])) {
            while (pos < source.length && isIdentChar(source[pos])) {
                pos++
            }
            tokens.add(Token(TokenType.Identifier, source.substring(start, pos), start))
            continue
        }

        // Two-character operators (check before single-char)
        if (pos + 1 < source.length) {
            val two = source.substring(pos, pos + 2)
            val twoType = TWO_CHAR_OPS[two]
            if (twoType != null) {
                tokens.add(Token(twoType, two, start))
                pos += 2
                continue
            }
        }

        // Single-character operators / punctuation
        val oneType = ONE_CHAR_OPS[source[pos]]
        if (oneType != null) {
            tokens.add(Token(oneType, source[pos].toString(), start))
            pos++
            continue
        }

        // Unknown character — skip (matching c.l behavior of ignoring bad characters)
        pos++
    }

    tokens.add(Token(TokenType.EOF, "", pos))
    return tokens
}

private fun readNumber(source: String, posIn: Int): Token {
    val start = posIn
    var pos = posIn

    // Hex: 0x or 0X
    if (source[pos] == '0' && pos + 1 < source.length && (source[pos + 1] == 'x' || source[pos + 1] == 'X')) {
        pos += 2
        while (pos < source.length && isHexDigit(source[pos])) {
            pos++
        }
        return Token(TokenType.Integer, source.substring(start, pos), start)
    }

    // Leading digits (could be integer, double, or scientific)
    val hasLeadingDigits = isDigit(source[pos])
    if (hasLeadingDigits) {
        while (pos < source.length && isDigit(source[pos])) {
            pos++
        }
    }

    // Decimal point
    val hasDot = pos < source.length && source[pos] == '.'
    if (hasDot) {
        pos++
        while (pos < source.length && isDigit(source[pos])) {
            pos++
        }
    }

    // Exponent
    if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
        pos++
        if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) {
            pos++
        }
        while (pos < source.length && isDigit(source[pos])) {
            pos++
        }
        return Token(TokenType.DoubleE, source.substring(start, pos), start)
    }

    if (hasDot) {
        return Token(TokenType.Double, source.substring(start, pos), start)
    }

    // Pure integer (including octal like 0377 — the parser handles interpretation)
    return Token(TokenType.Integer, source.substring(start, pos), start)
}
