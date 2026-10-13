package com.bitchat.viewmodel

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
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
        viewModels.assertFalse { viewModel ->
            val injected = viewModel.primaryConstructor?.parameters.orEmpty().map { it.name }.toSet()
            viewModel.functions(includeNested = false, includeLocal = false).any { it.name in injected }
        }
    }
}
