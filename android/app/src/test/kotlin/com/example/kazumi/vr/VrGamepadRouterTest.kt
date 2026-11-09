package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests: VrGamepadRouter deliberately avoids all android.view.* classes. */
class VrGamepadRouterTest {

    private fun press(r: VrGamepadRouter, key: Int, timeMs: Long = 0L): VrCommand =
        r.onKey(key, VrKeys.ACTION_DOWN, isGamepad = true, timeMs = timeMs)

    private fun release(r: VrGamepadRouter, key: Int, timeMs: Long = 0L): VrCommand =
        r.onKey(key, VrKeys.ACTION_UP, isGamepad = true, timeMs = timeMs)

    @Test
    fun nonGamepadEventsAlwaysPassThrough() {
        val r = VrGamepadRouter()
        assertEquals(
            VrCommand.PassThrough,
            r.onKey(VrKeys.BUTTON_L1, VrKeys.ACTION_DOWN, isGamepad = false),
        )
    }

    /** The original game must keep receiving every button while VR is off. */
    @Test
    fun keysPassThroughToTheGameWhenVrIsOffAndMenuIsClosed() {
        val r = VrGamepadRouter()
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.DPAD_UP))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_A))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT))
    }

    @Test
    fun shoulderComboTogglesVrExactlyOncePerHold() {
        val r = VrGamepadRouter()
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1))
        // OS key-repeat while both are held must not toggle again.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_R1))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_R1))

        r.vrEnabled = true
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1))
    }

    /**
     * START+SELECT is reserved for the menu long-press; it must NOT exit VR any more.
     *
     * The 500 ms timer starts when the combination first becomes held (the initial ACTION_DOWN at
     * 1000L), NOT on the first OS key-repeat. If it started on the repeat (1200L) instead, the
     * device's repeat delay would be added on top of the threshold and the 1600L event below would
     * still be only 400 ms in. The 1499L/1600L pair pins that contract down.
     */
    @Test
    fun startSelectDoesNotToggleVrButLongPressOpensMenu() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 1000L))
        // First OS key-repeat: 200 ms elapsed, still below threshold.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 1200L))
        // 499 ms since the initial press: must not fire yet.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 1499L))
        // 600 ms since the initial press: fires exactly once.
        assertEquals(VrCommand.ToggleMenu, press(r, VrKeys.BUTTON_START, 1600L))
    }

    @Test
    fun menuLongPressOpensOnlyInsideVr() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        // Short press of SELECT/START does NOT open menu (passes through to game).
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 1000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_SELECT, 1100L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 1200L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_START, 1300L))
        // Long-press START+SELECT together opens menu. The timer starts here at 2000L, when the
        // second key of the combination goes down (not at the first OS repeat).
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 2000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 2000L))
        // 100 ms in: still pass-through.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START, 2100L))
        // 600 ms in: past the 500 ms threshold, toggles menu.
        assertEquals(VrCommand.ToggleMenu, press(r, VrKeys.BUTTON_START, 2600L))
        r.menuOpen = true
        assertEquals(VrCommand.Confirm, press(r, VrKeys.BUTTON_A))
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_B))
    }

    /** HOME/MODE also opens the menu only after a long press, never on a short tap. */
    @Test
    fun homeKeyRequiresLongPress() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 1000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_MODE, 1100L))
        // Hold again: the timer starts at this initial press (2000L), so the first OS repeat below
        // is already 100 ms in. 500 ms after 2000L it fires exactly once.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 2000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 2100L))
        assertEquals(VrCommand.ToggleMenu, press(r, VrKeys.BUTTON_MODE, 2600L))
        // Key-repeat after firing must not toggle the menu back closed.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 3200L))
        // Releasing clears the latch, so a fresh long press can toggle again.
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_MODE, 3300L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 4000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 4100L))
        assertEquals(VrCommand.ToggleMenu, press(r, VrKeys.BUTTON_MODE, 4600L))
    }

    /** SELECT/START/MODE close the panel when it is already open (single press). */
    @Test
    fun menuKeyClosesThePanelWhenItIsAlreadyOpen() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_SELECT))
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_START))
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_MODE))
    }

    /**
     * Real-device regression (second device, 2026-11-08): "Select 和 Home 键无法正常被在游戏中使用".
     * With VR on and the panel CLOSED, a short SELECT or MODE press MUST be PassThrough so
     * MainActivity can forward it straight to the game via dispatchVrGameKey. The router reserves
     * these keys only for the long-press menu trigger; it must never swallow the short press, and
     * their OS key-repeats must stay pass-through too (the game owns the hold).
     */
    @Test
    fun selectAndModeShortPressPassThroughToTheGameWhileVrPanelIsClosed() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 1000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_SELECT, 1100L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 2000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_MODE, 2100L))
        // A lone SELECT hold never becomes the START+SELECT combo, so even its repeats pass through.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 3000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_SELECT, 3200L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_SELECT, 3300L))
    }

    /** Closing the panel must immediately make SELECT/MODE ordinary game keys again. */
    @Test
    fun selectAndModeBecomeGameKeysImmediatelyAfterPanelCloses() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_SELECT))
        assertEquals(VrCommand.Consumed, release(r, VrKeys.BUTTON_SELECT))
        r.menuOpen = false // VrSession.setMenuOpen(false)
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_MODE, 5000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_MODE, 5100L))
    }

    /**
     * LB / RB are the second-level PAGE keys (user request). The L1+R1 VR toggle is suppressed
     * while the panel is open, so the same physical shoulders can safely own both roles.
     */
    @Test
    fun shoulderButtonsPageTheMenuInsteadOfTogglingVr() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.ChangePage(-1), press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ChangePage(1), press(r, VrKeys.BUTTON_R1))
        // Even with both shoulders held at once, the VR toggle must NOT fire inside the menu.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_R1))
    }

    @Test
    fun openMenuRoutesDpadAndFaceButtonsOnlyToThePanel() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.MoveSelection(-1), press(r, VrKeys.DPAD_UP))
        assertEquals(VrCommand.MoveSelection(1), press(r, VrKeys.DPAD_DOWN))
        // D-pad LEFT/RIGHT are deliberately inert: they must neither page nor change a value,
        // and must never fall through to the hidden 2D Compose UI.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.DPAD_LEFT))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.DPAD_RIGHT))
        assertEquals(VrCommand.ChangePage(-1), press(r, VrKeys.PAGE_PREV_KEY))
        assertEquals(VrCommand.ChangePage(1), press(r, VrKeys.PAGE_NEXT_KEY))
        assertEquals(VrCommand.Confirm, press(r, VrKeys.DPAD_CENTER))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_X))
        assertEquals(VrCommand.Consumed, release(r, VrKeys.BUTTON_X))
    }

    /**
     * Regression: paging with BOTH shoulders held, then closing the panel while they are still
     * down, must NOT make the next unrelated key look like the L1+R1 VR toggle.
     */
    @Test
    fun shouldersHeldFromMenuPagingCannotToggleVrAfterPanelCloses() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.ChangePage(-1), press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ChangePage(1), press(r, VrKeys.BUTTON_R1))
        r.menuOpen = false // VrSession.setMenuOpen(false), shoulders still physically held
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_A))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.DPAD_UP))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_START))
        // The stale shoulders' OWN key-repeat is just as dangerous as an unrelated key: a plain
        // comboPressed() check would see L1+R1 here and exit VR. Only a fresh 0->1 edge may fire.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_R1))
        // Releasing and pressing the pair again MUST still work (guard is not a permanent lock).
        release(r, VrKeys.BUTTON_L1)
        release(r, VrKeys.BUTTON_R1)
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1))
    }

    /** Holding a shoulder must not fling the user across several category pages. */
    @Test
    fun heldShoulderDoesNotRepeatPageChanges() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.ChangePage(1), press(r, VrKeys.PAGE_NEXT_KEY, 1000L))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.PAGE_NEXT_KEY, 1100L))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.PAGE_NEXT_KEY, 5000L))
        assertEquals(VrCommand.Consumed, release(r, VrKeys.PAGE_NEXT_KEY, 5100L))
        // A genuinely new press changes exactly one more page.
        assertEquals(VrCommand.ChangePage(1), press(r, VrKeys.PAGE_NEXT_KEY, 5200L))
    }

    @Test
    fun verticalStickAdjustsCoarsely() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        // Vertical stick deflection uses coarse steps (5). Space the events past menuRepeatMs so
        // the router's rate limiter does not swallow the second one.
        assertEquals(VrCommand.Adjust(VrGamepadRouter.STICK_COARSE_STEPS), r.onAxis(0f, 0.9f, 0L))
        assertEquals(VrCommand.Adjust(-VrGamepadRouter.STICK_COARSE_STEPS), r.onAxis(0f, -0.9f, 1000L))
    }

    @Test
    fun horizontalStickAdjustsFinely() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        // Horizontal stick deflection uses one fine step.
        assertEquals(VrCommand.Adjust(-1), r.onAxis(-0.9f, 0f, 0L))
        assertEquals(VrCommand.Adjust(1), r.onAxis(0.9f, 0f, 1000L))
    }

    /**
     * Real-device regression: "左摇杆上下滑动是没有办法正常选择选项的切换". The old router always
     * emitted Adjust, which VrSession dropped while disarmed, so the stick looked completely dead.
     * Disarmed => NAVIGATE; A-armed => ADJUST. Both modes are pinned here.
     */
    @Test
    fun disarmedStickSelectsRowsAndArmedStickAdjustsValues() {
        val r = VrGamepadRouter()
        r.menuOpen = true
        assertEquals(VrCommand.MoveSelection(-1), r.onAxis(0f, -0.9f, 0L))
        assertEquals(VrCommand.MoveSelection(1), r.onAxis(0f, 0.9f, 1000L))
        // Horizontal deflection stays inert while disarmed: no paging, no accidental value drift.
        assertEquals(VrCommand.Consumed, r.onAxis(0.9f, 0f, 2000L))

        r.editing = true
        r.resetAxisHold()
        assertEquals(
            VrCommand.Adjust(-VrGamepadRouter.STICK_COARSE_STEPS),
            r.onAxis(0f, -0.9f, 3000L),
        )
        assertEquals(VrCommand.Adjust(1), r.onAxis(0.9f, 0f, 4000L))
    }

    /** Bluetooth pads often send the D-pad as AXIS_HAT_Y: one row per press, never auto-scroll. */
    @Test
    fun hatAxisSelectsRowsOncePerPressAndNotWhileHeld() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        assertEquals(VrCommand.MoveSelection(-1), r.onHat(-1f))
        assertEquals(VrCommand.Consumed, r.onHat(-1f))
        assertEquals(VrCommand.Consumed, r.onHat(0f))
        assertEquals(VrCommand.MoveSelection(1), r.onHat(1f))
        assertEquals(VrCommand.Consumed, r.onHat(1f))
        assertEquals(VrCommand.MoveSelection(-1), r.onHat(-1f))
    }

    @Test
    fun hatAxisIsIgnoredWhenMenuIsClosed() {
        assertEquals(VrCommand.PassThrough, VrGamepadRouter().onHat(-1f))
    }

    /** A restarts the stick throttle but must NOT re-arm a physically held HAT direction. */
    @Test
    fun resetAxisHoldRestartsStickButKeepsHeldHatEdge() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        assertEquals(VrCommand.Adjust(VrGamepadRouter.STICK_COARSE_STEPS), r.onAxis(0f, 0.9f, 1000L))
        // Without the reset this event (10ms later) would be swallowed by the repeat throttle.
        r.resetAxisHold()
        assertEquals(VrCommand.Adjust(VrGamepadRouter.STICK_COARSE_STEPS), r.onAxis(0f, 0.9f, 1010L))

        assertEquals(VrCommand.MoveSelection(-1), r.onHat(-1f))
        r.resetAxisHold()
        // Still physically held: resetting must NOT cause a phantom extra row ("press A, selection
        // jumps"). Zeroing lastHatY here would make the very next motion event fire again.
        assertEquals(VrCommand.Consumed, r.onHat(-1f))
        assertEquals(VrCommand.Consumed, r.onHat(0f))
        // Release + press again is a genuine new edge.
        assertEquals(VrCommand.MoveSelection(-1), r.onHat(-1f))
    }

    @Test
    fun axisIsIgnoredWhenMenuIsClosed() {
        val r = VrGamepadRouter()
        assertEquals(VrCommand.PassThrough, r.onAxis(1f, 0f, 1000L))
    }

    @Test
    fun axisBelowDeadZoneIsConsumedWithoutProducingACommand() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        assertEquals(VrCommand.Consumed, r.onAxis(0.1f, 0f, 1000L))
    }

    @Test
    fun customDeadZoneIsHonoured() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.deadZone = 0.5f }
        assertEquals(VrCommand.Consumed, r.onAxis(0.3f, 0f, 0L))
    }

    @Test
    fun verticalAxisCoarselyAdjustsWithRepeatDelay() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true; it.repeatMs = 140L }
        val coarse = VrCommand.Adjust(VrGamepadRouter.STICK_COARSE_STEPS)
        assertEquals(coarse, r.onAxis(0f, 0.9f, 1000L))
        assertEquals(VrCommand.Consumed, r.onAxis(0f, 0.9f, 1050L))
        assertEquals(coarse, r.onAxis(0f, 0.9f, 1200L))
    }

    @Test
    fun horizontalAxisAdjustsValue() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        assertEquals(VrCommand.Adjust(-1), r.onAxis(-0.9f, 0f, 0L))
    }

    @Test
    fun returningStickToCentreResetsRepeatTimer() {
        val r = VrGamepadRouter().also { it.menuOpen = true; it.editing = true }
        r.onAxis(0f, 0.9f, 1000L)
        assertEquals(VrCommand.Consumed, r.onAxis(0f, 0f, 1050L))
        assertEquals(
            VrCommand.Adjust(VrGamepadRouter.STICK_COARSE_STEPS),
            r.onAxis(0f, 0.9f, 1060L),
        )
    }

    @Test
    fun resetClearsHeldKeysAndTheComboLatch() {
        val r = VrGamepadRouter()
        press(r, VrKeys.BUTTON_L1)
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1))
        r.reset()
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L1))
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1))
    }

    /** Holding a D-pad direction must scroll the panel, not just move one row. */
    @Test
    fun heldDpadRepeatsNavigationAtTheConfiguredCadence() {
        val r = VrGamepadRouter().also {
            it.vrEnabled = true
            it.menuOpen = true
            it.repeatMs = 140L
        }
        assertEquals(VrCommand.MoveSelection(1), press(r, VrKeys.DPAD_DOWN, 1000L))
        // The first OS repeat is accepted immediately...
        assertEquals(VrCommand.MoveSelection(1), press(r, VrKeys.DPAD_DOWN, 1060L))
        // ...then repeats are rate limited to menuRepeatMs.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.DPAD_DOWN, 1100L))
        assertEquals(VrCommand.MoveSelection(1), press(r, VrKeys.DPAD_DOWN, 1210L))
    }

    /** OS key-repeat on A/B/SELECT must never confirm or close the panel repeatedly. */
    @Test
    fun heldActionButtonsDoNotRepeatInTheMenu() {
        val r = VrGamepadRouter().also { it.vrEnabled = true; it.menuOpen = true }
        assertEquals(VrCommand.Confirm, press(r, VrKeys.BUTTON_A, 1000L))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_A, 2000L))
        assertEquals(VrCommand.CloseMenu, press(r, VrKeys.BUTTON_B, 3000L))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_B, 4000L))
    }

    @Test
    fun keyRepeatHonoursTheSafetyFloor() {
        val r = VrGamepadRouter().also {
            it.vrEnabled = true
            it.menuOpen = true
            it.repeatMs = 10L
        }
        assertEquals(VrCommand.MoveSelection(-1), press(r, VrKeys.DPAD_UP, 1000L))
        assertEquals(VrCommand.MoveSelection(-1), press(r, VrKeys.DPAD_UP, 1050L))
        // 30 ms is below the 80 ms floor even though repeatMs says 10 ms.
        assertEquals(VrCommand.Consumed, press(r, VrKeys.DPAD_UP, 1080L))
        assertEquals(VrCommand.MoveSelection(-1), press(r, VrKeys.DPAD_UP, 1140L))
    }

    // --- L2+R2 passthrough ON/OFF shortcut -------------------------------------------------
    // User request: the shortcut ONLY opens/closes passthrough. The three layout styles are
    // settings and are deliberately NOT reachable from a gamepad hold.

    @Test
    fun l2r2LongPressTogglesPassthroughExactlyOncePerHold() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1000L))
        // Timer arms on the INITIAL down that completes the pair, not on the first OS repeat.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 1400L))
        assertEquals(VrCommand.TogglePassthrough, press(r, VrKeys.BUTTON_R2, 1500L))
        // The latch survives key-repeat; the repeat stays an ordinary game key.
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1600L))
        // Releasing EITHER trigger clears the hold, so a fresh deliberate long-press is needed.
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_L2, 1700L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1800L))
        assertEquals(VrCommand.TogglePassthrough, press(r, VrKeys.BUTTON_L2, 2300L))
    }

    @Test
    fun l2r2ShortPressNeverTogglesPassthrough() {
        val r = VrGamepadRouter().also { it.vrEnabled = true }
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 1000L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_R2, 1100L))
        assertEquals(VrCommand.PassThrough, release(r, VrKeys.BUTTON_L2, 1100L))
    }

    @Test
    fun l2r2IsInertOutsideVrAndWhileTheMenuIsOpen() {
        val r = VrGamepadRouter()
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 2000L))
        r.vrEnabled = true
        r.menuOpen = true
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_L2, 3000L))
        assertEquals(VrCommand.Consumed, press(r, VrKeys.BUTTON_R2, 4000L))
    }

    @Test
    fun heldTriggersDoNotInterfereWithTheL1R1VrEntryCombo() {
        val r = VrGamepadRouter()
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_R2, 1000L))
        assertEquals(VrCommand.PassThrough, press(r, VrKeys.BUTTON_L1, 1100L))
        assertEquals(VrCommand.ToggleVrMode, press(r, VrKeys.BUTTON_R1, 1100L))
    }

    // --- Analogue LT/RT (the REAL-DEVICE trigger path) --------------------------------------
    // Most pads report LT/RT as MotionEvent axes, not KEYCODE_BUTTON_L2/R2. VrSession feeds
    // those values into this pure predicate, so it must be locked down without Android stubs.

    @Test
    fun analogueTriggerPredicateRequiresBothPastThreshold() {
        val r = VrGamepadRouter()
        assertEquals(false, r.passthroughTriggersHeld(0f, 0f))
        assertEquals(false, r.passthroughTriggersHeld(1f, 0.49f))
        assertEquals(false, r.passthroughTriggersHeld(0.49f, 1f))
        assertEquals(true, r.passthroughTriggersHeld(0.5f, 0.5f))
        assertEquals(true, r.passthroughTriggersHeld(1f, 1f))
    }

    @Test
    fun analogueTriggerAxisConstantsMatchAospMotionEvent() {
        assertEquals(17, VrKeys.AXIS_LTRIGGER)
        assertEquals(18, VrKeys.AXIS_RTRIGGER)
        assertEquals("legacy gas", 22, VrKeys.AXIS_GAS)
        assertEquals("legacy brake", 23, VrKeys.AXIS_BRAKE)
        assertEquals(0.5f, VrKeys.TRIGGER_PRESS_THRESHOLD, 0f)
        assertEquals("debounce", 800L, VrKeys.PASSTHROUGH_TOGGLE_DEBOUNCE_MS)
    }
}
