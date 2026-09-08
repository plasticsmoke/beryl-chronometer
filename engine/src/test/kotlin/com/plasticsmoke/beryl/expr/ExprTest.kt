package com.plasticsmoke.beryl.expr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Kotlin port of chronometer-web `src/expr/__tests__/expr.test.ts`.
 * These cases are the correctness oracle for the expression engine: any behavioral
 * divergence from the TypeScript port should surface here.
 */
class ExprTest {

    private fun freshEnv(): Environment = createDefaultEnvironment()

    private fun nonEof(src: String): List<Token> = tokenize(src).filter { it.type != TokenType.EOF }

    // ========================================================================
    // Tokenizer
    // ========================================================================

    @Test fun simpleInteger() {
        val tokens = tokenize("42")
        assertEquals(TokenType.Integer, tokens[0].type)
        assertEquals("42", tokens[0].value)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test fun hexInteger() {
        val tokens = tokenize("0xff00c0ac")
        assertEquals(TokenType.Integer, tokens[0].type)
        assertEquals("0xff00c0ac", tokens[0].value)
    }

    @Test fun octalInteger() {
        val tokens = tokenize("0377")
        assertEquals(TokenType.Integer, tokens[0].type)
        assertEquals("0377", tokens[0].value)
    }

    @Test fun doubleLeadingDot() {
        val tokens = tokenize(".5")
        assertEquals(TokenType.Double, tokens[0].type)
        assertEquals(".5", tokens[0].value)
    }

    @Test fun doubleTrailingDot() {
        val tokens = tokenize("2.")
        assertEquals(TokenType.Double, tokens[0].type)
        assertEquals("2.", tokens[0].value)
    }

    @Test fun doubleBothSides() {
        val tokens = tokenize("3.14")
        assertEquals(TokenType.Double, tokens[0].type)
        assertEquals("3.14", tokens[0].value)
    }

    @Test fun scientificNotation() {
        val tokens = tokenize("1e10")
        assertEquals(TokenType.DoubleE, tokens[0].type)
        assertEquals("1e10", tokens[0].value)
    }

    @Test fun scientificWithSignAndDecimal() {
        val tokens = tokenize("3.14e-2")
        assertEquals(TokenType.DoubleE, tokens[0].type)
        assertEquals("3.14e-2", tokens[0].value)
    }

    @Test fun identifier() {
        val tokens = tokenize("myVar_123")
        assertEquals(TokenType.Identifier, tokens[0].type)
        assertEquals("myVar_123", tokens[0].value)
    }

    @Test fun allTwoCharOps() {
        val ops = listOf("<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=")
        for (op in ops) assertEquals(op, tokenize(op)[0].value)
    }

    @Test fun allSingleCharOps() {
        val ops = listOf("(", ")", ",", ":", "?", "+", "-", "*", "/", "%", "&", "|", "^", "~", "!", "<", ">", "=")
        for (op in ops) assertEquals(op, tokenize(op)[0].value)
    }

    @Test fun skipsWhitespace() {
        assertEquals(3, nonEof("  a  + b  ").size)
    }

    @Test fun skipsBlockComments() {
        val toks = nonEof("a /* comment */ + b")
        assertEquals(3, toks.size)
        assertEquals("a", toks[0].value)
        assertEquals("+", toks[1].value)
        assertEquals("b", toks[2].value)
    }

    @Test fun throwsOnUnterminatedComment() {
        assertFailsWith<TokenizerError> { tokenize("a /* unterminated") }
    }

    @Test fun complexHaleakalaInitTokenizes() {
        val src = "r=143, ri=r-5, th=26, bx=r*cos(th*pi/180), by=r*sin(th*pi/180)"
        val tokens = tokenize(src)
        assertEquals(TokenType.EOF, tokens.last().type)
        assertEquals("r", tokens[0].value)
        assertEquals("=", tokens[1].value)
        assertEquals("143", tokens[2].value)
    }

    @Test fun positionsTracked() {
        val tokens = tokenize("ab + cd")
        assertEquals(0, tokens[0].position)
        assertEquals(3, tokens[1].position)
        assertEquals(5, tokens[2].position)
    }

    // ========================================================================
    // Parser
    // ========================================================================

    @Test fun parseNumberLiteral() {
        assertEquals(NumberLiteral(42.0), parse("42"))
    }

    @Test fun parseHexNumberLiteral() {
        assertEquals(NumberLiteral(255.0), parse("0xff"))
    }

    @Test fun parseIdentifier() {
        assertEquals(Identifier("pi"), parse("pi"))
    }

    @Test fun parseSimpleAddition() {
        assertEquals(BinaryOp("+", NumberLiteral(1.0), NumberLiteral(2.0)), parse("1 + 2"))
    }

    @Test fun parsePrecedenceMulBeforeAdd() {
        assertEquals(
            BinaryOp("+", NumberLiteral(1.0), BinaryOp("*", NumberLiteral(2.0), NumberLiteral(3.0))),
            parse("1 + 2 * 3"),
        )
    }

    @Test fun parseParensOverridePrecedence() {
        val ast = parse("(1 + 2) * 3")
        assertTrue(ast is BinaryOp && ast.operator == "*")
    }

    @Test fun parseUnaryMinus() {
        assertEquals(UnaryOp("-", Identifier("x")), parse("-x"))
    }

    @Test fun parseFunctionCallNoArgs() {
        assertEquals(FunctionCall("hour24Number", emptyList()), parse("hour24Number()"))
    }

    @Test fun parseFunctionCallWithArgs() {
        val ast = parse("atan2(y, x)")
        assertTrue(ast is FunctionCall && ast.name == "atan2" && ast.args.size == 2)
    }

    @Test fun parseTernary() {
        assertTrue(parse("a ? b : c") is Ternary)
    }

    @Test fun parseAssignment() {
        assertEquals(Assignment("x", "=", NumberLiteral(5.0)), parse("x = 5"))
    }

    @Test fun parseCommaList() {
        val ast = parse("a = 1, b = 2, c = 3")
        assertTrue(ast is ExpressionList && ast.expressions.size == 3)
    }

    @Test fun parseNestedTernary() {
        val ast = parse("a ? b : c ? d : e")
        assertTrue(ast is Ternary && ast.alternate is Ternary)
    }

    @Test fun parseComparison() {
        val ast = parse("a >= 12")
        assertTrue(ast is BinaryOp && ast.operator == ">=")
    }

    @Test fun parseLogicalPrecedence() {
        val ast = parse("a && b || c")
        assertTrue(ast is BinaryOp && ast.operator == "||")
    }

    @Test fun throwsOnUnexpectedToken() {
        assertFailsWith<ParseError> { parse("") }
    }

    @Test fun throwsOnTrailingGarbage() {
        assertFailsWith<ParseError> { parse("1 2") }
    }

    @Test fun parseComplexInit() {
        val src = "r=143, ri=r-5, th=26, bx=r*cos(th*pi/180), by=r*sin(th*pi/180), dr=8, mx=dr*cos(th*pi/180), my=dr*sin(th*pi/180)"
        assertTrue(parse(src) is ExpressionList)
    }

    @Test fun parseRealTernary() {
        assertTrue(parse("hour24Number() >= 12 ? 0 : pi") is Ternary)
    }

    @Test fun parseNestedFunctionArithmetic() {
        assertTrue(parse("fmod((dayNumber()+1), 10)*2*pi/10") is BinaryOp)
    }

    @Test fun parseCompoundAction() {
        assertTrue(parse("manualSet() ? (tick(), stemIn()) : (tock(), stemOut())") is Ternary)
    }

    // ========================================================================
    // Evaluator
    // ========================================================================

    private fun ev(src: String, env: Environment = freshEnv()): Double = evaluateExpression(src, env)

    @Test fun evalIntegerLiteral() = assertEquals(42.0, ev("42"))

    @Test fun evalDoubleLiteral() = assertEquals(3.14, ev("3.14"), 1e-9)

    @Test fun evalHexColorConstant() = assertEquals(4278190080.0, ev("0xff000000"))

    @Test fun evalScientific() = assertEquals(1000.0, ev("1e3"))

    // iOS rejects leading-zero constants (EBVirtualMachine.m:601-615) — so do we.
    @Test fun evalOctal() {
        assertFailsWith<ParseError> { ev("0377") }
    }

    @Test fun evalPi() = assertEquals(PI, ev("pi"))

    @Test fun evalBasicArithmetic() {
        assertEquals(5.0, ev("2 + 3"))
        assertEquals(6.0, ev("10 - 4"))
        assertEquals(21.0, ev("3 * 7"))
        assertEquals(3.75, ev("15 / 4"))
        assertEquals(2.0, ev("17 % 5"))
    }

    @Test fun evalPrecedence() {
        assertEquals(14.0, ev("2 + 3 * 4"))
        assertEquals(20.0, ev("(2 + 3) * 4"))
    }

    @Test fun evalUnaryMinus() = assertEquals(-5.0, ev("-5"))

    @Test fun evalUnaryNot() {
        assertEquals(1.0, ev("!0"))
        assertEquals(0.0, ev("!1"))
        assertEquals(0.0, ev("!42"))
    }

    @Test fun evalComparisons() {
        assertEquals(1.0, ev("3 < 5"))
        assertEquals(0.0, ev("5 < 3"))
        assertEquals(1.0, ev("3 <= 3"))
        assertEquals(0.0, ev("3 > 5"))
        assertEquals(1.0, ev("5 >= 5"))
        assertEquals(1.0, ev("3 == 3"))
        assertEquals(1.0, ev("3 != 4"))
    }

    // iOS && / || return coerced 0/1 and test truth as > 0.5 (EBVirtualMachineOps.m:99-122).
    @Test fun evalLogicalShortCircuit() {
        assertEquals(1.0, ev("1 && 2"))
        assertEquals(0.0, ev("0 && 2"))
        assertEquals(1.0, ev("0 || 3"))
        assertEquals(1.0, ev("1 || 3"))
        assertEquals(0.0, ev("0.5 && 1"))   // (0, 0.5] is FALSE on iOS
        assertEquals(0.0, ev("-1 || 0"))    // negatives are FALSE on iOS
    }

    @Test fun evalBitwise() {
        assertEquals(1.0, ev("5 & 3"))
        assertEquals(7.0, ev("5 | 3"))
        assertEquals(6.0, ev("5 ^ 3"))
    }

    @Test fun evalShifts() {
        assertEquals(16.0, ev("1 << 4"))
        assertEquals(4.0, ev("16 >> 2"))
    }

    @Test fun evalTernary() {
        assertEquals(10.0, ev("1 ? 10 : 20"))
        assertEquals(20.0, ev("0 ? 10 : 20"))
    }

    @Test fun evalAssignmentAndRetrieval() {
        val env = freshEnv()
        ev("x = 42", env)
        assertEquals(42.0, env.variables["x"])
        assertEquals(42.0, ev("x", env))
    }

    @Test fun evalCompoundAssignment() {
        val env = freshEnv()
        ev("x = 10", env)
        ev("x += 5", env); assertEquals(15.0, env.variables["x"])
        ev("x -= 3", env); assertEquals(12.0, env.variables["x"])
        ev("x *= 2", env); assertEquals(24.0, env.variables["x"])
        ev("x /= 4", env); assertEquals(6.0, env.variables["x"])
    }

    @Test fun evalAssignmentChainInList() {
        val env = freshEnv()
        ev("a = 1, b = 2, c = a + b", env)
        assertEquals(1.0, env.variables["a"])
        assertEquals(2.0, env.variables["b"])
        assertEquals(3.0, env.variables["c"])
    }

    @Test fun evalSin() {
        assertEquals(0.0, ev("sin(0)"))
        assertEquals(1.0, ev("sin(pi/2)"), 1e-9)
    }

    @Test fun evalCos() {
        assertEquals(1.0, ev("cos(0)"))
        assertEquals(-1.0, ev("cos(pi)"), 1e-9)
    }

    @Test fun evalAtan2() = assertEquals(PI / 2, ev("atan2(1, 0)"), 1e-9)

    @Test fun evalSqrt() = assertEquals(2.0, ev("sqrt(4)"))

    @Test fun evalFloorCeil() {
        assertEquals(3.0, ev("floor(3.7)"))
        assertEquals(4.0, ev("ceil(3.2)"))
    }

    // iOS fmod is EC_fmod: FLOORED modulus (ECGlobals.m:135-139), not C fmod.
    @Test fun evalFmod() {
        assertEquals(1.0, ev("fmod(7, 3)"), 1e-9)
        assertEquals(2.0, ev("fmod(-7, 3)"), 1e-9)
    }

    @Test fun evalPow() = assertEquals(1024.0, ev("pow(2, 10)"))

    @Test fun evalNestedFunctions() = assertEquals(0.0, ev("abs(sin(pi))"), 1e-9)

    @Test fun evalUndefinedVariableThrows() {
        assertFailsWith<EvalError> { ev("undefinedVar") }
    }

    @Test fun evalUndefinedFunctionThrows() {
        assertFailsWith<EvalError> { ev("undefinedFunc()") }
    }

    @Test fun evalCustomFunction() {
        val env = freshEnv()
        env.functions["double"] = { x -> x[0] * 2 }
        assertEquals(42.0, ev("double(21)", env))
    }

    @Test fun evalColorConstants() {
        assertEquals(4278190080.0, ev("black"))    // 0xFF000000
        assertEquals(4294967295.0, ev("white"))    // 0xFFFFFFFF
        assertEquals(0.0, ev("clear"))
    }

    // ========================================================================
    // Integration: real watch XML expressions
    // ========================================================================

    @Test fun haleakalaInitBlock1() {
        val env = freshEnv()
        evaluateInit("hairline=0.25, nMoons=16, nightBg=black, azR=130, mainR=118, altR=79", env)
        assertEquals(0.25, env.variables["hairline"])
        assertEquals(16.0, env.variables["nMoons"])
        assertEquals(4278190080.0, env.variables["nightBg"])
        assertEquals(130.0, env.variables["azR"])
        assertEquals(118.0, env.variables["mainR"])
        assertEquals(79.0, env.variables["altR"])
    }

    @Test fun haleakalaCrossReferences() {
        val env = freshEnv()
        evaluateInit("riseX=-40, setX=-riseX, riseSetY=22, riseSetRadius=27", env)
        assertEquals(-40.0, env.variables["riseX"])
        assertEquals(40.0, env.variables["setX"])
        assertEquals(22.0, env.variables["riseSetY"])
        assertEquals(27.0, env.variables["riseSetRadius"])
    }

    @Test fun haleakalaTrig() {
        val env = freshEnv()
        evaluateInit("r=143, ri=r-5, th=26, bx=r*cos(th*pi/180), by=r*sin(th*pi/180), dr=8, mx=dr*cos(th*pi/180), my=dr*sin(th*pi/180)", env)
        assertEquals(143.0, env.variables["r"])
        assertEquals(138.0, env.variables["ri"])
        assertEquals(26.0, env.variables["th"])
        assertEquals(143 * cos(26 * PI / 180), env.variables["bx"]!!, 1e-5)
        assertEquals(143 * sin(26 * PI / 180), env.variables["by"]!!, 1e-5)
    }

    @Test fun genevaChainedReferences() {
        val env = freshEnv()
        evaluateInit("latitudeY=58, longitudeY=-latitudeY, latlongradius=20, errRadius=23, actRadius=8", env)
        assertEquals(58.0, env.variables["latitudeY"])
        assertEquals(-58.0, env.variables["longitudeY"])
        assertEquals(20.0, env.variables["latlongradius"])
    }

    @Test fun genevaComplexChains() {
        val env = freshEnv()
        evaluateInit("latlongradius=20, firstLatX=-latlongradius*3-6, secondLatX=-latlongradius-5, thirdLatX=-secondLatX, fourthLatX=-firstLatX", env)
        assertEquals(-66.0, env.variables["firstLatX"])
        assertEquals(-25.0, env.variables["secondLatX"])
        assertEquals(25.0, env.variables["thirdLatX"])
        assertEquals(66.0, env.variables["fourthLatX"])
    }

    @Test fun genevaHexColors() {
        val env = freshEnv()
        evaluateInit("frontBg=0xffb0b0b0, backBg=0xff808080, dials=white, dialmarks=black", env)
        assertEquals(0xffb0b0b0L.toDouble(), env.variables["frontBg"])
        assertEquals(0xff808080L.toDouble(), env.variables["backBg"])
        assertEquals(4294967295.0, env.variables["dials"])
        assertEquals(4278190080.0, env.variables["dialmarks"])
    }

    @Test fun ternaryWithFunctionPM() {
        val env = freshEnv()
        env.functions["hour24Number"] = { 14.0 }
        assertEquals(0.0, ev("hour24Number() >= 12 ? 0 : pi", env))
    }

    @Test fun ternaryWithFunctionAM() {
        val env = freshEnv()
        env.functions["hour24Number"] = { 8.0 }
        assertEquals(PI, ev("hour24Number() >= 12 ? 0 : pi", env))
    }

    @Test fun complexButtonTernary() {
        val env = freshEnv()
        env.functions["timeIsCorrect"] = { 0.0 }
        env.functions["manualSet"] = { 1.0 }
        env.functions["runningDemo"] = { 0.0 }
        assertEquals(1.0, ev("(!timeIsCorrect()) || manualSet() ? (runningDemo() == 1 ? 0 : 1) : 0", env))
    }

    @Test fun fmodFromSWheelAngle() {
        val env = freshEnv()
        env.functions["dayNumber"] = { 14.0 }
        assertEquals(PI, ev("fmod((dayNumber()+1), 10)*2*pi/10", env), 1e-5)
    }

    @Test fun nestedFmodWithFloor() {
        val env = freshEnv()
        env.functions["dayNumber"] = { 14.0 }
        assertEquals(PI / 5, ev("fmod(floor((dayNumber()+1)/10),10)*2*pi/10", env), 1e-5)
    }

    @Test fun genevaSecondInitBlock() {
        val env = freshEnv()
        evaluateInit("cr=136, cr2=114, gm=90, gw=.2, gc1=black, rs=36, ms=25, mainClrGold=0xff706040, mainClr=clear, subClr=0xff202000, innerBg=0xffc0c0c0, subBg=0xffe7e7e7", env)
        assertEquals(136.0, env.variables["cr"])
        assertEquals(0.2, env.variables["gw"]!!, 1e-9)
        assertEquals(4278190080.0, env.variables["gc1"])
        assertEquals(0.0, env.variables["mainClr"])
    }

    @Test fun timezoneOffsetComparison() {
        val env = freshEnv()
        env.functions["hour24Number"] = { 14.0 }
        env.functions["tzOffset"] = { -28800.0 }
        assertEquals(PI * 5 / 4, ev("hour24Number()-tzOffset()/3600>=24 ? pi*3/4 : pi*5/4", env), 1e-5)
    }

    @Test fun multipleInitBlocksSequential() {
        val env = freshEnv()
        evaluateInit("azR=130, mainR=118, altR=79", env)
        evaluateInit("riseX=-40, setX=-riseX, riseSetY=22, riseSetRadius=27, rsampmX=69", env)
        evaluateInit("dateY=-51, firstDateX=-14, monthRadius=86, monthX=-monthRadius+firstDateX+36, weekdayRadius=95", env)
        assertEquals(130.0, env.variables["azR"])
        assertEquals(40.0, env.variables["setX"])
        assertEquals(-64.0, env.variables["monthX"])
        assertEquals(95.0, env.variables["weekdayRadius"])
    }
}
