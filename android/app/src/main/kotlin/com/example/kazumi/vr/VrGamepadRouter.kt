package com.example.kazumi.vr

import kotlin.math.abs
import kotlin.math.max

/** Output of the gamepad router. The host decides what to do with each command. */
sealed class VrCommand {
    /** Let the original stream input pipeline handle this event unchanged. */
    object PassThrough : VrCommand()

    /** Swallowed by the VR layer (only happens while the VR settings panel is open). */
    object Consumed : VrCommand()

    object ToggleVrMode : VrCommand()
    /** L2+R2 long-press: FLIP passthroughEnabled only. Layout stays whatever the settings say. */
    object TogglePassthrough : VrCommand()
    object ToggleMenu : VrCommand()
    object CloseMenu : VrCommand()
    object Confirm : VrCommand()
    data class MoveSelection(val delta: Int) : VrCommand()
    /** LB / RB switch the second-level settings PAGE (never change a value). */
    data class ChangePage(val delta: Int) : VrCommand()
    /** Left stick only. VrSession gated by "A armed editing"; no D-pad ever emits this. */
    data class Adjust(val steps: Int) : VrCommand()
}

/**
 * Pure state machine for PHYSICAL GAMEPAD input in VR mode.
 *
 * Design rules (from the user, do not "improve" these):
 *  - No gaze / eye / dwell / head-gesture activation of any kind. Head pose is view-only.
 *  - L1+R1 is ENTRY-ONLY: it opens VR, never closes it (VrSession enforces this). Exiting is a
 *    deliberate act via the phone's BACK key or the "退出 VR" menu row. START+SELECT and
 *    HOME/MODE are NOT single-press shortcuts: the
 *    settings panel opens only after a LONG PRESS (>= VrKeys.MENU_LONG_PRESS_MS) so a stray tap
 *    in game can never open it. Short presses pass straight through to the game unchanged. The
 *    hold timer starts on the initial ACTION_DOWN that completes the combination, NOT on the first
 *    OS key-repeat (see the note in onKey for why).
 *  - When the panel is open, D-pad UP/DOWN and the LEFT STICK's vertical axis BOTH select a row;
 *    LB (L1) / RB (R1) switch the second-level PAGE; A arms value editing / flips a switch;
 *    B closes the panel. D-pad LEFT/RIGHT and horizontal stick deflection are deliberately inert
 *    while disarmed (the user asked that paging NOT live on the D-pad).
 *    NUMERIC VALUES are changed ONLY by the left analogue stick AND ONLY while A has armed
 *    editing (VrMenuModel.adjustIfEditing, enforced in VrSession). Before A, the stick MUST still
 *    do something useful: real-device feedback was "摇杆上下滑动不能切换选项", because the old
 *    router emitted Adjust unconditionally and VrSession silently dropped it while disarmed.
 *  - L2+R2 (LT/RT) long-press is a DEDICATED ON/OFF switch for camera passthrough. It emits ONE
 *    TogglePassthrough per hold and NEVER changes the layout mode: the three styles live in the
 *    settings (user request: "快捷键只负责开启和关闭，具体的样式调整放在设置里面调就好").
 *    It is disabled while the in-VR menu is open and while VR is off.
 *  - Pads that report their D-pad as AXIS_HAT_Y instead of KEYCODE_DPAD_* are handled by onHat().
 *
 * No Android framework types are used here; VrKeys holds plain Int constants so JVM unit tests run
 * without Robolectric or mocked Android stubs.
 */
class VrGamepadRouter {
    var vrEnabled: Boolean = false
    var menuOpen: Boolean = false
    /** Mirrors VrSession.editMode. While true the stick ADJUSTS; while false it NAVIGATES. */
    var editing: Boolean = false
    var deadZone: Float = VrSettings.DEFAULT.gamepadDeadZone
    var repeatMs: Long = VrSettings.DEFAULT.menuRepeatMs

    private val pressed = HashSet<Int>()
    private var comboFired = false
    private var lastAxisMs = Long.MIN_VALUE
    private var lastKeyRepeatMs = Long.MIN_VALUE
    /** Last normalised AXIS_HAT_Y (-1/0/1) so a held hat direction fires only ONCE. */
    private var lastHatY = 0f
    /** Time when a menu long-press hold began. Long.MIN_VALUE = no hold in progress. */
    private var menuHoldStartMs = Long.MIN_VALUE
    /** Latches once a long-press fired, until the hold is released, so repeats cannot re-toggle. */
    private var menuHoldFired = false
    /** Time when the L2+R2 hold began. Long.MIN_VALUE = no hold in progress. */
    private var passthroughHoldStartMs = Long.MIN_VALUE
    /** Latches once the L2+R2 long-press fired, until one of the triggers is released. */
    private var passthroughHoldFired = false

    fun reset() {
        pressed.clear()
        comboFired = false
        lastAxisMs = Long.MIN_VALUE
        lastKeyRepeatMs = Long.MIN_VALUE
        menuHoldStartMs = Long.MIN_VALUE
        menuHoldFired = false
        clearPassthroughHold()
        editing = false
        lastHatY = 0f
    }

    fun onKey(keyCode: Int, action: Int, isGamepad: Boolean, timeMs: Long = 0L): VrCommand {
        if (!isGamepad) return VrCommand.PassThrough
        if (action == VrKeys.ACTION_UP) {
            pressed.remove(keyCode)
            lastKeyRepeatMs = Long.MIN_VALUE
            if (!comboPressed()) comboFired = false
            // Releasing any key of the hold cancels the timer and clears the latch, so the user
            // must deliberately press-and-hold again to toggle the menu a second time.
            if (!menuHoldActive()) clearMenuHold()
            // Releasing EITHER trigger drops the hold, so a fresh deliberate long-press is
            // required for the next ON/OFF toggle. OS key-repeat alone can never re-fire it.
            if (!passthroughComboHeld()) clearPassthroughHold()
            return if (menuOpen) VrCommand.Consumed else VrCommand.PassThrough
        }
        if (action != VrKeys.ACTION_DOWN) return VrCommand.PassThrough

        // L1+R1 toggles VR ONLY on the edge where the combination BECOMES held. This is stricter
        // than a plain `comboPressed()` check and fixes two real-device bugs:
        //  1. Shoulders left in `pressed` after using them to PAGE the menu would make the very
        //     next unrelated key (A, D-pad, ...) look like the VR toggle -> accidental exit.
        //  2. Even their own OS key-repeat (alreadyDown == true) would re-arm the combo once the
        //     panel closed, with the same result.
        // A stale combination can never fire; the user must release and press the pair again.
        val comboBefore = comboPressed()
        val alreadyDown = !pressed.add(keyCode)
        val comboNow = comboPressed()
        // L2+R2 is a SEPARATE combo from L1+R1 (which enters VR). Tracking it here keeps the two
        // independent: a held trigger pair can never accidentally look like the VR toggle.
        val passthroughComboNow = passthroughComboHeld()
        val shoulderDown = keyCode == VrKeys.BUTTON_L1 || keyCode == VrKeys.BUTTON_R1
        if (!menuOpen && comboNow) {
            if (!comboBefore && shoulderDown && !comboFired) {
                comboFired = true
                return VrCommand.ToggleVrMode
            }
            // Suppressed while the menu is open (shoulders page it), and a held/repeated combo
            // must never reach the stream game either.
            if (menuOpen || alreadyDown || comboFired) return VrCommand.Consumed
        }
        // The OS repeats ACTION_DOWN while a key is held. Outside the menu those repeats must stay
        // pass-through so the game keeps its original behaviour. A repeat is also the only clock
        // tick this pure-JVM state machine gets: the timer is normally armed on the initial
        // ACTION_DOWN (see below) and a repeat merely checks whether 500 ms have elapsed. The
        // MIN_VALUE arm below is only a defensive fallback for a hold that became valid later.
        if (alreadyDown) {
            if (!menuOpen) {
                // L2+R2 long-press -> ONE edge-triggered ON/OFF toggle. It deliberately does NOT
                // cycle the three layout styles; those are settings (user request). The latch is
                // cleared only when a trigger is released, so OS key-repeat cannot re-fire it.
                if (vrEnabled && passthroughComboNow && !passthroughHoldFired &&
                    passthroughHoldStartMs != Long.MIN_VALUE &&
                    timeMs - passthroughHoldStartMs >= VrKeys.PASSTHROUGH_LONG_PRESS_MS
                ) {
                    passthroughHoldFired = true
                    return VrCommand.TogglePassthrough
                }
                if (vrEnabled && menuHoldActive()) {
                    if (menuHoldStartMs == Long.MIN_VALUE) {
                        menuHoldStartMs = timeMs
                    } else if (!menuHoldFired &&
                        timeMs - menuHoldStartMs >= VrKeys.MENU_LONG_PRESS_MS
                    ) {
                        menuHoldFired = true
                        return VrCommand.ToggleMenu
                    }
                } else {
                    clearMenuHold()
                }
                return VrCommand.PassThrough
            }
            // In the menu only the D-pad repeats: holding A/B/SELECT must never confirm or close
            // repeatedly. Repeat cadence follows the user's "menu repeat" setting (>= 80 ms).
            if (keyCode !in REPEATABLE_MENU_KEYS) return VrCommand.Consumed
            val delay = repeatMs.coerceAtLeast(MIN_KEY_REPEAT_MS)
            if (lastKeyRepeatMs != Long.MIN_VALUE && timeMs - lastKeyRepeatMs < delay) {
                return VrCommand.Consumed
            }
            lastKeyRepeatMs = timeMs
            return menuKey(keyCode)
        }

        lastKeyRepeatMs = Long.MIN_VALUE
        if (menuOpen) return menuKey(keyCode)
        // Arm the L2+R2 hold clock on the INITIAL down that completes the pair, exactly like the
        // menu long-press: starting it on the first OS key-repeat would add the device's repeat
        // delay on top of the user's 500 ms hold.
        if (vrEnabled && passthroughComboNow && !passthroughHoldFired &&
            passthroughHoldStartMs == Long.MIN_VALUE
        ) {
            passthroughHoldStartMs = timeMs
        }
        // Arm the long-press timer as soon as the hold becomes active: the initial ACTION_DOWN of
        // the second START/SELECT key, or of MODE. Starting it on the first OS key-repeat instead
        // would add that controller's repeat delay (~400 ms, varies per device) on top of the
        // user's 500 ms hold. Repeats are still required to FIRE (this class has no timer/Handler
        // of its own), they just no longer start the clock.
        if (vrEnabled && menuHoldActive()) {
            if (menuHoldStartMs == Long.MIN_VALUE) {
                menuHoldStartMs = timeMs
            }
        } else {
            clearMenuHold()
        }
        // No single key opens the menu on its own.
        return VrCommand.PassThrough
    }

    /**
     * Left analogue stick while the menu is open. TWO modes, chosen by [editing]:
     *
     *  - **Disarmed (default)**: vertical deflection SELECTS the previous/next row, at the same
     *    repeat cadence as the D-pad. Horizontal deflection is swallowed. This is the fix for the
     *    real-device report "左摇杆上下滑动没办法正常切换选项": previously every stick event became
     *    `Adjust`, which VrSession silently dropped while disarmed, so the stick looked broken.
     *  - **A-armed**: vertical = five coarse steps, horizontal = one fine step of the selected
     *    RANGE value. Nothing can drift before the user explicitly presses A.
     *
     * Returning to the centre resets the cadence, and every event is consumed so the stick never
     * also moves the in-game camera while the panel is up.
     */
    fun onAxis(x: Float, y: Float, timeMs: Long): VrCommand {
        if (!menuOpen) return VrCommand.PassThrough
        val ax = abs(x)
        val ay = abs(y)
        if (max(ax, ay) < deadZone) {
            lastAxisMs = Long.MIN_VALUE
            return VrCommand.Consumed
        }
        if (lastAxisMs != Long.MIN_VALUE && timeMs - lastAxisMs < repeatMs) return VrCommand.Consumed
        lastAxisMs = timeMs
        val vertical = ay >= ax
        if (!editing) {
            return if (vertical) VrCommand.MoveSelection(if (y < 0f) -1 else 1) else VrCommand.Consumed
        }
        return if (vertical) {
            VrCommand.Adjust(if (y < 0f) -STICK_COARSE_STEPS else STICK_COARSE_STEPS)
        } else {
            VrCommand.Adjust(if (x < 0f) -1 else 1)
        }
    }

    /**
     * D-pad reported as AXIS_HAT_Y (common on Bluetooth pads). Edge-triggered: pressing UP/DOWN
     * fires exactly one MoveSelection, and HOLDING it does not scroll until it is released and
     * pressed again. Horizontal hat is intentionally ignored — LB/RB own page navigation.
     */
    fun onHat(hatY: Float): VrCommand {
        if (!menuOpen) return VrCommand.PassThrough
        val direction = when {
            hatY <= -0.5f -> -1f
            hatY >= 0.5f -> 1f
            else -> 0f
        }
        val command = if (direction != 0f && direction != lastHatY) {
            VrCommand.MoveSelection(direction.toInt())
        } else {
            VrCommand.Consumed
        }
        lastHatY = direction
        return command
    }

    /**
     * Pure predicate shared with VrSession: are BOTH analogue LT/RT triggers pressed past the
     * threshold? Kept here (instead of in the Android-only VrSession) so JVM unit tests can lock
     * the real-device trigger path without Robolectric. The keycode path stays in onKey().
     */
    fun passthroughTriggersHeld(l2: Float, r2: Float): Boolean =
        l2 >= VrKeys.TRIGGER_PRESS_THRESHOLD && r2 >= VrKeys.TRIGGER_PRESS_THRESHOLD

    /**
     * Called when A arms/disarms editing or when the page changes, so the stick's repeat cadence
     * restarts and the new mode responds immediately. HAT state is deliberately NOT cleared:
     * lastHatY describes a PHYSICALLY HELD direction, and zeroing it would make a still-pressed
     * D-pad fire one extra MoveSelection on the next motion event ("press A, selection jumps").
     * Held hat directions therefore keep their edge until the user actually releases them.
     */
    fun resetAxisHold() {
        lastAxisMs = Long.MIN_VALUE
    }

    private fun menuKey(keyCode: Int): VrCommand = when (keyCode) {
        VrKeys.DPAD_UP -> VrCommand.MoveSelection(-1)
        VrKeys.DPAD_DOWN -> VrCommand.MoveSelection(1)
        // LB / RB own page navigation. D-pad LEFT/RIGHT are intentionally swallowed: the user
        // does not want paging on the D-pad, and leaving them as PassThrough would let the
        // underlying 2D Compose UI steal focus with them.
        VrKeys.PAGE_PREV_KEY -> VrCommand.ChangePage(-1)
        VrKeys.PAGE_NEXT_KEY -> VrCommand.ChangePage(1)
        VrKeys.DPAD_LEFT, VrKeys.DPAD_RIGHT -> VrCommand.Consumed
        VrKeys.BUTTON_A, VrKeys.DPAD_CENTER -> VrCommand.Confirm
        // B, SELECT, START and MODE all close the panel so a user without a B button is not trapped.
        VrKeys.BUTTON_B -> VrCommand.CloseMenu
        in VrKeys.MENU_CLOSE_KEYS -> VrCommand.CloseMenu
        else -> VrCommand.Consumed
    }

    private fun comboPressed(): Boolean = VrKeys.VR_TOGGLE_COMBOS.any { (a, b) ->
        pressed.contains(a) && pressed.contains(b)
    }

    /** True while START+SELECT are both held, OR the HOME/MODE key is held. */
    private fun menuHoldActive(): Boolean {
        val combo = VrKeys.MENU_LONG_PRESS_COMBO
        val bothHeld = pressed.contains(combo[0]) && pressed.contains(combo[1])
        return bothHeld || pressed.contains(VrKeys.BUTTON_MODE)
    }

    private fun clearMenuHold() {
        menuHoldStartMs = Long.MIN_VALUE
        menuHoldFired = false
    }

    /** True only while BOTH triggers are physically held. Independent of the L1+R1 VR combo. */
    private fun passthroughComboHeld(): Boolean =
        pressed.contains(VrKeys.PASSTHROUGH_TOGGLE_COMBO[0]) &&
            pressed.contains(VrKeys.PASSTHROUGH_TOGGLE_COMBO[1])

    private fun clearPassthroughHold() {
        passthroughHoldStartMs = Long.MIN_VALUE
        passthroughHoldFired = false
    }

    companion object {
        /** Vertical stick deflection is a coarse adjustment; horizontal is one fine step. */
        const val STICK_COARSE_STEPS = 5

        /**
         * Only UP/DOWN repeat while held (scrolling rows). A/B/SELECT must not, and neither may
         * LB/RB: those switch the second-level PAGE, and OS key-repeat would otherwise fling the
         * user to the first/last category from a single held press.
         */
        val REPEATABLE_MENU_KEYS = intArrayOf(
            VrKeys.DPAD_UP,
            VrKeys.DPAD_DOWN,
        )

        /** Safety floor so a very small menuRepeatMs cannot turn into a CPU-bound spin. */
        const val MIN_KEY_REPEAT_MS = 80L
    }
}
