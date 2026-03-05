# HorizonCam

**Real-time horizon lock for Android.** Record video with a perfectly level horizon - no matter how you tilt your phone.

Built with Camera2 API, OpenGL ES 2.0, and gyroscope sensor fusion. No post-processing, no external dependencies, no cloud - everything happens on-device in real time.

## What it does

HorizonCam counter-rotates the camera feed using gyroscope data so the horizon stays level at any angle of rotation. Tilt your phone 45°, 90°, even upside down - the recorded video stays upright.

**Normal mode** - standard video recording with OIS (optical image stabilization).

**Horizon Lock mode** - real-time gyroscope-driven counter-rotation with automatic crop scaling. The app crops into the sensor to create rotation headroom, then applies a GL shader to keep the frame level.

## How it works

The stabilization pipeline has two stages:

**Pass 1 - Camera → FBO:** The raw camera texture (OES) is rendered to an offscreen framebuffer using the `SurfaceTexture` transform matrix. This produces a clean, correctly-oriented RGBA image.

**Pass 2 - FBO → Screen/Recorder:** The FBO texture is rendered to the display and MediaRecorder surfaces with a rotation + crop shader. A complementary filter (gyroscope + accelerometer gravity vector) provides the roll angle. The shader counter-rotates sampling coordinates to cancel the phone's tilt.

Crop scale is pre-computed to guarantee no out-of-bounds sampling at any rotation angle, checked at the three critical angles (0°, 45°, 90°).

## Features

- Real-time horizon lock at any rotation angle (full 360°)
- Two-pass FBO rendering pipeline (no texMatrix interference)
- Complementary filter with atan2 wrapping fix (smooth through ±180°)
- Aspect ratio selection: 16:9, 4:3, 1:1
- Lens toggle: 1× main (OIS) / 0.5× ultra-wide
- OIS enabled, EIS disabled (prevents feedback loop with rotation shader)
- Saves to DCIM/HorizonCam via MediaStore
- Samsung-style custom UI (record button, flip camera, mode tabs)
- Smooth fade transitions on camera/mode switch
- Haptic feedback on all controls
- Portrait-locked recording with `setOrientationHint(0)`

## Requirements

- Android 8.0+ (API 26)
- Camera2 API support
- Gyroscope sensor (required for Horizon Lock mode)
- Ultra-wide camera (optional, for 0.5× lens toggle)

## Building

```bash
git clone https://github.com/YOUR_USERNAME/HorizonCam.git
cd HorizonCam
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

```
MainActivity.kt       - UI, mode tabs, lens toggle, recording controls
CameraHelper.kt       - Camera2 lifecycle, OIS, MediaRecorder, ultra-wide detection
GlRenderer.kt         - Two-pass GL pipeline (OES→FBO→display/recorder)
GlCameraView.kt       - SurfaceView + GL thread management
HorizonStabilizer.kt  - Complementary filter (gyro + accel → roll angle)
OverlayView.kt        - Crop guide, horizon line, angle readout
Buttons.kt            - Custom RecordButton + FlipButton views
```

## Known issues

- Ultra-wide lens typically lacks OIS - use 1× main lens for smoother handheld footage
- At extreme crop (45° rotation), effective zoom is ~2× - expected behavior, this is the inscribed rectangle limit
- No photo capture yet - video only

## Technical details

**Crop math:** At θ=0° the crop scale is 1.0 (no zoom). At θ=45° it's 0.509 for 9:16 output (the tightest fit). The formula checks bounding box extents at 0°, 45°, and 90° and takes the minimum.

**Why two passes?** The `SurfaceTexture.getTransformMatrix()` applies sensor orientation correction (typically 90° rotation + Y-flip). If you try to rotate texture coordinates in the same shader that applies this matrix, the two transforms interfere. The FBO pass bakes the orientation into a clean RGBA image, and the rotation pass operates on known, consistent coordinates.

**Why disable EIS?** Android's `CONTROL_VIDEO_STABILIZATION_MODE_ON` digitally warps frames to cancel detected motion. Our shader also rotates frames. The two systems fight each other, creating oscillation. OIS (physical lens movement) has no such conflict.

## License

MIT

## Credits

Built by Shubhandu Sharma. Inspired by GoPro Horizon Lock, Gyroflow.
