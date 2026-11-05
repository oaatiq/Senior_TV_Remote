# Senior TV Remote

An Android phone app that works like a physical remote for Android TV / Google TV sets on the
home network. Several TVs can be paired, and one explicitly selected TV is controlled at a time.
The senior opens the app and presses big buttons; connecting, reconnecting and recovering happen
automatically. Tapping the TV name at the top chooses another TV. A family member (the
"administrator") does the setup behind a PIN.

> **Status: working on two real devices, full validation still open.**
> The protocol adapter implements the Android TV Remote Service v2 (the protocol used by
> Google's own TV remote app). It has paired with and controlled a MINIX U8K-ULTRA and a Xiaomi
> MiTV-MSSP2 from a Samsung S26 Ultra. Waking works on the MINIX; the MiTV turns off but cannot
> be woken from the phone with its current settings. The long-run scenarios and performance
> trials are still to do, and Type text has not yet been tried on a real TV. See
> [docs/COMPATIBILITY_REPORT.md](docs/COMPATIBILITY_REPORT.md).

## What is in the box

| Deliverable (spec section 17) | Where |
|---|---|
| Complete Android project | this repository (Kotlin, Jetpack Compose, single `app` module) |
| Build and installation instructions | below |
| Installable debug APK | Built with `./gradlew assembleDebug`; not stored in Git |
| Administrator setup guide | [docs/ADMIN_SETUP_GUIDE.md](docs/ADMIN_SETUP_GUIDE.md) |
| Compatibility report | [docs/COMPATIBILITY_REPORT.md](docs/COMPATIBILITY_REPORT.md) (results for two real devices, and what is still unverified) |
| Automated tests | `app/src/test` (156 tests, plus two manual tools) |
| Test results (automated, emulator, real device) | [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md) |
| Known limitations | [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) |
| Sanitized diagnostic-log examples | [docs/DIAGNOSTIC_LOG_EXAMPLES.md](docs/DIAGNOSTIC_LOG_EXAMPLES.md) |
| Connection state machine and recovery strategy | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |

## Install the APK on a phone

1. On the phone: **Settings > Security (or Apps) > Install unknown apps** and allow your file
   manager or browser, *or* enable USB debugging (Developer options) to use `adb`.
2. Build the APK (see below). Copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it, or from a computer:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Open **TV Remote** and follow [the administrator setup guide](docs/ADMIN_SETUP_GUIDE.md).

Requirements: Android 8.0 (API 26) or newer, on the same Wi-Fi network as the TV. The TV must be
an Android TV / Google TV with the "Android TV Remote Service" (present on most Google TV and
Android TV devices; it is what the Google TV phone app uses).

## Build from source

Requirements: JDK 17 or 21 (not 25 - the Gradle version used does not run on it), Android SDK
with platform 35. Internet access on first build to download Gradle dependencies.

```bash
./gradlew assembleDebug
```

On Windows use `gradlew.bat`. If `JAVA_HOME` points to an older JDK, set it for the build, e.g.
PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"`.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For a release build, which is faster than a debug build, use `./gradlew assembleRelease`. The APK is
written to `app/build/outputs/apk/release/app-release.apk`. It is signed with the local development
key, for personal installs only.

Run the automated tests:

```bash
./gradlew testDebugUnitTest
```

## Trying the app without a TV (simulated TV)

Two simulated TVs (test code only) can be served so the installed app can be driven through
pairing, switching and use, e.g. on the Android emulator:

```bash
./gradlew :app:testDebugUnitTest --tests "*SimulatedTvServer*" -PsimtvMinutes=20
```

In the app choose **Enter the TV's address instead** and type `10.0.2.2` (the emulator's name
for the computer) for the first TV. For the second, forward its ports and use `127.0.0.1`:

```bash
adb -s emulator-5554 reverse tcp:6466 tcp:7466
```

```bash
adb -s emulator-5554 reverse tcp:6467 tcp:7467
```

The code "shown on the TV" is written to `app/build/sim-tv.log`. Writing `1 offline`, `1 online`,
`1 drop` or `1 revoke` (or `2 ...`) into `app/build/sim-tv.cmd` simulates an unplugged TV, power
restored, a TV restart, or pairing removed on the TV.

The simulated TVs only prove the app is consistent with itself. They are not evidence that a
real TV behaves the same way.

## Project layout

```
app/src/main/java/app/seniorremote/
  domain/        Models (SavedTv, TvLibrary, name rules, RemoteKey, capabilities) and interfaces:
                 TvDiscovery, TvPairing, TvTransport, DeviceRepository, CredentialStore,
                 ConnectionController, RemoteCommandDispatcher
  connection/    ConnectionMachine (pure state machine), ConnectionManager (single owner of
                 connection state, lifecycle and recovery), rediscovery
  command/       CommandDispatcher: ordering, hold-to-repeat, expiry, no replay
  text/          TextEntryController: one draft bound to one TV, sent only on an explicit tap
  protocol/atvr2 Android TV Remote Service v2 adapter: TLS identity and pinning, pairing,
                 control session (replaceable - nothing else knows the protocol)
  discovery/     mDNS discovery (NsdManager)
  net/           Wi-Fi/Ethernet monitor, network-bound sockets
  data/          TV library storage, Keystore-encrypted credentials per TV, TvManager (select,
                 rename, remove), TV pictures, settings, admin PIN
  diagnostics/   Sanitized local event log, performance trial recorder
  ui/            Compose screens: remote, TV chooser, Type text, setup wizard, family settings,
                 Manage TVs
app/src/main/proto/   Protocol message definitions (protobuf-lite)
app/src/test/         Unit and integration tests, simulated TV
```
