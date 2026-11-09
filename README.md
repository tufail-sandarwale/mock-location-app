# Mock Location Tester

A simple Android app for developers to inject a **fake GPS location** using
Android's **official mock-location (test provider) API**. Pick a point on an
OpenStreetMap map (no API key needed), or type latitude/longitude, then start a
foreground service that continuously reports that location to the system.

> This is a legitimate testing/QA tool. It uses only the sanctioned,
> non-root `LocationManager` test-provider API. Every injected location is
> flagged by Android as "mock" — see **Honest limitations** below.

## Features

- Set a fake GPS location by **latitude/longitude** or by **tapping/dragging** on the map.
- Uses Android's built-in **Mock Location** feature (`addTestProvider` / `setTestProviderLocation`).
- Shows the selected location on an **OpenStreetMap** map (osmdroid, no API key).
- **Save / load / delete** named mock-location configurations.
- Simple **Start / Stop / change** controls; live-follows the pin while active.
- Runs as a **foreground service** so the mock keeps working while backgrounded.
- Detects whether the app is selected as the system "Mock location app" and
  guides you to Developer Options if not.

## Requirements

- Android Studio (Koala / 2024.1+ recommended)
- Android device or emulator, **API 23+**
- JDK 17 (bundled with recent Android Studio)

## Build & run

1. Open the `MockLocationApp` folder in Android Studio and let it sync, **or** from a terminal:
   ```
   ./gradlew assembleDebug        # macOS/Linux
   gradlew.bat assembleDebug      # Windows
   ```
   (The Gradle wrapper will download Gradle 8.9 and the Android Gradle Plugin 8.5.2 on first run.)
2. Install on a device/emulator:
   ```
   ./gradlew installDebug
   ```

## One-time device setup (required)

Android only honors mock locations from an app you explicitly authorize:

1. Enable **Developer options**: Settings → About phone → tap **Build number** 7 times.
2. Settings → System → **Developer options** → **Select mock location app** →
   choose **Mock Location Tester**.
3. Grant the app **Location** permission when prompted.

The in-app **Open Developer Options** button and the prompt shown when you tap
**Start** will take you to the right screen.

## How to use

1. Tap on the map (or drag the pin) to choose a spot — or type Lat/Lng and tap **Go**.
2. Tap **Start** to begin injecting that location.
3. Move the pin while active to update the mocked position live.
4. Tap **Stop** to end mocking.
5. Enter a name and tap **Save** to store a configuration; pick it later from the dropdown.

## Honest limitations (please read)

This app uses the **only** officially supported, non-root mechanism for mock
locations. Because of that:

- Android sets `Location.isFromMockProvider()` / `isMock()` to **true** on every
  injected fix. Apps that warn about or block mock locations (banking, payment,
  ride-hailing, anti-fraud, games) read these **official OS flags** on purpose.
- Being the selected "Mock location app" is visible to the OS in Developer options.

This tool **does not** attempt to hide mock-location usage from, or bypass the
anti-fraud/anti-cheat detection of, other applications. Doing so would require
defeating a security control, which is intentionally out of scope. If your goal
is QA of **your own** location-aware app, this tool is exactly what you want.

## Project layout

```
app/src/main/java/com/example/mocklocation/
  MainActivity.kt          UI, map, controls, mock-app detection
  MockLocationService.kt   Foreground service injecting via test provider
  LocationConfig.kt        Config model + SharedPreferences store
  Extensions.kt            Small UI helper
app/src/main/res/          Layout, strings, theme, launcher icon
```
