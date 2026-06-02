# ForeSight Android MVP

ForeSight is a next-app prediction Android MVP. It reads recent foreground app launches from Android Usage Access, builds the model inputs expected by the shipped TensorFlow Lite model, runs CPU inference locally, and displays the top predicted next apps with confidence scores and latency.

The ML pipeline lives at:

https://github.com/gurnoorpannu/ForeSight-MLpipeline

## Assets

The Android app expects these files in `app/src/main/assets/`:

- `foresight_fp32.tflite`
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

## Model Inputs

The deployed model is `foresight_fp32.tflite` and is run with the TensorFlow Lite `Interpreter` on CPU.

Inputs:

- `app_sequences`: shape `[1, 10]`, dtype `int64`
- `context_sequences`: shape `[1, 3, 10]`, dtype `float32`

Context feature channels:

- `hour_of_day / 23.0`
- `day_of_week / 6.0`
- `time_gap_seconds / 3600.0`, clipped to `[0, 1]`

Important transpose warning: the PyTorch training layout was `[1, 10, 3]`, but the exported TFLite model expects context as `[1, 3, 10]`. The Android predictor fills context by channel first, then time step.

If model input ordering changes, the Android code inspects tensor shapes and routes:

- `[1, 10]` to app sequence input
- `[1, 3, 10]` to context sequence input

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

- `app_vocab.json` is label-based, while Usage Events provide package names. The app resolves package labels and includes common package aliases, but unknown apps fall back to ID `0` because the provided vocabulary has no explicit unknown token.
- The model is used exactly as exported: no retraining, cloud calls, GPU delegate, int8 quantization, or fp16 conversion.
- Predictions are only as useful as the recent Usage Events available after Usage Access is enabled.
- The UI is intentionally simple and functional for MVP validation.
