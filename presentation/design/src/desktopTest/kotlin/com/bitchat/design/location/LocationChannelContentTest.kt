package com.bitchat.design.location

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.viewvo.location.LocationChannelsState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * With no real fix the sheet lists the default place's channels, and says first that they are the
 * default's and why, whatever the permission state.
 */
@OptIn(ExperimentalTestApi::class)
class LocationChannelContentTest {
    private val notice = "location not set. showing channels for the default place (San Francisco)"
    private val hint = "enter a geohash below or pick a spot on the globe"

    private val inTheDefaultPlace = LocationChannelsState(
        availableChannels = listOf(
            GeohashChannel(GeohashChannelLevel.BLOCK, "9q8yymh"),
            GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "9q8yym"),
            GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"),
            GeohashChannel(GeohashChannelLevel.PROVINCE, "9q8y"),
            GeohashChannel(GeohashChannelLevel.REGION, "9q"),
        ),
        locationNames = mapOf(GeohashChannelLevel.CITY to "San Francisco"),
        meshParticipantCount = 2,
        locationServicesEnabled = true,
        locationUnavailableReason = notice,
    )

    /** A 411 x 900 dp phone at twice the density, tall enough that no row is left uncomposed. */
    private fun runSheetTest(block: SkikoComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(size = Size(822f, 1800f), density = Density(2f)) { block() }

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        state: LocationChannelsState,
        permission: State<PermissionState?> = mutableStateOf(PermissionState.AUTHORIZED),
        onUseAsDeviceLocation: () -> Unit = {},
        onClearDeviceLocation: () -> Unit = {},
    ) = setContent {
            MaterialTheme {
                LocationChannelContent(
                    state = state,
                    permissionState = permission.value,
                    onSelectMesh = {},
                    onSelectChannel = {},
                    onToggleBookmark = {},
                    onCustomGeohashChange = {},
                    onTeleport = {},
                    onUseAsDeviceLocation = onUseAsDeviceLocation,
                    onClearDeviceLocation = onClearDeviceLocation,
                    onOpenMap = {},
                    onToggleLocationServices = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                )
            }
        }

    @Test
    fun theNoticeTheHintAndTheNearbyChannelsAreAllShown() = runSheetTest {
        show(inTheDefaultPlace)

        onNodeWithText(notice).assertExists()
        onNodeWithText(hint).assertExists()
        onNode(hasText("#9q8yy •", substring = true)).assertExists()

        // For a human to look at; build output, never committed.
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        assertNotNull(png)
        File("build").mkdirs()
        File("build/location-sheet-default-place.png").writeBytes(png.bytes)
    }

    @Test
    fun theNoticeIsShownWhateverThePermissionState() = runSheetTest {
        val permission = mutableStateOf<PermissionState?>(null)
        show(inTheDefaultPlace, permission)
        for (each in listOf(null) + PermissionState.entries) {
            permission.value = each
            waitForIdle()

            onNodeWithText(notice).assertExists()
            onNodeWithText(hint).assertExists()
            onNode(hasText("#9q8yy •", substring = true)).assertExists()
        }
    }

    @Test
    fun withoutAReasonThereIsNoNotice() = runSheetTest {
        show(inTheDefaultPlace.copy(locationUnavailableReason = null))

        onNodeWithText(notice).assertDoesNotExist()
        onNodeWithText(hint).assertDoesNotExist()
        onNode(hasText("#9q8yy •", substring = true)).assertExists()
    }

    @Test
    fun `a saved place shows its notice and clear button without permission controls`() = runSheetTest {
        var cleared = 0
        show(
            inTheDefaultPlace.copy(
                savedPlace = "9q8yy",
                savedPlaceNotice = "location set on this device (#9q8yy)",
            ),
            onClearDeviceLocation = { cleared++ },
        )

        onNodeWithText("location set on this device (#9q8yy)").assertExists()
        onNodeWithText("clear").performClick()
        assertEquals(1, cleared)
        onNodeWithText("✓ location permission granted").assertDoesNotExist()

        show(inTheDefaultPlace)
        onNodeWithText("✓ location permission granted").assertExists()
    }

    @Test
    fun `the use as this device location button calls its callback`() = runSheetTest {
        var used = 0
        show(
            inTheDefaultPlace.copy(customGeohash = "9q8yy"),
            onUseAsDeviceLocation = { used++ },
        )

        onNodeWithText("use as this device's location").performClick()
        assertEquals(1, used)
    }

    @Test
    fun `the teleport and device location buttons both have width at phone size`() = runSheetTest {
        show(inTheDefaultPlace.copy(customGeohash = "9q8yy"))

        val teleportBounds = onNodeWithText("teleport").getUnclippedBoundsInRoot()
        val deviceLocationBounds = onNodeWithText("use as this device's location").getUnclippedBoundsInRoot()
        assertTrue(teleportBounds.right - teleportBounds.left > 0.dp)
        assertTrue(deviceLocationBounds.right - deviceLocationBounds.left > 0.dp)
    }
}
