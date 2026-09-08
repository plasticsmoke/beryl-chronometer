package com.plasticsmoke.beryl.expr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Tree-walking evaluator for C-like expressions from Chronometer watch XML files.
 *
 * All values are IEEE doubles, matching iOS's EBVirtualMachine. Integer/bitwise operators,
 * truthiness, and logical-op results follow the iOS ops (EBVirtualMachineOps.m): llrint
 * 64-bit coercion, `> 0.5` truth tests, and coerced 0/1 logical results.
 */

/** Watch/math function: variadic doubles in, one double out. */
typealias ExprFunction = (DoubleArray) -> Double

class Environment(
    /** Mutable variable bindings. */
    val variables: MutableMap<String, Double> = HashMap(),
    /** Function bindings (math builtins + watch-specific functions). */
    val functions: MutableMap<String, ExprFunction> = HashMap(),
    /** Kyoto hand mode: 0 = moving hand (default), 1 = fixed hand at top. */
    var kyHandMode: Int = 0,
    /** Observer latitude in radians (set by watch-env, used by sentinel scheduling). */
    var observerLatRad: Double? = null,
    /** Observer longitude in radians. */
    var observerLonRad: Double? = null,
    /** Timezone offset in seconds, east-positive. */
    var tzOffsetSec: Double? = null,
    /** Display-time source in epoch milliseconds (set by watch-env). */
    var getNow: (() -> Long)? = null,
) {
    /** Name of the button currently held down (iOS ChronometerAppDelegate `buttonPressed`). */
    var pressedButton: String? = null

    /**
     * Name of the button whose expressions are being evaluated right now — set by the renderer
     * around a button's attribute evals and by action execution (iOS `partBeingEvaluated`).
     * `thisButtonPressed()` is 1 only when this matches [pressedButton].
     */
    var evaluatingButton: String? = null
}

class EvalError(message: String) : RuntimeException(message)

/** iOS truthiness (EBVirtualMachineOps.m:87-122): questionColon/logicalAnd/logicalOr all
 *  test `> 0.5` — negative values and (0, 0.5] are FALSE. (Was JS `!= 0`.) */
private fun truthy(d: Double): Boolean = d > 0.5

/** iOS integer coercion (EBVirtualMachineOps.h:5): llrint — round to NEAREST (ties to even)
 *  into a 64-bit integer. (Was JS ToInt32: truncate to 32 bits.) */
private fun toInt64(d: Double): Long {
    if (d.isNaN() || d.isInfinite()) return 0
    return Math.rint(d).toLong()
}

/** Create an environment pre-populated with math builtins and standard constants. */
fun createDefaultEnvironment(): Environment {
    val variables = HashMap<String, Double>()
    val functions = HashMap<String, ExprFunction>()

    // Standard constants
    variables["pi"] = PI
    variables["true"] = 1.0
    variables["false"] = 0.0

    // Color constants (stored as unsigned 32-bit AARRGGBB values)
    variables["black"] = 0xFF000000L.toDouble()
    variables["white"] = 0xFFFFFFFFL.toDouble()
    variables["red"] = 0xFFFF0000L.toDouble()
    variables["green"] = 0xFF00FF00L.toDouble()
    variables["blue"] = 0xFF0000FFL.toDouble()
    variables["clear"] = 0.0
    variables["yellow"] = 0xFFFFFF00L.toDouble()
    variables["cyan"] = 0xFF00FFFFL.toDouble()
    variables["magenta"] = 0xFFFF00FFL.toDouble()
    variables["darkGray"] = 0xFF555555L.toDouble()   // iOS [UIColor darkGrayColor] = 1/3
    variables["lightGray"] = 0xFFAAAAAAL.toDouble()  // iOS [UIColor lightGrayColor] = 2/3

    // Planet number constants (matching ECPlanetNumber enum)
    variables["Sun"] = 0.0
    variables["Moon"] = 1.0
    variables["Mercury"] = 2.0
    variables["Venus"] = 3.0
    variables["Earth"] = 4.0
    variables["Mars"] = 5.0
    variables["Jupiter"] = 6.0
    variables["Saturn"] = 7.0
    variables["Uranus"] = 8.0
    variables["Neptune"] = 9.0
    variables["Pluto"] = 10.0
    // Legacy names (used in watch XML files)
    variables["planetSun"] = 0.0
    variables["planetMoon"] = 1.0

    // Math functions
    functions["sin"] = { a -> sin(a[0]) }
    functions["cos"] = { a -> cos(a[0]) }
    functions["tan"] = { a -> tan(a[0]) }
    functions["asin"] = { a -> asin(a[0]) }
    functions["acos"] = { a -> acos(a[0]) }
    functions["atan"] = { a -> atan(a[0]) }
    functions["atan2"] = { a -> atan2(a[0], a[1]) }
    functions["sqrt"] = { a -> sqrt(a[0]) }
    functions["abs"] = { a -> abs(a[0]) }
    functions["floor"] = { a -> floor(a[0]) }
    functions["ceil"] = { a -> ceil(a[0]) }
    // iOS ECVirtualMachineOps: log() is log10; ln() is the natural log. (Hernandez's log-scale
    // altitude gauge depends on log10 — natural log skews every tick and marker.)
    functions["log"] = { a -> log10(a[0]) }
    functions["ln"] = { a -> ln(a[0]) }
    functions["exp"] = { a -> exp(a[0]) }
    functions["pow"] = { a -> a[0].pow(a[1]) }
    functions["min"] = { a -> var r = Double.POSITIVE_INFINITY; for (v in a) r = min(r, v); r }
    functions["max"] = { a -> var r = Double.NEGATIVE_INFINITY; for (v in a) r = max(r, v); r }
    // iOS round() is llrint: round to NEAREST, ties to even (EBVirtualMachineOps.m:29-32).
    functions["round"] = { a -> Math.rint(a[0]) }
    // iOS fmod() is EC_fmod — FLOORED modulus, result takes the divisor's sign
    // (ECGlobals.m:135-139: arg1 - floor(arg1/arg2)*arg2), not C fmod.
    functions["fmod"] = { a -> a[0] - floor(a[0] / a[1]) * a[1] }
    // pi is both a variable and a zero-arg function on iOS (EBVM_OP0(pi)).
    functions["pi"] = { PI }

    return Environment(variables, functions, 0)
}

/** Evaluate an AST node in the given environment, returning a numeric result. */
fun evaluate(node: ASTNode, env: Environment): Double {
    return when (node) {
        is NumberLiteral -> node.value

        is Identifier -> env.variables[node.name]
            ?: throw EvalError("Undefined variable: ${node.name}")

        is UnaryOp -> {
            val operand = evaluate(node.operand, env)
            when (node.operator) {
                "+" -> operand
                "-" -> -operand
                "~" -> toInt64(operand).inv().toDouble()
                // iOS `!` is !llrint(arg) (EBVirtualMachineOps.m:65), not the >0.5 truthiness.
                "!" -> if (toInt64(operand) != 0L) 0.0 else 1.0
                else -> throw EvalError("Unknown unary operator: ${node.operator}")
            }
        }

        is BinaryOp -> evaluateBinaryOp(node.operator, node.left, node.right, env)

        is Ternary -> {
            val cond = evaluate(node.condition, env)
            if (truthy(cond)) evaluate(node.consequent, env) else evaluate(node.alternate, env)
        }

        is Assignment -> {
            val value = evaluate(node.value, env)
            val name = node.name
            when (node.operator) {
                "=" -> {
                    env.variables[name] = value
                    value
                }
                "+=" -> {
                    val result = (env.variables[name] ?: 0.0) + value
                    env.variables[name] = result
                    result
                }
                "-=" -> {
                    val result = (env.variables[name] ?: 0.0) - value
                    env.variables[name] = result
                    result
                }
                "*=" -> {
                    val result = (env.variables[name] ?: 0.0) * value
                    env.variables[name] = result
                    result
                }
                "/=" -> {
                    val result = (env.variables[name] ?: 0.0) / value
                    env.variables[name] = result
                    result
                }
                else -> throw EvalError("Unknown assignment operator: ${node.operator}")
            }
        }

        is FunctionCall -> {
            val fn = env.functions[node.name]
                ?: throw EvalError("Undefined function: ${node.name}")
            val args = DoubleArray(node.args.size) { evaluate(node.args[it], env) }
            fn(args)
        }

        is ExpressionList -> {
            var result = 0.0
            for (expr in node.expressions) {
                result = evaluate(expr, env)
            }
            result
        }
    }
}

private fun evaluateBinaryOp(operator: String, left: ASTNode, right: ASTNode, env: Environment): Double {
    // Short-circuit for logical operators
    // iOS && / || return COERCED 0.0/1.0, not the raw operand (EBVirtualMachineOps.m:99-122);
    // both short-circuit the right side like the iOS stream-skip.
    if (operator == "&&") {
        val l = evaluate(left, env)
        return if (truthy(l)) (if (truthy(evaluate(right, env))) 1.0 else 0.0) else 0.0
    }
    if (operator == "||") {
        val l = evaluate(left, env)
        return if (truthy(l)) 1.0 else (if (truthy(evaluate(right, env))) 1.0 else 0.0)
    }

    val l = evaluate(left, env)
    val r = evaluate(right, env)

    return when (operator) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "/" -> l / r
        // iOS % is llrint(l) % llrint(r) on 64-bit ints (EBVM_OP2 remainder).
        "%" -> if (toInt64(r) == 0L) Double.NaN else (toInt64(l) % toInt64(r)).toDouble()
        "<<" -> (toInt64(l) shl (toInt64(r).toInt() and 63)).toDouble()
        ">>" -> (toInt64(l) shr (toInt64(r).toInt() and 63)).toDouble()
        "<" -> if (l < r) 1.0 else 0.0
        ">" -> if (l > r) 1.0 else 0.0
        "<=" -> if (l <= r) 1.0 else 0.0
        ">=" -> if (l >= r) 1.0 else 0.0
        "==" -> if (l == r) 1.0 else 0.0
        "!=" -> if (l != r) 1.0 else 0.0
        "&" -> (toInt64(l) and toInt64(r)).toDouble()
        "^" -> (toInt64(l) xor toInt64(r)).toDouble()
        "|" -> (toInt64(l) or toInt64(r)).toDouble()
        else -> throw EvalError("Unknown binary operator: $operator")
    }
}

/** Parse and evaluate an expression string in one step. */
fun evaluateExpression(source: String, env: Environment): Double = evaluate(parse(source), env)

/**
 * Evaluate an `init expr` string — a comma-separated list of assignments.
 * The side effects (variable assignments) are the point; the return value
 * is the value of the last expression.
 */
fun evaluateInit(source: String, env: Environment): Double = evaluateExpression(source, env)
