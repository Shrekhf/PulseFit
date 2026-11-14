# PulseFit AI for Android

An Android starter app based on the provided PulseFit AI HTML prototype. The UI is built with Kotlin and Jetpack Compose and adapts the prototype into a mobile layout with bottom navigation.

## Included

- Dashboard with calorie budget, meal diary, workout summary, and step progress
- Weekly workout split with exercise completion toggles
- Rest timer in the top bar
- Meal description form with a local heuristic nutrition estimate and diary logging
- Editable step count with quick-add buttons; step count is saved locally
- Health Connect read permission for today's step totals, with a manual fallback
- Dark theme with the prototype's emerald and cyan accents

## Build

Open this folder in Android Studio and let it install the Android SDK (API 35) if prompted. Then run the `app` configuration, or from this directory run:

```powershell
.
gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions also builds this APK on pushes and manual workflow runs. Download `pulsefit-debug-apk` from the workflow run's Artifacts section.

For Health Connect, install or update the Health Connect app on the phone, grant PulseFit the Steps read permission from the Steps screen, and allow the connection in Health Connect. The app aggregates today's records on device resume.

## Notes

Meal estimates are local keyword-based estimates, not AI predictions. Photo-based meal analysis requires an image model/service. Step counts can be entered manually if Health Connect is unavailable or permission is declined.
