package com.example.kazumi.vr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-wide VR state and the single entry point for physical-gamepad interception.
 *
 * MainActivity calls onKeyEvent()/onMotionEvent() BEFORE NativeStreamInputRouter. Returning true
 * means the VR layer consumed the event; otherwise the original stream input path is untouched.
 *
 * IMPORTANT: there is no gaze/eye/dwell input anywhere in this file. Head pose only reaches
 * VrTheaterRenderer as a view matrix.
 */
object VrSession {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _settings = MutableStateFlow(VrSettings.DEFAULT)
    val settings: StateFlow<VrSettings> = _settings.asStateFlow()

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    /** Active second-level settings page (category chip). */
    private val _menuPage = MutableStateFlow(VrMenuModel.pages.first())
    val menuPage: StateFlow<VrMenuPage> = _menuPage.asStateFlow()

    /** True only after A armed value editing on a RANGE row; the left stick is gated on this. */
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /** Real decoder frame aspect, written by Streaming.kt.onFrameResolutionChanged. */
    @Volatile var videoAspect: Float = VrScreenGeometry.DEFAULT_ASPECT
        private set

    /** Guards against 0/NaN/rotated extremes; the renderer rebuilds its mesh when this changes. */
    fun setVideoAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height.toFloat()
        if (aspect.isNaN() || aspect.isInfinite()) return
        videoAspect = aspect.coerceIn(0.25f, 8f)
    }

    /** Set by VrModeHost so the "scan Cardboard QR" menu row can reach the SDK view. */
    @Volatile var onScanQr: (() -> Unit)? = null

    /** Set by VrModeHost so the VR menu can trigger head-tracking recenter. */
    @Volatile var onRecenter: (() -> Unit)? = null

    /**
     * Set by MainActivity. Fired on the OPEN -> CLOSED edge of the in-VR settings panel so the
     * host can hand input ownership back to the stream game (cancel a pending 2D menu hold and
     * clear a stuck streamUiActive). Real-device report: "关闭设置页时，无法正常地将输入焦点对焦
     * 至游戏". Deliberately NOT fired when VR itself is switched off — setEnabled(false) already
     * runs the full reset path and _enabled is false by then.
     */
    @Volatile var onVrMenuClosed: (() -> Unit)? = null

    /**
     * Set by MainActivity. Requests the CAMERA runtime permission whenever passthrough is switched
     * OFF -> ON, no matter WHICH entry point did it (2D settings page, in-VR 环境 menu, or the
     * L2+R2 shortcut). Without this, a shortcut-enabled passthrough on a device that never visited
     * the 2D settings page would silently fall back to black with no way to grant the permission.
     * MainActivity clears this in onDestroy() so the singleton never retains a dead Activity.
     */
    @Volatile var requestCameraPermission: (() -> Unit)? = null

    private var store: VrSettingsStore? = null
    private val router = VrGamepadRouter()

    // Analogue LT/RT long-press. A held trigger axis can STOP emitting MotionEvents once it
    // bottoms out, so unlike the keycode path there is no OS key-repeat to "兑现" the 500 ms hold.
    // A main-looper delayed runnable is the clock; releasing a trigger / opening the menu / leaving
    // VR cancels it. This is the REAL-DEVICE path for most Bluetooth / Xbox / PlayStation pads.
    // Lazy on purpose: VrSession.recenter() is reachable from the PURE-JVM VrMenuModelTest via
    // the RECENTER action row. An eager Handler(Looper.getMainLooper()) would initialise at class
    // load and crash those tests with "Method getMainLooper not mocked". The handler is only
    // needed on a real device once onTriggerEvent() arms a hold.
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var triggerHoldRunnable: Runnable? = null
    private var triggerHoldArmed = false
    /**
     * Latched once this physical hold has produced its ONE toggle. Some pads keep reporting a
     * bottomed-out trigger axis at a fixed rate; without this latch those extra MotionEvents would
     * re-arm the runnable every 500 ms and flip passthrough on/off while the user simply holds the
     * triggers down. It is cleared ONLY when a trigger is actually released (or VR/menu state
     * changes), so one deliberate long-press == exactly one ON/OFF.
     */
    private var triggerHoldConsumed = false
    private var lastPassthroughToggleMs = 0L

    fun attach(context: Context) {
        store = VrSettingsStore(context)
        _settings.value = store!!.load()
        syncRouter()
    }

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        Log.d("VrSession", "setEnabled($value): toggling VR; router.vrEnabled=$value")
        _enabled.value = value
        router.vrEnabled = value
        if (!value) {
            setMenuOpen(false)
            cancelTriggerHold()
            router.reset()
            VrVideoSurfaceRegistry.clear()
        }
    }

    fun toggle() = setEnabled(!_enabled.value)

    /** Triggers head-tracking recenter: current head orientation becomes the new "forward". */
    fun recenter() {
        onRecenter?.invoke()
    }

    fun setMenuOpen(value: Boolean) {
        val wasOpen = _menuOpen.value
        _menuOpen.value = value
        router.menuOpen = value
        // The L2+R2 shortcut is deliberately inert while the settings panel owns input.
        if (value) cancelTriggerHold()
        if (!value) {
            setEditMode(false)
            // Only the in-VR "panel closed" edge. setEnabled(false) flips _enabled first, so an
            // ordinary VR exit does NOT fire this and cannot disturb the restored 2D UI state.
            if (wasOpen && _enabled.value) onVrMenuClosed?.invoke()
        }
    }

    /** Switches the second-level page and lands selection on its first row. */
    fun setMenuPage(page: VrMenuPage) {
        _menuPage.value = page
        setEditMode(false)
        _selectedIndex.value = VrMenuModel.firstIndexOf(page)
    }

    /**
     * Single source of truth for A-armed editing. Mirrors the flag into the router (which decides
     * whether the stick NAVIGATES or ADJUSTS) and restarts the stick's repeat cadence, so the new
     * mode responds immediately instead of waiting out the previous mode's throttle window.
     */
    private fun setEditMode(value: Boolean) {
        _editMode.value = value
        router.editing = value
        router.resetAxisHold()
    }

    fun changePage(delta: Int) {
        val index = VrMenuModel.pageIndex(_menuPage.value) + delta
        val clamped = index.coerceIn(0, VrMenuModel.pages.lastIndex)
        setMenuPage(VrMenuModel.pages[clamped])
    }

    /** Selection never leaves the active page: D-pad cannot jump into another category. */
    fun setSelectedIndex(index: Int) {
        _selectedIndex.value = VrMenuModel.clampToPage(index, _menuPage.value)
    }

    fun updateSettings(value: VrSettings) {
        val wasPassthroughEnabled = _settings.value.passthroughEnabled
        val clean = value.sanitized()
        _settings.value = clean
        store?.save(clean)
        syncRouter()
        // ANY entry point that turns passthrough ON must ensure CAMERA has been granted. On denial
        // MainActivity restores the switch to off and the renderer falls back to black. Fires only
        // on the OFF -> ON edge, so re-saving an already-on setting never re-prompts.
        if (!wasPassthroughEnabled && clean.passthroughEnabled) requestCameraPermission?.invoke()
    }

    /**
     * Decides every physical key while VR is on.
     *
     * TWO real-device bugs fixed here (2026-11-08):
     *  1. Only `isGamepadSource()` events used to reach the router. Several Bluetooth pads report
     *     their face buttons / D-pad as SOURCE_KEYBOARD, so every menu key was handed to the 2D
     *     Compose UI instead: rows could not be selected and pages could not be switched. While
     *     VR is enabled we therefore do NOT gate on source — the router only emits commands for a
     *     small explicit key whitelist and leaves everything else alone.
     *  2. KEYCODE_BACK (which is what many pads map B to) used to be swallowed on ACTION_DOWN
     *     while VR was turned off in the same call. ACTION_UP then saw VR==false, fell through to
     *     the 2D back stack and FINISHED THE ACTIVITY. We now consume the whole down+up sequence
     *     and act only on the up.
     *
     * MainActivity additionally drops all non-VR keys once this returns false, so nothing can
     * reach the 2D UI behind the headset.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (_enabled.value) {
            // Back-like keys are owned by the VR layer for BOTH actions, unconditionally.
            if (keyCode == VrKeys.BACK) {
                if (action == VrKeys.ACTION_UP) {
                    if (_menuOpen.value) setMenuOpen(false) else setEnabled(false)
                }
                return true
            }
            val command = router.onKey(keyCode, action, isGamepad = true, event.eventTime)
            if (router.menuOpen) {
                // The settings panel owns input exclusively: even a key the router does not know
                // must not reach the 2D UI or the stream game.
                if (command == VrCommand.PassThrough) return true
                return handle(command)
            }
            return handle(command)
        }

        // VR off: only real controller events may trigger L1+R1 to ENTER VR. Keep the source gate
        // here so the phone's soft keyboard and the normal 2D UI are completely untouched.
        if (!isGamepadSource(event.source)) return false
        return handle(router.onKey(keyCode, action, isGamepad = true, event.eventTime))
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        // No source gate while the VR menu is open: some pads report axes from a keyboard-ish
        // source, which used to make the left stick silently do nothing.
        if (!_enabled.value || !router.menuOpen) return false
        val x = event.getAxisValue(VrKeys.AXIS_X)
        val y = event.getAxisValue(VrKeys.AXIS_Y)
        // A lot of Bluetooth pads send the physical D-pad as AXIS_HAT_Y, not KEYCODE_DPAD_*.
        // Since MainActivity deliberately stops forwarding generic motion to the 2D Compose UI
        // while VR is on, those events must be handled HERE or the D-pad looks completely dead.
        val hat = router.onHat(event.getAxisValue(VrKeys.AXIS_HAT_Y))
        val command = if (hat is VrCommand.MoveSelection) hat else router.onAxis(x, y, event.eventTime)
        return handle(command)
    }

    /**
     * Analogue L2/R2 (LT/RT) long-press, called from MainActivity.dispatchGenericMotionEvent().
     *
     * Most pads report the triggers as MotionEvent axes, NOT as KEYCODE_BUTTON_L2/R2. Unlike OS
     * key-repeat, a held analogue axis may stop producing events once it bottoms out, so the 500 ms
     * hold is兑现 by a main-looper delayed runnable rather than by waiting for another input event.
     * Fires exactly ONE TogglePassthrough per deliberate hold; releasing either trigger, opening
     * the settings menu or leaving VR cancels the pending toggle. The MotionEvent itself is NOT
     * consumed — the game still receives its triggers unchanged.
     *
     * A pad that emits BOTH keycodes and axes is handled by the debounce in handle().
     */
    fun onTriggerEvent(l2: Float, r2: Float) {
        if (!_enabled.value || _menuOpen.value) {
            cancelTriggerHold()
            return
        }
        if (!router.passthroughTriggersHeld(l2, r2)) {
            cancelTriggerHold()
            return
        }
        if (triggerHoldArmed || triggerHoldConsumed) return
        triggerHoldArmed = true
        val runnable = Runnable {
            triggerHoldArmed = false
            triggerHoldConsumed = true
            triggerHoldRunnable = null
            if (_enabled.value && !_menuOpen.value) handle(VrCommand.TogglePassthrough)
        }
        triggerHoldRunnable = runnable
        mainHandler.postDelayed(runnable, VrKeys.PASSTHROUGH_LONG_PRESS_MS)
    }

    private fun cancelTriggerHold() {
        triggerHoldArmed = false
        triggerHoldConsumed = false
        // Only touch the (lazy, Android-only) Handler when a runnable actually exists — which can
        // only happen after onTriggerEvent() ran on a real device. Keeps JVM tests Android-free.
        triggerHoldRunnable?.let {
            mainHandler.removeCallbacks(it)
            triggerHoldRunnable = null
        }
    }

    private fun handle(command: VrCommand): Boolean = when (command) {
        VrCommand.PassThrough -> false
        VrCommand.Consumed -> true
        VrCommand.ToggleVrMode -> {
            // L1+R1 is ENTRY-ONLY (user request). Exiting must be explicit: the phone's system
            // BACK key, or the "退出 VR" ACTION row in the settings panel. A slipped shoulder
            // combo can therefore never terminate a running session.
            if (!_enabled.value) toggle()
            true
        }
        VrCommand.TogglePassthrough -> {
            // L2+R2 long-press is ONLY an ON/OFF switch (user request). The layout mode is a
            // pure setting and is deliberately left untouched by this shortcut.
            // Debounce: a pad may emit BOTH keycodes (VrGamepadRouter.onKey) and analogue axes
            // (onTriggerEvent) for the same hold. Without this the two paths would each flip the
            // switch once and cancel each other out (no visible change).
            val now = SystemClock.uptimeMillis()
            if (now - lastPassthroughToggleMs >= VrKeys.PASSTHROUGH_TOGGLE_DEBOUNCE_MS) {
                lastPassthroughToggleMs = now
                val current = _settings.value
                updateSettings(current.copy(passthroughEnabled = !current.passthroughEnabled))
            }
            true
        }
        VrCommand.ToggleMenu -> { setMenuOpen(!_menuOpen.value); true }
        VrCommand.CloseMenu -> { setMenuOpen(false); true }
        VrCommand.Confirm -> { confirmSelected(); true }
        is VrCommand.MoveSelection -> {
            setSelectedIndex(_selectedIndex.value + command.delta)
            true
        }
        is VrCommand.ChangePage -> {
            changePage(command.delta)
            true
        }
        is VrCommand.Adjust -> {
            // Numeric values move ONLY while A has armed editing (see VrMenuModel.adjustIfEditing).
            // While disarmed the gate returns the SAME object, so we skip the SharedPreferences
            // write entirely instead of hammering disk with every analogue-stick motion event.
            val before = _settings.value
            val id = VrMenuModel.items[_selectedIndex.value].id
            val after = VrMenuModel.adjustIfEditing(before, id, command.steps, _editMode.value)
            if (after !== before && after != before) updateSettings(after)
            true
        }
    }

    private fun confirmSelected() {
        val id = VrMenuModel.items[_selectedIndex.value].id
        when (id) {
            // These two have host side effects; the rest are pure settings changes.
            VrMenuItemId.SCAN_QR -> { onScanQr?.invoke(); return }
            VrMenuItemId.EXIT_VR -> { setEnabled(false); return }
            else -> {
                val result = VrMenuModel.confirm(_settings.value, id, _editMode.value)
                updateSettings(result.settings)
                setEditMode(result.editing)
            }
        }
    }

    private fun syncRouter() {
        router.deadZone = _settings.value.gamepadDeadZone
        router.repeatMs = _settings.value.menuRepeatMs
    }

    private fun isGamepadSource(source: Int): Boolean =
        (source and VrKeys.SOURCE_GAMEPAD) == VrKeys.SOURCE_GAMEPAD ||
            (source and VrKeys.SOURCE_JOYSTICK) == VrKeys.SOURCE_JOYSTICK ||
            (source and VrKeys.SOURCE_DPAD) == VrKeys.SOURCE_DPAD
}
