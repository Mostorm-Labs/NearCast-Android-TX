package com.auditoryworks.nearcast.ui

import android.content.Intent
import android.app.UiAutomation
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.auditoryworks.nearcast.MainActivity
import com.auditoryworks.nearcast.R
import com.auditoryworks.nearcast.ui.theme.ScreenCastTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MobileUiDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Before fun keepUserAccessibilityServicesRunning() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tab(label: Int) = ui.onNode(hasText(context.getString(label)) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun launch(screen: String) = ActivityScenario.launch<AppearancePreviewActivity>(
        Intent(context, AppearancePreviewActivity::class.java).putExtra("screen", screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test fun navigationIsReachableAtTheBottomOfTheScreen() {
        launch("home").use {
            val tab = tab(R.string.ui_devices_tab).getUnclippedBoundsInRoot()
            val root = ui.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Device navigation must be below the content", tab.top > (root.bottom - root.top) * .65f)
        }
    }

    @Test fun localReceiverAndTypedCodeSurviveBottomTabSwitches() {
        launch("devices").use {
            ui.onNodeWithText("NearHub · Meeting room").performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.connection_pair_code)).performTextInput("AB12")
            tab(R.string.ui_settings).performClick()
            ui.onNodeWithText(context.getString(R.string.startup_permission_title)).assertExists()
            tab(R.string.ui_code_tab).performClick()
            ui.onNodeWithText("NearHub · Meeting room").assertExists()
            ui.onNodeWithContentDescription(context.getString(R.string.connection_pair_code)).assertTextEquals("AB12")
        }
    }

    @Test fun liveSessionSurvivesBrowsingScanAndSettingsTabs() {
        launch("session").use {
            tab(R.string.ui_settings).performClick()
            tab(R.string.ui_devices_tab).performClick()
            tab(R.string.ui_code_tab).performClick()
            ui.onNodeWithText(context.getString(R.string.session_action_stop)).assertExists()
        }
    }

    @Test fun systemLocaleResolvesAllFourShippedLanguages() {
        for ((tag, settings) in listOf("en-US" to "Settings", "zh-CN" to "设置", "ja-JP" to "設定", "de-DE" to "Einstellungen")) {
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
            val localized = context.createConfigurationContext(config)
            assertEquals(tag, settings, localized.getString(R.string.ui_settings))
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun systemLanguageChangeKeepsTheActivityThatOwnsTheConnection() {
        val initialLocales = context.getSystemService(LocaleManager::class.java).applicationLocales
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            if (ui.onAllNodesWithText(context.getString(R.string.startup_defer)).fetchSemanticsNodes().isNotEmpty()) {
                ui.onNodeWithText(context.getString(R.string.startup_defer)).performClick()
            }
            tab(R.string.ui_code_tab).performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.connection_pair_code)).performTextInput("AB12")
            var owner: MainActivity? = null
            scenario.onActivity {
                owner = it
                it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("de")
            }
            ui.waitUntil(5000) { ui.onAllNodesWithText("Kopplungscode eingeben").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertSame("Do not destroy the connection owner for a language change", owner, it) }
            ui.onNodeWithContentDescription("Kopplungscode").assertTextEquals("AB12")
        } finally {
            scenario.onActivity { it.getSystemService(LocaleManager::class.java).applicationLocales = initialLocales }
            scenario.close()
        }
    }

    @Test fun monochromeThemeFollowsSystemDayAndNightWithReadableText() {
        launch("home").use { scenario ->
            for (night in listOf(false, true)) {
                val config = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                var background = Color.Unspecified
                var text = Color.Unspecified
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides config) {
                        ScreenCastTheme {
                            val colors = MaterialTheme.colorScheme
                            SideEffect { background = colors.background; text = colors.onBackground }
                        }
                    }
                } }
                ui.runOnIdle {
                    assertEquals("System theme", night, background.luminance() < .1f)
                    val contrast = (maxOf(background.luminance(), text.luminance()) + .05f) /
                        (minOf(background.luminance(), text.luminance()) + .05f)
                    assertTrue("Body text contrast", contrast >= 7f)
                    assertEquals(background.red, background.green, .005f)
                    assertEquals(background.green, background.blue, .005f)
                }
            }
        }
    }
}
