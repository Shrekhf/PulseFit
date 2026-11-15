# PulseFit AI for Android

Native Kotlin / Jetpack Compose fitness planner based on the supplied PulseFit AI prototype.

## App features

- Material 3 dark UI, four-tab navigation, and Snackbar feedback
- Reactive dashboard calorie equation: target − food + calibrated steps + completed-workout burn
- Live protein, carbohydrate, and fat progress with an editable TDEE / goal profile
- Room persistence for daily logs, meals, ingredient rows, five workout splits, planned exercises, and logged sets
- Room migration from the earlier workout log schema to set-number, enum set-type, exercise-linked logging, and workout-day/exercise entities
- DataStore persistence for calorie/macro/step goals, calibration mode, and optional Gemini API key
- Default 5-day Bro Split plus Push/Pull/Legs + Upper, Upper/Lower, Arnold, and Athletic templates
- Expandable workout cards and set-by-set logging with weight, reps, RPE, Warmup/Working/Drop/Failure set types, haptic completion, Epley estimated 1RM, and live session volume
- Equipment-filtered biomechanical exercise swap sheet with joint-friendly notes
- Monotonic, persisted rest timer based on `SystemClock.elapsedRealtime()` with mute, notification beep, and vibration alerts
- Photo Picker, CameraX capture, ingredient inclusion/multipliers, restaurant customization chips, and live macro totals
- Optional Gemini multimodal meal estimate when a user supplies a Gemini API key; local text estimates remain available offline
- Health Connect step reading, optional Android hardware step counter, manual entry, and 500 kcal / 15k calibration with wrist-estimate comparison

## Build

Install Android SDK Platform 36 and Build Tools 36.0.0, then run from this directory:

```powershell
.\gradlew.bat assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the same APK on pushes and manual workflow runs; download `pulsefit-debug-apk` from the run's Artifacts section.

## AI and privacy

The app runs without an account or network connection for logging, workouts, manual steps, and local estimates. Gemini analysis is opt-in: add a key in Profile, then tap Analyze. That action sends the entered description and selected image to Google's Gemini API. The key is stored locally in DataStore. Meal photos are not uploaded until analysis is requested. Nutrition estimates are approximate and should be reviewed.

Health Connect step access requires the user to grant Steps read permission. If it is unavailable, the phone's hardware step counter or manual step adjustment can be used. The phone sensor is active while the app is in the foreground.
