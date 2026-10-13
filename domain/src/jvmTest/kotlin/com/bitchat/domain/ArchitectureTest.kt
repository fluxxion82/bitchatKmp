package com.bitchat.domain

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {

    private val production = Konsist.scopeFromProduction(moduleName = "domain")

    private val useCases = production.classes().filter { it.useCaseResult() != null }

    @Test
    fun aUseCaseIsNamedObserveExactlyWhenItReturnsAFlow() {
        assertTrue(useCases.isNotEmpty())
        useCases.assertTrue { useCase ->
            useCase.useCaseResult()!!.startsWith("Flow<") == useCase.name.startsWith("Observe")
        }
    }

    @Test
    fun everyPublicClassInAFeaturePackageIsAUseCase() {
        val featureClasses = production.classes(includeNested = false)
            .filter { it.packagee?.name?.let(::isFeaturePackage) == true }
            .filterNot { it.hasInternalModifier || it.hasPrivateModifier }
            .filter { it.name !in notUseCases }
        assertTrue(featureClasses.isNotEmpty())
        featureClasses.assertTrue { it.useCaseResult() != null }
    }

    @Test
    fun theDomainImportsNothingFromAnotherLayer() {
        assertTrue(production.imports.isNotEmpty())
        production.imports.assertTrue { import ->
            allowedImports.any { import.name.startsWith(it) }
        }
    }

    private fun isFeaturePackage(name: String): Boolean {
        val feature = name.removePrefix("com.bitchat.domain.")
        return feature != name && '.' !in feature && feature !in setOf("base", "di")
    }

    // The R of a class that implements `Usecase<P, R>`, or null when it is not a use case.
    private fun KoClassDeclaration.useCaseResult(): String? =
        parents().firstOrNull { it.name.substringBefore('<') == "Usecase" }?.typeArguments?.getOrNull(1)?.text

    private companion object {
        // PrivateTextLimit answers while a line is being sent, before the editor is cleared, so it cannot suspend.
        val notUseCases = setOf("AppForegroundState", "ChatNotices", "Notice", "PrivateTextLimit")

        val allowedImports = listOf("com.bitchat.domain.", "kotlin.", "kotlinx.", "org.koin.")
    }
}
