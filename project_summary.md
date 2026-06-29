# Project Summary

## Overview

`TableTennisTracker` is an Android app for detecting a table tennis ball from the device camera.

Current stack:

- Kotlin for Android UI, CameraX integration, and camera control flow
- C++ via JNI for frame analysis and ball candidate detection
- CameraX for live preview and image analysis
- Camera2 interop for advanced camera controls like ISO, shutter, focus, and white balance

The current restored version is focused on:

- live camera preview
- native per-frame ball detection
- a simple overlay box around the detected ball
- manual camera controls

It does **not** currently include:

- video import / playback mode
- exported debug video
- persistent calibration
- true frame-to-frame tracking with prediction
- multi-candidate debug overlay

## Main Goal Of The App

The app is intended to detect and visually mark a table tennis ball, especially orange or white balls, from a mobile camera feed.

The expected user experience is:

1. Open the app
2. Grant camera permission
3. Point the camera at a table tennis ball
4. See a green square drawn around the ball when detected
5. Adjust camera parameters if lighting or exposure needs help

## Current Architecture

### Android Side

The Android app is centered around `MainActivity`, which:

- requests camera permission
- starts CameraX preview and image analysis
- connects the analyzer to native ball detection
- updates the overlay UI
- exposes on-screen controls for exposure, zoom, torch, ISO, shutter, focus, and white balance

Main files:

- `app/src/main/java/com/example/tabletennistracker/MainActivity.kt`
- `app/src/main/java/com/example/tabletennistracker/BallTrackerAnalyzer.kt`
- `app/src/main/java/com/example/tabletennistracker/BallTrackingOverlay.kt`
- `app/src/main/java/com/example/tabletennistracker/NativeBallTracker.kt`
- `app/src/main/java/com/example/tabletennistracker/TrackerResult.kt`

### Native Side

The native layer is a C++ detector accessed through JNI.

Main native files:

- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/CMakeLists.txt`

The native detector:

- reads `YUV_420_888` camera frame planes
- subsamples the image
- thresholds likely orange or white ball pixels
- groups them into connected regions
- filters regions by size, aspect ratio, fill ratio, brightness, contrast, and circularity
- returns one best square bounding box plus a confidence value

## File-By-File Summary

### `MainActivity.kt`

Responsibilities:

- app startup
- permission handling
- binding preview and image analysis use cases
- updating the overlay and status text
- configuring and applying camera settings

Important behavior:

- starts the back camera only
- uses `PreviewView` plus `ImageAnalysis`
- updates `statusText` with either:
  - `Scanning for ball...`
  - `Ball locked <confidence>%`
- exposes advanced manual camera controls through Camera2 interop

Notable camera settings supported:

- exposure compensation
- zoom ratio
- torch
- manual ISO
- manual shutter speed
- manual focus distance
- manual white balance / warmth

### `BallTrackerAnalyzer.kt`

Responsibilities:

- receives frames from CameraX
- extracts `Y`, `U`, and `V` planes into byte arrays
- sends the frame to `NativeBallTracker.detect(...)`
- applies an additional Kotlin-side false-positive filter before showing a result

Current post-filter behavior:

- keeps some history of earlier accepted detections
- computes motion score from the current and previous luma frame
- prefers detections that are moving, repeated, or spatially close to the previous detection
- can reject detections that do not seem stable or meaningful enough

This means the current app is not purely raw single-frame detection. It already has some lightweight temporal filtering on top of the native result.

### `NativeBallTracker.kt`

Responsibilities:

- loads the native library `table_tennis_tracker`
- holds the selected ball profile:
  - `AUTO`
  - `ORANGE`
  - `WHITE`
- bridges Kotlin to JNI
- maps native raw pixel coordinates into normalized screen coordinates

Returned model:

- `TrackerResult(normalizedBox, confidence)`

### `TrackerResult.kt`

Very simple data model:

- `normalizedBox: RectF`
- `confidence: Float`

This reflects the restored simpler app state:

- one detection
- one square box
- one confidence value

### `BallTrackingOverlay.kt`

Responsibilities:

- draws the detected box on top of the camera preview
- draws a center dot
- draws text showing `Ball <confidence>%`

Current visuals:

- green rectangle
- white center dot
- white confidence label

There is no current debug overlay for:

- rejected candidates
- threshold mask
- detection reasons
- ball path
- predicted positions

### `activity_main.xml`

Layout structure:

- top section:
  - camera preview container
  - overlay view
  - status chip
- bottom section:
  - control card with scrollable camera controls

UI controls currently available:

- ball color profile radio group
- exposure slider
- zoom slider
- torch switch
- manual exposure switch
- ISO slider
- shutter slider
- manual focus switch
- focus slider
- manual white balance switch
- warmth slider
- reset button

There is no current UI for:

- loading saved videos
- play/pause video analysis
- tapping to calibrate the ball
- showing debug candidates

### `strings.xml`

Important current strings:

- app name
- camera control labels
- `Scanning for ball...`
- `Ball locked`

Note: the wording still says `Ball locked`, which is stronger than simple detection and may not match actual detection quality.

### `native-lib.cpp`

This is the core detector.

The native logic includes:

1. Reading YUV values from camera planes
2. Thresholding pixels that look like:
   - orange ball pixels
   - white ball pixels
3. Building a binary mask
4. Running connected-component style region extraction with BFS
5. Rejecting components that are:
   - too small
   - too large
   - touching edges
   - too rectangular
   - badly filled
   - too low contrast
   - too non-circular
   - too weak in expected color
6. Scoring each candidate
7. Returning the best one as a square box

The native scoring uses:

- fill ratio
- compactness / aspect ratio
- circularity
- contrast
- brightness
- color score

### `AndroidManifest.xml`

Declares:

- camera permission
- any camera hardware requirement
- portrait-only `MainActivity`

## Detection Logic Summary

### Kotlin-Side Detection Flow

1. CameraX delivers a frame to `BallTrackerAnalyzer`
2. The analyzer copies YUV planes into arrays
3. `NativeBallTracker.detect(...)` calls JNI
4. Native C++ returns a single best candidate box or null
5. Kotlin applies additional motion and stability filtering
6. If accepted, overlay draws the result

### Native Ball Profiles

The app supports:

- `AUTO`: allow orange or white
- `ORANGE`: emphasize orange-like YUV values
- `WHITE`: emphasize bright neutral YUV values

### Native Heuristics

Orange ball color test:

- luma high enough
- red chroma stronger than blue chroma
- sufficient orange separation

White ball color test:

- bright luma
- U and V near neutral 128

Candidate rejection includes:

- edge rejection
- size rejection
- aspect ratio rejection
- fill ratio rejection
- extreme brightness rejection
- low contrast rejection
- low circularity rejection
- low color score rejection

## Camera Controls Summary

The app supports both auto and manual style camera tuning.

### Basic Controls

- Exposure compensation
- Zoom
- Torch

### Advanced Controls

- Manual ISO
- Manual shutter speed
- Manual focus
- Manual white balance / warmth

These rely on device support. `MainActivity` checks camera capabilities before enabling them.

If unsupported:

- the relevant switches are disabled
- labels update to explain that the feature is unavailable

## Build And Tooling

### Android / Kotlin

- compile SDK: 34
- target SDK: 34
- min SDK: 26
- Kotlin JVM target: 17
- Java source/target: 17

### Native

- CMake enabled
- C++17 enabled
- NDK version: `26.3.11579264`

### Key Dependencies

- `androidx.camera:camera-core`
- `androidx.camera:camera-camera2`
- `androidx.camera:camera-lifecycle`
- `androidx.camera:camera-view`
- `com.google.android.material:material`
- `androidx.constraintlayout:constraintlayout`

## How To Run

### Recommended

1. Open the project in Android Studio
2. Let Gradle sync
3. Install requested SDK/NDK components if prompted
4. Run on a real Android device

### Why A Real Device Is Best

- camera permission flow is real
- CameraX works more reliably
- hardware camera controls can be tested
- actual ball detection depends on real lighting and real frame input

## Current Strengths

- clear Android/native split
- native detector already has meaningful geometric and color filtering
- manual camera controls are fairly advanced for a starter app
- UI is already structured for practical camera testing

## Current Weaknesses / Limitations

### Detection Quality

- the app returns only one best candidate, not a full debug view
- false positives can still occur
- false negatives can still occur in difficult lighting
- no direct visual proof of rejected candidates

### Debugging Limitations

- no threshold mask visualization
- no candidate-by-candidate overlay
- no reason display for candidate rejection
- no paused-frame inspection workflow

### Tracking / State Accuracy

- current analyzer has temporal false-positive filtering
- status text says `Ball locked`, but the app does not have robust, explicit tracking-state modeling in the restored version
- the app is closer to filtered detection than true reliable tracking

### Input Limitations

- no saved video input mode in the current restored version
- no tap-to-calibrate workflow
- no exported detections

## Current Risks

The main risk in the current codebase is that behavior and UI wording can overstate detection reliability.

Examples:

- status text says `Ball locked`
- analyzer promotes detections using repetition/motion heuristics
- native detector exposes only the winning candidate

This makes it harder to understand why the app succeeds or fails on a given frame.

## Recommended Next Steps

If continuing from this restored version, the safest improvement order is:

1. Add single-frame debug visualization first
   - threshold mask
   - all candidate blobs
   - reasons for rejection
2. Validate that paused visible-ball frames are detected correctly
3. Add saved video frame analysis
4. Only after reliable single-frame detection:
   - add tracking
   - add prediction
   - add smoothing

## Current Project Snapshot

In plain terms, the app right now is:

- an Android CameraX app
- with a native C++ heuristic detector
- that tries to find one orange or white table tennis ball
- and draws a square around it
- while giving the user strong manual camera control options

It is a solid foundation, but it still needs better debug visibility and more reliable ball validation before more advanced tracking should be layered on top.
