# Pro Evolution Soccer 5 for Android

An unofficial Android (arm64) port of the original Xbox version of *Pro Evolution Soccer 5*, made with the
[XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp) SDK. The game's x86 code in `default.xbe` is statically
recompiled to C++ on a PC, then built for Android together with the SDK's portable runtime and this repo's host code.
Drawing goes through Vulkan, and windowing, input and audio through SDL3. No emulator is involved.

> [!NOTE]
> This project does not include any game assets. You must provide the files from your own legally acquired copy of the
> game to install or build.

## Status

Work in progress. Brought up from the Linux port (PES5-XBERecomp), with the Android host of
[Warriors-Android](https://github.com/datfooldive/The-Warriors-Android) (itself from
[DW5-Android](https://github.com/datfooldive/DW5-Android) and [Black-Android](https://github.com/datfooldive/Black-Android)).

## Installation

Android 10 (API 29) or newer, arm64, with a **Vulkan 1.1** driver or newer.

1. Install the APK.
2. Open **PES5 Android** and allow **all files access** when it asks (on Android 10, the **storage** permission).
3. Copy the game into `/storage/emulated/0/PES5`, or into a `PES5` folder at the top of your SD card: the release
   this build was made from (title id `0x4B4E0030`), either as a disc image (`.iso`) or as `default.xbe` with its data
   files beside it. The app creates the folder once access is allowed, and starts the game as soon as it finds the
   files. A copy in the app's own `Android/data/com.datfooldive.pes5/files/assets` works too.

The saves (`hdd`) live in the game's folder, beside its files, so uninstalling the app does not take the progress with
it. `settings.toml` stays in the app's own files folder. There is no log file on the device: the release writes its
Report and Error lines to logcat (`adb logcat -s pes5`).

## Vulkan

The renderer (`native/vulkan.cpp`) needs Vulkan 1.1 and takes what the device offers above it, chosen at run time and
reported in the log:

| | Vulkan 1.1 | above it |
| --- | --- | --- |
| drawing | render passes | dynamic rendering (1.3, or `VK_KHR_dynamic_rendering`) |
| CPU and GPU in step | fences | timeline semaphores (1.2) |
| topology and strides | in the pipelines | extended dynamic state, where the driver gets it right |

Entry points past 1.1 are looked up on the device, since the NDK's `libvulkan.so` for API 29 exports 1.1 only, and
VMA is held to 1.1.

## Controls

A controller works as on the Xbox, with its rumble; while one is plugged in the on-screen controls are hidden, the gear
staying for the panel. A controller some phones' game services hand over as a virtual device is read by the activity
and plays port 1 all the same (`native/loose.cpp`). A keyboard works as port 1's pad (keys in
`src/common/keyboard.cpp`).

The on-screen controls are the Xbox pad's, where the pad has them: the left stick, the **A B X Y** diamond, White and
Black above it, the triggers, the stick clicks, the D-pad, START and BACK, and the right stick under a finger on the
right. They are thin light outlines with next to no backing, so the pitch shows through, as a native mobile game's
are. Each face button carries a white icon for what it does with the ball (A short pass, B lofted pass or cross, X shot,
Y through ball, as the title maps the pad onto the PS2 game's Cross, Circle, Square and Triangle) and the pad's letter on
a small badge on its top edge; LT (change player) and RT (sprint) are round as the face buttons, a size smaller, with their names on
the badge, and START, BACK and the stick clicks small round buttons. They are an Android view over the game
(`TouchControls.java`) that draws each control from an image, normal and held (`res/drawable-nodpi/ctl_*.png`, made by
`tools/controls_art.py` from the icons in `art/icons/`), and sends what the fingers hold to the game over JNI
(`native/touch.cpp`): the game's thread draws nothing for them. The **gear**, top middle, opens the
settings panel; **Move controls** in it places each control, and a size bar there sizes the one last taken, 50 to 150%.

## Settings

The gear opens the settings panel, the activity's own (`SettingsDialog.java`), over the game, which keeps running behind
it, in tabs: Graphics, Performance, Display and Controls. A value is applied as it changes and written to
`settings.toml` (`native/settings_bridge.cpp`): render resolution, anti-aliasing (SMAA, MSAA), anisotropic filtering,
vertical sync, frame skip, a 30 FPS lock, the aspect ratio, the match camera's height and distance, the FPS counter,
HDR, and the on-screen controls' opacity, right stick mode and sensitivity.

The camera height and distance (`src/game/camera.cpp`) scale the eye's offset in the title's table of match camera
types, which only the cameras of play read: the distance moves the camera back or in along its line of sight, keeping
its angle, and the height raises or lowers it on top of that. 100% is the title's own, and the menus and cut scenes are
left as they are.

The picture is the part of the frame the game draws: like DW5 and the Linux port, the black border the game leaves for
a television's overscan is cut, the picture kept in the TV's proportions.

## Building

Everything runs on Linux. Requirements: clang, cmake, ninja, git, the Android SDK with NDK `30.0.16138531` and CMake
`3.22.1`, and Java 17 or newer. `local.properties` points at the SDK (`build.sh` writes it from `ANDROID_HOME`,
default `~/Android/Sdk`).

1. `assets` and `generated` are symlinks into the Linux port, whose build already recompiled the xbe (with its
   hand-made `config/game.toml` fixes). On another machine, put `default.xbe` (or the disc image with it extracted
   beside it) in `assets/`.
2. Run `./build.sh`. Without `generated/game` it builds the recompiler and recompiles `default.xbe` first, then builds
   `app/build/outputs/apk/release/app-release.apk` with Gradle.
3. Install: `adb install -r app/build/outputs/apk/release/app-release.apk`.

The first native build compiles 256 partitions of recompiled code for arm64: minutes on a many-core PC, much longer on
a small one.

## Layout

| path | |
| --- | --- |
| `native/` | the Android host: `main.cpp` (SDL window, event loop, lifecycle, pacing), `vulkan.cpp` (the Vulkan 1.1+ backend), `vk_shader.cpp` (HLSL to SPIR-V with glslang), `touch.cpp` (what the on-screen controls hold, and their layout in the settings file), `settings_bridge.cpp` (the settings panel's and the controls' JNI), `loose.cpp`, `game_files.cpp`, `system_info.cpp` |
| `src/` | the game's host code, shared with the Linux port, with the Android options, FPS counter and touch input; `src/d3d/render_thread.cpp` is the Android render thread (NEON, frame skip) |
| `sdk/` | XboxXBERecomp as the Linux port has it, with the Android runtime changes (logcat, arm64, the dispatch level lock, `kTexShift`) |
| `config/game.toml` | recompiler input, the Linux port's |
| `app/` | the Gradle module: `PES5Activity`, `SettingsDialog`, `LayoutEditor`, the storage permission prompts, the icon |
| `tools/host/` | the recompiler as a PC tool, built by `build.sh` |

## Credits

- [TGP482](https://github.com/TGP482) for [XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp), the recompiler
  and runtime SDK this port is built on.
- The Android host and Vulkan renderer from [Black-Android](https://github.com/datfooldive/Black-Android),
  [DW5-Android](https://github.com/datfooldive/DW5-Android) and
  [Warriors-Android](https://github.com/datfooldive/The-Warriors-Android).
- [SDL](https://github.com/libsdl-org/SDL), [glslang](https://github.com/KhronosGroup/glslang),
  [toml++](https://github.com/marzer/tomlplusplus), [Dear ImGui](https://github.com/ocornut/imgui) and
  [VulkanMemoryAllocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator).

Pro Evolution Soccer 5 is © 2005 Konami. This project is not affiliated with or endorsed by Konami.
