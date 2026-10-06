# Phase 0 — Environment inventory (2026-10-06)

Raw evidence: `results/raw/phase0-inventory.txt` (PowerShell transcript), `results/raw/phase0-device.txt`.

## Host (Windows 11, build 26200) — reused, nothing installed
| Tool | Found | Notes |
|---|---|---|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (ANDROID_HOME set) | platforms 35/36/37, build-tools 34/36/36.1 |
| Android NDK | 27.2.12479018 | upstream sample pins 29.0.13113456 — we try 27.2 first |
| CMake | 3.22.1 (SDK) | not on PATH; llama.cpp requires >=3.14; upstream sample pins 3.31.6 |
| adb | SDK platform-tools | |
| JDK | 21 (Oracle) + Android Studio JBR | JAVA_HOME = Studio JBR |
| Gradle | wrapper caches 8.9 / 8.13 / 9.2 / 9.6 | no global gradle; use wrapper |
| cmdline-tools | 8.0 + latest | sdkmanager available if something must be added |
| Android Studio | installed | not required; CLI workflow |

## Device
| Property | Value |
|---|---|
| Model | Samsung Galaxy Z Fold6 (SM-F956B, `q6q`) |
| SoC | Qualcomm SM8650 (Snapdragon 8 Gen 3, `pineapple`) |
| ABI | arm64-v8a only |
| Android | 16 (API 36), build BP4A.251205.006.F956BXXS4DZH5 |
| RAM | MemTotal 11,381,252 kB (~10.9 GiB visible of 12 GB); MemAvailable at idle ≈ 3.7 GB |
| CPU | 8 cores, 3 tiers: cpu7 Cortex-X4 (0xd82) 3.40 GHz · cpu2-4 Cortex-A720 (0xd81) 3.15 GHz · cpu5-6 A720 2.96 GHz · cpu0-1 Cortex-A520 (0xd80) 2.27 GHz |
| CPU features | asimd, asimddp (dotprod), i8mm, bf16, fphp — **no SVE/SVE2 advertised** |
| Swap | SwapTotal 12.0 GiB (zram "RAM Plus"); /proc/swaps not readable |
| Storage | /data 222 G, 142 G free |
| Thermal | Thermal HAL AIDL 3 connected; status 0; AP 35.8 °C, BAT 34.5 °C at inspection |
| Battery | dumpsys exposes level, voltage (mV), `current now` (µA), temperature — V·I is an instantaneous power *proxy*, not energy |

Note: MemAvailable at idle (~3.7 GB) is far below MemTotal. Android reclaims cached/background
memory under pressure, so this is not a hard limit, but it means a ~5 GB 8B Q4 model is a genuine test.

## Existing llama.cpp (LLM2Swarm)
- Only prebuilt **Windows x64 CUDA** binaries (b10993) — not usable on Android, no source.
- Reusable GGUFs: `Qwen3-4B-Q4_K_M.gguf`, `Ministral-3-3B-Instruct-2512-Q4_K_M.gguf`.

## Integration choice
Current llama.cpp ships an official Android sample, `examples/llama.android`, split into an
`app` module and a reusable `lib` module (`com.arm.aichat`: Kotlin `InferenceEngine` → JNI
`ai_chat.cpp` → llama.cpp via CMake `add_subdirectory`). We follow that architecture with
llama.cpp vendored unmodified as a git submodule, and our own app + instrumented JNI layer.
Upstream pins NDK 29 / CMake 3.31.6 / AGP 8.13 / Gradle 8.14.3; llama.cpp itself only needs
CMake >= 3.14, so we first try the installed NDK 27.2 + CMake 3.22.1 and only add the pinned
versions if the build actually fails because of them.

## Implications for the experiment
- Thread count: 1 prime + 5 mid cores is the natural "fast" set (6 threads); the two A520 little cores are
  expected to hurt decode. Choose the thread count once on the smallest rung, then freeze it for all rungs.
- GGUF weights are mmap'd (file-backed): under memory pressure the kernel drops and re-reads them from
  flash rather than swapping them to zram. A model "loading" is therefore not proof it fits in RAM —
  process RSS and decode speed must be checked.
- Phone was USB-powered/charging at inspection. Charging adds heat; benchmark runs should record
  `plugged` state and prefer a consistent condition.
