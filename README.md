# 🎮 GFN xCloud Controller

A custom Android virtual Xbox controller designed for **Xbox Cloud Gaming**.

GFN xCloud Controller provides a GeForce NOW-inspired touchscreen gamepad overlay that allows Android users to control Xbox Cloud Gaming games using on-screen controls instead of a physical controller.

## ✨ Features

- 🎮 Full Xbox-style virtual controller (XInput compliant)
- 📱 Designed for Android with Edge-to-Edge display
- ☁️ Built for Xbox Cloud Gaming (`xbox.com/play`)
- 🕹️ GeForce NOW-inspired controller overlay
- 🚀 **60+ FPS & 25 Mbps WebRTC Stream Optimization** (SDP munging)
- 📊 **Real-time decoded FPS & Ping telemetry badge**
- ⚡ **High refresh rate display mode** (90Hz / 120Hz unlock)
- 📳 **Dual-motor vibration & haptic feedback** support
- 🎯 Virtual analog sticks with responsive spring-back
- 🎛️ LT / RT analog triggers with progressive touch
- 🔘 LB / RB bumpers & L3 / R3 stick clicks
- 🔵 Xbox-style A / B / X / Y buttons & D-pad
- ⚙️ **In-game Quick Settings**: opacity slider, haptics toggle, 60 FPS toggle, cache wipe
- 🌑 Transparent dark gaming UI with customizable opacity
- 🔄 Landscape gaming layout
- ⚡ Lightweight direct JavaScript bridge (<1ms latency)
- 🚫 No physical controller required
- 🔓 No root required

## 📱 Requirements

- Android 8.0 or newer
- Internet connection
- Xbox Cloud Gaming access
- Microsoft/Xbox account

A physical Xbox controller is not required.

## 🚀 Installation

1. Download the latest APK from the **Releases** section.
2. Install the APK on your Android device.
3. Open GFN xCloud Controller.
4. Sign in to Xbox/Microsoft when prompted.
5. Open Xbox Cloud Gaming.
6. Launch a supported cloud game.
7. Use the on-screen controller to play.

> **Note:** Android may ask you to allow installation from the source used to download the APK.

## 🎮 Controls

| Control | Function |
|---|---|
| A | Xbox A |
| B | Xbox B |
| X | Xbox X |
| Y | Xbox Y |
| D-Pad | Directional input |
| Left Stick | Movement |
| Right Stick | Camera |
| L3 | Left-stick click |
| R3 | Right-stick click |
| LB | Left bumper |
| RB | Right bumper |
| LT | Left trigger |
| RT | Right trigger |
| View | View / Back |
| Menu | Menu / Start |

## 🧠 How It Works

GFN xCloud Controller combines an Android WebView-based Xbox Cloud Gaming interface with a touchscreen virtual gamepad.

Touch input is translated into virtual controller states, including:

- Digital button presses
- Analog stick movement
- Analog trigger values
- Bumper inputs
- Stick-click inputs
- D-pad inputs

The project is designed around a virtual Gamepad API approach rather than simply displaying a non-functional controller overlay.

## 🛠️ Building From Source

### Requirements

- Android Studio
- JDK 17+
- Android SDK
- Gradle wrapper included with the project

### Build

Clone the repository:

```bash
git clone https://github.com/ayazzxcs/GFNxCloudController.git
cd GFNxCloudController
```

### Windows

```bash
gradlew.bat assembleDebug
```

### Linux / macOS

```bash
./gradlew assembleDebug
```

The generated debug APK can normally be found at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 📦 Releases

Stable APK builds can be published under the repository's **Releases** section.

If you only want to use the application, download the APK from the latest release rather than building the project yourself.

<img src="https://i.ibb.co/v4VtGPc2/Screenshot-20260907-200112-GFNx-Cloud.jpg" alt="Alternative Text" width="500">

## ⚠️ Disclaimer

This project is an independent community project.

It is **not affiliated with, endorsed by, or sponsored by NVIDIA, Microsoft, Xbox, or GeForce NOW**.

"Xbox", "Xbox Cloud Gaming", and related trademarks are properties of Microsoft.

"GeForce NOW" and related trademarks are properties of NVIDIA.

This project takes visual inspiration from existing gaming interfaces.

## 🐛 Bug Reports

If something doesn't work:

1. Check that you are using the latest release.
2. Restart the application.
3. Reconnect to Xbox Cloud Gaming.
4. Try launching another cloud game.
5. Open a GitHub Issue with:
   - Android version
   - Device model
   - App version
   - Game being played
   - Description of the problem
   - Relevant logs/screenshots

**Never post your Microsoft password, authentication tokens, or other private information in an issue.**

## 🤝 Contributing

Contributions are welcome.

You can contribute by:

- Reporting bugs
- Suggesting features
- Improving controller layouts
- Improving touch responsiveness
- Improving Android compatibility
- Submitting pull requests
- Improving documentation


## ⭐ Support the Project

If this project is useful to you:

⭐ Star the repository  
🐛 Report bugs  
💡 Suggest improvements  
🔧 Contribute code

---

### Made for touchscreen cloud gaming 🎮☁️
