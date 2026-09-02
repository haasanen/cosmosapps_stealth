package com.cosmos.unreddit.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard against inflating views with Material3-only theme attributes while the
 * app theme is Material2 (Theme.MaterialComponents.DayNight.NoActionBar).
 *
 * Real-world bug this pins down: view_feed_progress.xml used
 * ?attr/colorSurfaceVariant, which only Material3 themes define. The M2 theme
 * chain resolves it to nothing, so PostListFragment.onCreateView crashed with
 * "Failed to resolve attribute at index 13: TypedValue{t=0x2/d=0x7f040115}" on
 * every launch (build fe7c4b3, user device log, 2026-09-02).
 *
 * The check is static: every ?attr/ reference in res/layout must either be an
 * android: attr or an attr that the M2 MaterialComponents theme chain actually
 * defines (per material 1.7.0 values.xml). M3-exclusive attrs are listed in
 * M3_ONLY so the test fails fast with a readable message instead of a crash
 * loop on device.
 */
class M2ThemeAttrGuardTest {

    // Material3-exclusive color/shape/text attrs (material 1.7.0).
    // None of these resolve under Base.Theme.MaterialComponents.* chains.
    private val m3Only = setOf(
        "colorSurfaceVariant",
        "colorOnSurfaceVariant",
        "colorOutline",
        "colorOutlineVariant",
        "colorSurfaceContainer",
        "colorSurfaceContainerLow",
        "colorSurfaceContainerHigh",
        "colorSurfaceContainerHighest",
        "colorSurfaceContainerLowest",
        "colorSurfaceInverse",
        "colorOnSurfaceInverse",
        "colorErrorContainer",
        "colorOnErrorContainer",
        "colorPrimaryContainer",
        "colorOnPrimaryContainer",
        "colorSecondaryContainer",
        "colorOnSecondaryContainer",
        "colorTertiary",
        "colorOnTertiary",
        "colorTertiaryContainer",
        "colorOnTertiaryContainer",
        "textAppearanceDisplayLarge",
        "textAppearanceDisplayMedium",
        "textAppearanceDisplaySmall",
        "textAppearanceHeadlineLarge",
        "textAppearanceHeadlineMedium",
        "textAppearanceHeadlineSmall",
        "textAppearanceTitleLarge",
        "textAppearanceTitleMedium",
        "textAppearanceTitleSmall",
        "textAppearanceBodyLarge",
        "textAppearanceBodyMedium",
        "textAppearanceBodySmall",
        "textAppearanceLabelLarge",
        "textAppearanceLabelMedium",
        "textAppearanceLabelSmall",
    )

    private val attrRef = Regex("""\?(?:([A-Za-z0-9_]+):)?(attr/)?([A-Za-z0-9_]+)""")

    @Test
    fun layoutsUseNoM3OnlyThemeAttributes() {
        val resDir = File("src/main/res")
        assertTrue(
            "res dir not found (test must run with the app module as working dir)",
            resDir.isDirectory
        )
        val offenders = mutableListOf<String>()
        resDir.walk()
            .filter { it.extension == "xml" && it.parentFile.name == "layout" }
            .sortedBy { it.path }
            .forEach { f ->
                f.readText()
                    .lineSequence()
                    .filter { it.contains("?") && it.contains("attr") }
                    .filterNot { it.trim().startsWith("<!--") }
                    .forEach { line ->
                        attrRef.findAll(line).forEach { m ->
                            val attrName = m.groupValues[3]
                            if (attrName in m3Only) {
                                offenders += "${f.name}:${line.trim()} -> ?attr/$attrName"
                            }
                        }
                    }
            }
        assertTrue(
            "Material3-only ?attr/ references would crash at inflation " +
                "under the M2 app theme (see M2ThemeAttrGuardTest doc): " + offenders,
            offenders.isEmpty()
        )
    }
}
