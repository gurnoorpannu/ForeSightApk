# ForeSight — Predictive On-Device Memory Manager

ForeSight predicts which app you're about to open next, then acts on that prediction in real time to manage memory before you ever feel a slowdown. It runs the full loop on-device, on CPU, with no usage data ever leaving the phone:

- **Predict** — an on-device LSTM looks at your last 10 app switches plus light context (time of day, day of week, time since last switch) and predicts what's next.
- **Protect / free up memory** — apps about to be needed get shielded from being killed in the background; apps very unlikely to be opened next get frozen to free up RAM for what's coming.
- **Pre-warm** — the top-predicted app(s) get launched ahead of time in the background so they're already warm instead of cold-starting when you tap them. How many apps get pre-warmed scales with how confident the model is.
- **Personalize** — a nightly on-device job fine-tunes the model's per-app embeddings against your own logged outcomes, so ForeSight adapts to your habits over time without a full retrain and without any data leaving the device.

This repo is the Kotlin Android app. The model it runs is trained and exported in a sibling repo: [ForeSight-MLpipeline](https://github.com/gurnoorpannu/ForeSight-MLpipeline).

## How it works

1. **`UsageEventReader`** reads your last ~10 foreground app launches from `UsageStatsManager`, widening the lookback window (24h → 7d → 30d) until it has enough history, and dedupes consecutive repeats of the same package.
2. **`ForeSightBackgroundService`** runs the whole loop on a configurable interval (default 60s) as a foreground service, pausing automatically when the battery is low and not charging.
3. Each cycle, the recent-app sequence goes to **`InferenceService`** — a separate `:inference` process — which runs the TFLite model and returns the top predicted next apps with confidence scores. Inference runs in its own process so a native crash there never takes the UI down with it.
4. **`AppDecisionEngine`** turns predictions into per-app actions: `PROTECT` top predictions, `FREEZE` low-probability apps that aren't recent/protected/system-critical, `UNFREEZE` apps that get predicted again after being frozen, or `IGNORE`. Freeze/unfreeze is applied through `ActivityManager`, capped at a few apps per cycle so it never feels aggressive.
5. **`PreWarmEngine`** takes the same prediction and launches the top apps ahead of time in the background (`FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`, so nothing pops into the foreground), with a RAM safety valve that skips pre-warming if available memory is already low. The number of apps pre-warmed adapts to confidence — fewer apps when the model is sure, more when it's hedging.
6. **`ShadowEvaluationStore`** checks every prediction against what you actually opened next, tracking live top-1/top-3/top-5 accuracy and a safety metric — how often the policy would have frozen the app you were about to open — and logs the outcome as a reward signal.
7. A nightly **WorkManager** job (constraints: charging, idle, battery not low) takes the accumulated reward signal and fine-tunes only the model's embedding layer — the LSTM core stays frozen — so personalization never needs a full retrain and never sends anything off-device.

## Model

- File: `app/src/main/assets/foresight_aet.tflite` (~1.01 MB)
- Runtime: `com.google.ai.edge.litert:litert:2.1.5`, CPU, single-threaded, XNNPACK disabled
- Inputs: `app_sequences` `[1,10]` int64, `context_sequences` `[1,10,3]` float32 (hour-of-day, day-of-week, time-gap)
- Output: `[1,87]` float32 raw logits over an 87-app vocabulary (softmax applied on-device); the embedding layer is a distinct module from the LSTM core, which is what makes per-user fine-tuning possible without touching the rest of the network.
- This model was originally exported through ONNX/`onnx2tf`, which silently transposed the context axis and crashed natively on Android (`SIGSEGV` inside `runForMultipleInputsOutputs`). It's now exported straight from the PyTorch checkpoint via LiteRT Torch, which preserves the original `[B,10,3]` layout and fixed the crash. Full investigation log: [`docs/foresight_android_debug_summary.md`](docs/foresight_android_debug_summary.md).

## Permissions

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` | Read recent app-launch events (granted manually in Settings → Special App Access → Usage Access) |
| `QUERY_ALL_PACKAGES` | Resolve labels and launchability for all installed apps |
| `KILL_BACKGROUND_PROCESSES` | Apply real freeze decisions |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep the prediction loop alive |
| `POST_NOTIFICATIONS` | Show the persistent "ForeSight running" status notification |
| `RECEIVE_BOOT_COMPLETED` | Restart the background loop after a reboot |

## Decision policy (tunable in-app)

| Setting | Default |
|---|---|
| Freeze threshold | 0.02 |
| Protect threshold | 0.03 |
| Recent-app protection window | last 10 apps |
| Max apps frozen per cycle | 3 |
| Pre-warm tiers | confidence > 0.80 → top 3 · 0.50–0.80 → top 5 · < 0.50 → top 8 |
| Prediction interval | 60s |
| Pause when battery low | on, ≤15% and not charging |
| Fine-tune schedule | nightly, requires charging + idle + battery not low |
| Min shadow logs before fine-tune | 50 |

All of these are adjustable from the in-app **Policy Settings** card, alongside a manual protected-app allowlist and per-app vocabulary override tool.

## Safety guardrails

Regardless of prediction confidence, the decision engine always protects: the app itself, anything used in the last *N* launches, the launcher, keyboard/IME, System UI, dialer, Settings, clock/alarm, and accessibility/TalkBack apps. Only apps that are launchable, mapped to the model's vocabulary, not recently used, and below the freeze threshold are ever frozen — capped at a few per cycle.

## In-app dashboard

The Compose UI shows, live: recent app launches and their model mapping, top predictions with confidence, the current decision plan with human-readable reasons, pre-warm status, a policy settings editor, an app inventory/mapping override tool, action history, last fine-tune timestamp, and a shadow-evaluation card (rolling top-1/3/5 accuracy, inference latency, unknown-app rate, and any unsafe freeze hits) — with one-tap export of the underlying JSONL logs.

## Project structure

```
app/src/main/java/com/example/foresightapk/
├── MainActivity.kt               # Compose UI — dashboard, policy editor, inventory, logs
├── ForeSightBackgroundService.kt # Foreground service running the full predict/act loop
├── InferenceService.kt           # Isolated :inference process — loads model, runs predict()
├── InferenceContract.kt          # Messenger IPC contract between the two processes
├── ForeSightPredictor.kt         # TFLite loading, input building, inference, output parsing
├── AppDecisionEngine.kt          # Prediction -> PROTECT / FREEZE / UNFREEZE / IGNORE
├── PreWarmEngine.kt              # Confidence-adaptive background pre-warming
├── NightlyFineTuneWorker.kt      # WorkManager job — embedding-only on-device personalization
├── AppPolicyStore.kt             # Persists thresholds, allowlist, frozen set, action history
├── ShadowEvaluationStore.kt      # Live accuracy + safety evaluation, reward logging
├── PredictionLogger.kt           # JSONL logging of predictions/decisions/errors
├── AppInventoryReader.kt         # Installed-app inventory + launchability/system-app detection
├── AppMapper.kt / AppVocab.kt    # Package/label -> model vocabulary ID resolution
├── AppMappingStore.kt            # Manual per-package vocabulary override storage
├── AppPackageAliases.kt          # Known package -> vocab-label aliases for common apps
├── UsageEventReader.kt           # UsageStatsManager polling, lookback widening, dedup
└── PredictionModels.kt           # Shared data classes (PredictionResult, DecisionPlan, etc.)
```

## Building

```bash
git clone https://github.com/gurnoorpannu/ForeSightApk.git
cd ForeSightApk
./gradlew :app:assembleDebug
```

Install the debug APK, open ForeSight, grant Usage Access when prompted (Settings → Special App Access → Usage Access), then tap **Refresh** or **Start background service**.

- minSdk 24 · targetSdk / compileSdk 36 · Kotlin 2.2.10 · Jetpack Compose
- No DI framework, no Room — state lives in `SharedPreferences` + JSONL files

## Related

- ML pipeline (training, model export, personalization reference implementation): [ForeSight-MLpipeline](https://github.com/gurnoorpannu/ForeSight-MLpipeline)
