# 🎥 HorizonCam

### Get the S26 Ultra's most viral feature on *any* Android device.

**Real-time Horizon Lock for Android.** Record video with a perfectly level horizon—no matter how you tilt, rotate, or flip your phone. 

Inspired by the "Horizon Lock" and "360° Stabilization" features popularized by the **Samsung Galaxy S26 Ultra** and action cameras like GoPro. This app brings that same high-end cinematic experience to your existing hardware.

---

## 🔥 The Magic: 360° Horizon Lock
Most phone cameras tilt the video when you tilt your hand. **HorizonCam doesn't.**

Using a high-performance OpenGL shader pipeline and real-time gyroscope sensor fusion, HorizonCam counter-rotates the camera feed instantly. 
- **Tilt 45°?** The horizon stays flat.
- **Rotate 90° to Landscape?** The video stays upright.
- **Flip it 180° upside down?** Your footage remains perfectly level.

## 🌟 Key Features
- **360° Horizon Lock:** Cinematic level-sensing at any angle of rotation.
- **Action Camera Performance:** Smooth, stabilized footage using your phone's built-in OIS.
- **Real-time Processing:** No long waits for "post-processing" or cloud uploads. What you see is what you record.
- **Ultra-Wide Support:** Toggle to your 0.5× lens for even more stabilization headroom.
- **Multiple Aspect Ratios:** Choose between **16:9**, **4:3**, or **1:1** for social media ready content.
- **Clean UI:** Samsung-inspired interface with smooth transitions and haptic feedback.
- **Privacy First:** Everything happens on-device. No internet required.

## 🛠️ How it Works (The Tech)
HorizonCam uses a sophisticated **Two-Pass Rendering Pipeline**:

1.  **Pass 1 (Camera → FBO):** The raw camera feed is captured and baked into a clean RGBA image, correcting for sensor orientation.
2.  **Pass 2 (FBO → Record):** A custom GLSL shader uses data from a **Complementary Filter** (Gyroscope + Accelerometer) to calculate the exact roll angle. The shader then performs a high-speed counter-rotation and automatic crop scaling to ensure no "black edges" are visible, even at a 45° tilt.

## 📱 Requirements
- **Android 8.0+** (API 26)
- **Gyroscope Sensor** (Required for Horizon Lock)
- **Camera2 API Support**

## 🚀 Getting Started
1.  **Download:** Grab the latest APK from the [Releases](https://github.com/shubhandu/HorizonCam/releases) section.
2.  **Permissions:** Grant Camera and Microphone access.
3.  **Mode:** Tap the **Horizon** tab to activate the lock.
4.  **Record:** Hit the red button and start moving!

## 🏗️ Build from Source
```bash
git clone https://github.com/shubhandu/HorizonCam.git
cd HorizonCam
./gradlew assembleRelease
```
The signed APK will be located in `app/build/outputs/apk/release/HorizonCam.apk`.

---

## 📄 License
MIT License. Free to use, modify, and share.

## ✍️ Credits
Created by **Shubhandu Sharma**. 
Bringing professional action-cam features to every Android user.
