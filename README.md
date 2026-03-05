# 🎥 HorizonCam

### Get the Galaxy S26 Ultra's most viral feature on *any* Android device.

[![Download APK](https://img.shields.io/badge/Download-Latest_APK-blue?style=for-the-badge&logo=android)](https://github.com/shubhandu/HorizonCam/releases/)

**Real-time Horizon Lock for Android.** Record video with a perfectly level horizon—no matter how you tilt, rotate, or flip your phone. 

Inspired by the "Horizon Lock" and "360° Stabilization" features popularized by high-end flagship phones and action cameras like GoPro. This app brings that same cinematic experience to your existing hardware using a custom OpenGL ES 2.0 shader pipeline and real-time gyroscope sensor fusion. 

No post-processing, no external dependencies, no cloud—everything happens on-device in real time.

---

## 🔥 The Magic: 360° Horizon Lock
Most phone cameras tilt the video when your hand tilts. **HorizonCam doesn't.**

By counter-rotating the camera feed instantly based on high-frequency gyroscope data, your footage stays completely anchored to the horizon.
* **Tilt 45°?** The horizon stays flat.
* **Rotate 90° to Landscape?** The video stays upright.
* **Flip it 180° upside down?** Your footage remains perfectly level.

## 🌟 Key Features
* **360° Horizon Lock:** Real-time, gyroscope-driven counter-rotation with automatic crop scaling to prevent black edges.
* **Pure OIS Performance:** Utilizes hardware Optical Image Stabilization while actively disabling EIS to prevent feedback loops with the rotation shader.
* **Multiple Aspect Ratios:** Select between 16:9, 4:3, or 1:1 for social media-ready content.
* **Ultra-Wide Support:** Toggle to your 0.5× lens for massive stabilization headroom.
* **Zero Gimbal Lock:** Complementary filter features an `atan2` wrapping fix for perfectly smooth transitions through ±180°.
* **Samsung-Inspired UI:** Clean interface with smooth fade transitions, haptic feedback, and a portrait-locked UI layout `setOrientationHint(0)`.

## 🚀 Getting Started
**Requirements:** Android 8.0+ (API 26), Camera2 API Support, and a Gyroscope sensor.

1. **Download:** Grab the latest `HorizonCam.apk` directly from the **[Releases Page](https://github.com/shubhandu/HorizonCam/releases/)**.
2. **Permissions:** Grant Camera and Microphone access upon launch.
3. **Mode:** Tap the **Horizon** tab to activate the lock.
4. **Record:** Hit the red button and start moving!

---

## 🛠️ The Tech: How It Works
HorizonCam uses a sophisticated, low-latency **Two-Pass Rendering Pipeline**:

1. **Pass 1 (Camera → FBO):** The raw camera texture (OES) is rendered to an offscreen framebuffer using the `SurfaceTexture` transform matrix. This corrects sensor orientation and bakes it into a clean RGBA image. *(Why? Applying texture coordinate rotations in the same shader as the sensor orientation matrix causes interference. Pass 1 guarantees clean coordinates).*
2. **Pass 2 (FBO → Screen/Recorder):** The FBO texture is sent to the display and `MediaRecorder` surfaces via a custom GLSL shader. A Complementary Filter (Gyroscope + Accelerometer gravity vector) calculates the exact roll angle. The shader then counter-rotates sampling coordinates to cancel the phone's tilt.

## ⚠️ Known Behaviors & Physical Limits (Crop Math)
To keep the horizon level without showing black corners, the app must crop into the sensor. 
* **The 45° Limit:** At a 0° tilt, crop scale is 1.0 (no zoom). At exactly 45°, the bounding box hits its tightest fit, resulting in an effective zoom of **~2×** for 9:16 output. This is expected physical behavior (the inscribed rectangle limit).
* **Ultra-Wide OIS:** Most phone ultra-wide lenses lack hardware OIS. For the smoothest handheld footage, stick to the 1× main lens.
* **Video Only:** HorizonCam is currently optimized entirely for video pipelines; photo capture is not yet supported.

## 🏗️ Architecture Overview
* `MainActivity.kt` — UI, mode tabs, lens toggle, recording controls
* `CameraHelper.kt` — Camera2 lifecycle, OIS configuration, MediaRecorder integration
* `GlRenderer.kt` — Two-pass GL pipeline (OES → FBO → display/recorder)
* `GlCameraView.kt` — SurfaceView + GL thread management
* `HorizonStabilizer.kt` — Complementary filter (gyro + accel → roll angle)
* `OverlayView.kt` — Crop guides, horizon line rendering, angle readout

## 💻 Building from Source
```bash
git clone [https://github.com/shubhandu/HorizonCam.git](https://github.com/shubhandu/HorizonCam.git)
cd HorizonCam
./gradlew assembleDebug
```
*APK output:* `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License
[MIT License](LICENSE). Free to use, modify, and share.

## ✍️ Credits
Built by **Shubhandu Sharma**. Inspired by GoPro Horizon Lock and Gyroflow.
