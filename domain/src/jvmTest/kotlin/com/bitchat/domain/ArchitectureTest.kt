package com.bitchat.domain

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.provider.KoLocationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {

    private val production = Konsist.scopeFromProduction(moduleName = "domain")

    private val useCases = production.classes().filter { it.useCaseResult() != null }

    @Test
    fun aUseCaseIsNamedObserveExactlyWhenItReturnsAFlow() {
        useCases.assertEach { useCase ->
            useCase.useCaseResult()!!.startsWith("Flow<") == useCase.name.startsWith("Observe")
        }
    }

    @Test
    fun everyPublicClassInAFeaturePackageIsAUseCase() {
        production.classes(includeNested = false)
            .filter { it.packagee?.name?.let(::isFeaturePackage) == true }
            .filterNot { it.hasInternalModifier || it.hasPrivateModifier }
            .filter { it.name !in notUseCases }
            .assertEach { it.useCaseResult() != null }
    }

    @Test
    fun theDomainImportsNothingFromAnotherLayer() {
        production.imports.assertEach { import ->
            allowedImports.any { import.name.startsWith(it) }
        }
    }

    @Test
    fun everyRepositoryInterfaceSitsInItsFeaturesRepositoryPackage() {
        assertPlacedIn("Repository", "repository")
    }

    @Test
    fun everyEventBusInterfaceSitsInItsFeaturesEventbusPackage() {
        assertPlacedIn("EventBus", "eventbus")
    }

    private fun assertPlacedIn(suffix: String, subpackage: String) {
        production.interfaces().filter { it.name.endsWith(suffix) }.assertEach { declaration ->
            val name = declaration.packagee?.name.orEmpty()
            name.endsWith(".$subpackage") && isFeaturePackage(name.removeSuffix(".$subpackage"))
        }
    }

    // Konsist's own assertions skip whatever is annotated `@Suppress` with the calling function's name.
    private fun <T> List<T>.assertEach(holds: (T) -> Boolean) where T : KoNameProvider, T : KoLocationProvider {
        assertTrue(isNotEmpty(), "The rule selected nothing.")
        val broken = filterNot(holds)
        assertTrue(broken.isEmpty(), broken.joinToString("\n") { "${it.name} (${it.location})" })
    }

    private fun isFeaturePackage(name: String): Boolean {
        val feature = name.removePrefix("com.bitchat.domain.")
        return feature != name && '.' !in feature && feature !in setOf("base", "di")
    }

    // The R of a class that implements `Usecase<P, R>`, or null when it is not a use case.
    private fun KoClassDeclaration.useCaseResult(): String? =
        parents().firstOrNull { it.name.substringBefore('<') == "Usecase" }?.typeArguments?.getOrNull(1)?.text

    private companion object {
        val notUseCases = setOf("AppForegroundState", "ChatNotices", "Notice")

        val allowedImports = listOf("com.bitchat.domain.", "kotlin.", "kotlinx.", "org.koin.")
    }
}
