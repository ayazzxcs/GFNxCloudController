(function() {
    'use strict';
    // Immediate safety guard: Never execute on login, account, or authentication endpoints
    const currentHref = (window.location && window.location.href) ? window.location.href.toLowerCase() : "";
    if (
        currentHref.includes("login.live.com") ||
        currentHref.includes("login.microsoftonline.com") ||
        currentHref.includes("account.live.com") ||
        currentHref.includes("account.microsoft.com") ||
        currentHref.includes("xboxlive.com") ||
        currentHref.includes("/auth") ||
        currentHref.includes("signin") ||
        currentHref.includes("oauth")
    ) {
        return;
    }

    if (window.__GFN_CONTROLLER_INJECTED__) {
        if (typeof window.__dispatchGamepadConnected === 'function') {
            window.__dispatchGamepadConnected(false);
        }
        return;
    }
    window.__GFN_CONTROLLER_INJECTED__ = true;

    console.log("[GFNxCloud] Initializing virtual Xbox controller injector...");

    const rawButtons = new Float32Array(17);
    const rawAxes = new Float32Array(4);
    const lastPolledButtons = new Float32Array(17);
    const lastPolledAxes = new Float32Array(4);

    // Per-button pulse state for guaranteed 1-tap registration & anti-turbo-scroll pulse capping
    const buttonPendingPress = new Uint8Array(17);
    const buttonPressStartTime = new Float64Array(17);
    const buttonPolledCount = new Uint16Array(17);

    const DPAD_PULSE_MS = 85;
    const DPAD_REPEAT_INTERVAL_MS = 550;
    const ACTION_MIN_HOLD_MS = 75;

    // Left stick menu step pulse & anti-turbo-scroll window (0..85ms active, 85..320ms zero pause, >320ms continuous)
    let prevRawLeftStickMag = 0.0;
    let leftStickPulseActive = 0;
    let leftStickStartTime = 0;
    let leftStickPolledCount = 0;
    let leftStickLatchedX = 0.0;
    let leftStickLatchedY = 0.0;

    let lastTimestamp = performance.now();
    let lastGetGamepadsPollTime = 0;
    let lastDirectInputTime = 0;
    let lastConnectedDispatchTime = 0;
    let lastFocusDispatchTime = 0;
    let lastFocusCheckTime = 0;
    let wasAnyNonZero = false;
    let releaseConfirmFramesRemaining = 0;
    let lastReleaseConfirmTime = 0;

    function nextMonotonicTimestamp() {
        const now = performance.now();
        lastTimestamp = now > lastTimestamp ? now : (lastTimestamp + 0.01);
        return lastTimestamp;
    }

    const vibrationActuator = {
        type: "dual-rumble",
        reset: function() {
            if (window.AndroidBridge && window.AndroidBridge.cancelVibration) {
                try { window.AndroidBridge.cancelVibration(); } catch (e) {}
            }
            return Promise.resolve("complete");
        },
        playEffect: function(type, params) {
            if (window.__vibrationEnabled === false) {
                return Promise.resolve("complete");
            }
            try {
                if (window.AndroidBridge && window.AndroidBridge.vibrate) {
                    if (typeof window.AndroidBridge.isVibrationEnabled === 'function' && !window.AndroidBridge.isVibrationEnabled()) {
                        return Promise.resolve("complete");
                    }
                    const duration = (params && params.duration) ? params.duration : 150;
                    const strong = (params && params.strongMagnitude) ? params.strongMagnitude : 0.5;
                    const weak = (params && params.weakMagnitude) ? params.weakMagnitude : 0.5;
                    window.AndroidBridge.vibrate(duration, strong, weak);
                }
            } catch (e) {
                console.error("[GFNxCloud] Rumble error", e);
            }
            return Promise.resolve("complete");
        }
    };

    function createButtonSnapshot(val) {
        const numVal = Number(val) || 0.0;
        const btn = {
            pressed: numVal > 0.1,
            touched: numVal > 0.0,
            value: numVal
        };
        if (window.GamepadButton && window.GamepadButton.prototype) {
            try { Object.setPrototypeOf(btn, window.GamepadButton.prototype); } catch (e) {}
        }
        return btn;
    }

    const GAMEPAD_ID = "Xbox 360 Controller (XInput STANDARD GAMEPAD)";

    function createRawGamepadObject(ts, axesArr, buttonsArr) {
        const pad = {
            id: GAMEPAD_ID,
            index: 0,
            connected: true,
            timestamp: ts,
            mapping: "standard",
            axes: axesArr,
            buttons: buttonsArr,
            vibrationActuator: vibrationActuator,
            hapticActuators: []
        };
        if (window.Gamepad && window.Gamepad.prototype) {
            try { Object.setPrototypeOf(pad, window.Gamepad.prototype); } catch (e) {}
        }
        return pad;
    }

    let currentSnapshot = createRawGamepadObject(
        lastTimestamp,
        [0.0, 0.0, 0.0, 0.0],
        Array.from({ length: 17 }, () => createButtonSnapshot(0.0))
    );
    window.__virtualGamepad = currentSnapshot;

    try {
        if (window.Gamepad && typeof Symbol !== 'undefined' && Symbol.hasInstance) {
            const origHasInstance = Function.prototype[Symbol.hasInstance];
            Object.defineProperty(window.Gamepad, Symbol.hasInstance, {
                value: function(instance) {
                    if (instance && (instance === currentSnapshot || instance.id === GAMEPAD_ID)) return true;
                    return origHasInstance ? origHasInstance.call(this, instance) : false;
                },
                configurable: true
            });
        }
    } catch (e) {}

    function hasDirectionalInputActive() {
        for (let i = 12; i <= 15; i++) {
            if (lastPolledButtons[i] > 0.05 || rawButtons[i] > 0.05 || buttonPendingPress[i] === 1) return true;
        }
        for (let i = 0; i < 4; i++) {
            if (Math.abs(lastPolledAxes[i]) > 0.01 || Math.abs(rawAxes[i]) > 0.01) return true;
        }
        return false;
    }

    // Build or reuse IMMUTABLE gamepad snapshot:
    // 1. Snapshots are NEVER mutated in-place after creation, so xCloud's `prevPad !== currPad`
    //    diff detector always sees the exact 0 -> 1 press edge and 1 -> 0 release edge.
    // 2. D-Pad buttons (12..15) emit a crisp 85ms pulse (guaranteed >= 3 getGamepads() polls)
    //    and then automatically return to 0.0 even if held, preventing the game's 200ms
    //    turbo auto-scroll from ever triggering while ensuring 1-tap always moves 1 option.
    function buildGamepadSnapshot(isFromGetGamepadsPoll) {
        const now = performance.now();
        let stateChanged = false;
        let anyNonZero = false;
        const effButtons = new Float32Array(17);

        for (let i = 0; i < 17; i++) {
            const rawVal = Number(rawButtons[i]) || 0.0;
            const elapsed = now - buttonPressStartTime[i];
            let numVal = 0.0;

            if (i >= 12 && i <= 15) {
                // D-Pad (Up, Down, Left, Right):
                // Fixed 85ms single-step pulse (at least 3 polls), then forced 0.0 pause until 550ms
                if (buttonPendingPress[i] === 1) {
                    if (elapsed < DPAD_PULSE_MS || (buttonPolledCount[i] < 3 && elapsed < 150)) {
                        numVal = 1.0;
                        if (isFromGetGamepadsPoll) {
                            buttonPolledCount[i]++;
                        }
                    } else {
                        buttonPendingPress[i] = 0;
                        numVal = 0.0;
                    }
                } else if (rawVal > 0.1 && elapsed >= DPAD_REPEAT_INTERVAL_MS) {
                    // Slow discrete step if user intentionally holds D-Pad for > 550ms
                    buttonPendingPress[i] = 1;
                    buttonPressStartTime[i] = now;
                    buttonPolledCount[i] = isFromGetGamepadsPoll ? 1 : 0;
                    numVal = 1.0;
                } else {
                    numVal = 0.0;
                }
            } else {
                // Action & Shoulder Buttons (0..11, 16):
                // Hold for at least 75ms (and >= 3 polls) on quick tap, or continuously while held
                if (rawVal > 0.1) {
                    numVal = rawVal;
                    if (isFromGetGamepadsPoll) {
                        buttonPolledCount[i]++;
                    }
                } else if (buttonPendingPress[i] === 1) {
                    if (elapsed < ACTION_MIN_HOLD_MS || (buttonPolledCount[i] < 3 && elapsed < 150)) {
                        numVal = 1.0;
                        if (isFromGetGamepadsPoll) {
                            buttonPolledCount[i]++;
                        }
                    } else {
                        buttonPendingPress[i] = 0;
                        numVal = 0.0;
                    }
                } else {
                    numVal = 0.0;
                }
            }

            effButtons[i] = numVal;
            if (numVal > 0.05) {
                anyNonZero = true;
            }
            if (Math.abs(numVal - lastPolledButtons[i]) > 0.001) {
                stateChanged = true;
            }
        }

        // Left Stick (axes[0], axes[1]):
        // 0..85ms (and >= 3 polls): output deflection so a quick flick always moves 1 menu item
        // 85..320ms: output 0.0 pause window so holding ~250ms while watching cloud video stream never triggers 1-round turbo scroll
        // >320ms: continuous analog movement for 3D walking/running
        const rawMag = Math.hypot(rawAxes[0], rawAxes[1]);
        let ax0 = 0.0;
        let ax1 = 0.0;
        if (leftStickPulseActive === 1) {
            const stickElapsed = now - leftStickStartTime;
            if (stickElapsed < DPAD_PULSE_MS || (leftStickPolledCount < 3 && stickElapsed < 150)) {
                ax0 = rawMag > 0.22 ? Number(rawAxes[0]) : leftStickLatchedX;
                ax1 = rawMag > 0.22 ? Number(rawAxes[1]) : leftStickLatchedY;
                if (isFromGetGamepadsPoll) {
                    leftStickPolledCount++;
                }
            } else if (stickElapsed < 320) {
                ax0 = 0.0;
                ax1 = 0.0;
                if (rawMag <= 0.15) {
                    leftStickPulseActive = 0;
                }
            } else {
                if (rawMag > 0.01) {
                    ax0 = Number(rawAxes[0]);
                    ax1 = Number(rawAxes[1]);
                } else {
                    leftStickPulseActive = 0;
                    ax0 = 0.0;
                    ax1 = 0.0;
                }
            }
        } else if (rawMag > 0.01) {
            ax0 = Number(rawAxes[0]);
            ax1 = Number(rawAxes[1]);
        }

        const ax2 = Math.abs(rawAxes[2]) > 0.01 ? Number(rawAxes[2]) : 0.0;
        const ax3 = Math.abs(rawAxes[3]) > 0.01 ? Number(rawAxes[3]) : 0.0;

        if (Math.abs(ax0) > 0.01 || Math.abs(ax1) > 0.01 || Math.abs(ax2) > 0.01 || Math.abs(ax3) > 0.01) {
            anyNonZero = true;
        }

        if (
            Math.abs(ax0 - lastPolledAxes[0]) > 0.001 ||
            Math.abs(ax1 - lastPolledAxes[1]) > 0.001 ||
            Math.abs(ax2 - lastPolledAxes[2]) > 0.001 ||
            Math.abs(ax3 - lastPolledAxes[3]) > 0.001
        ) {
            stateChanged = true;
        }

        const justTransitionedToZero = wasAnyNonZero && !anyNonZero;
        if (justTransitionedToZero) {
            releaseConfirmFramesRemaining = 3;
            lastReleaseConfirmTime = now;
            stateChanged = true;
        } else if (!anyNonZero && releaseConfirmFramesRemaining > 0 && (now - lastReleaseConfirmTime >= 16)) {
            releaseConfirmFramesRemaining--;
            lastReleaseConfirmTime = now;
            stateChanged = true;
        } else if (anyNonZero) {
            releaseConfirmFramesRemaining = 0;
        }
        wasAnyNonZero = anyNonZero;

        if (!stateChanged && !anyNonZero && releaseConfirmFramesRemaining <= 0 && currentSnapshot) {
            return currentSnapshot;
        }

        const ts = nextMonotonicTimestamp();
        const buttonsSnap = new Array(17);
        for (let i = 0; i < 17; i++) {
            lastPolledButtons[i] = effButtons[i];
            buttonsSnap[i] = createButtonSnapshot(effButtons[i]);
        }

        lastPolledAxes[0] = ax0;
        lastPolledAxes[1] = ax1;
        lastPolledAxes[2] = ax2;
        lastPolledAxes[3] = ax3;

        const snapshot = createRawGamepadObject(ts, [ax0, ax1, ax2, ax3], buttonsSnap);
        currentSnapshot = snapshot;
        window.__virtualGamepad = snapshot;

        return snapshot;
    }

    // Override navigator.vibrate so any web/game stream calls respect the vibration setting
    const _origVibrate = navigator.vibrate ? navigator.vibrate.bind(navigator) : null;
    navigator.vibrate = function(pattern) {
        if (window.__vibrationEnabled === false) return false;
        if (window.AndroidBridge && typeof window.AndroidBridge.isVibrationEnabled === 'function') {
            if (!window.AndroidBridge.isVibrationEnabled()) return false;
        }
        if (_origVibrate) {
            try { return _origVibrate(pattern); } catch (e) { return false; }
        }
        return false;
    };

    function createGamepadEvent(type, gamepadObj) {
        let ev;
        try {
            ev = new Event(type, { bubbles: false, cancelable: false });
        } catch (e) {
            ev = new CustomEvent(type, { detail: { gamepad: gamepadObj } });
        }
        ev.gamepad = gamepadObj;
        return ev;
    }

    function dispatchGamepadConnected(force) {
        if (hasDirectionalInputActive()) {
            return;
        }
        const now = performance.now();
        if (!force && (now - lastConnectedDispatchTime < 1200)) {
            return;
        }
        lastConnectedDispatchTime = now;
        const ev = createGamepadEvent("gamepadconnected", currentSnapshot);
        try {
            window.dispatchEvent(ev);
        } catch (e) {}
        try {
            if (typeof window.ongamepadconnected === 'function') {
                window.ongamepadconnected(ev);
            }
        } catch (e) {}
    }

    window.__dispatchGamepadConnected = dispatchGamepadConnected;

    // Prevent window blur / visibilitychange from pausing xCloud's GamepadInputProcessor,
    // and block phantom Android sensor devices from firing native gamepadconnected/gamepaddisconnected.
    try {
        window.addEventListener('blur', function(e) {
            e.stopImmediatePropagation();
            e.stopPropagation();
        }, true);
        document.addEventListener('visibilitychange', function(e) {
            e.stopImmediatePropagation();
            e.stopPropagation();
        }, true);
        document.addEventListener('webkitvisibilitychange', function(e) {
            e.stopImmediatePropagation();
            e.stopPropagation();
        }, true);

        window.addEventListener('gamepadconnected', function(e) {
            if (e && e.gamepad && e.gamepad.id !== GAMEPAD_ID) {
                e.stopImmediatePropagation();
                e.stopPropagation();
            }
        }, true);
        window.addEventListener('gamepaddisconnected', function(e) {
            e.stopImmediatePropagation();
            e.stopPropagation();
        }, true);

        Object.defineProperty(window, 'onblur', {
            get: () => null,
            set: () => {},
            configurable: true
        });
        Object.defineProperty(document, 'onvisibilitychange', {
            get: () => null,
            set: () => {},
            configurable: true
        });
    } catch (e) {}

    const origWindowAddEventListener = window.addEventListener ? window.addEventListener.bind(window) : null;
    if (origWindowAddEventListener) {
        window.addEventListener = function(type, listener, options) {
            if (type === "blur" || type === "visibilitychange" || type === "webkitvisibilitychange" || type === "gamepaddisconnected") {
                return;
            }
            const res = origWindowAddEventListener(type, listener, options);
            if (type === "gamepadconnected" && listener) {
                setTimeout(() => {
                    try {
                        const ev = createGamepadEvent("gamepadconnected", currentSnapshot);
                        if (typeof listener === "function") {
                            listener.call(window, ev);
                        } else if (typeof listener.handleEvent === "function") {
                            listener.handleEvent(ev);
                        }
                    } catch (e) {}
                }, 15);
            }
            return res;
        };
    }

    const origDocAddEventListener = document.addEventListener ? document.addEventListener.bind(document) : null;
    if (origDocAddEventListener) {
        document.addEventListener = function(type, listener, options) {
            if (type === "visibilitychange" || type === "webkitvisibilitychange" || type === "blur") {
                return;
            }
            return origDocAddEventListener(type, listener, options);
        };
    }

    function ensureStreamActiveFocus() {
        try {
            if (typeof window.focus === 'function') {
                window.focus();
            }
            const streamEl = document.querySelector('video') ||
                             document.querySelector('[data-testid="media-container"]') ||
                             document.querySelector('#game-stream') ||
                             document.body;
            if (streamEl && typeof streamEl.focus === 'function' && document.activeElement !== streamEl) {
                if (!streamEl.hasAttribute('tabindex')) {
                    streamEl.setAttribute('tabindex', '-1');
                }
                streamEl.focus({ preventScroll: true });
            }
            const now = performance.now();
            if (now - lastFocusDispatchTime > 1000) {
                lastFocusDispatchTime = now;
                try {
                    window.dispatchEvent(new FocusEvent('focus'));
                } catch (e) {
                    window.dispatchEvent(new Event('focus'));
                }
            }
        } catch (e) {}
    }

    const ZERO_STATE_JSON = '{"b":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],"a":[0,0,0,0]}';

    function resetVirtualGamepadToZero() {
        for (let i = 0; i < 17; i++) {
            rawButtons[i] = 0.0;
        }
        for (let i = 0; i < 4; i++) {
            rawAxes[i] = 0.0;
        }
        prevRawLeftStickMag = 0.0;
    }

    function updateRawStateFromArrays(buttonsArr, axesArr) {
        const now = performance.now();
        let wakeButtonJustPressed = false;

        if (buttonsArr && buttonsArr.length >= 17) {
            for (let i = 0; i < 17; i++) {
                const numVal = Number(buttonsArr[i]) || 0.0;
                const prevVal = rawButtons[i];
                if (numVal > 0.1 && prevVal <= 0.1) {
                    buttonPendingPress[i] = 1;
                    buttonPressStartTime[i] = now;
                    buttonPolledCount[i] = 0;
                    if (i === 0 || i === 1 || i === 2 || i === 3 || i === 8 || i === 9 || i === 16) {
                        wakeButtonJustPressed = true;
                    }
                }
                rawButtons[i] = numVal;
            }
        }

        if (axesArr && axesArr.length >= 4) {
            const ax0 = Number(axesArr[0]) || 0.0;
            const ax1 = Number(axesArr[1]) || 0.0;
            const mag = Math.hypot(ax0, ax1);
            if (mag > 0.22 && prevRawLeftStickMag <= 0.22) {
                leftStickPulseActive = 1;
                leftStickStartTime = now;
                leftStickPolledCount = 0;
                leftStickLatchedX = ax0;
                leftStickLatchedY = ax1;
            } else if (mag > 0.22) {
                leftStickLatchedX = ax0;
                leftStickLatchedY = ax1;
            }
            prevRawLeftStickMag = mag;

            for (let i = 0; i < 4; i++) {
                rawAxes[i] = Number(axesArr[i]) || 0.0;
            }
        }

        return wakeButtonJustPressed;
    }

    // Read latest state from AndroidBridge into rawButtons and rawAxes
    function pollFromAndroid() {
        if (window.AndroidBridge && typeof window.AndroidBridge.getGamepadState === 'function') {
            try {
                const raw = window.AndroidBridge.getGamepadState();
                if (!raw || raw === ZERO_STATE_JSON) {
                    resetVirtualGamepadToZero();
                } else {
                    const state = JSON.parse(raw);
                    updateRawStateFromArrays(
                        state.b || [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                        state.a || [0,0,0,0]
                    );
                }
            } catch (e) {}
        }
    }

    // Direct fast-path called from Kotlin evaluateJavascript when state changes.
    window.onControllerInput = function(buttons, axes) {
        const now = performance.now();
        lastDirectInputTime = now;
        if (now - lastFocusCheckTime > 500) {
            lastFocusCheckTime = now;
            ensureStreamActiveFocus();
        }

        let wakeButtonJustPressed = false;
        if (buttons && buttons.length >= 17) {
            wakeButtonJustPressed = updateRawStateFromArrays(buttons, axes);
        } else {
            pollFromAndroid();
        }

        if (wakeButtonJustPressed) {
            buildGamepadSnapshot(false);
            dispatchGamepadConnected(true);
        }
    };

    // Override navigator.getGamepads to return ONLY the standard 4-port XInput array [snapshot, null, null, null].
    function virtualGetGamepads() {
        const now = performance.now();
        lastGetGamepadsPollTime = now;
        if (now - lastDirectInputTime > 12) {
            pollFromAndroid();
        }
        const snap = buildGamepadSnapshot(true);
        dispatchGamepadConnected(false);
        return [snap, null, null, null];
    }

    try {
        Object.defineProperty(navigator, 'getGamepads', {
            value: virtualGetGamepads,
            writable: true,
            configurable: true,
            enumerable: true
        });
    } catch(e) {
        navigator.getGamepads = virtualGetGamepads;
    }

    try {
        Object.defineProperty(Navigator.prototype, 'getGamepads', {
            value: virtualGetGamepads,
            writable: true,
            configurable: true,
            enumerable: true
        });
    } catch(e) {}

    try {
        Object.defineProperty(navigator, 'webkitGetGamepads', {
            value: virtualGetGamepads,
            writable: true,
            configurable: true
        });
    } catch (e) {}

    window.addEventListener('DOMContentLoaded', () => setTimeout(() => dispatchGamepadConnected(true), 300));
    window.addEventListener('load', () => setTimeout(() => dispatchGamepadConnected(true), 600));

    // Hook History API pushState / replaceState for SPA route transitions
    try {
        const origPushState = history.pushState;
        if (origPushState) {
            history.pushState = function(...args) {
                const res = origPushState.apply(this, args);
                setTimeout(() => dispatchGamepadConnected(true), 300);
                setTimeout(() => dispatchGamepadConnected(true), 1200);
                setTimeout(() => dispatchGamepadConnected(true), 3000);
                return res;
            };
        }
        const origReplaceState = history.replaceState;
        if (origReplaceState) {
            history.pushState = origPushState ? history.pushState : origPushState;
            history.replaceState = function(...args) {
                const res = origReplaceState.apply(this, args);
                setTimeout(() => dispatchGamepadConnected(true), 300);
                setTimeout(() => dispatchGamepadConnected(true), 1200);
                setTimeout(() => dispatchGamepadConnected(true), 3000);
                return res;
            };
        }
    } catch (e) {}

    // =========================================================================
    // XBOX CLOUD GAMING 60+ FPS & HIGH PERFORMANCE STREAM OPTIMIZER
    // =========================================================================
    console.log("[GFNxCloud] Initializing 60+ FPS WebRTC stream optimizer...");

    // 1. Prevent background tab/visibility frame throttling, enforce document.hasFocus(), & spoof Desktop 60fps capabilities
    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
        document.hasFocus = function() { return true; };
        if (typeof Document !== 'undefined' && Document.prototype) {
            Document.prototype.hasFocus = function() { return true; };
        }

        // Spoof Windows Desktop Edge platform to unlock 1080p 60fps desktop stream tier
        const desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";
        Object.defineProperty(navigator, 'userAgent', { get: () => desktopUa, configurable: true });
        Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
        Object.defineProperty(navigator, 'appVersion', { get: () => desktopUa.replace(/^Mozilla\//, ''), configurable: true });
        if (navigator.userAgentData) {
            Object.defineProperty(navigator.userAgentData, 'platform', { get: () => 'Windows', configurable: true });
            Object.defineProperty(navigator.userAgentData, 'mobile', { get: () => false, configurable: true });
        }

        // Spoof MediaCapabilities so xCloud never downgrades stream to 30fps
        if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
            const origDecodingInfo = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
            navigator.mediaCapabilities.decodingInfo = async function(config) {
                try {
                    const res = await origDecodingInfo(config);
                    return {
                        supported: true,
                        smooth: true,
                        powerEfficient: true,
                        keySystemAccess: res ? res.keySystemAccess : null,
                        configuration: config
                    };
                } catch (e) {
                    return { supported: true, smooth: true, powerEfficient: true, configuration: config };
                }
            };
        }

        // Prevent network save-data throttling to 30fps
        if (navigator.connection) {
            Object.defineProperty(navigator.connection, 'saveData', { get: () => false, configurable: true });
            Object.defineProperty(navigator.connection, 'effectiveType', { get: () => '4g', configurable: true });
            Object.defineProperty(navigator.connection, 'downlink', { get: () => 50, configurable: true });
            Object.defineProperty(navigator.connection, 'rtt', { get: () => 15, configurable: true });
        }
    } catch (e) {
        console.warn("[GFNxCloud] Visibility/Platform hook error", e);
    }

    // 2. SDP Munging: Force 60+ FPS framerate, H.264 Level 4.2 & 35 Mbps bandwidth in WebRTC negotiation
    function force60FpsSdp(sdp) {
        if (!sdp || typeof sdp !== 'string') return sdp;
        try {
            const lines = sdp.split(/\r?\n/);
            const output = [];
            let inVideo = false;
            let videoHasBitrate = false;
            let videoHasFramerate = false;

            const forceHigh = (window.AndroidBridge && typeof window.AndroidBridge.isForce60FpsEnabled === 'function')
                ? window.AndroidBridge.isForce60FpsEnabled()
                : (window.__force60FpsEnabled !== undefined ? window.__force60FpsEnabled : true);
            const targetFps = forceHigh ? 60 : 60;
            const maxFr = forceHigh ? 120 : 60;
            const maxBitrate = forceHigh ? 35000 : 15000;
            const startBitrate = forceHigh ? 24000 : 10000;
            const minBitrate = forceHigh ? 16000 : 6000;

            for (let i = 0; i < lines.length; i++) {
                let line = lines[i];

                if (line.startsWith('m=video')) {
                    inVideo = true;
                    videoHasBitrate = false;
                    videoHasFramerate = false;
                    output.push(line);
                    // Explicitly inject standard media-level framerate specification
                    output.push('a=framerate:' + targetFps + '.00');
                    videoHasFramerate = true;
                    continue;
                } else if (line.startsWith('m=')) {
                    inVideo = false;
                }

                if (inVideo) {
                    if (line.startsWith('b=AS:') || line.startsWith('b=TIAS:')) {
                        output.push('b=AS:' + maxBitrate);
                        videoHasBitrate = true;
                        continue;
                    }

                    if (line.startsWith('a=framerate:')) {
                        output.push('a=framerate:' + targetFps + '.00');
                        videoHasFramerate = true;
                        continue;
                    }

                    if (line.startsWith('a=fmtp:')) {
                        // Upgrade H.264 profile-level-id to Level 4.2 (0x2a) to unlock 1080p @ 60 FPS
                        line = line.replace(/profile-level-id=([0-9a-fA-F]{4})(1[ef]|28)/gi, 'profile-level-id=$12a');

                        // Inject framerate parameters
                        if (!line.includes('max-fr=') && !line.includes('max-fps=')) {
                            line += ';max-fr=' + maxFr + ';max-fps=' + maxFr + ';min-fr=60;framerate=' + targetFps;
                        } else {
                            line = line.replace(/max-fr=\d+/g, 'max-fr=' + maxFr)
                                       .replace(/max-fps=\d+/g, 'max-fps=' + maxFr)
                                       .replace(/min-fr=\d+/g, 'min-fr=60')
                                       .replace(/framerate=\d+/g, 'framerate=' + targetFps);
                        }

                        // Macroblock parameters for full 1080p 60fps throughput
                        if (!line.includes('max-mbps=')) {
                            line += ';max-mbps=489600;max-fs=8160;max-cpb=200;max-dpb=200;max-br=' + maxBitrate;
                        } else {
                            line = line.replace(/max-mbps=\d+/g, 'max-mbps=489600')
                                       .replace(/max-fs=\d+/g, 'max-fs=8160');
                        }

                        // High performance bandwidth parameters
                        if (!line.includes('x-google-min-bitrate=')) {
                            line += ';x-google-min-bitrate=' + minBitrate + ';x-google-max-bitrate=' + maxBitrate + ';x-google-start-bitrate=' + startBitrate;
                        } else {
                            line = line.replace(/x-google-min-bitrate=\d+/g, 'x-google-min-bitrate=' + minBitrate)
                                       .replace(/x-google-max-bitrate=\d+/g, 'x-google-max-bitrate=' + maxBitrate)
                                       .replace(/x-google-start-bitrate=\d+/g, 'x-google-start-bitrate=' + startBitrate);
                        }
                    }
                }

                output.push(line);

                // If m=video didn't have b=AS, append it after c=IN line
                if (inVideo && !videoHasBitrate && line.startsWith('c=IN')) {
                    output.push('b=AS:' + maxBitrate);
                    videoHasBitrate = true;
                }
            }
            return output.join('\r\n');
        } catch (err) {
            console.error("[GFNxCloud] SDP optimization error", err);
            return sdp;
        }
    }

    // Hook RTCPeerConnection for SDP manipulation & optimal latency
    const activePeerConnections = new Set();
    window.__activePeerConnections = activePeerConnections;

    function optimizeReceiverLatency(receiver) {
        if (!receiver) return;
        try {
            const reflexOn = (window.AndroidBridge && typeof window.AndroidBridge.isGfnReflexEnabled === 'function')
                ? window.AndroidBridge.isGfnReflexEnabled()
                : (window.__gfnReflexEnabled !== undefined ? window.__gfnReflexEnabled : true);

            if (reflexOn) {
                if ('playoutDelayHint' in receiver) {
                    receiver.playoutDelayHint = 0;
                }
                if ('jitterBufferTarget' in receiver) {
                    receiver.jitterBufferTarget = 0;
                }
            }
        } catch (e) {}
    }

    function applyReflexToPeerConnection(pc) {
        if (!pc) return;
        try {
            if (pc.getReceivers) {
                pc.getReceivers().forEach(optimizeReceiverLatency);
            }
        } catch (e) {}
    }

    if (window.RTCPeerConnection) {
        const OrigPeerConnection = window.RTCPeerConnection;

        window.RTCPeerConnection = function(...args) {
            const pc = new OrigPeerConnection(...args);
            activePeerConnections.add(pc);

            pc.addEventListener('connectionstatechange', () => {
                if (pc.connectionState === 'closed' || pc.connectionState === 'failed') {
                    activePeerConnections.delete(pc);
                } else if (pc.connectionState === 'connected') {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(true);
                    setTimeout(() => dispatchGamepadConnected(true), 800);
                }
            });

            pc.addEventListener('track', (evt) => {
                if (evt && evt.receiver) {
                    optimizeReceiverLatency(evt.receiver);
                }
                ensureStreamActiveFocus();
                dispatchGamepadConnected(true);
            });

            return pc;
        };
        window.RTCPeerConnection.prototype = OrigPeerConnection.prototype;

        const origCreateOffer = OrigPeerConnection.prototype.createOffer;
        OrigPeerConnection.prototype.createOffer = async function(...args) {
            const offer = await origCreateOffer.apply(this, args);
            if (offer && offer.sdp) {
                try {
                    return new RTCSessionDescription({
                        type: offer.type,
                        sdp: force60FpsSdp(offer.sdp)
                    });
                } catch (e) {
                    console.warn("[GFNxCloud] createOffer SDP rewrite failed", e);
                }
            }
            return offer;
        };

        const origCreateAnswer = OrigPeerConnection.prototype.createAnswer;
        OrigPeerConnection.prototype.createAnswer = async function(...args) {
            const answer = await origCreateAnswer.apply(this, args);
            if (answer && answer.sdp) {
                try {
                    return new RTCSessionDescription({
                        type: answer.type,
                        sdp: force60FpsSdp(answer.sdp)
                    });
                } catch (e) {
                    console.warn("[GFNxCloud] createAnswer SDP rewrite failed", e);
                }
            }
            return answer;
        };

        const origSetRemoteDescription = OrigPeerConnection.prototype.setRemoteDescription;
        OrigPeerConnection.prototype.setRemoteDescription = function(desc) {
            if (desc && desc.sdp) {
                try {
                    const optimizedSdp = force60FpsSdp(desc.sdp);
                    desc = new RTCSessionDescription({
                        type: desc.type,
                        sdp: optimizedSdp
                    });
                } catch (e) {
                    console.warn("[GFNxCloud] Remote SDP rewrite skipped", e);
                }
            }
            const res = origSetRemoteDescription.call(this, desc);
            applyReflexToPeerConnection(this);
            return res;
        };

        const origSetLocalDescription = OrigPeerConnection.prototype.setLocalDescription;
        OrigPeerConnection.prototype.setLocalDescription = function(desc) {
            if (desc && desc.sdp) {
                try {
                    const optimizedSdp = force60FpsSdp(desc.sdp);
                    desc = new RTCSessionDescription({
                        type: desc.type,
                        sdp: optimizedSdp
                    });
                } catch (e) {
                    console.warn("[GFNxCloud] Local SDP rewrite skipped", e);
                }
            }
            return origSetLocalDescription.call(this, desc);
        };

        const origAddTransceiver = OrigPeerConnection.prototype.addTransceiver;
        if (origAddTransceiver) {
            OrigPeerConnection.prototype.addTransceiver = function(...args) {
                const transceiver = origAddTransceiver.apply(this, args);
                if (transceiver && transceiver.receiver) {
                    optimizeReceiverLatency(transceiver.receiver);
                }
                return transceiver;
            };
        }

        // Prefer motion hint on video tracks
        const origAddTrack = OrigPeerConnection.prototype.addTrack;
        if (origAddTrack) {
            OrigPeerConnection.prototype.addTrack = function(track, ...streams) {
                if (track && track.kind === 'video') {
                    try {
                        if ('contentHint' in track) track.contentHint = 'motion';
                    } catch (e) {}
                }
                return origAddTrack.call(this, track, ...streams);
            };
        }
    }

    // Periodic sweep ensuring newly established media tracks keep 0 jitter buffer delay
    setInterval(() => {
        activePeerConnections.forEach(applyReflexToPeerConnection);
    }, 2000);

    // 3. Monitor Video Elements, optimize hardware compositing layer, and calculate real-time FPS
    window.__clarityBoostEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isClarityBoostEnabled === 'function')
        ? window.AndroidBridge.isClarityBoostEnabled()
        : false;

    window.__gfnVividEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isGfnVividEnabled === 'function')
        ? window.AndroidBridge.isGfnVividEnabled()
        : true;

    window.__gfnReflexEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isGfnReflexEnabled === 'function')
        ? window.AndroidBridge.isGfnReflexEnabled()
        : true;

    function removeClarityBoostFilter() {
        try {
            const svg = document.getElementById('gfn-clarity-boost-svg');
            if (svg && svg.parentNode) {
                svg.parentNode.removeChild(svg);
            }
        } catch (e) {}
    }

    function injectClarityBoostFilter() {
        if (!window.__clarityBoostEnabled) return;
        if (document.getElementById('gfn-clarity-boost-svg')) return;
        try {
            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.id = 'gfn-clarity-boost-svg';
            svg.style.position = 'absolute';
            svg.style.width = '0';
            svg.style.height = '0';
            svg.style.pointerEvents = 'none';
            svg.innerHTML = `
                <defs>
                    <filter id="gfn-clarity-filter" color-interpolation-filters="sRGB" x="0%" y="0%" width="100%" height="100%">
                        <feConvolveMatrix order="3" preserveAlpha="true" kernelMatrix="0 -0.10 0 -0.10 1.40 -0.10 0 -0.10 0" divisor="1" bias="0" edgeMode="duplicate"/>
                    </filter>
                </defs>
            `;
            (document.body || document.documentElement).appendChild(svg);
        } catch (e) {
            console.warn("[GFNxCloud] Could not inject Clarity Boost SVG filter", e);
        }
    }

    window.__motionSmoothingEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isMotionSmoothingEnabled === 'function')
        ? window.AndroidBridge.isMotionSmoothingEnabled()
        : true;

    window.setMotionSmoothing = function(enabled) {
        window.__motionSmoothingEnabled = !!enabled;
        const videos = document.querySelectorAll('video');
        videos.forEach(applyVisualEnhancementsToVideo);
        console.log("[GFNxCloud] 30-to-60 FPS Motion Smoothing set to:", window.__motionSmoothingEnabled);
    };

    function applyVisualEnhancementsToVideo(v) {
        if (!v) return;
        const isClarity = !!window.__clarityBoostEnabled;
        const isVivid = window.__gfnVividEnabled !== false;
        const isSmoothing = window.__motionSmoothingEnabled !== false;

        const filters = [];
        if (isClarity) {
            filters.push('url(#gfn-clarity-filter)');
        }
        if (isVivid) {
            // GeForce NOW Digital Vibrance Profile:
            // Elevates flat 8-bit SDR stream with rich console/PC color saturation and deep dynamic contrast.
            // When Clarity Boost (visual sharpening) is active, edge contrast is already naturally heightened;
            // use a luminance-preserving profile to keep darks/shadows crystal clear and vibrant without shadow crushing.
            if (isClarity) {
                filters.push('contrast(1.04) saturate(1.18) brightness(1.03)');
            } else {
                filters.push('contrast(1.09) saturate(1.18) brightness(1.02)');
            }
        }

        if (filters.length > 0) {
            v.style.filter = filters.join(' ');
            v.style.imageRendering = '-webkit-optimize-contrast';
        } else {
            v.style.filter = 'none';
            v.style.removeProperty('filter');
            v.style.removeProperty('image-rendering');
        }

        // Hardware compositing layer & sub-pixel motion pacing
        if (isSmoothing) {
            v.style.transform = 'translateZ(0)';
            v.style.backfaceVisibility = 'hidden';
            v.style.willChange = 'transform, filter';
        } else {
            v.style.removeProperty('transform');
            v.style.removeProperty('backface-visibility');
            v.style.removeProperty('willChange');
        }
    }

    window.setClarityBoost = function(enabled) {
        window.__clarityBoostEnabled = !!enabled;
        if (window.__clarityBoostEnabled) {
            injectClarityBoostFilter();
        } else {
            removeClarityBoostFilter();
        }
        const videos = document.querySelectorAll('video');
        videos.forEach(applyVisualEnhancementsToVideo);
        console.log("[GFNxCloud] Clarity Boost set to:", window.__clarityBoostEnabled);
    };

    window.setGfnVivid = function(enabled) {
        window.__gfnVividEnabled = !!enabled;
        const videos = document.querySelectorAll('video');
        videos.forEach(applyVisualEnhancementsToVideo);
        console.log("[GFNxCloud] GeForce NOW Vivid Mode set to:", window.__gfnVividEnabled);
    };

    window.setGfnReflex = function(enabled) {
        window.__gfnReflexEnabled = !!enabled;
        activePeerConnections.forEach(applyReflexToPeerConnection);
        console.log("[GFNxCloud] GeForce NOW Reflex Ultra-Low Latency set to:", window.__gfnReflexEnabled);
    };

    window.__fpsCounterEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isFpsCounterEnabled === 'function')
        ? window.AndroidBridge.isFpsCounterEnabled()
        : true;

    window.setFpsCounterEnabled = function(enabled) {
        window.__fpsCounterEnabled = !!enabled;
        if (window.__fpsCounterEnabled) {
            const videos = document.querySelectorAll('video');
            videos.forEach(v => {
                if (typeof v.__startFpsCounter === 'function') {
                    v.__startFpsCounter();
                }
            });
        }
        console.log("[GFNxCloud] FPS counter set to:", window.__fpsCounterEnabled);
    };

    window.__force60FpsEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isForce60FpsEnabled === 'function')
        ? window.AndroidBridge.isForce60FpsEnabled()
        : true;

    window.setForce60Fps = function(enabled) {
        window.__force60FpsEnabled = !!enabled;
        console.log("[GFNxCloud] Force 60+ FPS set to:", window.__force60FpsEnabled);
    };

    window.__vibrationEnabled = (window.AndroidBridge && typeof window.AndroidBridge.isVibrationEnabled === 'function')
        ? window.AndroidBridge.isVibrationEnabled()
        : true;

    window.setVibrationEnabled = function(enabled) {
        window.__vibrationEnabled = !!enabled;
        if (!window.__vibrationEnabled && window.AndroidBridge && window.AndroidBridge.cancelVibration) {
            try { window.AndroidBridge.cancelVibration(); } catch (e) {}
        }
        console.log("[GFNxCloud] Vibration set to:", window.__vibrationEnabled);
    };

    function monitorStreamVideo() {
        if (window.__clarityBoostEnabled) {
            injectClarityBoostFilter();
        } else {
            removeClarityBoostFilter();
        }
        const videos = document.querySelectorAll('video');
        videos.forEach(v => {
            if (!v.__gfn_stream_optimized__) {
                v.__gfn_stream_optimized__ = true;
                v.playsInline = true;
                v.disablePictureInPicture = true;
                if ('disableRemotePlayback' in v) v.disableRemotePlayback = true;
                if ('preservesPitch' in v) v.preservesPitch = false;

                applyVisualEnhancementsToVideo(v);
                ensureStreamActiveFocus();
                dispatchGamepadConnected(true);

                v.addEventListener('loadeddata', () => {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(true);
                });

                v.addEventListener('playing', () => {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(true);
                    setTimeout(() => dispatchGamepadConnected(true), 1000);
                });

                // Calculate real decoded stream FPS only when FPS counter is wanted
                let lastTime = performance.now();
                let frames = 0;

                function countFps(now, metadata) {
                    if (!window.__fpsCounterEnabled) {
                        v.__fpsCounterRunning = false;
                        return;
                    }
                    frames++;
                    const delta = now - lastTime;
                    if (delta >= 1000) {
                        const calculatedFps = Math.round((frames * 1000) / delta);
                        frames = 0;
                        lastTime = now;
                        window.__streamFps = calculatedFps;
                        if (window.AndroidBridge && window.AndroidBridge.updateFps) {
                            try {
                                window.AndroidBridge.updateFps(calculatedFps);
                            } catch (e) {}
                        }
                    }
                    if (v.requestVideoFrameCallback && window.__fpsCounterEnabled) {
                        v.requestVideoFrameCallback(countFps);
                    } else {
                        v.__fpsCounterRunning = false;
                    }
                }

                v.__startFpsCounter = function() {
                    if (!v.__fpsCounterRunning && v.requestVideoFrameCallback && window.__fpsCounterEnabled) {
                        v.__fpsCounterRunning = true;
                        frames = 0;
                        lastTime = performance.now();
                        v.requestVideoFrameCallback(countFps);
                    }
                };

                if (window.__fpsCounterEnabled) {
                    v.__startFpsCounter();
                }
            } else if (v.readyState >= 2 && !v.paused) {
                ensureStreamActiveFocus();
                dispatchGamepadConnected(false);
            }
        });
    }

    setInterval(monitorStreamVideo, 1200);

    console.log("[GFNxCloud] Virtual Xbox Gamepad and 60+ FPS Optimizer ready");
})();
