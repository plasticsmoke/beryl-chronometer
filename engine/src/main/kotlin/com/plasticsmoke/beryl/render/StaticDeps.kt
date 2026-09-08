package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.expr.ASTNode
import com.plasticsmoke.beryl.expr.Assignment
import com.plasticsmoke.beryl.expr.BinaryOp
import com.plasticsmoke.beryl.expr.ExpressionList
import com.plasticsmoke.beryl.expr.FunctionCall
import com.plasticsmoke.beryl.expr.Identifier
import com.plasticsmoke.beryl.expr.Ternary
import com.plasticsmoke.beryl.expr.UnaryOp
import com.plasticsmoke.beryl.watch.ButtonPart
import com.plasticsmoke.beryl.watch.ImagePart
import com.plasticsmoke.beryl.watch.QDialPart
import com.plasticsmoke.beryl.watch.QRectPart
import com.plasticsmoke.beryl.watch.QTextPart
import com.plasticsmoke.beryl.watch.StaticPart
import com.plasticsmoke.beryl.watch.Watch
import com.plasticsmoke.beryl.watch.WatchPart
import com.plasticsmoke.beryl.watch.WindowPart

/**
 * Static-content dependency analysis, backing the app's selective cache invalidation.
 *
 * iOS never re-rasterizes anything on a button act — every part is an independent GL texture.
 * Our static-run baking made every act pay a full rebake "just in case" a static part read a
 * changed variable, and those 30-80 ms rebake frames tore holes in concurrent hand sweeps.
 * [staticVarRefs] lists the variable names the BAKED content actually reads, so the app can
 * skip the rebake when an action's changes don't touch any of them (Terra's ring pusher:
 * topRingSlot is read only by dynamic parts).
 */

private fun collectAstNames(node: ASTNode?, out: MutableSet<String>, functions: MutableSet<String>?) {
    when (node) {
        null -> {}
        is Identifier -> out.add(node.name)
        is Assignment -> { out.add(node.name); collectAstNames(node.value, out, functions) }
        is UnaryOp -> collectAstNames(node.operand, out, functions)
        is BinaryOp -> { collectAstNames(node.left, out, functions); collectAstNames(node.right, out, functions) }
        is Ternary -> {
            collectAstNames(node.condition, out, functions)
            collectAstNames(node.consequent, out, functions)
            collectAstNames(node.alternate, out, functions)
        }
        is FunctionCall -> {
            functions?.add(node.name)
            for (a in node.args) collectAstNames(a, out, functions)
        }
        is ExpressionList -> for (e in node.expressions) collectAstNames(e, out, functions)
        else -> {}
    }
}

/** Every ASTNode reachable from [part]'s fields (reflection: parts are flat data classes). */
private fun collectPartRefs(part: WatchPart, vars: MutableSet<String>, functions: MutableSet<String>) {
    for (f in part.javaClass.declaredFields) {
        f.isAccessible = true
        when (val v = f.get(part)) {
            is ASTNode -> collectAstNames(v, vars, functions)
            is List<*> -> for (e in v) {
                if (e is ASTNode) collectAstNames(e, vars, functions)
                if (e is WatchPart) collectPartRefs(e, vars, functions)
            }
        }
    }
}

/** True for parts whose pixels end up inside baked static-run sprites (must match the
 *  isStaticPart partition in WatchRenderer, plus windows, which bake with their cluster). */
private fun bakesStatic(part: WatchPart): Boolean = when (part) {
    is StaticPart, is QDialPart, is ImagePart, is QRectPart, is QTextPart, is WindowPart -> true
    else -> false
}

/**
 * Variable names read (or assigned) by any part that bakes into a static sprite. A `<static>`
 * block bakes ALL its children wholesale, so their refs count regardless of type.
 */
fun staticVarRefs(watch: Watch): Set<String> {
    val vars = HashSet<String>()
    collectStaticRefs(watch, vars, HashSet())
    return vars
}

private fun collectStaticRefs(watch: Watch, vars: MutableSet<String>, functions: MutableSet<String>) {
    fun walk(p: WatchPart, forced: Boolean) {
        if (p is StaticPart) {
            for (c in p.children) walk(c, true)
            return
        }
        if (forced || bakesStatic(p)) collectPartRefs(p, vars, functions)
    }
    for (p in watch.parts) walk(p, false)
}

/** Function leaves whose value cannot change between two acts: pure math. Anything else
 *  (time/stem/alarm leaves, fetchPersistentValue) read by baked content makes the statics
 *  volatile under non-pure actions. */
private val STATIC_SAFE_FUNCS = setOf(
    "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "floor", "ceil", "round", "abs",
    "min", "max", "fmod", "log", "exp", "sqrt", "pow", "pi",
)

/**
 * Non-math function leaves the baked static content reads. Empty — true for every shipped
 * face — means a button act can never change what the statics draw unless it assigns one of
 * [staticVarRefs]: iOS-faithful (parts are independent textures; acts never re-rasterize),
 * and what lets a repeating advanceDay()/advanceHour() run at the full iOS cadence instead
 * of paying a 30-80 ms rebake per act.
 */
fun staticVolatileFuncRefs(watch: Watch): Set<String> {
    val functions = HashSet<String>()
    collectStaticRefs(watch, HashSet(), functions)
    return functions - STATIC_SAFE_FUNCS
}

/**
 * Buttons whose pixels live inside a `<static>` block (baked wholesale): pressing one still
 * needs a cache invalidation for its depress art to show — top-level buttons render live.
 */
fun buttonsBakedInStatics(watch: Watch): Set<ButtonPart> {
    val out = HashSet<ButtonPart>()
    fun walk(p: WatchPart) {
        if (p is StaticPart) for (c in p.children) {
            if (c is ButtonPart) out.add(c) else walk(c)
        }
    }
    for (p in watch.parts) walk(p)
    return out
}

/** Side-effect leaves that cannot change what any static part draws. */
private val PURE_ACTION_LEAVES = setOf(
    "storePersistentValue", "fetchPersistentValue", "saveBody", "thisButtonPressed",
    "floor", "ceil", "round", "abs", "min", "max", "fmod",
)

/**
 * True when [action]'s only function calls are [PURE_ACTION_LEAVES] — its effects are then
 * fully described by the variables it assigns, so the caller can compare those against
 * [staticVarRefs]. Actions calling anything else (stem/time/alarm leaves) may flip values
 * that static parts read through FUNCTIONS (alarmEnabled(), manualSet()); callers must
 * invalidate for those, as before.
 */
fun actionAffectsOnlyVariables(action: ASTNode): Boolean {
    val vars = HashSet<String>()
    val functions = HashSet<String>()
    collectAstNames(action, vars, functions)
    return PURE_ACTION_LEAVES.containsAll(functions)
}
