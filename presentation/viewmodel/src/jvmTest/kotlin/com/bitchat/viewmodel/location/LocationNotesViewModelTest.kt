package com.bitchat.viewmodel.location

import com.bitchat.domain.location.GetLocationGeohash
import com.bitchat.domain.location.ObserveNotes
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.SendNote
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.viewmodel.BaseViewModelTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A host with no location source at all (the Pi) hands the view model the channel the user has
 * joined; without one it asks for a fix at building precision, as the Compose apps do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesViewModelTest : BaseViewModelTest() {
    private val dispatcher get() = instantExecutorRule.testDispatcher

    private val sent = ArrayList<String>()

    /** Held while a test wants the place to still be unresolved. */
    private val slowName = CompletableDeferred<String>()

    private fun build(geohash: String?, resolveSlowly: Boolean = false) = LocationNotesViewModel(
        observeNotes = mockk<ObserveNotes> { coEvery { this@mockk.invoke(any()) } returns emptyFlow() },
        sendNote = mockk<SendNote> {
            coEvery { this@mockk.invoke(any()) } answers { sent += firstArg<SendNote.Params>().content }
        },
        getUserNickname = mockk<GetUserNickname> { coEvery { this@mockk.invoke(Unit) } returns flowOf("anon") },
        getLocationGeohash = mockk<GetLocationGeohash> {
            coEvery { this@mockk.invoke(any()) } throws IllegalStateException("no location source")
        },
        resolveLocationName = mockk<ResolveLocationName> {
            coEvery { this@mockk.invoke(any()) } coAnswers { if (resolveSlowly) slowName.await() else "San Francisco" }
        },
        geohash = geohash,
    )

    @Test
    fun `a given geohash is used instead of a location fix`() = runTest(dispatcher) {
        val viewModel = build("9q8yy")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals("9q8yy", viewModel.state.value.geohash)
        assertEquals("San Francisco", viewModel.state.value.locationName)

        viewModel.onInputTextChange("hello here")
        assertEquals(true, viewModel.onSendNote())
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello here"), sent)
    }

    @Test
    fun `without a geohash a host with no fix says so and posts nothing`() = runTest(dispatcher) {
        val viewModel = build(geohash = null)
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.state.value.errorMessage?.contains("no location source"))

        viewModel.onInputTextChange("hello here")
        assertEquals(false, viewModel.onSendNote(), "refused, so the caller keeps the line")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a note goes through while the geocoder is still being asked what the place is called`() = runTest(dispatcher) {
        val viewModel = build("9q8yy", resolveSlowly = true)
        instantExecutorRule.scheduler.advanceUntilIdle()

        viewModel.onInputTextChange("hello here")
        assertEquals(true, viewModel.onSendNote(), "the place is known; only its name is not")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello here"), sent)
        assertEquals(null, viewModel.state.value.locationName)

        slowName.complete("San Francisco")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals("San Francisco", viewModel.state.value.locationName)
    }

    @Test
    fun `a note refused for want of a place says so instead of vanishing`() = runTest(dispatcher) {
        val viewModel = build(geohash = null)
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.onInputTextChange("hello here")
        assertEquals(false, viewModel.onSendNote())
        assertEquals(NOT_READY_YET, viewModel.state.value.errorMessage)
    }
}
