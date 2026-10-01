package com.bitchat.canary

import kotlin.test.Test
import kotlin.test.assertEquals

// KT-88544: in each function below a local var starts as Placeholder and is later set to a String on a
// path that reaches its read only through `break` or `continue`. Kotlin/Native 2.4.20's ComputeTypes pass
// misses that write, so an optimized link believes the var is always Placeholder and turns the template's
// toString() into a direct Placeholder.toString() call. Without apple.kotlinNativeReleaseArgs these tests
// see "(placeholder)" where the String belongs; Placeholder.toString() never reads its receiver, so the
// miscompile shows up as a wrong value rather than a crash.
private object Placeholder {
    override fun toString(): String = "placeholder"
}

class ComputeTypesCanaryTest {
    @Test
    fun writeBeforeBreakReachesCodeAfterTheLoop() {
        assertEquals("(abc)", nameAfterBreak(listOf("", "abc", "def")))
    }

    @Test
    fun writeBeforeContinueReachesTheLoopCondition() {
        assertEquals("(placeholder)(x)(y)", namesSeenByCondition(listOf("x", "y")))
    }

    @Test
    fun optionReadBeforeContinueReachesCodeAfterTheLoop() {
        assertEquals("(bob)", nameOption(listOf("--verbose", "--name", "bob")))
    }
}

private fun nameAfterBreak(candidates: List<String>): String {
    var name: Any = Placeholder
    for (candidate in candidates) {
        if (candidate.isNotEmpty()) {
            name = candidate
            break
        }
    }
    return "($name)"
}

// The condition is reached from the loop entry and from `continue`; the body never falls through to it.
private fun namesSeenByCondition(candidates: List<String>): String {
    val seen = StringBuilder()
    var name: Any = Placeholder
    var index = 0
    while (seen.append("($name)").length < 1_000) {
        if (index < candidates.size) {
            name = candidates[index++]
            continue
        }
        break
    }
    return seen.toString()
}

// An argument parser: the write reaches the code after the loop through `continue` and the condition.
private fun nameOption(args: List<String>): String {
    var name: Any = Placeholder
    var index = 0
    while (index < args.size) {
        if (args[index] == "--name" && index + 1 < args.size) {
            name = args[index + 1]
            index += 2
            continue
        }
        index++
    }
    return "($name)"
}
