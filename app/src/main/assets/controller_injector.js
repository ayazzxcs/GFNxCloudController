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

    if (window.__GFN_CONTROLLER_INJECTED__) return;
    window.__GFN_CONTROLLER_INJECTED__ = true;

    console.log("[GFNxCloud] Initializing virtual Xbox controller injector...");

    // Create the Virtual Gamepad compliant with W3C Gamepad API and XInput standard
    const virtualGamepad = {
        id: "Xbox 360 Controller (XInput STANDARD GAMEPAD)",
        index: 0,
        connected: true,
        timestamp: performance.now(),
        mapping: "standard",
        axes: [0.0, 0.0, 0.0, 0.0],
        buttons: Array.from({ length: 17 }, () => ({
            pressed: false,
            touched: false,
            value: 0.0
        })),
        vibrationActuator: {
            type: "dual-rumble",
            reset: function() { return Promise.resolve("complete"); },
            playEffect: function(type, params) {
                try {
                    if (window.AndroidBridge && window.AndroidBridge.vibrate) {
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
        },
        hapticActuators: []
    };

    window.__virtualGamepad = virtualGamepad;

    let hasFiredConnected = false;
    function dispatchGamepadConnected() {
        if (!hasFiredConnected) {
            hasFiredConnected = true;
            try {
                const ev = new GamepadEvent("gamepadconnected", { gamepad: virtualGamepad });
                window.dispatchEvent(ev);
            } catch (e) {
                try {
                    const ev = new CustomEvent("gamepadconnected", { detail: { gamepad: virtualGamepad } });
                    ev.gamepad = virtualGamepad;
                    window.dispatchEvent(ev);
                } catch (e2) {}
            }
            console.log("[GFNxCloud] gamepadconnected event dispatched");
        }
    }

    window.__dispatchGamepadConnected = dispatchGamepadConnected;

    // Synchronize latest state directly from AndroidBridge if available
    function pollFromAndroid() {
        if (window.AndroidBridge && window.AndroidBridge.getGamepadState) {
            try {
                const raw = window.AndroidBridge.getGamepadState();
                if (raw) {
                    const state = JSON.parse(raw);
                    if (state.b) {
                        for (let i = 0; i < 17; i++) {
                            const val = state.b[i] || 0.0;
                            virtualGamepad.buttons[i].value = val;
                            virtualGamepad.buttons[i].pressed = val > 0.1;
                            virtualGamepad.buttons[i].touched = val > 0.0;
                        }
                    }
                    if (state.a) {
                        virtualGamepad.axes[0] = state.a[0] || 0.0;
                        virtualGamepad.axes[1] = state.a[1] || 0.0;
                        virtualGamepad.axes[2] = state.a[2] || 0.0;
                        virtualGamepad.axes[3] = state.a[3] || 0.0;
                    }
                }
            } catch (e) {}
        }
        virtualGamepad.timestamp = performance.now();
    }

    // Direct fast-path called from Kotlin evaluateJavascript for instant reaction
    window.onControllerInput = function(buttons, axes) {
        if (buttons && buttons.length >= 17) {
            for (let i = 0; i < 17; i++) {
                const val = buttons[i];
                virtualGamepad.buttons[i].value = val;
                virtualGamepad.buttons[i].pressed = val > 0.1;
                virtualGamepad.buttons[i].touched = val > 0.0;
            }
        }
        if (axes && axes.length >= 4) {
            virtualGamepad.axes[0] = axes[0];
            virtualGamepad.axes[1] = axes[1];
            virtualGamepad.axes[2] = axes[2];
            virtualGamepad.axes[3] = axes[3];
        }
        virtualGamepad.timestamp = performance.now();
        dispatchGamepadConnected();
    };

    // Override navigator.getGamepads
    const origGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : null;

    function virtualGetGamepads() {
        pollFromAndroid();
        dispatchGamepadConnected();
        const physical = origGetGamepads ? Array.from(origGetGamepads()) : [];
        const result = [virtualGamepad];
        for (let i = 1; i < physical.length; i++) {
            if (physical[i]) result.push(physical[i]);
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

    // Dispatch connected after page load or user interaction
    window.addEventListener('DOMContentLoaded', () => setTimeout(dispatchGamepadConnected, 500));
    window.addEventListener('load', () => setTimeout(dispatchGamepadConnected, 800));
    window.addEventListener('pointerdown', dispatchGamepadConnected, { passive: true });
    window.addEventListener('touchstart', dispatchGamepadConnected, { passive: true });
    window.addEventListener('keydown', dispatchGamepadConnected, { passive: true });

    // Periodic heartbeat to ensure game recognizes controller
    let attempts = 0;
    const interval = setInterval(() => {
        attempts++;
        dispatchGamepadConnected();
        if (attempts > 30) clearInterval(interval);
    }, 1000);

    // =========================================================================
    // XBOX CLOUD GAMING 60+ FPS & HIGH PERFORMANCE STREAM OPTIMIZER
    // =========================================================================
    console.log("[GFNxCloud] Initializing 60+ FPS WebRTC stream optimizer...");

    // 1. Prevent background tab/visibility frame throttling
    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
    } catch (e) {
        console.warn("[GFNxCloud] Visibility hook error", e);
    }

    // 2. SDP Munging: Force 60+ FPS framerate and 25 Mbps bandwidth in WebRTC negotiation
    function force60FpsSdp(sdp) {
        if (!sdp || typeof sdp !== 'string') return sdp;
        try {
            const lines = sdp.split(/\r?\n/);
            const output = [];
            let inVideo = false;
            let videoHasBitrate = false;

            for (let i = 0; i < lines.length; i++) {
                let line = lines[i];

                if (line.startsWith('m=video')) {
                    inVideo = true;
                    videoHasBitrate = false;
                    output.push(line);
                    continue;
                } else if (line.startsWith('m=')) {
                    inVideo = false;
                }

                if (inVideo) {
                    // Force high bandwidth allocation (25 Mbps)
                    if (line.startsWith('b=AS:') || line.startsWith('b=TIAS:')) {
                        output.push('b=AS:25000');
                        videoHasBitrate = true;
                        continue;
                    }

                    // Inject 60fps & 1080p frame size parameters into video fmtp lines
                    if (line.startsWith('a=fmtp:')) {
                        if (!line.includes('max-fr=') && !line.includes('max-fps=')) {
                            line += ';max-fr=60;max-fps=60;min-fr=60';
                        } else {
                            line = line.replace(/max-fr=\d+/g, 'max-fr=60')
                                       .replace(/max-fps=\d+/g, 'max-fps=60');
                        }
                        if (!line.includes('x-google-min-bitrate=')) {
                            line += ';x-google-min-bitrate=15000;x-google-max-bitrate=25000;x-google-start-bitrate=20000';
                        }
                    }
                }

                output.push(line);

                // If m=video didn't have b=AS, append it after c=IN line
                if (inVideo && !videoHasBitrate && line.startsWith('c=IN')) {
                    output.push('b=AS:25000');
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
    if (window.RTCPeerConnection) {
        const OrigPeerConnection = window.RTCPeerConnection;

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
            return origSetRemoteDescription.call(this, desc);
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

    // 3. Monitor Video Elements, optimize hardware compositing layer, and calculate real-time FPS
    window.__clarityBoostEnabled = true;

    function injectClarityBoostFilter() {
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
                    <filter id="gfn-clarity-filter">
                        <feConvolveMatrix order="3" preserveAlpha="true" kernelMatrix="0 -0.35 0 -0.35 2.4 -0.35 0 -0.35 0"/>
                    </filter>
                </defs>
            `;
            (document.body || document.documentElement).appendChild(svg);
        } catch (e) {
            console.warn("[GFNxCloud] Could not inject Clarity Boost SVG filter", e);
        }
    }

    function applyClarityBoostToVideo(v) {
        if (!v) return;
        if (window.__clarityBoostEnabled) {
            v.style.filter = 'url(#gfn-clarity-filter) contrast(1.04) saturate(1.04)';
        } else {
            v.style.filter = 'none';
        }
    }

    window.setClarityBoost = function(enabled) {
        window.__clarityBoostEnabled = !!enabled;
        injectClarityBoostFilter();
        const videos = document.querySelectorAll('video');
        videos.forEach(applyClarityBoostToVideo);
        console.log("[GFNxCloud] Clarity Boost set to:", window.__clarityBoostEnabled);
    };

    function monitorStreamVideo() {
        injectClarityBoostFilter();
        const videos = document.querySelectorAll('video');
        videos.forEach(v => {
            if (!v.__gfn_stream_optimized__) {
                v.__gfn_stream_optimized__ = true;
                v.playsInline = true;
                v.disablePictureInPicture = true;
                if ('disableRemotePlayback' in v) v.disableRemotePlayback = true;

                // Force GPU layer composition
                v.style.transform = 'translateZ(0)';
                v.style.willChange = 'transform';
                applyClarityBoostToVideo(v);

                // Calculate real decoded stream FPS
                let lastTime = performance.now();
                let frames = 0;

                function countFps(now, metadata) {
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
                    if (v.requestVideoFrameCallback) {
                        v.requestVideoFrameCallback(countFps);
                    }
                }

                if (v.requestVideoFrameCallback) {
                    v.requestVideoFrameCallback(countFps);
                }
            }
        });
    }

    setInterval(monitorStreamVideo, 1200);

    console.log("[GFNxCloud] Virtual Xbox Gamepad and 60+ FPS Optimizer ready");
})();
