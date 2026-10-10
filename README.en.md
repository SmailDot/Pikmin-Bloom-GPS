# Pikmin Bloom GPS

[繁體中文](README.md) ｜ **English** ｜ [日本語](README.ja.md)

A Big Flower patrol helper for **Pikmin Bloom**. No root needed. The app speaks English, Traditional Chinese and
Japanese and follows your phone's language (on Android 13+ you can also pick a language for this app alone under
Settings → Apps → Language).

## Features
- **Patrol**: mark Big Flowers on the map; the app walks the avatar from one to the next with a simulated walk until you tap "Go home".
- **Steps while patrolling**: the simulated distance is written to Health Connect as steps, so the game grows your seedlings.
- **Go home**: walk or teleport back to your real location, or go to a saved home (Home 1, Home 2, …) and stay there.
- **Go now / Go and stay there**: tap a Big Flower to head straight there. "Go and stay there" pauses on arrival, handy for leaving it running overnight and collecting in the morning. If the trip is longer than a set distance (15 km by default), the app first asks whether to walk (writes steps) or take a vehicle (no steps).
- **Vehicles**: Other, Car and Plane, with speeds you can set. Vehicles write no steps and switch back to walking at the next Big Flower.
- **Floating bar and joystick**: control the patrol on top of the game (pause, go home, change vehicle). The joystick can be dragged anywhere and comes in three sizes.
- **Auto expedition (experimental, off by default)**: one tap on the floating bar sends Pikmin on every fruit and seedling-pot expedition in the Expedition list, using the game's own auto team picker. Red gifts and mushrooms are never touched. Needs Android 11 or newer and the accessibility service turned on. While a run goes, it reads the game's screen (screenshots are never saved) and taps in it.
- **Auto feed (experimental, off by default)**: on the game's feed screen, start it from the floating bar's flag button (choose Feed nectar and the number of rounds). Each round feeds the nectar shown in the bubble to your current squad, then harvests the petals, sweeping again while flowers are left. Stop it from the notification. Same detection risk as auto expedition: the game can see that the accessibility service is on, so use it at your own risk.
- **Real location switch**: hand the real GPS back to the phone for a while (for Google Maps, say), then switch back to the virtual location.
- **Find nearby candidate Big Flowers**: pulls nearby landmarks from OpenStreetMap as candidates.
- **Resume from checkpoint**: if the system closes the app, carry on from the same spot and state.

## Requirements
- Android 9 (API 28) or later; steps need Android 14 or later (Health Connect is built in).
- Developer options → **Select mock location app** → Pikmin Bloom GPS.

## Setup (the in-app "Setup" screen checks each step)
1. Location and notification permissions, and "Display over other apps" (for the floating bar).
2. Developer options → Select mock location app → this app.
3. Health Connect read/write permission for steps and distance.
4. **⚠ In Health Connect, open steps → "Data sources and priority" and add "Pikmin Bloom GPS".**
   This is the step people miss. Without it the steps are written but not counted in the daily total, so the game never sees them.
5. Exclude this app from battery optimization (on HyperOS, also allow Autostart).
6. In Pikmin Bloom's settings, set the step source to **Health Connect**, and in Health Connect allow Pikmin Bloom
   to read steps, also in the background. Set the game's location permission to "Allow all the time".
7. Turning off "Google Location Accuracy" (Settings → Location → Location services) is recommended: it lowers the chance of the location jumping back to your real one.

> If the game counts steps with the phone's own step sensor, it reads the hardware step counter, which no app can write to. It has to use Health Connect.

## How to use
1. **Long-press** the map to add a Big Flower, or use "Find nearby candidate Big Flowers".
2. Tap "Start patrol", then start planting flowers in the game.
3. When the arrival alert pops up, tap the Big Flower in the game and swipe down to collect Nectar.
4. To finish, tap "Go home". Once the avatar is back at your real location the app stops mocking.

## Settings worth knowing
| Setting | Suggestion |
|---|---|
| Walking speed | 18 km/h by default, the steadiest in testing. The maximum is 20; at 19–20 a phone that stutters from overheating may jump ahead in one go, and the game may think you are in a vehicle. |
| Go and stay there: ask how to travel when farther than (km) | 15 by default. 0 never asks. |
| Circle the Big Flower after arriving | Off by default. Turn it on only to push one Big Flower to bloom (300 flowers). |
| Stride | 70 cm. Steps = distance ÷ stride. |
| Daily step cap | Empty means no cap. Enter 50000 to follow the game's seedling growth cap. |
| Auto expedition (experimental) | Off by default. It reads the game's screen and taps in it through the accessibility service, only while a run goes; any app can see that the service is on, and the game may notice too. Use at your own risk. |

## Build
Needs JDK 21 and the Android SDK (platform 37).
```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```
Release signing reads `../keystore/keystore.properties` **next to** the repository (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); never put it inside the repo. Without that file the release APK is unsigned and will not install.

Install with adb and make it the mock location app:
```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set app.pikminbloom.gps android:mock_location allow
```

## Where the Big Flower locations come from
Niantic does not publish Big Flower coordinates. Big Flowers grow on Wayspots, so the app asks the OpenStreetMap
Overpass API for nearby landmarks (memorials, public art, temples and churches, playgrounds and so on) as
**candidates**. Roughly 30–60% of them really have a Big Flower; keep the ones that do.
OpenStreetMap data is licensed under the ODbL.

## Bug reports and suggestions
In the app: menu → **Report a bug / suggest** (also at the bottom of Settings). Pick email or GitHub; the report opens
already filled in with the app and Android versions, the phone model and the last error or crash. It never includes
your location, and you can read and edit everything before sending. You can also write directly:
[GitHub Issues](https://github.com/SmailDot/Pikmin-Bloom-GPS/issues) or smaildot@aidot.me.

## Disclaimer
This app has nothing to do with Niantic, Scopely or Nintendo. Changing your location and steps breaks the Pikmin Bloom
terms of service (three strikes: a 7-day warning → a 30-day suspension → a permanent ban). You use it at your own risk.
Keep to sensible speeds and avoid teleporting.
