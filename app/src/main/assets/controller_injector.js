(function() {
    'use strict';
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

    console.log("[GFNxCloud] Virtual Xbox Gamepad ready");
})();
