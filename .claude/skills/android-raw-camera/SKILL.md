---
name: android-raw-camera
description: Domain knowledge for building this project's Android pro camera - Camera2 RAW capture, unlocking vendor-hidden sensor modes and lenses, burst align/merge, spectral film emulation, DNG correctness and the measurement protocol. Use for any work on capture, device profiles, the processing pipeline, film looks or quality validation in this repo.
---

# Android RAW camera: working knowledge

Source of truth for findings and citations: `docs/research/stage-0-research.md`. Read it before changing direction.

## Core thesis
1. Third-party apps lose to stock apps because the vendor HAL **classifies the calling app** and gates sensor modes, lenses and ISP features by package. Example: vivo X300 Ultra full-size modes exist only when `AppType == vivo`.
2. We win by owning the pipeline (RAW burst, align/merge, our own tone and colour) and by **data-driven per-device unlocking**, never by stacking LUTs on JPEG.

## Access levels (every device gets mapped to the highest safe one)
- **L0** Public Camera2: `REQUEST_AVAILABLE_CAPABILITIES` RAW / MANUAL_SENSOR / BURST_CAPTURE / READ_SENSOR_SETTINGS.
- **L1** Hidden camera IDs. Try opening ids 0..15, not only `getCameraIdList()`. Example: Nothing Phone (3) real multi-cam is id 4 over sensors 0/2/3. Use `getPhysicalCameraIds()` and `OutputConfiguration.setPhysicalCameraId`.
- **L2** Vendor tags through session params or requests, discovered via `getKeys()` / `getAvailableSessionKeys()`. Example (OPPO X9 Ultra): `com.oplus.is.sdk.camera.package` (byte 1) and `com.oplus.is.from.extension` (int 1) as session params. `com.oplus.engineercamera.agingtest.mode.select` goes on **still requests only**.
- **L3** Vendor gate through a hidden class. Example: vivo `android.hardware.vivocamera.VivoCameraManager` via reflection, `getInfo(40)`/`setValue(40,1)`. The value is **system-wide**: journal the previous value, restore it on close, and recover it on the next start after a crash.
- **L4** Root or patched libraries: out of scope.

## Device profile rules
- Profiles are versioned JSON matched by model. Each has camera/physical id, streams (RAW viewfinder + optional full-size photo stream + companion YUV), session (api java/native, template, gate), vendorKeys with scope (session/repeating/still) and explicit `workarounds`.
- Known HAL traps:
  - Some HALs will not start RAW alone. Add a small companion YUV stream.
  - A null or empty AF ROI crashes the vivo X200 Ultra provider. Keep the template regions.
  - A session left open when another camera app opens crashes the vivo HAL. Close it on pause.
  - A full-size request without the vendor keys crashes the vivo X300 Ultra.
- Never silently fall back from RAW to JPEG. Report the capability honestly.
- Our profile **schema and code are our own**. rawr / FILCamera / MotionCam are GPL-3 and FreeDcam is GPL-2: learn facts from them, do not copy code unless the project license decision says GPL.

## Pipeline
- Zero-shutter-lag: a ring buffer of RAW frames, and pick by `SENSOR_TIMESTAMP` nearest the press.
- Align/merge: Hasinoff 2016 (tile pyramid, Wiener-style merge). Reference: timothybrooks/hdr-plus (MIT).
- Super-res/detail: Wronski 2019. Reference: Jamy-L Handheld-MFSR (MIT).
- Tone: exposure fusion on a synthetic exposure stack plus one underexposed frame for highlights (MotionCam approach).
- Colour: work in linear (ProPhoto or AP1). Use `SENSOR_COLOR_TRANSFORM1/2`, `FORWARD_MATRIX1/2`, `CALIBRATION_TRANSFORM` and the illuminants, plus the lens shading map.
- Film: spectral simulation (negative, print, halation, diffusion, stochastic grain), after spektrafilm and spectral_film_lut. Datasheet curves alone are not enough.
- Preview must be computed from RAW on the GPU so it matches the output. Spatial film stages get a screen-scale realtime approximation.
- Defaults: minimal processing, no global unsharp mask, and noise reduction by merging rather than smoothing.

## DNG correctness (CI gate)
- Run Adobe `dng_validate` on every fixture, check that LibRaw/darktable open the files, and check that the colour matrices are present.
- Known failure seen elsewhere: magenta tint means a wrong or missing matrix.

## Measurement protocol (no "looks better" claims without numbers)
- ColorChecker ΔE2000, slanted-edge MTF50 plus overshoot, SNR per ISO, DR in stops, and shutter lag in ms (240fps video of an LED clock).
- Thermal and battery over 30 min.
- Blind 2AFC with at least 20 photographers against the stock app and MotionCam/GCam.
