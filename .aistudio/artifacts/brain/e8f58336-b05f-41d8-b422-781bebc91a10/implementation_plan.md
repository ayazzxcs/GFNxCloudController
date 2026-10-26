# Zero-Lag Controller & Anti-Scroll Fix for Samsung Galaxy M56

Eliminates the controller input lag introduced in the previous build and permanently resolves the unwanted menu scrolling on both the D-Pad and Left Joystick in Resident Evil 2 on the Samsung Galaxy M56.

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your answers, this plan is tailored to your exact preferences for **Samsung Galaxy M56** gameplay in **Resident Evil 2**:

- **Confirmed Decision 1 (D-Pad Menu Behavior)**: **Single step per tap, repeat only on long hold** — tapping a D-Pad button moves the menu selection by exactly one item and stops immediately; holding the D-Pad down intentionally past the hold threshold steps smoothly and stops the instant you lift your finger.
- **Confirmed Decision 2 (Controls Affected by Scroll Bug)**: **Both D-Pad buttons and Left Joystick** — fixes the root cause shared by both directional controls so neither D-Pad taps nor Left Joystick movements trigger extra menu scrolling rounds.
- **Confirmed Decision 3 (Zero-Latency Synchronous Input & Session Preservation)**: Restores instant controller speed by reading touch state synchronously without WebView IPC queue bottlenecks, while preserving the working `"Press A"` title screen fix and keeping your Xbox login session intact.

---

## 1. Overview & Core Concept

- **What It Does**: Restores immediate, zero-lag responsiveness to all on-screen controller buttons and thumbsticks while ensuring D-Pad taps and Left Joystick flicks advance game menus by one crisp step without runaway scrolling.
- **Target Audience / Persona**: Cloud gamers playing Resident Evil 2 and Xbox Cloud Gaming titles on high-refresh-rate Android devices like the Samsung Galaxy M56.
- **Key Value**: Combines instant button/stick responsiveness with exact 1-to-1 menu navigation control and uninterrupted stream focus.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Title Screen & `"Press A"` Prompt**: Pressing `A` or `Menu` immediately wakes the stream focus and advances past the `"Press A on the controller you want to use"` prompt on the first tap.
  2. **Menu Navigation (D-Pad & Left Stick)**:
     - A single tap on any D-Pad arrow moves the menu cursor by **1 item** and stops immediately.
     - Holding a D-Pad arrow intentionally for more than `450ms` steps through items at a controlled pace and stops the exact millisecond the finger lifts.
     - A quick flick of the Left Joystick moves the menu cursor by **1 item** without micro-jitter triggering a full round of scrolling, while continuous hold provides smooth 360-degree character movement in gameplay.
  3. **Instant Action & Aiming Response**: Moving the Right Stick or pressing triggers/buttons responds on the very next display frame with zero queueing lag.
- **Visual Identity & Theme**:
  - Retains the dark-glass GeForce NOW controller overlay aesthetic (`#107C10` Xbox accent green, translucent HUD controls, tactile dot-matrix thumbsticks, and live FPS/ping telemetry badge).
- **Interactive Feedback & Motion**:
  - Zero-delay thumbstick tracking during touch drag, crisp haptic click feedback on button press, and instant state release when lifting fingers off the screen.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Eliminate WebView IPC Queue Flooding via Synchronous State Pulling**
  - *Chosen Approach*: Always read the live controller state synchronously from the atomic native bridge on every `navigator.getGamepads()` poll, and stop firing asynchronous `evaluateJavascript` calls on every thumbstick drag pixel.
  - *Why*: On the Samsung Galaxy M56's high-frequency touchscreen, sending an asynchronous `evaluateJavascript` message with a result callback on every stick movement flooded the WebView message queue. That backlog made the controller feel slow AND caused queued movement events to keep draining for a full second after lifting your finger. Reading the atomic state synchronously takes `< 0.05ms` and reflects finger lift in `0ms`.
  - *Alternatives Considered*: Throttling `evaluateJavascript` calls—passed over because synchronous native bridge reads are already zero-latency and never build up a queue.

- **Decision 2: Strict State-Change Snapshot Timestamps (Fixing 120Hz Multi-Step Scrolling)**
  - *Chosen Approach*: Only generate a new `Gamepad` snapshot and only increment `Gamepad.timestamp` when a button or axis value actually changes (`stateChanged === true`), rather than generating a new timestamp on every 120Hz poll while a button or stick is held.
  - *Why*: Previously, while any button or stick was non-zero (`anyNonZero`), the injector minted a brand-new `Gamepad` object with an incremented `timestamp` on every single 120Hz frame. Cloud gaming input pollers treat each new `timestamp` as a fresh HID input packet, causing a 150ms touch on the D-Pad or Left Stick to fire 15–20 consecutive directional steps (1 full round of menu scrolling).
  - *Alternatives Considered*: Artificially pausing inputs for `320ms`—passed over because artificial dead-windows make the controller feel sluggish without fixing the underlying timestamp spam.

- **Decision 3: Deduplicated Single-Registration `gamepadconnected` Dispatch**
  - *Chosen Approach*: Track registered `'gamepadconnected'` listeners in a `WeakSet` so each stream listener is notified exactly once, and remove the recurring 1.2-second `gamepadconnected` broadcast from `navigator.getGamepads()`.
  - *Why*: Repeatedly firing `gamepadconnected` for Controller Slot 0 while the game is already polling causes Xbox Cloud Gaming to register duplicate controller listeners for Slot 0, multiplying directional inputs.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                On-Screen Touch Controller Overlay                       │
│   • D-Pad Buttons, ABXY, Triggers, Dual Thumbsticks (0.18 Deadzone)     │
└───────────────────┬─────────────────────────────────┬───────────────────┘
                    │ Continuous State Write          │ Discrete Wake Edge Only
                    │ (Lock-Free Atomic Update)       │ (Button Press Edge)
                    ▼                                 ▼
┌───────────────────────────────────────┐ ┌───────────────────────────────┐
│     Atomic Native Controller State    │ │   Stream Focus Wake Handler   │
│   • Instant 0ms read/write in memory  │ │   • Wakes stream focus on A / │
│   • Zero IPC queue backlog on release │ │     Menu press without flood  │
└───────────────────┬───────────────────┘ └───────────────┬───────────────┘
                    │ Synchronous Pull (<0.05ms)          │
                    ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                 Virtual Xbox 360 W3C Gamepad Engine                     │
│   • Always reads live atomic state on every getGamepads() call          │
│   • D-Pad: Crisp 45ms single-step on tap; controlled repeat after 450ms │
│   • Left Stick: Latch-quantized initial flick; smooth 3D hold movement  │
│   • Updates Gamepad.timestamp ONLY when button/axis values change       │
│   • Deduplicates gamepadconnected events via WeakSet listener tracking  │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ W3C Gamepad API [pad0, null, null, null]
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│             Xbox Cloud Gaming WebRTC Input -> Resident Evil 2           │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **Atomic Controller State**: Holds the real-time `buttons[0..16]` and `axes[0..3]` arrays in a lock-free atomic reference updated immediately by touch gestures and reset to zero the instant a finger lifts.
- **State-Diff Snapshot Cache**: Retains `currentSnapshot` and compares incoming effective button/axis values against `lastPolledButtons` and `lastPolledAxes`. A new `Gamepad` instance and monotonic `timestamp` are created strictly when values change.
- **D-Pad Tap & Hold State Machine**:
  - **Initial Tap (`0..45ms`, min 2 polls)**: Emits `1.0` for one clean step, then drops to `0.0` so normal finger tap durations (`80..250ms`) never trigger game auto-scroll.
  - **Intentional Long Hold (`> 450ms`)**: Emits discrete `45ms` step pulses every `180ms` while the button remains physically held, terminating immediately to `0.0` as soon as the finger lifts.
- **Left Stick Flick Stabilizer**:
  - Filters resting thumb noise with an `0.18` inner deadzone and stabilizes initial cardinal flicks so menu selection moves 1 step cleanly before transitioning into continuous 1:1 analog movement.

### Interactive Component & State Mapping
1. **Touch Down / Drag on Stick or D-Pad**: Updates the atomic state in memory immediately without queuing `evaluateJavascript` strings.
2. **Synchronous `navigator.getGamepads()` Poll**: Reads the atomic state directly on every frame, computes effective D-Pad/Stick values, and returns the cached snapshot if unchanged or a fresh snapshot if changed.
3. **Touch Release (Finger Lift)**: Immediately writes `0.0` to the atomic state so the very next `getGamepads()` poll sees `0.0` with zero queue delay.
