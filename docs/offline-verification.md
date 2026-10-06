# Phase 1 — Offline / no-PC verification (2026-10-06)

Raw evidence: `results/raw/offline/results/interactive.jsonl` (records written by the app itself).

| Run | airplane_mode | plugged (0 = battery) | phone network (as Android reports) | decode tok/s | tokens |
|---|---|---|---|---|---|
| reference, USB connected | false | 2 (USB) | online | 77.0 | 256 |
| **offline test** (done by Roman on the phone) | **true** | **0** | online* | **61.2** | 256 |

- USB cable disconnected (`plugged=0`) → no adb, no PC process involved; generation ran to completion.
- The app declares **no `android.permission.INTERNET`** (checked with `dumpsys package dev.rq.llmceiling`:
  only `ACCESS_NETWORK_STATE` is requested), so it cannot open a network socket regardless of phone state.
- *Android still reported an active network during airplane mode, i.e. Wi-Fi had been re-enabled inside
  airplane mode (or a VPN profile was up). This does not affect the conclusion because of the missing
  INTERNET permission, but a stricter repeat with Wi-Fi off is noted below if it gets done.
- The model in this test was loaded while USB was still connected; loading is a local file read
  (`/sdcard/Android/data/dev.rq.llmceiling/files/models`), not a network operation.
- 61 vs 77 tok/s on battery vs USB is a single pair of runs, not a controlled comparison.
  Power state is recorded on every benchmark run so it can be controlled for.
