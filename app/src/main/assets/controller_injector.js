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
            window.__dispatchGamepadConnected(true);
        }
        return;
    }
    window.__GFN_CONTROLLER_INJECTED__ = true;

    console.log("[GFNxCloud] Initializing virtual Xbox controller injector...");

    // Raw internal controller state (17 standard XInput buttons, 4 axes)
    const rawButtons = new Float32Array(17);
    const rawAxes = new Float32Array(4);
    let lastTimestamp = performance.now();

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
        try {
            if (window.GamepadButton && window.GamepadButton.prototype) {
                Object.setPrototypeOf(btn, window.GamepadButton.prototype);
            }
        } catch (e) {}
        return btn;
    }

    // Build a fresh immutable Gamepad snapshot whenever state changes so xCloud & RE Engine
    // diff pollers (which compare previousPad.timestamp !== currentPad.timestamp and
    // previousPad.buttons[i].pressed !== currentPad.buttons[i].pressed) always detect edges.
    function buildGamepadSnapshot() {
        const now = performance.now();
        lastTimestamp = now > lastTimestamp ? now : (lastTimestamp + 0.01);

        const buttonsSnap = new Array(17);
        for (let i = 0; i < 17; i++) {
            buttonsSnap[i] = createButtonSnapshot(rawButtons[i]);
        }

        const axesSnap = [rawAxes[0], rawAxes[1], rawAxes[2], rawAxes[3]];

        const snapshot = {
            id: "Xbox 360 Controller (XInput STANDARD GAMEPAD)",
            index: 0,
            connected: true,
            timestamp: lastTimestamp,
            mapping: "standard",
            axes: axesSnap,
            buttons: buttonsSnap,
            vibrationActuator: vibrationActuator,
            hapticActuators: []
        };

        try {
            if (window.Gamepad && window.Gamepad.prototype) {
                Object.setPrototypeOf(snapshot, window.Gamepad.prototype);
            }
        } catch (e) {}

        return snapshot;
    }

    let currentGamepadSnapshot = buildGamepadSnapshot();
    window.__virtualGamepad = currentGamepadSnapshot;

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
        try {
            return new GamepadEvent(type, { gamepad: gamepadObj });
        } catch (e) {
            let ev;
            try {
                ev = new Event(type, { bubbles: false, cancelable: false });
            } catch (e2) {
                ev = new CustomEvent(type, { detail: { gamepad: gamepadObj } });
            }
            ev.gamepad = gamepadObj;
            return ev;
        }
    }

    let hasFiredConnected = false;
    let lastConnectedHref = "";
    let lastConnectedDispatchTime = 0;

    function dispatchGamepadConnected(force) {
        const now = performance.now();
        const href = (window.location && window.location.href) ? window.location.href : "";
        const routeChanged = href !== lastConnectedHref;

        if (!hasFiredConnected || force || routeChanged) {
            if (!force && !routeChanged && (now - lastConnectedDispatchTime < 500)) {
                return;
            }
            hasFiredConnected = true;
            lastConnectedHref = href;
            lastConnectedDispatchTime = now;

            const ev = createGamepadEvent("gamepadconnected", currentGamepadSnapshot);
            try {
                window.dispatchEvent(ev);
            } catch (e) {}
            try {
                if (typeof window.ongamepadconnected === 'function') {
                    window.ongamepadconnected(ev);
                }
            } catch (e) {}
            console.log("[GFNxCloud] gamepadconnected event dispatched for", href);
        }
    }

    window.__dispatchGamepadConnected = dispatchGamepadConnected;

    // Intercept window.addEventListener so if Xbox Cloud Gaming's stream engine attaches a
    // 'gamepadconnected' listener dynamically when launching Resident Evil, it immediately
    // receives the connection event for Slot 0.
    const origAddEventListener = window.addEventListener ? window.addEventListener.bind(window) : null;
    if (origAddEventListener) {
        window.addEventListener = function(type, listener, options) {
            const res = origAddEventListener(type, listener, options);
            if (type === "gamepadconnected" && typeof listener === "function") {
                setTimeout(() => {
                    try {
                        const ev = createGamepadEvent("gamepadconnected", currentGamepadSnapshot);
                        if (typeof listener === "function") {
                            listener.call(window, ev);
                        } else if (listener && typeof listener.handleEvent === "function") {
                            listener.handleEvent(ev);
                        }
                    } catch (e) {}
                }, 15);
            }
            return res;
        };
    }

    // Ensure the WebRTC stream container and window stay focused while the user interacts
    // with the native Android Compose controller overlay above the WebView.
    function ensureStreamActiveFocus() {
        try {
            if (typeof window.focus === 'function') {
                window.focus();
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

    // Synchronize latest state directly from AndroidBridge if available
    let lastRawGamepadState = "";
    function pollFromAndroid() {
        if (window.AndroidBridge && window.AndroidBridge.getGamepadState) {
            try {
                const raw = window.AndroidBridge.getGamepadState();
                if (raw && raw !== lastRawGamepadState) {
                    lastRawGamepadState = raw;
                    const state = JSON.parse(raw);
                    let changed = false;
                    if (state.b) {
                        for (let i = 0; i < 17; i++) {
                            const val = Number(state.b[i]) || 0.0;
                            if (Math.abs(rawButtons[i] - val) > 0.0001) {
                                rawButtons[i] = val;
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
                    if (changed) {
                        currentGamepadSnapshot = buildGamepadSnapshot();
                        window.__virtualGamepad = currentGamepadSnapshot;
                    }
                }
            } catch (e) {}
        }
    }

    // Direct fast-path called from Kotlin evaluateJavascript for instant reaction
    window.onControllerInput = function(buttons, axes) {
        lastRawGamepadState = "";
        let changed = false;
        let hasActiveInput = false;

        if (buttons && buttons.length >= 17) {
            for (let i = 0; i < 17; i++) {
                const val = Number(buttons[i]) || 0.0;
                if (val > 0.05) hasActiveInput = true;
                if (Math.abs(rawButtons[i] - val) > 0.0001) {
                    rawButtons[i] = val;
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
            window.__virtualGamepad = currentGamepadSnapshot;
        }

        if (hasActiveInput) {
            ensureStreamActiveFocus();
        }
        dispatchGamepadConnected(false);
    };

    // Override navigator.getGamepads to return standard 4-port XInput array [pad0, null, null, null]
    // so Resident Evil 2 / 3 (RE Engine) always binds the virtual controller to Player 1 (Slot 0)
    // without phantom Android sensor devices hijacking Slot 0.
    const origGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : null;

    function virtualGetGamepads() {
        pollFromAndroid();
        dispatchGamepadConnected(false);
        const result = [currentGamepadSnapshot, null, null, null];
        if (origGetGamepads) {
            try {
                const physical = origGetGamepads();
                let nextSlot = 1;
                for (let i = 0; i < physical.length && nextSlot < 4; i++) {
                    const p = physical[i];
                    if (p && p.connected && p.mapping === "standard" && p.buttons && p.buttons.length >= 12) {
                        result[nextSlot++] = p;
                    }
                }
            } catch (e) {}
        }
        return result;
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

    // Dispatch connected after page load, user interaction, or SPA route transitions
    window.addEventListener('DOMContentLoaded', () => setTimeout(() => dispatchGamepadConnected(true), 300));
    window.addEventListener('load', () => setTimeout(() => dispatchGamepadConnected(true), 600));
    window.addEventListener('popstate', () => setTimeout(() => dispatchGamepadConnected(true), 200));
    window.addEventListener('pointerdown', () => dispatchGamepadConnected(false), { passive: true });
    window.addEventListener('touchstart', () => dispatchGamepadConnected(false), { passive: true });
    window.addEventListener('keydown', () => dispatchGamepadConnected(false), { passive: true });

    // Hook History API pushState / replaceState so SPA transitions into /play/launch/...
    // immediately announce the controller to the newly mounted game stream session
    try {
        const origPushState = history.pushState;
        if (origPushState) {
            history.pushState = function(...args) {
                const res = origPushState.apply(this, args);
                setTimeout(() => dispatchGamepadConnected(true), 300);
                setTimeout(() => dispatchGamepadConnected(true), 1500);
                return res;
            };
        }
        const origReplaceState = history.replaceState;
        if (origReplaceState) {
            history.replaceState = function(...args) {
                const res = origReplaceState.apply(this, args);
                setTimeout(() => dispatchGamepadConnected(true), 300);
                return res;
            };
        }
    } catch (e) {}

    // Continuous heartbeat to ensure game stream recognizes controller across session launches
    setInterval(() => {
        const href = (window.location && window.location.href) ? window.location.href : "";
        if (href !== lastConnectedHref) {
            dispatchGamepadConnected(true);
        } else {
            dispatchGamepadConnected(false);
        }
    }, 1500);

    // =========================================================================
    // XBOX CLOUD GAMING 60+ FPS & HIGH PERFORMANCE STREAM OPTIMIZER
    // =========================================================================
    console.log("[GFNxCloud] Initializing 60+ FPS WebRTC stream optimizer...");

    // 1. Prevent background tab/visibility frame throttling, enforce document.hasFocus(), & spoof Desktop 60fps capabilities
    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
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
                dispatchGamepadConnected(true);

                v.addEventListener('playing', () => {
                    ensureStreamActiveFocus();
                    dispatchGamepadConnected(true);
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
