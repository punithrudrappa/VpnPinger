# VPN Pinger

A minimal, always-on Android app that **calls a URL of your choice every time the
device's VPN connects, disconnects, or switches servers**, and notifies you about
each change.

Built to be tiny and boring: zero external runtime dependencies, no ads, no
tracking, no accounts. Configure it once and forget about it.

## Why this exists

With ProtonVPN's Android app, the DNS handed to the phone is always **plain
IPv4/IPv6 DNS servers** — the app has no DNS over TLS, DNS over QUIC or
DNS over HTTPS mode. That matters because with plain UDP/TCP DNS, the DNS
service (a **NextDNS** account, in my case) associates your account **by the
source IP the queries come from**:

- When you **switch VPN servers**, the tunnel's public egress IP changes. The
  new IP is not yet tied to your account, so requests stop being served "as
  your account" (your filter profile, custom rules, etc.) until the account is
  attached to the new IP.
- This app is that glue: on every VPN **connect / disconnect / server switch**
  it calls the configured URL — in this use, the NextDNS endpoint that
  re-attaches the account to the new egress IP — so DNS requests keep being
  served as your account again.

With encrypted DNS (DoT/DoQ/DoH) that glue would not be needed: the client
talks to the DNS endpoint directly, so the query's source IP never needs
re-association. Plain IPv4/IPv6 DNS plus this pinger is the workaround for
exactly that missing capability — which is why the app pings on *every*
transition instead of only on connect.

## Disclaimer

This project was **generated with AI assistance** and then reviewed, tested and
iterated on with a human in the loop. There is nothing fancy going on under the
hood: no dependency injection, no ViewModels, no networking libraries — it is
plain Android platform APIs (`ConnectivityManager`, `NotificationManager`,
`HttpURLConnection`) in a handful of small files, a few hundred lines of Kotlin
in total including a foreground service, notifications and permission handling.
The only genuinely fiddly part is correctly detecting VPN connect / disconnect /
server-switch events. Read it, audit it, and use it at your own risk.

## Features

- **Event-driven detection** — no polling in the hot path. The OS pushes network
  changes to the app via a `ConnectivityManager.NetworkCallback`.
- **Catches server switches** — detection compares the *identity* of the connected
  VPN network (its netId), not a boolean "VPN up/down". A server switch that
  briefly overlaps (new tunnel up while the old one is being torn down) still
  triggers, which a boolean comparison would silently miss.
- **Reliable** — a lightweight 3-second state reconciliation runs as a safety net,
  so a callback the OS fails to deliver still triggers within seconds. Rapid
  same-kind churn is coalesced into a single trigger.
- **Always on** — foreground service with a persistent notification that shows the
  live VPN state and trigger count; auto-starts on boot; restarts itself if killed.
- **Configurable delay + manual test** — after a VPN change the URL call waits a
  user-set delay (0–3600 s, default 3 s) so the new connection has time to fully
  establish before the request fires; the setup screen also has a **Ping now**
  button that fires an immediate test ping without touching the VPN.
- **Zero dependencies** — pure Android platform APIs (`HttpURLConnection`, no
  OkHttp, no AndroidX). The debug APK is under 1 MB.
- **Tested** — the detection state machine and HTTP ping have host-side unit tests.

## How detection works

A VPN appears to Android as a network whose capabilities carry the
`TRANSPORT_VPN` transport. This is true for every `VpnService`-based tunnel —
ProtonVPN's OpenVPN, WireGuard and IKEv2 modes included.

`MonitorService` registers a network callback for **VPN-transport networks only**
(deliberately not filtered on `NET_CAPABILITY_INTERNET`, which a tunnel can briefly
lack while connecting/disconnecting). Each event feeds a snapshot of the connected
VPN network identities into `VpnTransitionDetector`, which classifies:

| Transition                                                                                 | Trigger             |
| ------------------------------------------------------------------------------------------ | ------------------- |
| no VPN → VPN                                                                               | `VPN connected`     |
| VPN → no VPN                                                                               | `VPN disconnected`  |
| VPN → different VPN instance (server switch / reconnect, even overlapping)                 | `VPN changed`       |

Each trigger schedules one HTTP `GET` (8 s timeouts) to the configured URL after
the user-configurable delay (default 3 s — the wait gives the new connection time
to fully establish), then posts a notification with the outcome, e.g.
`VPN connected / GET https://… / HTTP 200 in 312 ms`. When a delay is set, an
instant notification first announces the scheduled call (`VPN connected / GET
https://… in 3s…`) so you see the trigger immediately; the follow-up notification
then replaces it with the HTTP result. A server switch with a real gap is naturally
reported as a disconnect + connect pair.

---

## Building the app

### What you need

| Tool      | Version / notes                                                                                    |
| --------- | ------------------------------------------------------------------------------------------------- |
| Device    | Android 8.0+ (API 26+) phone or emulator for testing ([see below](#testing-that-it-works)); building works without one. |
| JDK       | 17 or newer. Android Studio's bundled JBR counts, or install e.g. Temurin 17.                      |
| Android Studio | Any recent release. Only needed for the GUI build path; not required for command-line builds. |
| Android SDK | `platforms;android-34` and `build-tools;34.0.0` (Android Studio installs these via the SDK Manager). |

The project pins its own toolchain in the repo and downloads it automatically on
first build, so you normally never touch these versions:
Gradle **8.9** (wrapper), Android Gradle Plugin **8.5.2**, Kotlin **1.9.24**,
compileSdk/targetSdk **34**. Building needs an internet connection on the first run
(downloads Gradle + plugin jars).

### Get the code

```bash
git clone https://github.com/punithrudrappa/VpnPinger.git
cd VpnPinger
```

Or, if you are working from the project folder without git yet:

```bash
git init -b main
git add -A
git commit -m "Initial import"
```

> `local.properties` (machine-specific SDK path) is git-ignored and never part of
> the repo. If you copied the folder to a new machine and it already exists,
> delete it so Android Studio/Gradle regenerate it.

### Option A — build with Android Studio (recommended for most people)

1. **Install Android Studio** and launch it.
2. On first launch, let it install the Android SDK, then open the SDK Manager
   (**More Actions → SDK Manager**, or **Tools → SDK Manager**) and make sure
   these are installed (tick them if not, press Apply):
   - `Android SDK Platform 34`
   - `Android SDK Build-Tools 34.0.0`
3. **File → Open…**, select the `VpnPinger` folder (the one containing
   `settings.gradle.kts`) and click **Open**. Choose **Trust Project** if asked.
4. Wait for the **Gradle sync** to finish (bottom progress bar). First sync
   downloads Gradle 8.9, AGP and Kotlin — it can take several minutes.
   If sync fails, see [Troubleshooting builds](#troubleshooting-builds).
5. If you are using a real phone: plug it in, enable **Developer options → USB
   debugging**, and accept the RSA fingerprint prompt on the phone.
6. Select your device in the toolbar's device dropdown, then press the green
   **Run ▶** button. Android Studio builds, installs and launches the app.
7. Continue at [First run: configuring the app](#first-run-configuring-the-app).

*No phone handy, or just want the APK file?* Use **Build → Build APK(s)** — the
APK lands in `app/build/outputs/apk/debug/app-debug.apk` — then see
[Installing the APK manually](#installing-the-apk-manually).

### Option B — build from the command line

1. Install a **JDK 17+** and make sure `java -version` works.
2. Install the **Android SDK** and either set the environment variable
   `ANDROID_HOME` or create a `local.properties` file in the project root:

   ```properties
   # local.properties (never commit this file)
   sdk.dir=/home/you/Android/Sdk          # Linux
   sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk   # Windows (escape backslashes)
   ```

   With the SDK's own package manager, install what the build needs once:

   ```bash
   # if 'sdkmanager' is not on your PATH (e.g. the SDK was installed by Android
   # Studio), call it as "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
   sdkmanager "platforms;android-34" "build-tools;34.0.0"
   ```

3. Build the debug APK:

   ```bash
   # Linux / macOS
   ./gradlew :app:assembleDebug

   # Windows
   gradlew.bat :app:assembleDebug
   ```

   The first run downloads Gradle 8.9 and the plugin jars; expect a few minutes.
   Subsequent builds are incremental and fast (the app has no third-party deps).

4. Result:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Troubleshooting builds

| Symptom | Fix |
| --- | --- |
| `SDK location not found. Define location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local.properties file.` | Install the SDK and point at it via `ANDROID_HOME` or `local.properties` (above). |
| `Failed to find target with hash string 'android-34'` | Install `platforms;android-34` (SDK Manager / `sdkmanager`). |
| `Could not determine java version from '…'` or odd Java errors during sync | Make sure Gradle runs on JDK 17+ — in Android Studio: **File → Project Structure → SDK Location → Gradle JDK**. |
| First build very slow or "Downloading…" hangs | Normal — it downloads the Gradle distribution and plugins. Check your internet / proxy. |
| Gradle daemon out of memory | Edit `org.gradle.jvmargs` in `gradle.properties` (default `-Xmx2048m`). |
| `Execution failed for task ':app:lint…'` | Run `./gradlew lintDebug` and read the report at `app/build/reports/lint-results-debug.html`. |

## Installing the APK manually

- **With adb (fastest for testing):**

  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  # -r reinstalls over an existing version without losing data
  ```

- **Without a computer cable:** copy the APK to the phone (email, cloud drive,
  USB), tap it, and allow **Install unknown apps** for the source app when
  prompted. You may also need to toggle "Install unknown apps" / "Install from
  unknown sources" in Settings for the file manager you use.

The debug APK is signed with a debug key automatically during the build, so it
installs on your own device without any extra setup. (Publishing the APK for other
people would need a proper signing setup — not covered here.)

## Running the app

### First run: configuring the app

1. **Open VPN Pinger.** On Android 13+ it asks for the **Notifications**
   permission — allow it, otherwise you get no change alerts (the service still
   runs and still pings; you just won't see notifications).
2. Enter the **URL to ping** (include the scheme — `http://` and `https://` both
   work), e.g. `https://example.com/webhook`.
3. Optional: set the **delay** — how many seconds to wait after a VPN change
   before the URL is called (0 = immediately). Default is 3 s, which gives the
   new connection time to fully establish before the request fires.
4. Tap **Save settings**, then tap **Ignore battery optimizations** and choose
   **Allow**. The battery exemption is what lets the app run and react reliably
   "all the time"; if you skip it, triggers can be delayed or missed while the
   screen is off.
5. Test: tap **Ping now (test URL)** — an immediate manual ping runs and the
   result arrives as a notification, with no VPN toggle needed.
6. That's it — the monitor is already running. You can now leave the app; the
   persistent notification shows its live state:
   `VPN: not connected | triggers: 0 | https://example.com/webhook`.

> The screen also has **Start / restart monitor** (forces the service to
> restart, e.g. after changing settings) and **Stop monitor**.

### Testing that it works

1. Turn your VPN **on** → you should get a notification (`VPN connected …`) and
   the URL should be hit. The persistent notification flips to `VPN: connected`
   and the trigger counter increments.
2. Turn your VPN **off** → notification `VPN disconnected`, counter increments.
3. **Switch servers** in your VPN app → you should get a `VPN changed` trigger
   (or a quick `disconnected` + `connected` pair when the VPN drops fully between
   servers — that is expected).

### Diagnosing when triggers seem missing

- Check the **persistent notification**: it always shows the current VPN state and
  trigger count, so you can tell whether detection happened even if you missed the
  transient alert.
- Check the app has the **Notifications permission** (Settings → Apps → VPN Pinger
  → Notifications) — without it, triggers happen silently.
- Read the log while toggling the VPN:

  ```bash
  adb logcat -s VpnPinger
  ```

  You should see `baseline: vpnUp=0/1` when the service starts and a
  `TRIGGER VPN connected/disconnected/changed (reason=…, trigger#N)` line for
  every detected change. A TRIGGER line with no notification = permission problem.
  No TRIGGER line at all while the VPN visibly changed = the OS did not deliver a
  change event to the app (share that log output if it happens).
- Remember the built-in limit of using only public Android APIs: if a VPN
  implementation ever switched servers *without* dropping/recreating its tunnel
  network, Android itself exposes no event — only the VPN app would know.

---

## Running the checks

```bash
./gradlew testDebugUnitTest   # unit tests for the detector and the ping
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # debug APK
```

CI (`.github/workflows/build.yml`) runs all three on every push/PR and uploads the
debug APK as a build artifact (GitHub → Actions → workflow run → Artifacts).

## Publishing a release

A GitHub **Release** is a tag plus a description and attached files (binaries). Two
ways to get the APK there:

**Automatic (recommended)** — `.github/workflows/release.yml` builds the APK and
creates a Release whenever you push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The Release then appears under **Releases** with `VpnPinger-v1.0.0.apk` attached
(unit tests are run by the build workflow; the release workflow keeps the release
build fast by only assembling the APK).

**Manual** — from your machine after a local build:

```bash
# with the GitHub CLI:
gh release create v1.0.0 app/build/outputs/apk/debug/app-debug.apk \
  --title "v1.0.0" --notes "What changed in this version"

# or: create the release at github.com/punithrudrappa/VpnPinger/releases/new
# and drag the APK file into the "Attach binaries" box.
```

The release APK is the **debug** APK — auto-signed, installs on any device, fine
for personal / small-scale distribution. If you later want releases for the general
public under your own signature, add a release-signing config
(`signingConfigs` + keystore) and build `assembleRelease` instead.

## Project layout

```
app/src/main/java/com/vpnpinger/
  MainActivity.kt            minimal setup screen (URL entry, start/stop)
  MonitorService.kt          foreground service: VPN detection, ping, notifications
  VpnTransitionDetector.kt   pure detection state machine (unit-tested, no Android deps)
  Pinger.kt                  the HTTP GET
  BootReceiver.kt            auto-start after reboot
app/src/test/java/com/vpnpinger/
  VpnTransitionDetectorTest.kt   detector behaviour incl. coalescing
  PingerTest.kt                 ping against a real loopback HTTP server
app/src/main/AndroidManifest.xml
.github/workflows/build.yml     CI: tests + lint + APK artifact on push/PR
.github/workflows/release.yml   builds the APK and creates a GitHub Release on version tags
```
## Android reality checks

- **Target SDK is pinned to 34** on purpose: Android 15's 6 h/day cap on
  `dataSync` foreground services only applies to apps that *target SDK 35+*. The
  service declares `foregroundServiceType="dataSync"`. Do not bump `targetSdk`
  without reworking the service type (e.g. `specialUse`) or testing accordingly.
- A **foreground-service notification is mandatory** on modern Android; the
  service cannot run silently forever.
- Force-stopping the app from Settings kills it until you open it again — standard
  Android behaviour.
- Some OEMs / Android versions refuse to start a foreground service from
  `BOOT_COMPLETED` before the user unlocks the device; opening the app once after
  boot always starts it.
- The URL may be `http://` or `https://` (cleartext is enabled for flexibility).
- The ping is a fire-and-forget `GET` (no body is read). Adding POST, custom
  headers or a body is a small change in `Pinger.kt`.

## License

[MIT](LICENSE)
