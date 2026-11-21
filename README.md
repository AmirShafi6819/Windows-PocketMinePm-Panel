<p align="center">
  <img src="assets/logo.png" alt="Project logo" width="88">
</p>

<p align="center">
  <img src="assets/banner.svg" alt="Windows PocketMine Panel" width="100%">
</p>

<p align="center">
  <strong>The Windows port of Android PacketMine Panel.</strong>
  <br>
  Run a Minecraft Bedrock server locally on your Windows PC with PocketMine-MP - a native desktop app, no phone required.
</p>

<p align="center">
  <a href="https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/stargazers"><img src="https://img.shields.io/github/stars/AmirShafi6819/Windows-PacketMinePm-Panel?style=for-the-badge&color=8b5cf6&label=Stars" alt="GitHub stars"></a>
  <a href="https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/network/members"><img src="https://img.shields.io/github/forks/AmirShafi6819/Windows-PacketMinePm-Panel?style=for-the-badge&color=06b6d4&label=Forks" alt="GitHub forks"></a>
  <a href="https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/releases"><img src="https://img.shields.io/github/v/release/AmirShafi6819/Windows-PacketMinePm-Panel?style=for-the-badge&color=17c964&label=Release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Windows-10%2B%20x64-0078d4?style=for-the-badge&logo=windows&logoColor=white" alt="Windows 10+">
</p>

---

## ✨ About

**Windows PacketMine Panel** is a desktop port of [Android PacketMinePm Panel](https://github.com/EXBYPIXEL/Android-PacketMinePm-Panel). It lets you run **PocketMine-MP** locally on a Windows PC and host a **Minecraft Bedrock** server without a separate server machine.

The whole panel UI, every feature and every tool of the Android app is preserved - only the Android plumbing (WebView client, SAF file pickers, foreground service, battery optimizations) was replaced with native Windows equivalents.

## 📥 Releases — Start here

Open the [Releases page](https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/releases) and download **one** of these:

| File | What it is |
| --- | --- |
| `PMPanel-Setup-x.y.z-x64.exe` | The installer - per-user install, no admin rights required, Start-menu shortcut |
| `PMPanel-x.y.z-portable-win64.zip` | The portable version - unzip anywhere and run `PMPanel.exe` inside |

Everything is bundled: the Windows PHP runtime (official pmmp build), PocketMine-MP and the fake-player engine. No Java, no PHP, no other dependencies to install.

> **SmartScreen note:** the binaries are unsigned. If Windows SmartScreen shows a warning, click **More info → Run anyway**. This is normal for open-source apps without a code-signing certificate.

## 🚀 Features (identical to the Android app)

- 🖥️ Run a PocketMine-MP server and host a Minecraft Bedrock world directly on your Windows PC
- 📱 **Same panel UI** - the original mobile interface (Persian/English, dark theme) runs unchanged inside the app
- 🧩 Choose from supported server versions (official pmmp + NG builds, with the built-in updater)
- 🧪 Test plugins with the built-in fake-player engine (`pmbot.exe`, built from the same Go source as the Android version)
- 🛠️ Compile or decompile your own plugins (pure-Java Phar tools, no PHP needed)
- 🗂️ Multi-server profiles with per-server ports, memory limits and consoles
- 📦 Config zip importer, backups & restore, file manager, property editor, log viewer
- 🛒 Poggit plugin market with icons, READMEs and one-click install
- 🌐 Public IP / UPnP port mapping, connectivity self-test, RCON console, GS4 query stats
- 📊 Live statistics: TPS, CPU & RAM of the server process, online players, network I/O
- 🪟 **Desktop niceties added by the port:** opens maximized with a resizable/responsive layout, `F11` for true Windows fullscreen, and a system tray icon keeps the server running in the background when you close the window (with a "stop all servers" shortcut)

## 🛠️ Getting Started

1. Download the **Setup exe** from [Releases](https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/releases) and run it (or unzip the portable version).
2. Launch **PM Panel** from the Start menu (or `PMPanel.exe`).
3. Pick your language, select the server version you want in the panel, press **Start**, and share your address (see the network tools in the Tools tab).
4. Friends join from the same network with `your-ip:19132`; use the Public-IP / UPnP tool to go online.

> Data (servers, worlds, plugins, settings) lives in `%APPDATA%\PMPanel` - same layout as the Android app's private storage. Diagnostics go to `%APPDATA%\PMPanel\logs\app-crash.log`.

## 🩺 Troubleshooting

| Problem | What to do |
| --- | --- |
| Window opens white / empty | Fixed automatically since 2.8.1: the app detects a failed render and relaunches itself in software-rendering mode, and falls back to a backup copy of the UI if the local server is blocked. If you still see it, just restart the app once. |
| App does not open at all | Check `%APPDATA%\PMPanel\logs\app-crash.log`, then delete `%LOCALAPPDATA%\PMPanel\app` (extracted UI cache) and start again. |
| Antivirus complains about `pmbot.exe` / `php.exe` | They are the bundled fake-player engine and the official pmmp PHP runtime - whitelist the `%APPDATA%\PMPanel\bin` folder. |
| Port 19132 already in use | The panel detects the conflict and moves the server to a free port automatically; check the console note. |
| Friends cannot connect | Start the connectivity self-test (Tools tab), forward UDP `19132` on your router or use the built-in UPnP tool, and make sure Windows Firewall allows `php.exe`. |

## 🏗️ How the port works

| Android | Windows |
| --- | --- |
| `WebView` (Chromium) + `addJavascriptInterface` | JavaFX `WebView` + local loopback UI server with the same `window.PMPanel` JSON bridge |
| Bundled ARM PHP (`libpmphp.so`) | Official pmmp **PHP 8.4 Windows x64** runtime, extracted on first run |
| Foreground service + notification | System tray icon (`TrayService`) |
| SAF file pickers | Native Windows file dialogs |
| `/proc/<pid>/stat` CPU & RSS sampling | PowerShell process probe (`WinProc`) |
| Battery optimization / wakelocks | Not needed on desktop (kept as harmless no-ops so the UI stays identical) |
| `pmbot` Go engine (`libpmbot.so`) | Same Go source, cross-compiled to `pmbot.exe` in CI |

The panel UI (`www/`) is bundled **byte-identical** to the Android app. A tiny runtime shim (injected by the desktop shell, not by the files) defers boot until the native bridge is attached and adds a TTF fallback for the Vazirmatn fonts.

### Build from source

Requirements: **JDK 21** and **Go 1.21+** (the Go toolchain version is pinned in `pmbot/go.mod` and downloaded automatically).

**Windows (cmd.exe):**

```bat
:: 1. build the bot engine (or let CI build it)
cd pmbot
go build -trimpath -ldflags "-s -w" -o ..\build\resources-native\native\pmbot.exe .
cd ..

:: 2. fetch the official PHP runtime
curl -L -o build\resources-native\native\php-windows.zip ^
  https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.4-latest/PHP-8.4-Windows-x64-PM5.zip

:: 3. build the self-contained app image (jlink + jpackage)
gradlew.bat packageApp
:: result: build\jpackage\PMPanel\PMPanel.exe
```

**Linux / macOS (cross-compile the bot engine for Windows):**

```bash
cd pmbot
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o ../build/resources-native/native/pmbot.exe .
cd ..

mkdir -p build/resources-native/native
curl -L -o build/resources-native/native/php-windows.zip \
  https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.4-latest/PHP-8.4-Windows-x64-PM5.zip

./gradlew packageApp
```

CI (`.github/workflows/build.yml`) builds everything on a Windows runner on every push and attaches the installer + portable zip to every `v*` release tag.

## 🤝 Contributions and Issues

Have a suggestion or found a problem? Open an [issue](https://github.com/AmirShafi6819/Windows-PacketMinePm-Panel/issues). When reporting a bug, include your Windows version, app version, and steps to reproduce it.

## ⭐ Support the Project

If you find this project useful, consider giving it a **Star** to help others discover it.

<p align="center">
  Made for the Minecraft Bedrock and PocketMine-MP community 💜
</p>
