# Table Tennis Tracker

Android starter app for tracking a table tennis ball with:

- Kotlin for camera/UI flow
- C++ for native frame analysis
- CameraX for preview and image processing
- Exposure, zoom, and torch controls on screen

## What it does

- Opens the back camera
- Analyzes `YUV_420_888` frames in native code
- Detects likely white or orange table tennis balls
- Draws a square overlay around the detected ball
- Lets you adjust exposure compensation, zoom, and torch live

## Project structure

- `app/src/main/java/com/example/tabletennistracker/MainActivity.kt`
  Camera permission, CameraX binding, and control handling
- `app/src/main/java/com/example/tabletennistracker/BallTrackerAnalyzer.kt`
  Frame analyzer bridge from CameraX to native tracker
- `app/src/main/java/com/example/tabletennistracker/NativeBallTracker.kt`
  JNI bridge and coordinate mapping
- `app/src/main/java/com/example/tabletennistracker/BallTrackingOverlay.kt`
  Overlay square rendering
- `app/src/main/cpp/native-lib.cpp`
  Native detector

## Build notes

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Install the Android SDK/NDK versions requested by the project if Studio asks.
4. Run on a real Android device for camera testing.

## Next improvements

- Add calibration for different ball colors and lighting conditions
- Add tap-to-focus and manual ISO/shutter controls
- Add OpenCV or TensorFlow Lite if you want more reliable detection
- Save tracking sessions and export coordinates or video
