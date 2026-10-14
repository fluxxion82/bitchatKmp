package com.bitchat.viewmodel

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {

    private val viewModels = Konsist.scopeFromProduction(moduleName = "presentation/viewmodel")
        .classes()
        .filter { it.name.endsWith("ViewModel") }

    // A function named like an injected use case shadows it: the call then runs the function.
    @Test
    fun noViewModelFunctionIsNamedLikeAConstructorParameter() {
        assertTrue(viewModels.isNotEmpty())
        // Konsist's own assertions skip whatever is annotated `@Suppress` with the test's name.
        val shadowing = viewModels.filter { viewModel ->
            val injected = viewModel.primaryConstructor?.parameters.orEmpty().map { it.name }.toSet()
            viewModel.functions(includeNested = false, includeLocal = false).any { it.name in injected }
        }
        assertTrue(shadowing.isEmpty(), shadowing.joinToString { it.name })
    }
}
