# Resident Evil 2 & 3 Virtual Controller Input Fix

Resolves an issue where Resident Evil 2 and Resident Evil 3 (RE Engine titles on Xbox Cloud Gaming) ignore on-screen virtual touch controller inputs at the "Press Any Button" title screen and during gameplay.

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your feedback, we are targeting the **on-screen virtual touch controller** specifically for **Resident Evil 2 and Resident Evil 3** where **the game ignores all inputs at the title screen**.

- **Confirmed Decision 1**: Fix virtual on-screen touch controller state snapshots so Xbox Cloud Gaming's input diffing engine and RE Engine detect every button press and stick movement immediately.
- **Confirmed Decision 2**: Keep the WebRTC stream container and WebView actively focused (`document.hasFocus() === true`) while interacting with the native Android Compose controller overlay so inputs are never suppressed at the title screen.
- **Confirmed Decision 3**: Re-dispatch `gamepadconnected` automatically whenever Xbox Cloud Gaming transitions into a game launch session or registers a new gamepad listener.

---

## 1. Overview & Core Concept

- **What It Does**: Restores full, zero-latency responsiveness to the on-screen Xbox controller overlay in Resident Evil 2, Resident Evil 3, and other RE Engine titles on Xbox Cloud Gaming.
- **Target Audience / Persona**: Cloud gamers playing Resident Evil titles on Android using the built-in GeForce NOW-style touch controller overlay.
- **Key Value**: Eliminates the unresponsive "Press Any Button" title screen dead-lock and ensures all 17 buttons, analog triggers, and dual thumbsticks register reliably.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. User launches Resident Evil 2 or Resident Evil 3 from the Xbox Cloud Gaming catalog.
  2. As the cloud stream connects and reaches the title screen, the virtual controller automatically binds as Controller Slot 0 and activates stream input focus.
  3. Pressing `A`, `Menu (Start)`, or any button on the touch overlay immediately advances past the title screen into the main menu and gameplay without needing to hide the overlay or tap the video feed.
- **Visual Identity & Theme**:
  - Preserves the existing high-contrast dark gaming HUD and translucent GeForce NOW-style on-screen controls (`#107C10` Xbox accent green, translucent dark surfaces, and tactile button press feedback).
- **Interactive Feedback & Motion**:
  - Instantaneous button state transitions and haptic click confirmation on every touch input.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Immutable Per-Frame Gamepad Snapshots vs. Shared Mutable Singleton**
  - *Chosen Approach*: Generate distinct `Gamepad` and `GamepadButton` snapshot objects (inheriting from `Gamepad.prototype` and `GamepadButton.prototype`) whenever controller state updates, returning a standard 4-slot array `[snapshot, null, null, null]`.
  - *Why*: Xbox Cloud Gaming's input poller caches the previous `navigator.getGamepads()[0]` reference (`lastPad`) and compares `lastPad.timestamp !== currentPad.timestamp` and `lastPad.buttons[i].pressed !== currentPad.buttons[i].pressed`. Previously, mutating a single shared `virtualGamepad` object in-place caused `lastPad` and `currentPad` to point to the exact same object reference, making every diff check evaluate to "no change" and dropping button presses at the Resident Evil title screen.
  - *Alternatives Considered*: Mutating only button values while cloning the root object—passed over because deep button-array caching in web game engines still sees mutated button references unless each `GamepadButton` snapshot is freshly instantiated on state changes.

- **Decision 2: Stream Focus Lock (`document.hasFocus` & Stream Element Activation)**
  - *Chosen Approach*: Override `document.hasFocus()` to always return `true`, keep Android `WebView` focus active alongside the Compose overlay, and automatically focus the active stream container when controller input occurs.
  - *Why*: Because the on-screen touch controller is rendered in a native Android Jetpack Compose layer above the WebView, touching the controller buttons does not send DOM touch/focus events into the WebView. Xbox Cloud Gaming pauses gamepad transmission when `document.hasFocus()` is `false` or when the stream view lacks focus.
  - *Alternatives Considered*: Forwarding synthetic touch events to the WebView—passed over because synthetic touches trigger Xbox Cloud's own native touch overlay prompts and interfere with in-game camera aiming.

- **Decision 3: Continuous SPA & Listener-Aware Gamepad Connection Dispatch**
  - *Chosen Approach*: Hook `window.addEventListener('gamepadconnected', ...)` to immediately notify newly attached listeners when a game stream launches, and inject the controller bridge as early as `onPageStarted` and `onProgressChanged`.
  - *Why*: Previously, `hasFiredConnected` only allowed `gamepadconnected` to fire once on the initial catalog page (`/play`). When launching Resident Evil via Single-Page Application (SPA) routing (`/play/launch/...`), the newly initialized stream client never received the `gamepadconnected` event.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                Jetpack Compose: GFNControllerOverlay                    │
│     (Touch Buttons, Analog Triggers, Dual Sticks, Deadzone/Curve)       │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ Updates state atomically
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Kotlin: ControllerStateManager                      │
│   • AtomicReference<GamepadSnapshot>                                    │
│   • Fast IPC push: webView.evaluateJavascript("onControllerInput(...)") │
└─────────────────┬───────────────────────────────────┬───────────────────┘
                  │ Fast Push                         │ Synchronous Pull
                  ▼                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                 WebView JS: controller_injector.js                      │
│   • Builds immutable Gamepad & GamepadButton snapshots on state change  │
│   • Protects monotonic timestamp so xCloud diff poller detects edges    │
│   • Overrides document.hasFocus() -> true & focuses stream container    │
│   • Intercepts addEventListener('gamepadconnected') for SPA game launch │
│   • Returns standard 4-port [pad0, null, null, null] in getGamepads()   │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ W3C Gamepad API
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│          Xbox Cloud Gaming WebRTC Input Channel -> RE Engine            │
│        (Resident Evil 2 / Resident Evil 3 Title Screen & Gameplay)      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **Internal Controller State**: Maintains live `buttons[0..16]` (`Float` `0.0..1.0`) and `axes[0..3]` (`Float` `-1.0..1.0`) plus a monotonically increasing `stateVersion` / `timestamp`.
- **Snapshot Cloning**: Whenever any button or axis value changes via `onControllerInput` or `pollFromAndroid`, a new immutable snapshot object is constructed with `Gamepad.prototype` and `GamepadButton.prototype` chains and a strictly incremented `performance.now()` timestamp.
- **4-Slot Gamepad Array**: `navigator.getGamepads()` returns `[currentSnapshot, null, null, null]` so Resident Evil 2 and 3 always bind the virtual controller to Primary Player Slot 0 without interference from phantom Android sensor devices.

### Interactive Component & State Mapping
1. **Early & SPA-Resilient Injection**: `MainActivity` injects `controller_injector.js` at page start, during loading progress, and on page completion so `navigator.getGamepads` is hooked before xCloud's input bundle initializes.
2. **Listener Registration Hook**: Wrapping `window.addEventListener` detects when xCloud's stream session registers a `'gamepadconnected'` handler and dispatches a `GamepadEvent` carrying the latest snapshot.
3. **Focus Retention**: Touching any button or stick on `GFNControllerOverlay` triggers `onControllerInput`, which ensures `window.focus()` is active and dispatches `gamepadconnected` if the stream recently re-mounted.
