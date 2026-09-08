package com.plasticsmoke.beryl.expr

import java.math.BigInteger

/**
 * Recursive-descent parser for C-like expressions used in Chronometer watch XML files.
 *
 * Faithful Kotlin port of chronometer-web `src/expr/parser.ts`. The grammar matches the
 * yacc specification in `Parser/c.y` of the original Chronometer source. Produces an AST
 * from a token list.
 */

sealed interface ASTNode

data class NumberLiteral(val value: Double) : ASTNode
data class Identifier(val name: String) : ASTNode
data class UnaryOp(val operator: String, val operand: ASTNode) : ASTNode
data class BinaryOp(val operator: String, val left: ASTNode, val right: ASTNode) : ASTNode
data class Ternary(val condition: ASTNode, val consequent: ASTNode, val alternate: ASTNode) : ASTNode
data class Assignment(val name: String, val operator: String, val value: ASTNode) : ASTNode
data class FunctionCall(val name: String, val args: List<ASTNode>) : ASTNode
data class ExpressionList(val expressions: List<ASTNode>) : ASTNode

class ParseError(message: String, val position: Int) : RuntimeException(message)

/** True when evaluating [node] can mutate environment variables (contains an assignment).
 *  Such expressions are eval-count-sensitive: each evaluation advances state. */
fun hasAssignment(node: ASTNode): Boolean = when (node) {
    is Assignment -> true
    is UnaryOp -> hasAssignment(node.operand)
    is BinaryOp -> hasAssignment(node.left) || hasAssignment(node.right)
    is Ternary -> hasAssignment(node.condition) || hasAssignment(node.consequent) || hasAssignment(node.alternate)
    is FunctionCall -> node.args.any { hasAssignment(it) }
    is ExpressionList -> node.expressions.any { hasAssignment(it) }
    else -> false
}

/** Parse a C-expression string into an AST. */
fun parse(source: String): ASTNode {
    val tokens = tokenize(source)
    val parser = Parser(tokens)
    val result = parser.parseExpression()
    parser.expect(TokenType.EOF)
    return result
}

class Parser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token = tokens[pos++]

    fun expect(type: TokenType): Token {
        val tok = peek()
        if (tok.type != type) {
            throw ParseError("Expected $type but got ${tok.type} ('${tok.value}')", tok.position)
        }
        return advance()
    }

    private fun match(type: TokenType, value: String? = null): Boolean {
        val tok = peek()
        if (tok.type != type) return false
        if (value != null && tok.value != value) return false
        return true
    }

    private fun save(): Int = pos
    private fun restore(saved: Int) {
        pos = saved
    }

    // ========================================================================
    // Grammar rules — following c.y precedence exactly
    // ========================================================================

    // expression → assignment_expression (',' assignment_expression)*
    fun parseExpression(): ASTNode {
        val first = parseAssignment()
        if (!match(TokenType.Comma)) {
            return first
        }
        val expressions = ArrayList<ASTNode>()
        expressions.add(first)
        while (match(TokenType.Comma)) {
            advance()
            expressions.add(parseAssignment())
        }
        return ExpressionList(expressions)
    }

    // assignment_expression → IDENTIFIER ('='|'+='|'-='|'*='|'/=') assignment_expression
    //                       | conditional_expression
    private fun parseAssignment(): ASTNode {
        if (match(TokenType.Identifier)) {
            val saved = save()
            val idTok = advance()
            val tok = peek()
            if (tok.type == TokenType.Equals ||
                tok.type == TokenType.PlusEquals ||
                tok.type == TokenType.MinusEquals ||
                tok.type == TokenType.StarEquals ||
                tok.type == TokenType.SlashEquals
            ) {
                val op = advance()
                val value = parseAssignment()
                return Assignment(idTok.value, op.value, value)
            }
            // Not an assignment — backtrack
            restore(saved)
        }
        return parseConditional()
    }

    // conditional_expression → logical_or ('?' expression ':' conditional_expression)?
    private fun parseConditional(): ASTNode {
        var node = parseLogicalOr()
        if (match(TokenType.Question)) {
            advance()
            val consequent = parseExpression()
            expect(TokenType.Colon)
            val alternate = parseConditional()
            node = Ternary(node, consequent, alternate)
        }
        return node
    }

    // logical_or → logical_and ('||' logical_and)*
    private fun parseLogicalOr(): ASTNode {
        var node = parseLogicalAnd()
        while (match(TokenType.PipePipe)) {
            advance()
            val right = parseLogicalAnd()
            node = BinaryOp("||", node, right)
        }
        return node
    }

    // logical_and → inclusive_or ('&&' inclusive_or)*
    private fun parseLogicalAnd(): ASTNode {
        var node = parseBitwiseOr()
        while (match(TokenType.AmpAmp)) {
            advance()
            val right = parseBitwiseOr()
            node = BinaryOp("&&", node, right)
        }
        return node
    }

    // inclusive_or → exclusive_or ('|' exclusive_or)*
    private fun parseBitwiseOr(): ASTNode {
        var node = parseBitwiseXor()
        while (match(TokenType.Pipe)) {
            advance()
            val right = parseBitwiseXor()
            node = BinaryOp("|", node, right)
        }
        return node
    }

    // exclusive_or → and_expression ('^' and_expression)*
    private fun parseBitwiseXor(): ASTNode {
        var node = parseBitwiseAnd()
        while (match(TokenType.Caret)) {
            advance()
            val right = parseBitwiseAnd()
            node = BinaryOp("^", node, right)
        }
        return node
    }

    // and_expression → equality ('&' equality)*
    private fun parseBitwiseAnd(): ASTNode {
        var node = parseEquality()
        while (match(TokenType.Ampersand)) {
            advance()
            val right = parseEquality()
            node = BinaryOp("&", node, right)
        }
        return node
    }

    // equality → relational (('=='|'!=') relational)*
    private fun parseEquality(): ASTNode {
        var node = parseRelational()
        while (match(TokenType.EqualEqual) || match(TokenType.BangEqual)) {
            val op = advance()
            val right = parseRelational()
            node = BinaryOp(op.value, node, right)
        }
        return node
    }

    // relational → shift (('<'|'>'|'<='|'>=') shift)*
    private fun parseRelational(): ASTNode {
        var node = parseShift()
        while (
            match(TokenType.LessThan) ||
            match(TokenType.GreaterThan) ||
            match(TokenType.LessEqual) ||
            match(TokenType.GreaterEqual)
        ) {
            val op = advance()
            val right = parseShift()
            node = BinaryOp(op.value, node, right)
        }
        return node
    }

    // shift → additive (('<<'|'>>') additive)*
    private fun parseShift(): ASTNode {
        var node = parseAdditive()
        while (match(TokenType.LeftShift) || match(TokenType.RightShift)) {
            val op = advance()
            val right = parseAdditive()
            node = BinaryOp(op.value, node, right)
        }
        return node
    }

    // additive → multiplicative (('+'|'-') multiplicative)*
    private fun parseAdditive(): ASTNode {
        var node = parseMultiplicative()
        while (match(TokenType.Plus) || match(TokenType.Minus)) {
            val op = advance()
            val right = parseMultiplicative()
            node = BinaryOp(op.value, node, right)
        }
        return node
    }

    // multiplicative → unary (('*'|'/'|'%') unary)*
    private fun parseMultiplicative(): ASTNode {
        var node = parseUnary()
        while (match(TokenType.Star) || match(TokenType.Slash) || match(TokenType.Percent)) {
            val op = advance()
            val right = parseUnary()
            node = BinaryOp(op.value, node, right)
        }
        return node
    }

    // unary → ('+'|'-'|'~'|'!') unary | postfix
    private fun parseUnary(): ASTNode {
        if (
            match(TokenType.Plus) ||
            match(TokenType.Minus) ||
            match(TokenType.Tilde) ||
            match(TokenType.Bang)
        ) {
            val op = advance()
            val operand = parseUnary()
            return UnaryOp(op.value, operand)
        }
        return parsePostfix()
    }

    // postfix → IDENTIFIER '(' argList? ')' | primary
    private fun parsePostfix(): ASTNode {
        if (match(TokenType.Identifier)) {
            val saved = save()
            val idTok = advance()
            if (match(TokenType.LParen)) {
                advance() // consume '('
                if (match(TokenType.RParen)) {
                    advance() // no args
                    return FunctionCall(idTok.value, emptyList())
                }
                val args = ArrayList<ASTNode>()
                args.add(parseAssignment())
                while (match(TokenType.Comma)) {
                    advance()
                    args.add(parseAssignment())
                }
                expect(TokenType.RParen)
                return FunctionCall(idTok.value, args)
            }
            // Not a function call — it's just an identifier
            restore(saved)
        }
        return parsePrimary()
    }

    // primary → NUMBER | IDENTIFIER | '(' expression ')'
    private fun parsePrimary(): ASTNode {
        val tok = peek()

        if (tok.type == TokenType.Integer || tok.type == TokenType.Double || tok.type == TokenType.DoubleE) {
            advance()
            return NumberLiteral(parseNumericLiteral(tok))
        }

        if (tok.type == TokenType.Identifier) {
            advance()
            return Identifier(tok.value)
        }

        if (tok.type == TokenType.LParen) {
            advance()
            val expr = parseExpression()
            expect(TokenType.RParen)
            return expr
        }

        throw ParseError("Unexpected token ${tok.type} ('${tok.value}')", tok.position)
    }
}

private fun parseNumericLiteral(tok: Token): Double {
    val s = tok.value

    if (tok.type == TokenType.Integer) {
        // Hex — parse as unsigned 32-bit (matches color constants like 0xffb0b0b0)
        if (s.startsWith("0x") || s.startsWith("0X")) {
            val v = BigInteger(s.substring(2), 16).and(BigInteger.valueOf(0xFFFFFFFFL))
            return v.toLong().toDouble()
        }
        // iOS rejects leading-zero constants outright ("octal constants not supported — use
        // hex or decimal", EBVirtualMachine.m:601-615); silently parsing base-8 would make a
        // typo like update='08' mean something different here than on the iPhone.
        if (s.length > 1 && s.startsWith("0")) {
            throw ParseError("octal constants not supported (use hex or decimal): $s", tok.position)
        }
        // Decimal integer
        return s.toDouble()
    }

    // Double or DoubleE
    return s.toDouble()
}
