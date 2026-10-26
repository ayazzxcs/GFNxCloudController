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

    // Minimum hold duration (ms) for sub-frame touchscreen taps so 60Hz xCloud + RE Engine
    // input polling never misses a quick tap, without triggering menu auto-repeat.
    const MIN_BUTTON_HOLD_MS = 45;

    // Raw internal controller state (17 standard XInput buttons, 4 axes)
    const rawButtons = new Float32Array(17);
    const desiredButtons = new Float32Array(17);
    const buttonHoldUntil = new Float64Array(17);
    const buttonReleaseTimers = new Array(17).fill(null);
    const rawAxes = new Float32Array(4);
    let lastTimestamp = performance.now();

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
        return {
            pressed: numVal > 0.1,
            touched: numVal > 0.0,
            value: numVal
        };
    }

    // Persistent reference used for gamepadconnected event.gamepad
    const virtualGamepad = {
        id: "Xbox 360 Controller (XInput STANDARD GAMEPAD)",
        index: 0,
        connected: true,
        timestamp: lastTimestamp,
        mapping: "standard",
        axes: [0.0, 0.0, 0.0, 0.0],
        buttons: Array.from({ length: 17 }, () => createButtonSnapshot(0.0)),
        vibrationActuator: vibrationActuator,
        hapticActuators: []
    };

    // Allow `snapshot instanceof Gamepad` checks to succeed safely without prototype pollution
    try {
        if (window.Gamepad && typeof Symbol !== 'undefined' && Symbol.hasInstance) {
            const origHasInstance = Function.prototype[Symbol.hasInstance];
            Object.defineProperty(window.Gamepad, Symbol.hasInstance, {
                value: function(instance) {
                    if (instance && (instance === virtualGamepad || instance.id === virtualGamepad.id)) return true;
                    return origHasInstance ? origHasInstance.call(this, instance) : false;
                },
                configurable: true
            });
        }
    } catch (e) {}

    // Build a fresh plain-JS Gamepad snapshot on every state change!
    // CRITICAL: Never mutate the previously returned snapshot in-place!
    // Xbox Cloud Gaming stores `this.lastGamepad = navigator.getGamepads()[0]` on Frame N
    // and compares `this.lastGamepad` against `currentPad` on Frame N+1.
    // Mutating in-place on release (1 -> 0) overwrites `this.lastGamepad` to 0 before Frame N+1 runs,
    // causing xCloud to miss the release edge and keep scrolling forever.
    function buildGamepadSnapshot() {
        const ts = nextMonotonicTimestamp();
        const buttonsSnap = new Array(17);
        for (let i = 0; i < 17; i++) {
            const numVal = Number(rawButtons[i]) || 0.0;
            buttonsSnap[i] = createButtonSnapshot(numVal);

            const liveBtn = virtualGamepad.buttons[i];
            liveBtn.pressed = numVal > 0.1;
            liveBtn.touched = numVal > 0.0;
            liveBtn.value = numVal;
        }

        const ax0 = Number(rawAxes[0]) || 0.0;
        const ax1 = Number(rawAxes[1]) || 0.0;
        const ax2 = Number(rawAxes[2]) || 0.0;
        const ax3 = Number(rawAxes[3]) || 0.0;
        const axesSnap = [ax0, ax1, ax2, ax3];

        virtualGamepad.axes[0] = ax0;
        virtualGamepad.axes[1] = ax1;
        virtualGamepad.axes[2] = ax2;
        virtualGamepad.axes[3] = ax3;
        virtualGamepad.timestamp = ts;

        const snapshot = {
            id: "Xbox 360 Controller (XInput STANDARD GAMEPAD)",
            index: 0,
            connected: true,
            timestamp: ts,
            mapping: "standard",
            axes: axesSnap,
            buttons: buttonsSnap,
            vibrationActuator: vibrationActuator,
            hapticActuators: []
        };
        window.__virtualGamepad = snapshot;
        return snapshot;
    }

    let currentGamepadSnapshot = buildGamepadSnapshot();

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

    // Track listeners so each registered 'gamepadconnected' listener in xCloud is notified
    // cleanly without duplicate connection spam (which causes Xbox OS / Resident Evil 2 & 3
    // to think multiple controllers are hot-plugging and pop up "Press A on the controller you want to use").
    const notifiedListeners = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
    let hasFiredConnected = false;
    let lastConnectedPathname = "";

    function dispatchGamepadConnected(force) {
        const pathname = (window.location && window.location.pathname) ? window.location.pathname : "";
        const routeChanged = pathname !== lastConnectedPathname;

        if (!hasFiredConnected || force || routeChanged) {
            hasFiredConnected = true;
            lastConnectedPathname = pathname;

            const ev = createGamepadEvent("gamepadconnected", virtualGamepad);
            try {
                window.dispatchEvent(ev);
            } catch (e) {}
            try {
                if (typeof window.ongamepadconnected === 'function') {
                    window.ongamepadconnected(ev);
                }
            } catch (e) {}
            console.log("[GFNxCloud] gamepadconnected event dispatched for", pathname);
        }
    }

    window.__dispatchGamepadConnected = dispatchGamepadConnected;

    // =========================================================================
    // FOCUS LOCK & EVENT GUARD:
    // Prevent Android Compose overlay touches or SettingsDialog window focus transitions
    // from firing 'blur' or 'visibilitychange' on window/document (which causes xCloud's
    // GamepadInputProcessor to set isFocused=false and ignore all controller inputs).
    // =========================================================================
    try {
        window.addEventListener('blur', function(e) {
            e.stopImmediatePropagation();
            e.stopPropagation();
        }, true);
        window.addEventListener('focusout', function(e) {
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

        // Block native 'gamepadconnected' and 'gamepaddisconnected' events from phantom Android
        // sensors (e.g. fingerprint/gyroscope InputDevices) so Slot 0 virtualGamepad is the ONLY controller.
        window.addEventListener('gamepadconnected', function(e) {
            if (e && e.gamepad && e.gamepad !== virtualGamepad && e.gamepad.id !== virtualGamepad.id) {
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

    // Intercept addEventListener on Window and Document so:
    // 1. 'blur' and 'visibilitychange' listeners never pause xCloud's input loop
    // 2. Any new 'gamepadconnected' listener attached when launching Resident Evil immediately
    //    receives the virtualGamepad connection event once.
    const origWindowAddEventListener = window.addEventListener ? window.addEventListener.bind(window) : null;
    if (origWindowAddEventListener) {
        window.addEventListener = function(type, listener, options) {
            if (type === "blur" || type === "focusout" || type === "visibilitychange" || type === "webkitvisibilitychange" || type === "gamepaddisconnected") {
                return;
            }
            const res = origWindowAddEventListener(type, listener, options);
            if (type === "gamepadconnected" && listener) {
                const isObjOrFn = typeof listener === "function" || typeof listener === "object";
                const alreadyNotified = (notifiedListeners && isObjOrFn)
                    ? notifiedListeners.has(listener)
                    : false;
                if (!alreadyNotified) {
                    if (notifiedListeners && isObjOrFn) {
                        notifiedListeners.add(listener);
                    }
                    setTimeout(() => {
                        try {
                            const ev = createGamepadEvent("gamepadconnected", virtualGamepad);
                            if (typeof listener === "function") {
                                listener.call(window, ev);
                            } else if (listener && typeof listener.handleEvent === "function") {
                                listener.handleEvent(ev);
                            }
                        } catch (e) {}
                    }, 15);
                }
            }
            return res;
        };
    }

    const origDocAddEventListener = document.addEventListener ? document.addEventListener.bind(document) : null;
    if (origDocAddEventListener) {
        document.addEventListener = function(type, listener, options) {
            if (type === "visibilitychange" || type === "webkitvisibilitychange" || type === "blur" || type === "focusout") {
                return;
            }
            return origDocAddEventListener(type, listener, options);
        };
    }

    // Ensure the WebRTC stream container and window stay actively focused while the user interacts
    // with the native Android Compose controller overlay above the WebView.
    let lastFocusDispatchTime = 0;
    function ensureStreamActiveFocus() {
        try {
            const now = performance.now();
            if (now - lastFocusDispatchTime > 400) {
                lastFocusDispatchTime = now;
                try {
                    window.dispatchEvent(new FocusEvent('focus'));
                } catch (e) {
                    window.dispatchEvent(new Event('focus'));
                }
            }
            const streamEl = document.querySelector('video, [data-testid="media-container"], #game-stream');
            if (streamEl && document.activeElement !== streamEl) {
                if (!streamEl.hasAttribute('tabindex')) {
                    streamEl.setAttribute('tabindex', '-1');
                }
                if (typeof streamEl.focus === 'function') {
                    streamEl.focus({ preventScroll: true });
                }
            }
        } catch (e) {}
    }

    // Update a single button with minimum hold duration guarantee so ultra-short touchscreen taps
    // stay pressed across MIN_BUTTON_HOLD_MS (~3 frames at 60Hz) and then reliably release to 0.
    function updateButtonWithHoldLatch(index, targetVal, now) {
        const cleanVal = Number(targetVal) || 0.0;
        desiredButtons[index] = cleanVal;

        if (cleanVal > 0.1) {
            if (buttonReleaseTimers[index] !== null) {
                clearTimeout(buttonReleaseTimers[index]);
                buttonReleaseTimers[index] = null;
            }
            if (rawButtons[index] <= 0.1) {
                buttonHoldUntil[index] = now + MIN_BUTTON_HOLD_MS;
            }
            if (Math.abs(rawButtons[index] - cleanVal) > 0.0001) {
                rawButtons[index] = cleanVal;
                return true;
            }
            return false;
        } else {
            const remainingHold = buttonHoldUntil[index] - now;
            if (rawButtons[index] > 0.1 && remainingHold > 1) {
                if (buttonReleaseTimers[index] === null) {
                    buttonReleaseTimers[index] = setTimeout(() => {
                        buttonReleaseTimers[index] = null;
                        if (desiredButtons[index] <= 0.1 && rawButtons[index] !== 0.0) {
                            rawButtons[index] = 0.0;
                            currentGamepadSnapshot = buildGamepadSnapshot();
                        }
                    }, Math.ceil(remainingHold));
                }
                return false;
            } else {
                if (buttonReleaseTimers[index] !== null) {
                    clearTimeout(buttonReleaseTimers[index]);
                    buttonReleaseTimers[index] = null;
                }
                if (Math.abs(rawButtons[index] - cleanVal) > 0.0001) {
                    rawButtons[index] = cleanVal;
                    return true;
                }
                return false;
            }
        }
    }

    function flushExpiredButtonHolds(now) {
        let changed = false;
        for (let i = 0; i < 17; i++) {
            if (rawButtons[i] > 0.1 && desiredButtons[i] <= 0.1 && now >= buttonHoldUntil[i]) {
                rawButtons[i] = 0.0;
                if (buttonReleaseTimers[i] !== null) {
                    clearTimeout(buttonReleaseTimers[i]);
                    buttonReleaseTimers[i] = null;
                }
                changed = true;
            }
        }
        return changed;
    }

    // Synchronize latest state directly from AndroidBridge on every poll
    const ZERO_STATE_JSON = '{"b":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],"a":[0,0,0,0]}';
    let lastRawGamepadState = "";

    function pollFromAndroid() {
        const now = performance.now();
        let changed = flushExpiredButtonHolds(now);

        if (window.AndroidBridge && window.AndroidBridge.getGamepadState) {
            try {
                const raw = window.AndroidBridge.getGamepadState();
                if (raw === ZERO_STATE_JSON) {
                    // Fast self-healing zero path: whenever Android reports all buttons/axes released,
                    // guarantee desiredButtons and rawAxes are 0 so no button or stick can ever stick!
                    lastRawGamepadState = raw;
                    for (let i = 0; i < 17; i++) {
                        if (updateButtonWithHoldLatch(i, 0.0, now)) {
                            changed = true;
                        }
                    }
                    for (let i = 0; i < 4; i++) {
                        if (Math.abs(rawAxes[i]) > 0.0001) {
                            rawAxes[i] = 0.0;
                            changed = true;
                        }
                    }
                } else if (raw && raw !== lastRawGamepadState) {
                    lastRawGamepadState = raw;
                    const state = JSON.parse(raw);
                    if (state.b) {
                        for (let i = 0; i < 17; i++) {
                            if (updateButtonWithHoldLatch(i, state.b[i], now)) {
                                changed = true;
                            }
                        }
                    }
                    if (state.a) {
                        for (let i = 0; i < 4; i++) {
                            const val = Number(state.a[i]) || 0.0;
                            if (Math.abs(rawAxes[i] - val) > 0.0001) {
                                rawAxes[i] = val;
                                changed = true;
                            }
                        }
                    }
                }
            } catch (e) {}
        }

        if (changed) {
            currentGamepadSnapshot = buildGamepadSnapshot();
        }
    }

    // Direct fast-path called from Kotlin evaluateJavascript for instant reaction.
    // When AndroidBridge is available, always pull from the atomic AndroidBridge.getGamepadState()
    // single source of truth so queued IPC move events can never overwrite a newer release state.
    window.onControllerInput = function(buttons, axes) {
        if (window.AndroidBridge && typeof window.AndroidBridge.getGamepadState === 'function') {
            lastRawGamepadState = "";
            pollFromAndroid();
            for (let i = 0; i < 17; i++) {
                if (rawButtons[i] > 0.05) {
                    ensureStreamActiveFocus();
                    break;
                }
            }
            for (let i = 0; i < 4; i++) {
                if (Math.abs(rawAxes[i]) > 0.05) {
                    ensureStreamActiveFocus();
                    break;
                }
            }
            dispatchGamepadConnected(false);
            return;
        }

        const now = performance.now();
        lastRawGamepadState = "";
        let changed = false;
        let hasActiveInput = false;

        if (buttons && buttons.length >= 17) {
            for (let i = 0; i < 17; i++) {
                const val = Number(buttons[i]) || 0.0;
                if (val > 0.05) hasActiveInput = true;
                if (updateButtonWithHoldLatch(i, val, now)) {
                    changed = true;
                }
            }
        }
        if (axes && axes.length >= 4) {
            for (let i = 0; i < 4; i++) {
                const val = Number(axes[i]) || 0.0;
                if (Math.abs(val) > 0.05) hasActiveInput = true;
                if (Math.abs(rawAxes[i] - val) > 0.0001) {
                    rawAxes[i] = val;
                    changed = true;
                }
            }
        }

        if (changed) {
            currentGamepadSnapshot = buildGamepadSnapshot();
        }

        if (hasActiveInput) {
            ensureStreamActiveFocus();
        }
        dispatchGamepadConnected(false);
    };

    // Override navigator.getGamepads to return ONLY the standard 4-port XInput array [virtualGamepad, null, null, null].
    // Never include origGetGamepads() devices because Android built-in sensors (fingerprint/gyro/keypad)
    // register as extra controllers, causing Resident Evil 2 / 3 to halt with "Press A on the controller you want to use".
    function virtualGetGamepads() {
        pollFromAndroid();
        dispatchGamepadConnected(false);
        return [currentGamepadSnapshot, null, null, null];
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

    // Spoof webkitGetGamepads if probed by legacy gamepad libraries
    try {
        Object.defineProperty(navigator, 'webkitGetGamepads', {
            value: virtualGetGamepads,
            writable: true,
            configurable: true
        });
    } catch (e) {}

    // Dispatch connected once on initial load and when SPA route changes into /play/launch/...
    window.addEventListener('DOMContentLoaded', () => setTimeout(() => dispatchGamepadConnected(false), 300));
    window.addEventListener('load', () => setTimeout(() => dispatchGamepadConnected(false), 600));
    window.addEventListener('popstate', () => setTimeout(() => dispatchGamepadConnected(false), 200));

    // Hook History API pushState / replaceState so SPA transitions into /play/launch/...
    // announce the controller cleanly if the pathname changed
    try {
        const origPushState = history.pushState;
        if (origPushState) {
            history.pushState = function(...args) {
                const res = origPushState.apply(this, args);
                setTimeout(() => {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(false);
                }, 300);
                return res;
            };
        }
        const origReplaceState = history.replaceState;
        if (origReplaceState) {
            history.replaceState = function(...args) {
                const res = origReplaceState.apply(this, args);
                setTimeout(() => dispatchGamepadConnected(false), 300);
                return res;
            };
        }
    } catch (e) {}

    // Continuous heartbeat to ensure game stream stays focused and recognizes controller on route transitions
    setInterval(() => {
        dispatchGamepadConnected(false);
    }, 1500);

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
                }
            });

            pc.addEventListener('track', (evt) => {
                if (evt && evt.receiver) {
                    optimizeReceiverLatency(evt.receiver);
                }
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
                dispatchGamepadConnected(false);

                v.addEventListener('playing', () => {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(false);
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
            }
        });
    }

    setInterval(monitorStreamVideo, 1200);

    console.log("[GFNxCloud] Virtual Xbox Gamepad and 60+ FPS Optimizer ready");
})();
