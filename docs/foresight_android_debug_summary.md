# ForeSight Android MVP Debug Summary

Date: 2026-06-08  
Project: `ForeSightApk`  
Package: `com.example.foresightapk`  
Goal: Android MVP for local next-app prediction using the exported `foresight_fp32.tflite` model.

## 2026-06-08 Model Export Update

The ML-side export was fixed after this original crash investigation.

Old broken model:

```text
app/src/main/assets/foresight_fp32.tflite
```

New model:

```text
app/src/main/assets/foresight_aet.tflite
```

New model source on this Mac:

```text
/Users/gurnoor/Downloads/foresight_aet.tflite
```

The old model was exported through ONNX/onnx2tf and crashed native TFLite/LiteRT during `runForMultipleInputsOutputs`.

The new model was exported from the PyTorch checkpoint using Google's LiteRT Torch path:

```text
litert-torch / import litert_torch
```

New model tensor contract:

```text
app input:
name: serving_default_args_0
shape: [1, 10]
dtype: int64

context input:
name: serving_default_args_1
shape: [1, 10, 3]
dtype: float32

output:
name: serving_default_output_0_output
shape: [1, 87]
dtype: float32
```

Important Android change:

The old ONNX/onnx2tf model used context `[1, 3, 10]`.
The new LiteRT Torch model uses context `[1, 10, 3]`.

Android must build context as:

```text
context[time_index][feature_index]
```

not:

```text
context[feature_channel][time_index]
```

As of this update, the Android predictor has been changed locally to:

- load `foresight_aet.tflite`
- remove the old `foresight_fp32.tflite` asset
- route `[1, 10]` int64 as app input
- route `[1, 10, 3]` float32 as context input
- build context time-major
- keep output handling as `[1, 87]`

## Executive Summary

The Android app shell is working:

- Usage Access permission detection works.
- Recent app launch events are read from `UsageStatsManager` / `UsageEvents`.
- The last 10 app launches are collected.
- App labels/package names are mapped into the model vocabulary where possible.
- Context input is built in the required TFLite shape `[1, 3, 10]`.
- TensorFlow Lite / LiteRT input tensor routing correctly detects:
  - `app_sequences`: `[1, 10]`, `INT64`
  - `context_sequences`: `[1, 3, 10]`, `FLOAT32`
- Logging works through Logcat tag `ForeSight`.
- Local JSONL error logging works.
- The main Android UI now survives native inference crashes by running inference in a separate `:inference` process.

The current blocker is the model artifact:

`foresight_fp32.tflite` crashes Android native TFLite / LiteRT at `runForMultipleInputsOutputs`.

This happens across multiple runtime configurations:

- `org.tensorflow:tensorflow-lite:2.17.0`
- `org.tensorflow:tensorflow-lite:2.14.0`
- `org.tensorflow:tensorflow-lite:2.16.1`
- `com.google.ai.edge.litert:litert:1.4.1`
- `com.google.ai.edge.litert:litert:2.1.5`

The crash is native:

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Java_org_tensorflow_lite_NativeInterpreterWrapper_run
```

Because this is a native `SIGSEGV`, Kotlin `try/catch` cannot catch it. The app now contains the crash by running inference in a separate Android process.

## Repository And Push State

Initial Android MVP was pushed to:

```text
https://github.com/gurnoorpannu/ForeSightApk
```

Pushed commits:

```text
c7916bc Build ForeSight Android MVP
0a8a677 Stabilize TFLite inference
```

Important note:

After the user said "dont push yet", all later debugging changes were kept local and were not pushed.

Current local unpushed changes include:

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/foresightapk/MainActivity.kt`
- `app/src/main/java/com/example/foresightapk/InferenceContract.kt`
- `app/src/main/java/com/example/foresightapk/InferenceService.kt`
- this summary document

## Assets

The model and vocabulary were copied from the ML pipeline repository:

```text
https://github.com/gurnoorpannu/ForeSight-MLpipeline
```

Temporary clone location used during development:

```text
/private/tmp/ForeSight-MLpipeline
```

Copied files:

```text
/private/tmp/ForeSight-MLpipeline/models/foresight_fp32.tflite
-> app/src/main/assets/foresight_fp32.tflite

/private/tmp/ForeSight-MLpipeline/outputs/app_vocab.json
-> app/src/main/assets/app_vocab.json
```

Current Android asset paths:

```text
app/src/main/assets/foresight_fp32.tflite
app/src/main/assets/app_vocab.json
```

Model size observed in logs:

```text
Loaded model asset bytes=1005080
```

Vocabulary size observed in logs:

```text
Loaded app vocab entries=87 from app_vocab.json
```

## Model Contract

The exported model expects two inputs:

```text
app_sequences
shape: [1, 10]
dtype: INT64

context_sequences
shape: [1, 3, 10]
dtype: FLOAT32
```

This was confirmed at runtime:

```text
Input tensor[0]: name=app_sequences, shape=[1, 10], dtype=INT64
Input tensor[1]: name=context_sequences, shape=[1, 3, 10], dtype=FLOAT32
```

The output tensor has 87 classes:

```text
Output classes: 87
```

Important transpose detail:

The original PyTorch training layout was:

```text
[1, 10, 3]
```

The exported TFLite model expects:

```text
[1, 3, 10]
```

The Android app builds context as channel-first:

```text
context[hour_channel][time_index]
context[day_channel][time_index]
context[gap_channel][time_index]
```

Context features:

```text
hour_of_day / 23.0
day_of_week / 6.0
time_gap_seconds / 3600.0, clipped to [0, 1]
```

## Android MVP Features Implemented

### Permission

Manifest permission:

```xml
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

Usage Access is checked with `AppOpsManager.OPSTR_GET_USAGE_STATS`.

Runtime log example:

```text
Usage access mode=0, enabled=true
```

### Usage Events

Recent launches are read with:

```kotlin
UsageStatsManager.queryEvents(startMillis, endMillis)
```

Foreground event types used:

```text
UsageEvents.Event.MOVE_TO_FOREGROUND
UsageEvents.Event.ACTIVITY_RESUMED
```

Lookback windows:

```text
24 hours
7 days
30 days
```

The app deduplicates consecutive launches of the same package.

Log examples:

```text
Querying usage events: lookbackMs=86400000, limit=10
Usage event query returned 5 deduped launches
Querying usage events: lookbackMs=604800000, limit=10
Usage event query returned 124 deduped launches
Loaded 10 recent app launches
```

### Vocabulary Mapping

The provided `app_vocab.json` is app-label based, not package-name based.

Because Usage Events provide package names, the app:

1. Resolves app label from package manager.
2. Uses known package aliases for common apps.
3. Falls back to model ID `0` if there is no vocabulary match.

Observed warnings:

```text
No vocab match for package=com.example.talkeys_new, label=com.example.talkeys_new; falling back to ID 0
No vocab match for package=com.google.android.apps.nexuslauncher, label=com.google.android.apps.nexuslauncher; falling back to ID 0
```

This warning is not the crash. It only means those apps are outside the training vocabulary.

Observed model input example:

```text
Mapped input IDs: [0, 0, 0, 0, 0, 0, 9, 0, 9, 0]
Unknown/PAD ID count: 8
```

The high unknown/PAD count is expected on a device running apps that are not in the 87-class training vocabulary.

## Files Implemented

### `MainActivity.kt`

Responsibilities:

- Compose UI.
- Usage Access status.
- Refresh button.
- Reads Usage Events.
- Requests isolated inference process.
- Shows recent apps.
- Shows predictions if inference succeeds.
- Shows error card if inference crashes/fails.
- Shows diagnostics.

Current local behavior:

- Startup no longer auto-runs model inference.
- Startup only checks Usage Access.
- Inference runs only when Refresh is tapped.

This avoids crashing the inference subprocess on every app open.

### `UsageEventReader.kt`

Responsibilities:

- Checks Usage Access.
- Reads recent launch events.
- Resolves app labels.
- Deduplicates consecutive package events.

### `ForeSightPredictor.kt`

Responsibilities:

- Loads `foresight_fp32.tflite`.
- Loads `app_vocab.json`.
- Builds app sequence input.
- Builds context input.
- Inspects model input tensor names/shapes/dtypes.
- Routes model inputs by shape:
  - `[1, 10]` -> app input
  - `[1, 3, 10]` -> context input
- Runs TFLite / LiteRT interpreter.
- Converts logits/scores to probabilities if needed.
- Produces top-5 predictions.

Important implementation details:

- XNNPACK disabled.
- CPU single-threaded.
- Direct `ByteBuffer` used for input/output transport.

### `ForeSightLog.kt`

Single Logcat tag:

```text
ForeSight
```

### `PredictionLogger.kt`

Writes local JSONL logs:

```text
prediction_events.jsonl
foresight_errors.jsonl
```

Successful prediction logs include:

- timestamp
- input app sequence
- input app labels
- top-k predictions
- inference latency
- diagnostics

Error logs include:

- timestamp
- failed stage
- exception type
- exception message
- stack trace
- diagnostics

### `InferenceService.kt`

Local unpushed crash-containment change.

Runs in a separate Android process:

```xml
android:process=":inference"
```

Purpose:

- Native TFLite / LiteRT crashes kill only the `:inference` process.
- The main UI process survives.
- The main process detects service death via `onServiceDisconnected`.
- The UI displays a clear error instead of disappearing.

Observed successful containment:

```text
Cmdline: com.example.foresightapk:inference
Fatal signal 11 (SIGSEGV)
PROCESS ENDED for com.example.foresightapk:inference
Isolated inference process disconnected
Prediction refresh failed: Inference process crashed while running the TFLite model.
Logged error at stage=Prediction refresh
```

### `InferenceContract.kt`

Local unpushed crash-containment change.

Defines Messenger message IDs and Bundle serialization for:

- launch events
- prediction result
- prediction error

Used for communication between:

```text
MainActivity process
InferenceService :inference process
```

## Runtime Versions Tried

### 1. TensorFlow Lite `2.17.0`

Dependency:

```text
org.tensorflow:tensorflow-lite:2.17.0
```

Result:

- Build succeeded.
- Runtime loaded `libtensorflowlite_jni.so`.
- Native inference crashed.
- XNNPACK was initially enabled by default.

Crash:

```text
Created TensorFlow Lite XNNPACK delegate for CPU.
Calling TFLite interpreter.runForMultipleInputsOutputs
Fatal signal 11 (SIGSEGV)
Java_org_tensorflow_lite_NativeInterpreterWrapper_run
```

### 2. TensorFlow Lite `2.17.0` With XNNPACK Disabled

Changes:

- `setUseXNNPACK(false)`
- `setNumThreads(1)`
- Direct `ByteBuffer` input/output

Result:

- Build succeeded.
- Runtime confirmed XNNPACK disabled.
- Native inference still crashed.

Log:

```text
TFLite interpreter initialized on CPU with 2 inputs; XNNPACK disabled
Tensor transport: direct ByteBuffer
Calling TFLite interpreter.runForMultipleInputsOutputs
Fatal signal 11 (SIGSEGV)
```

### 3. TensorFlow Lite `2.14.0`

Dependency:

```text
org.tensorflow:tensorflow-lite:2.14.0
```

Reason tried:

To avoid the newer LiteRT-transformed native binary and test an older runtime.

Build issue:

AGP 9 complained that:

```text
Namespace 'org.tensorflow.lite' is used in multiple modules and/or libraries:
org.tensorflow:tensorflow-lite:2.14.0
org.tensorflow:tensorflow-lite-api:2.14.0
```

Temporary workaround:

- `compileOnly(tensorflow-lite-api)`
- `implementation(tensorflow-lite)` with transitive API excluded

Result:

- Build succeeded.
- Android showed 16 KB compatibility warning.

Warning:

```text
Android App Compatibility
This app isn't 16 KB compatible.
libtensorflowlite_jni.so: LOAD segment not aligned
```

Conclusion:

`2.14.0` is not acceptable for modern Android 16 KB page-size devices/emulators.

### 4. TensorFlow Lite `2.16.1`

Dependency:

```text
org.tensorflow:tensorflow-lite:2.16.1
```

Result:

- Build succeeded.
- `libtensorflowlite_jni.so` still not 16 KB aligned.

Local ELF alignment check:

```text
libtensorflowlite_jni.so ['0x1000', '0x1000', '0x1000'] 16KB_OK=False
```

Conclusion:

Not acceptable for 16 KB page-size compatibility.

### 5. LiteRT `1.4.1`

Dependency:

```text
com.google.ai.edge.litert:litert:1.4.1
```

Result:

- Build succeeded.
- Native libraries were 16 KB aligned.
- Native inference still crashed.

### 6. LiteRT `2.1.5`

Current local runtime dependency:

```text
com.google.ai.edge.litert:litert:2.1.5
```

Result:

- Build succeeded.
- Runtime loads `libLiteRt.so`.
- All arm64 native libs are 16 KB aligned.
- Native inference still crashes.

Current local alignment check:

```text
lib/arm64-v8a/libLiteRt.so ['0x4000', '0x4000', '0x4000'] 16KB_OK=True
lib/arm64-v8a/libLiteRtClGlAccelerator.so ['0x4000', '0x4000', '0x4000'] 16KB_OK=True
lib/arm64-v8a/libandroidx.graphics.path.so ['0x4000', '0x4000', '0x4000'] 16KB_OK=True
```

Current crash:

```text
Load ... libLiteRt.so ... ok
TensorFlowLite I Loaded native library: LiteRt
TFLite interpreter initialized on CPU with 2 inputs; XNNPACK disabled
Calling TFLite interpreter.runForMultipleInputsOutputs
Fatal signal 11 (SIGSEGV)
Java_org_tensorflow_lite_NativeInterpreterWrapper_run
```

Conclusion:

The crash is not fixed by current LiteRT runtime.

## 16 KB Page Size Compatibility

Android showed this compatibility warning when older native libraries were packaged:

```text
This app isn't 16 KB compatible.
ELF alignment check failed.
```

The warning listed:

```text
libandroidx.graphics.path.so
libtensorflowlite_jni.so
```

Fixes applied locally:

```text
androidx.graphics:graphics-path:1.1.0-beta01
com.google.ai.edge.litert:litert:2.1.5
```

After this, local ELF alignment check shows all arm64 native libs are 16 KB aligned.

## Current Local Dependencies

Current local `gradle/libs.versions.toml` relevant entries:

```toml
androidxGraphicsPath = "1.1.0-beta01"
liteRt = "2.1.5"

androidx-graphics-path = { group = "androidx.graphics", name = "graphics-path", version.ref = "androidxGraphicsPath" }
litert = { group = "com.google.ai.edge.litert", name = "litert", version.ref = "liteRt" }
```

Current local `app/build.gradle.kts` relevant entries:

```kotlin
implementation(libs.androidx.graphics.path)
implementation(libs.litert)
```

## Current Local Runtime Behavior

On startup:

1. Main app process starts.
2. UI is shown.
3. Usage Access is checked.
4. Model inference is not automatically run.

Log:

```text
MainActivity resumed; checking Usage Access
Usage access mode=0, enabled=true
```

On tapping Refresh:

1. Main process reads Usage Events.
2. UI updates recent app list.
3. Main process starts/binds isolated `:inference` process.
4. `:inference` process loads model and LiteRT.
5. `:inference` process crashes inside native runtime.
6. Main process detects service disconnect.
7. UI shows error.
8. Error is logged locally.

Observed UI error:

```text
Prediction refresh failed: Inference process crashed while running the TFLite model.
```

## Why Kotlin Error Handling Cannot Fix This

Normal Kotlin/Java exceptions can be caught:

```kotlin
try {
    interpreter.run(...)
} catch (e: Exception) {
    ...
}
```

But this failure is not a Java/Kotlin exception.

It is a native process crash:

```text
Fatal signal 11 (SIGSEGV)
```

When native code segfaults:

- the Android process is killed
- no Kotlin `catch` block runs
- no normal error callback is possible

This is why the app was changed to run inference in a separate process.

## Exact Native Crash Point

The crash always happens immediately after:

```text
Calling TFLite interpreter.runForMultipleInputsOutputs
```

Stack excerpts:

```text
Java_org_tensorflow_lite_NativeInterpreterWrapper_run+88
com.example.foresightapk.ForeSightPredictor.predict
```

With LiteRT `2.1.5`:

```text
libLiteRt.so
Java_org_tensorflow_lite_NativeInterpreterWrapper_run+88
```

With older TensorFlow Lite:

```text
libtensorflowlite_jni.so
Java_org_tensorflow_lite_NativeInterpreterWrapper_run+88
```

This means:

- model loads
- tensor shapes are discoverable
- interpreter initializes
- crash occurs during actual graph execution

## What Is Not The Cause

Based on logs and tests, these are not the root cause:

### Usage Access

Usage Access works:

```text
Usage access mode=0, enabled=true
```

### Usage Event Reading

Usage Events work:

```text
Loaded 10 recent app launches
```

### Missing Assets

Model and vocab load successfully:

```text
Loaded model asset bytes=1005080
Loaded app vocab entries=87
```

### Wrong Tensor Routing

Tensor routing is correct:

```text
Input tensor[0]: name=app_sequences, shape=[1, 10], dtype=INT64
Input tensor[1]: name=context_sequences, shape=[1, 3, 10], dtype=FLOAT32
```

### XNNPACK

XNNPACK was disabled and the crash remained:

```text
XNNPACK disabled
```

### JVM Array Input Transport

Direct `ByteBuffer` transport was used and the crash remained:

```text
Tensor transport: direct ByteBuffer
```

### 16 KB Page Alignment

16 KB alignment was fixed with LiteRT `2.1.5`, and the crash remained.

## Most Likely Root Cause

The exported TFLite graph is not stable on Android LiteRT/TFLite runtime.

The model was exported through ONNX/TFLite conversion and contains LSTM/Gather-related graph components.

Strings found inside the TFLite model include:

```text
foresight_lstm
embedding
embedding_gather_indices_wrapped_runtime
embedding_gather_indices_normalized
embedding_gather_indices_i32
context_sequences_onnx_ncx_internal_perm
node_LSTM_68
node_LSTM_129
```

This suggests the converted graph contains ONNX-to-TFLite transformed LSTM and embedding/gather logic. That pattern appears to be crashing Android native runtime during invoke.

## Recommended ML-Side Fix

Re-export `foresight_fp32.tflite` into an Android-stable TFLite graph.

Important:

- Do not retrain unless needed.
- Keep the trained weights.
- Change the export/conversion path.
- Validate the exported model with Android LiteRT before shipping it.

Recommended goals for the new export:

1. Avoid ONNX-to-TFLite conversion if possible.
2. Export from TensorFlow/Keras directly to TFLite if possible.
3. Avoid unsupported or fragile converted LSTM patterns.
4. Prefer built-in TFLite-compatible ops.
5. Avoid Flex ops for MVP if possible.
6. Keep input shapes:
   - app sequence: `[1, 10]`
   - context sequence: `[1, 3, 10]` or clearly document if changed
7. Keep output size 87.
8. Test on Android with:
   - `com.google.ai.edge.litert:litert:2.1.5`
   - CPU only
   - XNNPACK disabled and enabled
   - 16 KB page-size emulator/device

Minimum validation before replacing the Android asset:

```text
Model loads on Android
Input tensors inspect correctly
interpreter.runForMultipleInputsOutputs completes
Output tensor returns finite floats
Top-5 predictions can be computed
No native SIGSEGV
```

## Android-Side Status

Android app is ready to accept a fixed model artifact.

The app already handles:

- asset loading
- vocab loading
- usage access
- usage events
- input construction
- tensor routing by shape
- inference process isolation
- error display
- diagnostics
- JSONL logging

Once a stable TFLite model is provided, replace:

```text
app/src/main/assets/foresight_fp32.tflite
```

Then rebuild:

```bash
./gradlew :app:assembleDebug
```

## Current Local APK

Current local debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This APK:

- is 16 KB aligned for packaged arm64 native libs
- uses LiteRT `2.1.5`
- does not auto-run inference on startup
- runs inference in `:inference`
- keeps main UI alive when native inference crashes

## Open Decision

The user asked not to push after the first push.

Therefore, the latest crash-containment and LiteRT changes remain local.

Before pushing, decide whether to push:

1. The crash-containment Android changes only, with the known model artifact still crashing.
2. Wait for a fixed `foresight_fp32.tflite`, then push the full working MVP.
3. Create a branch for debugging state, clearly marked as model-blocked.

Recommended:

Wait for a fixed model artifact, then push the final working MVP.
