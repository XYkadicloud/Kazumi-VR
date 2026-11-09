package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VrMenuModelTest {

    @Test
    fun exposesAllRowsWithUniqueIds() {
        // 23 baseline + 5 passthrough rows + 1 layout-mode row + 4 camera-control rows
        // (resolution / fps / rotation / mirror) + 1 head-lock row = 34.
        assertEquals(34, VrMenuModel.items.size)
        assertEquals(VrMenuModel.items.size, VrMenuModel.items.map { it.id }.toSet().size)
        assertEquals(VrMenuItemId.SCREEN_SCALE, VrMenuModel.items.first().id)
        assertEquals(VrMenuItemId.EXIT_VR, VrMenuModel.items.last().id)
    }

    @Test
    fun increasingScreenScaleUsesTheDeclaredStep() {
        val next = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.SCREEN_SCALE, 1)
        assertEquals(
            VrSettings.DEFAULT.screenScale + VrRanges.SCREEN_SCALE_STEP,
            next.screenScale,
            1e-5f,
        )
    }

    @Test
    fun adjustingClampsAtBothEnds() {
        val maxed = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.SCREEN_SCALE, 10_000)
        val minned = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.SCREEN_SCALE, -10_000)
        assertEquals(VrRanges.SCREEN_SCALE_MAX, maxed.screenScale, 0f)
        assertEquals(VrRanges.SCREEN_SCALE_MIN, minned.screenScale, 0f)
    }

    @Test
    fun zeroStepsIsANoOp() {
        val s = VrSettings.DEFAULT
        assertEquals(s, VrMenuModel.adjust(s, VrMenuItemId.BRIGHTNESS, 0))
    }

    @Test
    fun actionRowsDoNotChangeSettingsOnAdjust() {
        val s = VrSettings.DEFAULT
        assertEquals(s, VrMenuModel.adjust(s, VrMenuItemId.EXIT_VR, 5))
        assertEquals(s, VrMenuModel.adjust(s, VrMenuItemId.SCAN_QR, -5))
        assertEquals(s, VrMenuModel.adjust(s, VrMenuItemId.RESET_DEFAULTS, 1))
    }

    @Test
    fun enumAndToggleRowsFlip() {
        val s = VrSettings.DEFAULT
        val flipped = VrMenuModel.adjust(s, VrMenuItemId.VIEWER_MODE, 1)
        assertEquals(VrViewerMode.SIMULATION, flipped.viewerMode)
        assertEquals(
            VrViewerMode.CARDBOARD,
            VrMenuModel.adjust(flipped, VrMenuItemId.VIEWER_MODE, -1).viewerMode,
        )
        assertEquals(false, VrMenuModel.adjust(s, VrMenuItemId.HEAD_TRACKING, 1).headTrackingEnabled)
        assertEquals(false, VrMenuModel.adjust(s, VrMenuItemId.SHOW_STATUS, 1).showStatus)
    }

    @Test
    fun activateResetsDefaults() {
        val changed = VrSettings(screenScale = 2.5f, brightness = 0.5f)
        assertEquals(VrSettings.DEFAULT, VrMenuModel.activate(changed, VrMenuItemId.RESET_DEFAULTS))
    }

    @Test
    fun activateOnActionRowsOtherThanResetDoesNotMutateSettings() {
        val s = VrSettings.DEFAULT
        assertEquals(s, VrMenuModel.activate(s, VrMenuItemId.EXIT_VR))
        assertEquals(s, VrMenuModel.activate(s, VrMenuItemId.SCAN_QR))
        assertEquals(s, VrMenuModel.activate(s, VrMenuItemId.RECENTER))
    }

    @Test
    fun menuRepeatIsStoredAsLong() {
        val s = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.MENU_REPEAT, 1)
        assertEquals(VrSettings.DEFAULT.menuRepeatMs + VrRanges.REPEAT_STEP_MS, s.menuRepeatMs)
    }

    @Test
    fun newToggleRowsFlipAndAreReversible() {
        val s = VrSettings.DEFAULT
        assertEquals(true, VrMenuModel.adjust(s, VrMenuItemId.HORIZONTAL_MIRROR, 1).horizontalMirror)
        assertEquals(
            false,
            VrMenuModel.adjust(s.copy(horizontalMirror = true), VrMenuItemId.HORIZONTAL_MIRROR, 1)
                .horizontalMirror,
        )
        assertEquals(true, VrMenuModel.adjust(s, VrMenuItemId.VERTICAL_FLIP, 1).verticalFlip)
        assertEquals(
            false,
            VrMenuModel.adjust(s.copy(verticalFlip = true), VrMenuItemId.VERTICAL_FLIP, 1).verticalFlip,
        )
        assertEquals(true, VrMenuModel.adjust(s, VrMenuItemId.CENTER_ALIGN_LINE, 1).showCenterAlignmentLine)
        assertEquals(true, VrMenuModel.adjust(s, VrMenuItemId.BACKGROUND, 1).backgroundEnabled)
        assertEquals(false, VrMenuModel.adjust(s.copy(backgroundEnabled = true), VrMenuItemId.BACKGROUND, 1)
            .backgroundEnabled)
    }

    @Test
    fun backgroundBrightnessRangeIsClamped() {
        val high = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.BACKGROUND_BRIGHTNESS, 10_000)
        val low = VrMenuModel.adjust(VrSettings.DEFAULT, VrMenuItemId.BACKGROUND_BRIGHTNESS, -10_000)
        assertEquals(VrRanges.BACKGROUND_BRIGHTNESS_MAX, high.backgroundBrightness, 1e-4f)
        assertEquals(VrRanges.BACKGROUND_BRIGHTNESS_MIN, low.backgroundBrightness, 1e-4f)
    }

    @Test
    fun displayNeverReturnsBlank() {
        VrMenuItemId.values().forEach { id ->
            assertTrue("blank label for $id", VrMenuModel.display(VrSettings.DEFAULT, id).isNotBlank())
        }
    }

    @Test
    fun everyRangeItemStaysWithinItsDeclaredBounds() {
        VrMenuModel.items.filter { it.kind == VrMenuItemKind.RANGE }.forEach { item ->
            val high = VrMenuModel.adjust(VrSettings.DEFAULT, item.id, 10_000)
            val low = VrMenuModel.adjust(VrSettings.DEFAULT, item.id, -10_000)
            assertTrue("${item.id} upper", VrMenuModel.rawValue(high, item.id) <= item.max + 1e-4f)
            assertTrue("${item.id} lower", VrMenuModel.rawValue(low, item.id) >= item.min - 1e-4f)
        }
    }

    @Test
    fun rangeItemsUseTheirOwnSteps() {
        assertEquals(
            VrRanges.BRIGHTNESS_STEP,
            VrMenuModel.items.first { it.id == VrMenuItemId.BRIGHTNESS }.step,
            0f,
        )
        assertEquals(
            VrRanges.DISTANCE_STEP,
            VrMenuModel.items.first { it.id == VrMenuItemId.SCREEN_DISTANCE }.step,
            0f,
        )
    }

    @Test
    fun ipdScaleUsesItsOwnStepAndIsClamped() {
        val s = VrSettings.DEFAULT
        assertEquals(1.0f, s.ipdScale, 0f)
        assertEquals(
            1.0f + VrRanges.IPD_SCALE_STEP,
            VrMenuModel.adjust(s, VrMenuItemId.IPD_SCALE, 1).ipdScale,
            1e-5f,
        )
        assertEquals(
            VrRanges.IPD_SCALE_MAX,
            VrMenuModel.adjust(s, VrMenuItemId.IPD_SCALE, 10_000).ipdScale,
            1e-4f,
        )
        assertEquals(
            VrRanges.IPD_SCALE_MIN,
            VrMenuModel.adjust(s, VrMenuItemId.IPD_SCALE, -10_000).ipdScale,
            1e-4f,
        )
    }

    @Test
    fun screenFillModeTogglesBetweenDistortedAndFill() {
        val s = VrSettings.DEFAULT
        assertEquals(VrScreenFillMode.FILL, s.screenFillMode)
        val toggled = VrMenuModel.activate(s, VrMenuItemId.SCREEN_FILL_MODE)
        assertEquals(VrScreenFillMode.DISTORTED, toggled.screenFillMode)
        val toggledBack = VrMenuModel.activate(toggled, VrMenuItemId.SCREEN_FILL_MODE)
        assertEquals(VrScreenFillMode.FILL, toggledBack.screenFillMode)
    }

    /** "单纯放大" is a normal RANGE row: adjustable, clamped, and A-armed like every other value. */
    @Test
    fun zoomScaleRowStaysWithinItsDeclaredBounds() {
        val s = VrSettings.DEFAULT
        assertEquals(VrRanges.ZOOM_MIN, 1.00f, 1e-4f)
        // 1.35 after real-device feedback ("依然不能全屏"); 1.18 was not enough to clear the bands.
        assertEquals(1.35f, s.zoomScale, 1e-4f)
        assertEquals(2.50f, VrRanges.ZOOM_MAX, 1e-4f)
        assertTrue(VrMenuModel.display(s, VrMenuItemId.ZOOM_SCALE).isNotBlank())
        assertEquals(
            VrRanges.ZOOM_MAX,
            VrMenuModel.adjust(s, VrMenuItemId.ZOOM_SCALE, 10_000).zoomScale,
            1e-4f,
        )
        assertEquals(
            VrRanges.ZOOM_MIN,
            VrMenuModel.adjust(s, VrMenuItemId.ZOOM_SCALE, -10_000).zoomScale,
            1e-4f,
        )
        val armed = VrMenuModel.confirm(s, VrMenuItemId.ZOOM_SCALE, editing = false)
        assertEquals(s, armed.settings)
        assertEquals(true, armed.editing)
        assertEquals(false, VrMenuModel.confirm(s, VrMenuItemId.ZOOM_SCALE, editing = true).editing)
    }

    @Test
    fun newToggleRowsAreReversible() {
        val s = VrSettings.DEFAULT
        assertEquals(true, VrMenuModel.activate(s, VrMenuItemId.HORIZONTAL_MIRROR).horizontalMirror)
        assertEquals(
            false,
            VrMenuModel.activate(s.copy(horizontalMirror = true), VrMenuItemId.HORIZONTAL_MIRROR)
                .horizontalMirror,
        )
        assertEquals(true, VrMenuModel.activate(s, VrMenuItemId.VERTICAL_FLIP).verticalFlip)
        assertEquals(
            false,
            VrMenuModel.activate(s.copy(verticalFlip = true), VrMenuItemId.VERTICAL_FLIP)
                .verticalFlip,
        )
    }

    @Test
    fun pageStructureIsValid() {
        assertEquals(5, VrMenuModel.pages.size)
        assertEquals("画面", VrMenuModel.pages[0].title)
        assertEquals("观看", VrMenuModel.pages[1].title)
        assertEquals("操作", VrMenuModel.pages[2].title)
        assertEquals("环境", VrMenuModel.pages[3].title)
        assertEquals("快捷", VrMenuModel.pages[4].title)
    }

    @Test
    fun itemsForPageReturnsCorrectSublists() {
        val displayItems = VrMenuModel.itemsFor(VrMenuModel.pages[0])
        assertEquals(10, displayItems.size)
        assertEquals(VrMenuItemId.SCREEN_SCALE, displayItems.first().id)
        assertEquals(VrMenuItemId.VERTICAL_FLIP, displayItems.last().id)

        val viewItems = VrMenuModel.itemsFor(VrMenuModel.pages[1])
        assertEquals(5, viewItems.size)
        assertEquals(VrMenuItemId.VIEWER_MODE, viewItems.first().id)
        assertEquals(VrMenuItemId.RECENTER, viewItems.last().id)
    }

    @Test
    fun clampToPageKeepsSelectionInsidePage() {
        val displayPage = VrMenuModel.pages[0]
        val viewPage = VrMenuModel.pages[1]
        // Clamp selection 0 (first of display page) when on view page -> should clamp to view page first
        assertEquals(
            VrMenuModel.firstIndexOf(viewPage),
            VrMenuModel.clampToPage(0, viewPage)
        )
        // Clamp selection 21 (last item) when on display page -> should clamp to display page last
        assertEquals(
            VrMenuModel.lastIndexOf(displayPage),
            VrMenuModel.clampToPage(21, displayPage)
        )
    }

    @Test
    fun adjustIfEditingOnlyChangesWhenEditingArmed() {
        val s = VrSettings.DEFAULT
        val itemId = VrMenuItemId.SCREEN_SCALE
        // Not editing -> no change
        assertEquals(s, VrMenuModel.adjustIfEditing(s, itemId, 5, editing = false))
        // Editing -> changes
        val changed = VrMenuModel.adjustIfEditing(s, itemId, 1, editing = true)
        assertEquals(s.screenScale + VrRanges.SCREEN_SCALE_STEP, changed.screenScale, 1e-5f)
    }

    @Test
    fun confirmOnRangeTogglesEditingWithoutChangingValue() {
        val s = VrSettings.DEFAULT
        val itemId = VrMenuItemId.SCREEN_SCALE
        // Not editing -> A arms editing
        val result = VrMenuModel.confirm(s, itemId, editing = false)
        assertEquals(s, result.settings)
        assertEquals(true, result.editing)
        // Editing -> A disarms editing
        val result2 = VrMenuModel.confirm(s, itemId, editing = true)
        assertEquals(s, result2.settings)
        assertEquals(false, result2.editing)
    }

    @Test
    fun confirmOnToggleFlipsImmediatelyAndDisarmsEditing() {
        val s = VrSettings.DEFAULT
        val itemId = VrMenuItemId.HORIZONTAL_MIRROR
        val result = VrMenuModel.confirm(s, itemId, editing = true)
        assertEquals(true, result.settings.horizontalMirror)
        assertEquals(false, result.editing)
    }

    @Test
    fun confirmOnActionExecutesAndDisarmsEditing() {
        val s = VrSettings(screenScale = 2.5f, brightness = 0.5f)
        val result = VrMenuModel.confirm(s, VrMenuItemId.RESET_DEFAULTS, editing = true)
        assertEquals(VrSettings.DEFAULT, result.settings)
        assertEquals(false, result.editing)
    }

    // --- 摄像头透视 / 三种布局样式（纯设置项，不是快捷键步骤） -------------------------------

    @Test
    fun passthroughRowsArePresentOnTheEnvironmentPage() {
        val env = VrMenuModel.itemsFor(VrMenuModel.pages[3])
        assertEquals(13, env.size)
        assertEquals(VrMenuItemId.BACKGROUND, env.first().id)
        assertEquals(VrMenuItemId.PASSTHROUGH_HEAD_LOCKED, env.last().id)
        assertTrue(VrMenuModel.display(VrSettings.DEFAULT, VrMenuItemId.PASSTHROUGH_MODE).isNotBlank())
    }

    @Test
    fun cameraControlRowsCycleAndNeverTouchTheOnOffShortcut() {
        val s = VrSettings.DEFAULT
        val r1 = VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_RESOLUTION)
        assertEquals(VrPassthroughResolution.P720, r1.passthroughResolution)
        var cycled = r1
        repeat(VrPassthroughResolution.values().size) {
            cycled = VrMenuModel.activate(cycled, VrMenuItemId.PASSTHROUGH_RESOLUTION)
        }
        assertEquals(VrPassthroughResolution.P720, cycled.passthroughResolution)
        assertEquals(VrPassthroughFrameRate.FPS_60,
            VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_FPS).passthroughFrameRate)
        // Three tiers: 30 -> 60 -> 15 -> 30. 15 fps was added on user request as a low-power tier.
        assertEquals(VrPassthroughFrameRate.FPS_15, cycleFps(s, 2).passthroughFrameRate)
        assertEquals(VrPassthroughFrameRate.FPS_30, cycleFps(s, 3).passthroughFrameRate)
        assertEquals(VrPassthroughFrameRate.FPS_60, cycleFps(s, 4).passthroughFrameRate)
        // The lowest tier is not a dead end: 15 -> 30.
        assertEquals(VrPassthroughFrameRate.FPS_30,
            VrMenuModel.activate(
                VrSettings(passthroughFrameRate = VrPassthroughFrameRate.FPS_15),
                VrMenuItemId.PASSTHROUGH_FPS,
            ).passthroughFrameRate)
        val allRotations = VrPassthroughRotation.values().size
        var rotated = s
        repeat(allRotations) { rotated = VrMenuModel.activate(rotated, VrMenuItemId.PASSTHROUGH_ROTATION) }
        assertEquals(VrPassthroughRotation.AUTO, rotated.passthroughRotation)
        assertEquals(true, VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_MIRROR).passthroughMirror)
        assertEquals(true, VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_HEAD_LOCKED).passthroughHeadLocked)
        // These are pure settings. The L2+R2 shortcut flips ONLY passthroughEnabled.
        assertEquals(false, VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_FPS).passthroughEnabled)
        assertEquals(VrPassthroughMode.OVERLAY,
            VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_RESOLUTION).passthroughMode)
    }

    @Test
    fun cameraControlRowsHaveReadableLabels() {
        val s = VrSettings.DEFAULT
        listOf(
            VrMenuItemId.PASSTHROUGH_RESOLUTION,
            VrMenuItemId.PASSTHROUGH_FPS,
            VrMenuItemId.PASSTHROUGH_ROTATION,
            VrMenuItemId.PASSTHROUGH_MIRROR,
            VrMenuItemId.PASSTHROUGH_HEAD_LOCKED,
        ).forEach { id ->
            assertTrue("$id has no label", VrMenuModel.display(s, id).isNotBlank())
            assertTrue(VrMenuModel.rawValue(s, id) >= 0f)
        }
    }

    @Test
    fun passthroughModeCyclesThroughTheThreeStyles() {
        val s = VrSettings.DEFAULT
        assertEquals(VrPassthroughMode.OVERLAY, s.passthroughMode)
        val a = VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_MODE)
        assertEquals(VrPassthroughMode.CAMERA_MAIN, a.passthroughMode)
        val b = VrMenuModel.activate(a, VrMenuItemId.PASSTHROUGH_MODE)
        assertEquals(VrPassthroughMode.SIDE_BY_SIDE, b.passthroughMode)
        val c = VrMenuModel.activate(b, VrMenuItemId.PASSTHROUGH_MODE)
        assertEquals(VrPassthroughMode.OVERLAY, c.passthroughMode)
        // The ON/OFF switch and the layout are INDEPENDENT settings: flipping one never moves
        // the other (this is exactly what the L2+R2 shortcut relies on).
        val toggled = VrMenuModel.activate(c, VrMenuItemId.PASSTHROUGH)
        assertEquals(true, toggled.passthroughEnabled)
        assertEquals(VrPassthroughMode.OVERLAY, toggled.passthroughMode)
    }

    @Test
    fun passthroughRangeRowsClampToTheirDeclaredBounds() {
        val s = VrSettings.DEFAULT
        assertEquals(false, s.passthroughEnabled)
        assertEquals(VrPassthroughFacing.BACK, s.passthroughCameraFacing)
        assertEquals(VrPassthroughAspect.FOUR_THREE, s.passthroughAspect)
        assertEquals(1.00f, s.passthroughOpacity, 0f)
        assertEquals(0.85f, s.passthroughWindowScale, 0f)
        assertEquals(
            VrRanges.PASSTHROUGH_OPACITY_MAX,
            VrMenuModel.adjust(s, VrMenuItemId.PASSTHROUGH_OPACITY, 10_000).passthroughOpacity,
            1e-4f,
        )
        assertEquals(
            VrRanges.PASSTHROUGH_WINDOW_MIN,
            VrMenuModel.adjust(s, VrMenuItemId.PASSTHROUGH_WINDOW, -10_000).passthroughWindowScale,
            1e-4f,
        )
        assertEquals(
            VrPassthroughFacing.FRONT,
            VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_FACING).passthroughCameraFacing,
        )
        assertEquals(
            VrPassthroughAspect.SIXTEEN_NINE,
            VrMenuModel.activate(s, VrMenuItemId.PASSTHROUGH_ASPECT).passthroughAspect,
        )
    }

    private fun cycleFps(s: VrSettings, times: Int): VrSettings {
        var current = s
        repeat(times) { current = VrMenuModel.activate(current, VrMenuItemId.PASSTHROUGH_FPS) }
        return current
    }

    // --- 滚动窗口（2026-11-09 真机：设置项超出设置页面，看不到下面的项） -------------------------

    @Test
    fun visibleWindowNeverOverflowsAnyPageAndAlwaysShowsTheSelection() {
        VrMenuModel.pages.forEach { page ->
            val count = VrMenuModel.itemsFor(page).size
            val first = VrMenuModel.firstIndexOf(page)
            (first..VrMenuModel.lastIndexOf(page)).forEach { sel ->
                val w = VrMenuModel.visibleWindowFor(page, sel)
                assertTrue("$page $sel first", w.first >= 0)
                assertTrue("$page $sel last", w.last < count)
                assertTrue(
                    "$page $sel size",
                    w.last - w.first + 1 <= VrMenuModel.VISIBLE_ROWS,
                )
                assertTrue("$page $sel shows selection", (sel - first) in w)
            }
        }
    }

    @Test
    fun thirteenRowEnvironmentPageScrollsDownToItsLastRow() {
        val env = VrMenuModel.pages[3]
        val first = VrMenuModel.firstIndexOf(env)
        val items = VrMenuModel.itemsFor(env)
        assertEquals(13, items.size)
        assertEquals(0, VrMenuModel.visibleWindowFor(env, first).first)
        val bottom = VrMenuModel.visibleWindowFor(env, VrMenuModel.lastIndexOf(env))
        assertEquals(items.size - VrMenuModel.VISIBLE_ROWS, bottom.first)
        assertEquals(items.size - 1, bottom.last)
        assertEquals(VrMenuItemId.PASSTHROUGH_HEAD_LOCKED, items[bottom.last].id)
    }

    @Test
    fun windowOnlyMovesWhenSelectionPassesItsBottomEdge() {
        val env = VrMenuModel.pages[3]
        val first = VrMenuModel.firstIndexOf(env)
        // The first ten rows all render in the top window.
        (0 until VrMenuModel.VISIBLE_ROWS).forEach { row ->
            assertEquals(0, VrMenuModel.visibleWindowFor(env, first + row).first)
        }
        // Each further row scrolls the window by exactly one.
        assertEquals(1, VrMenuModel.visibleWindowFor(env, first + 10).first)
        assertEquals(2, VrMenuModel.visibleWindowFor(env, first + 11).first)
        assertEquals(3, VrMenuModel.visibleWindowFor(env, first + 12).first)
        // Going back up returns to the top window (stateless).
        assertEquals(0, VrMenuModel.visibleWindowFor(env, first).first)
    }

    @Test
    fun visibleWindowIsStatelessClampsOutOfRangeSelectionAndHonoursCustomRowCounts() {
        val env = VrMenuModel.pages[3]
        val last = VrMenuModel.itemsFor(env).size - 1
        val w = VrMenuModel.visibleWindowFor(env, 100_000)
        assertEquals(last, w.last)
        assertTrue(w.first >= 0)
        // Future pages: any visibleRows value keeps the selection in view.
        val small = VrMenuModel.visibleWindowFor(env, VrMenuModel.lastIndexOf(env), 3)
        assertEquals(3, small.last - small.first + 1)
        assertEquals(VrMenuModel.itemsFor(env).size - 3, small.first)
        assertEquals(last, small.last)
        assertEquals(10, VrMenuModel.VISIBLE_ROWS)
    }
}
