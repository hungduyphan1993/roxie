package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Catalog
import com.example.data.model.Category
import com.example.data.model.ProbeResult
import com.example.data.model.ProbeState
import com.example.data.model.ResolvedStream
import com.example.data.model.StreamRef
import com.example.ui.PrismCastViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read app name and verify hidden whitelabel and resolver tabs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("PrismCast", context.getString(R.string.app_name))

        composeTestRule.setContent {
            PrismCastApp()
        }
        composeTestRule.waitForIdle()

        // Consumer navigation tabs are visible on Live Hub
        composeTestRule.onNodeWithTag("nav_tab_live").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_schedule").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_multiview").assertIsDisplayed()

        // Whitelabel and Resolver screens/buttons are completely hidden
        composeTestRule.onNodeWithTag("nav_tab_studio").assertDoesNotExist()
        composeTestRule.onNodeWithTag("nav_tab_resolver").assertDoesNotExist()
    }

    @Test
    fun `watch opens fullscreen with transmorphic info bar and 4-way stream and category controls`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrismCastViewModel(app)

        val catalog = Catalog(
            categories = listOf(
                Category(slug = "soccer", title = "Soccer", route = "/soccer"),
                Category(slug = "nba", title = "NBA", route = "/nba")
            )
        )
        val streams = listOf(
            ResolvedStream(
                ref = StreamRef(label = "Arsenal vs Chelsea", path = "s1.m3u8", sport = "soccer"),
                probe = ProbeResult("https://edge/s1.m3u8", ProbeState.LIVE),
                candidates = listOf("https://edge/s1.m3u8"),
                liveUrl = "https://edge/s1.m3u8",
                viewers = 500
            ),
            ResolvedStream(
                ref = StreamRef(label = "Real Madrid vs Barcelona", path = "s2.m3u8", sport = "soccer"),
                probe = ProbeResult("https://edge/s2.m3u8", ProbeState.LIVE),
                candidates = listOf("https://edge/s2.m3u8"),
                liveUrl = "https://edge/s2.m3u8",
                viewers = 420
            ),
            ResolvedStream(
                ref = StreamRef(label = "Lakers vs Celtics", path = "n1.m3u8", sport = "nba"),
                probe = ProbeResult("https://edge/n1.m3u8", ProbeState.LIVE),
                candidates = listOf("https://edge/n1.m3u8"),
                liveUrl = "https://edge/n1.m3u8",
                viewers = 610
            )
        )
        viewModel.seedCatalogForTest(catalog, streams)

        composeTestRule.setContent {
            PrismCastApp(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        // Watching a stream from Live Hub immediately opens fullscreen player + transmorphic info bar
        viewModel.playResolvedStream(streams[0])
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.isFullscreenPlayer)
        assertEquals("Arsenal vs Chelsea", viewModel.uiState.value.activeFeed?.title)
        composeTestRule.onNodeWithTag("broadcast_stage_player").assertIsDisplayed()
        composeTestRule.onNodeWithTag("transmorphic_info_bar").assertIsDisplayed()
        // App navigation bar and top-left brand logo/title are absent during fullscreen playback
        composeTestRule.onNodeWithTag("nav_tab_live").assertDoesNotExist()
        composeTestRule.onNodeWithText("PrismCast").assertDoesNotExist()

        // Tapping stage to hide HUD keeps screen 100% clean without top-left logo/title
        composeTestRule.onNodeWithTag("broadcast_stage_player").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("PrismCast").assertDoesNotExist()

        // Tap stage again to wake transmorphic info bar
        composeTestRule.onNodeWithTag("broadcast_stage_player").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("transmorphic_info_bar").assertIsDisplayed()

        // Down / Next stream navigates through live streams
        composeTestRule.onNodeWithTag("hud_next_stream").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Real Madrid vs Barcelona", viewModel.uiState.value.activeFeed?.title)

        // Right / Next category switches from All Sports -> Football ("soccer" formatted as Football)
        composeTestRule.onNodeWithTag("hud_next_category").performClick()
        composeTestRule.waitForIdle()
        assertEquals("soccer", viewModel.uiState.value.selectedCategorySlug)
        assertEquals("Football", viewModel.uiState.value.activeCategoryDisplayName)

        // Right again switches from Football -> NBA and tunes into Lakers vs Celtics
        composeTestRule.onNodeWithTag("hud_next_category").performClick()
        composeTestRule.waitForIdle()
        assertEquals("nba", viewModel.uiState.value.selectedCategorySlug)
        assertEquals("NBA", viewModel.uiState.value.activeCategoryDisplayName)
        assertEquals("Lakers vs Celtics", viewModel.uiState.value.activeFeed?.title)

        // Closing fullscreen player returns cleanly to Live Hub
        composeTestRule.onNodeWithTag("player_close_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isFullscreenPlayer)
        composeTestRule.onNodeWithTag("nav_tab_live").assertIsDisplayed()
    }

    @Test
    fun `multiscreen opens edge to edge fullscreen without title headers or toolbar buttons`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrismCastViewModel(app)
        viewModel.seedCatalogForTest(Catalog(), emptyList())

        composeTestRule.setContent {
            PrismCastApp(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_multiview").performClick()
        composeTestRule.waitForIdle()

        // Fullscreen MultiView wall is displayed
        composeTestRule.onNodeWithTag("multiview_screen").assertIsDisplayed()

        // Zero app nav bars, zero title headers, and zero space-consuming toolbar buttons
        composeTestRule.onNodeWithTag("nav_tab_live").assertDoesNotExist()
        composeTestRule.onNodeWithText("MultiView Broadcast Wall").assertDoesNotExist()
        composeTestRule.onNodeWithTag("multiview_autofill_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("multiview_clear_button").assertDoesNotExist()
    }

    @Test
    fun `auto retry cycles backup candidates continuously and wraps around without stopping`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrismCastViewModel(app)

        val multiFeedStream = ResolvedStream(
            ref = StreamRef(label = "Champions League Final", path = "ucl.m3u8", sport = "soccer"),
            probe = ProbeResult("https://edge1/ucl.m3u8", ProbeState.LIVE),
            candidates = listOf("https://edge1/ucl.m3u8", "https://edge2/ucl.m3u8"),
            liveUrl = "https://edge1/ucl.m3u8",
            viewers = 1200
        )
        viewModel.seedCatalogForTest(Catalog(), listOf(multiFeedStream))
        viewModel.playResolvedStream(multiFeedStream)

        assertEquals(0, viewModel.uiState.value.activeFeed?.currentCandidateIndex)
        assertEquals(0, viewModel.uiState.value.activeFeed?.retryNonce)

        // First error switches to backup candidate (index 1)
        viewModel.onPrimaryPlayerError()
        assertEquals(1, viewModel.uiState.value.activeFeed?.currentCandidateIndex)
        assertEquals("https://edge2/ucl.m3u8", viewModel.uiState.value.activeFeed?.activeUrl)
        assertEquals(1, viewModel.uiState.value.activeFeed?.retryNonce)

        // Second error wraps back to index 0 and increments retryNonce so player keeps retrying
        viewModel.onPrimaryPlayerError()
        assertEquals(0, viewModel.uiState.value.activeFeed?.currentCandidateIndex)
        assertEquals("https://edge1/ucl.m3u8", viewModel.uiState.value.activeFeed?.activeUrl)
        assertEquals(2, viewModel.uiState.value.activeFeed?.retryNonce)

        // Verify session auto-resume state round-trip
        val activeFeed = viewModel.uiState.value.activeFeed!!
        val session = com.example.data.model.LastPlaybackSession(
            mode = "SINGLE",
            activeFeed = activeFeed,
            selectedCategorySlug = "soccer"
        )
        val encoded = com.example.data.local.WhitelabelPreferences.encodeSession(session)
        val decoded = com.example.data.local.WhitelabelPreferences.decodeSession(encoded)
        org.junit.Assert.assertNotNull(decoded)
        assertEquals("SINGLE", decoded?.mode)
        assertEquals("Champions League Final", decoded?.activeFeed?.title)
        assertEquals("ucl.m3u8", decoded?.activeFeed?.streamPath)
        assertEquals("soccer", decoded?.selectedCategorySlug)

        viewModel.closePrimaryPlayer()
    }

    @Test
    fun `search bar stays passive on dpad focus and only activates text input when ok is pressed`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrismCastViewModel(app)
        viewModel.seedCatalogForTest(Catalog(), emptyList())

        composeTestRule.setContent {
            PrismCastApp(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        // Navigate to Schedule tab where search bar is visible
        composeTestRule.onNodeWithTag("nav_tab_schedule").performClick()
        composeTestRule.waitForIdle()

        // Passive search bar is displayed, while active TextField is NOT mounted
        composeTestRule.onNodeWithTag("schedule_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("schedule_search_input_active_field").assertDoesNotExist()

        // Pressing OK / clicking on the search bar activates the editable TextField
        composeTestRule.onNodeWithTag("schedule_search_input").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("schedule_search_input_active_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("schedule_search_input").assertDoesNotExist()

        // Dismissing edit mode returns to passive D-Pad-safe search bar without popping keyboard
        composeTestRule.onNodeWithTag("schedule_search_input_done").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("schedule_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("schedule_search_input_active_field").assertDoesNotExist()
    }
}
