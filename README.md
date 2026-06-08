# ForeSight Android MVP

ForeSight is a next-app prediction Android MVP. It reads recent foreground app launches from Android Usage Access, builds the model inputs expected by the shipped TensorFlow Lite model, runs CPU inference locally, and displays the top predicted next apps with confidence scores and latency.

The ML pipeline lives at:

https://github.com/gurnoorpannu/ForeSight-MLpipeline

## Assets

The Android app expects these files in `app/src/main/assets/`:

- `foresight_aet.tflite`
- `app_vocab.json`

They are already included in this project from the ML pipeline export. If you replace them later, keep the same filenames.

## Usage Access

ForeSight needs Android Usage Access to read recent app launch events:

1. Run the app.
2. Tap **Usage Settings**.
3. Find **ForeSightApk** in the Usage Access settings screen.
4. Enable usage access.
5. Return to ForeSight and tap **Refresh**.

The required manifest permission is:

```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
```

The debug inventory screen can also show all installed packages, including non-launchable system packages. That mode uses:

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

## Model Inputs

The deployed model is `foresight_aet.tflite` and is run with the TensorFlow Lite / LiteRT `Interpreter` on CPU.

Inputs:

- `app_sequences`: shape `[1, 10]`, dtype `int64`
- `context_sequences`: shape `[1, 10, 3]`, dtype `float32`

Context feature channels:

- `hour_of_day / 23.0`
- `day_of_week / 6.0`
- `time_gap_seconds / 3600.0`, clipped to `[0, 1]`

Important export note: the old ONNX/onnx2tf model expected context as `[1, 3, 10]` and crashed native Android runtime. The current LiteRT Torch export preserves PyTorch axis order and expects context as `[1, 10, 3]`. The Android predictor fills context by time step first, then feature index.

If model input ordering changes, the Android code inspects tensor shapes and routes:

- `[1, 10]` to app sequence input
- `[1, 10, 3]` to context sequence input

## App Inventory And Mapping

ForeSight now reads installed apps through `PackageManager` and shows a local inventory in the app. The inventory has two modes:

- **Launchable**: apps visible through the Android launcher intent.
- **All Installed**: all packages visible to the app, including non-launchable and system packages.

For each launchable app, the app displays:

- package name
- app label
- whether Android reports it as a system app or user app
- whether it is launchable
- mapped model app ID, when known
- mapping source and confidence

Package-to-vocab mapping is resolved in this order:

1. manual override stored on device
2. known package alias
3. exact app label match
4. normalized app label match
5. exact package-name match
6. fallback to unknown/PAD ID `0` for model inference

Inventory rows that do not map to a real vocab label are shown as unmapped. Prediction still falls back to ID `0` so inference can run even when recent apps are not in `app_vocab.json`.

Manual overrides are saved locally with Android `SharedPreferences` under:

```text
app_mapping_overrides
```

Use the **Manual Mapping** section to bind a package name to an exact vocab label from `app_vocab.json`. This does not require root.

## Running

Build from the project root:

```bash
./gradlew :app:assembleDebug
```

Then run the debug APK from Android Studio or install it with adb:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Local Logs

Each successful prediction is appended to internal app storage as:

```text
prediction_events.jsonl
```

Caught errors are appended to:

```text
foresight_errors.jsonl
```

The app also writes Logcat messages with this tag:

```text
ForeSight
```

In Android Studio, filter Logcat by `ForeSight`. From adb:

```bash
adb logcat -s ForeSight
```

Each JSON line includes:

- timestamp
- input app sequence IDs
- input app labels/mapping
- top-k predictions
- inference latency in milliseconds
- diagnostics, including tensor routing and unknown/PAD counts

Error JSON lines include:

- timestamp
- failed stage
- exception type and message
- stack trace
- any diagnostics collected before the failure

## Current Limitations

- `app_vocab.json` is label-based, while Usage Events provide package names. The app resolves package labels, common package aliases, normalized labels, and manual overrides, but unknown apps still fall back to ID `0` during inference because the provided vocabulary has no explicit unknown token.
- Phase 2 is still no-root. It can inspect launchable installed apps and improve mapping, but it cannot freeze or unfreeze apps.
- The model is used exactly as exported: no retraining, cloud calls, GPU delegate, int8 quantization, or fp16 conversion.
- Predictions are only as useful as the recent Usage Events available after Usage Access is enabled.
- The UI is intentionally simple and functional for MVP validation.
