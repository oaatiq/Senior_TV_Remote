# Test results

Dates: 2026-11-04 (single TV), 2026-11-05 (multiple TVs, first real devices). App version 1.0.0
(debug build).

Three levels of testing. The first two are complete; the third has only begun.

1. **Automated tests** (JVM): 156 tests, all passing, plus two manual tools.
2. **The real app on the Android emulator against the simulated TV**: the installed APK on a Pixel
   emulator (Android 17 image), driven through setup, use and failure scenarios. The simulated TV
   ran on the development computer.
3. **Real-device tests**: the owner paired two real devices and ran the guided button checks. The
   long-run scenarios are still open. See section 3.

## 1. Automated tests

Command: `./gradlew testDebugUnitTest`. Result: **156 passed, 0 failed**. The last full check of
stability was 3 consecutive rebuilds and runs at 126 tests; the standby and Type text tests were
added after. `SimulatedTvServer` and `RealTvTextProbe` are manual tools and are skipped in normal runs.

| Suite | Tests | Covers (spec 15.1) |
|---|---|---|
| `ConnectionMachineTest` | 38 | All state transitions; TV screen reports, TV off after standby and silence, claim not repeated after returning; switching TV (old session, attempt and retries dropped, late results discarded); retry timing, cap and jitter; cancellation; one attempt at a time; outdated results ignored; credential rejection; identity mismatch; unsupported device; background/grace/foreground; network loss/restore/change; TV announcement trigger |
| `ConnectionManagerTest` | 28 | Manager with fake transport and virtual time: TV that sleeps and leaves the network, then returns; TV that stays connected in standby; switching connects only to the new TV; late session from the previous TV closed; rapid switching settles on the last choice; unreachable TV keeps the selection and other TVs are not contacted; another TV can be chosen during failures; per-TV address updates; renaming does not disturb the session; renewed pairing reconnects; removal rules; launch to Ready; automatic recovery with new generation; retries never overlap; recovery once reachable; late session from a cancelled attempt closed; quick return reuses session; reconnect after grace; network loss/restore; changed address via rediscovery; similarly named TV never substituted; revoked pairing; send failure; forgetting the TV; announcement-triggered reconnect (same and new address) |
| `CommandDispatcherTest` | 13 | Press for one TV never delivered to another; queued commands do not cross TVs; switching during a hold stops repetition for good; ordering; press while not ready gives feedback and is never replayed; hold timing (first immediate, repeat at 400 ms, every 150 ms) and stop on release; non-repeatable keys; hold stops on connection loss and does not resume; `cancelAll` on backgrounding; failed send not retried; expired commands dropped; commands for a replaced session dropped; hold safety limit |
| `TextEntryControllerTest` | 14 | Blank text cannot be sent; editing sends nothing; exact text with no key press after it; rapid taps send once; switching TV closes and clears; another TV's session never receives a draft; connection loss keeps the draft with no later delivery; failed or stale sends are uncertain and not retried; line breaks; length limit; typed text absent from logs |
| `AtvProtocolTest` | 17 | Real TLS + protobuf client against the simulated TV: text as one edit with the TV's counters; character positions; long text; TV without the text feature; send on a closed session; faster detection after standby; a TV still talking in standby keeps its session; pairing, key codes and order, wrong code, malformed code, ping answers, revoked pairing -> AUTH_REJECTED, different TV -> IDENTITY_MISMATCH, nothing listening -> UNREACHABLE, TV drops connection, idle timeout |
| `EndToEndSimulatedTvTest` | 1 | Pairing + ConnectionManager + dispatcher + TLS transport together: control, recovery after drop, revoked pairing -> PairingRequired, pairing code absent from log |
| `ThreeSimulatedTvsTest` | 8 | Three independently paired simulated TVs over real TLS: each TV receives only what was pressed while selected; reselecting needs no pairing; switching during a hold; presses while connecting are dropped, not replayed; rapid switching; pairing removed on one TV does not affect others; unavailable TV stays selected and another can be chosen; renaming and removing keep other TVs working |
| `TvLibraryTest` | 8 | Selection and removal rules; capabilities per TV; storage round trip; upgrade from the single-TV format |
| `TvNamesTest` | 5 | Free-form names; custom over original; numbered fallback; duplicate rules; default subtitle |
| `TvManagerTest` | 10 | Commands cancelled before selection changes; selecting the current TV does nothing; renaming keeps pairing; removing one TV leaves others' credentials; last removal clears everything; picture replacement |
| `RediscoveryTest` | 2 | Matching candidates only for discovered TVs; all candidates for manually added TVs |
| `RedactionTest` | 3 | Codes, secrets, keys, PEM and base64 removed; IP/MAC truncated |
| `CapabilityTest` | 5 | Supported only when every related check confirmed; skips keep earlier results; unsupported hidden; Power needs verified standby |
| `StorageAndMetricsTest` | 4 | Credential encoding round trip and `toString` redaction; percentile/abandoned reporting; recovery clock restart |

Defects the tests found during development (fixed):

- protobuf wraps socket errors (timeouts, resets) in `InvalidProtocolBufferException`. A dropped
  connection during setup would have been reported as "Unsupported device", which stops all
  retries. The adapter now unwraps the underlying I/O error.
- Rediscovery threw instead of returning candidates when discovery ended early.
- If an attempt was cancelled at the moment it connected (for example the user had just switched
  TV), the result was thrown away and the socket to the previous TV stayed open. The session is
  now closed in that case.

## 2. Real app on the emulator, against the simulated TV

Environment: Android emulator, Pixel 10 Pro XL profile, Android 17 (API 37.1), software
rendering, **debug** build. Simulated TV on the host at `10.0.2.2:6467/6466`, pinging every 5 s.

| Scenario | Result |
|---|---|
| First launch | "Set up your TV remote" screen, single "Set up TV" button. No empty remote. |
| Discovery | Listed two real TVs on the network ("U8K-ULTRA", "Android TV") with address and ID suffix. Not selected. |
| Manual address + pairing | Code read from the simulated TV, typed in lower case, accepted; credentials stored via Android Keystore |
| Wrong code | "That code did not match... new code" message; a new code was issued; the correct code then paired |
| Guided checks | All 9 checks sent the right Android key codes in the right order (log: 20,19 / 22,21 / 23 / 4 / 3 / 24,25 / 164,164 / 26 / 26). Mute answered "No" -> Mute hidden. Standby "Yes" -> Power shown. |
| Remote screen | Status, Power, d-pad with distinct round OK, Back/Home, Vol-/Vol+, Family settings. Labels exposed to accessibility services ("Volume up", "Volume down", ...). |
| Hold Vol+ 2 s | 12 commands: first immediate, second after 405 ms, then every ~152 ms; stopped at release |
| Wi-Fi off (mobile data still on) | "Check home Wi-Fi"; controls dimmed but not moved; "Try again" appeared in its reserved place; pressing OK was not queued |
| Wi-Fi on again | Ready again 5.5 s after `svc wifi enable` (includes Wi-Fi reassociation) |
| Force-stop and reopen (x3) | Opened directly to the remote; reconnected with the saved pairing; no new pairing |
| 5 quick app switches | Session reused each time; no reconnects; foreground-to-Ready 0-2 ms |
| TV unplugged (simulated TV stopped) | "Reconnecting..." then "TV unavailable" after about 6 s; a press showed "still trying" feedback |
| Different TV at the same address (new simulated identity) | Refused at TLS (`IDENTITY_MISMATCH`), never reached a session; after 3 attempts: "Ask a family member to reconnect" |
| Forget this TV + set up again | Setup reopened; PIN step skipped (PIN kept); paired again |
| Power restored after outage | New session 0.86 s after the TV came back (simulated TV timestamps) |
| TV restart (connections dropped) | New session 0.58 s after the drop |
| Family settings | Wrong PIN rejected; correct PIN opened settings with capabilities, options, measurements and log |
| Small phone 360x780 dp | All controls fit without scrolling at 100 % and 130 % font size |
| 200 % font size | Text readable; page scrolls (allowed by spec 4.1). The Power label was clipped by a fixed-height button; **fixed** (min height instead) |
| Dark mode | High-contrast dark theme |

Timings measured by the app on the emulator (Family settings > Measured performance):

| Metric | Samples | Note |
|---|---|---|
| Launch to remote visible | 2.8-3.4 s | Debuggable build, software GPU, emulator. **Not** comparable to the 500 ms target. Measure on the real phone with a release build. |
| Cold launch to Ready | 1.8-2.2 s | Same caveat |
| Foreground reopen to Ready | 0-2 ms (5 samples) | Session reused within the grace period |
| Press to transmit | 1-18 ms (JVM end-to-end test) | Lower bound only: the TV gives no execution confirmation |

The emulator run also showed the address-recovery step attempting TLS connections to the two
real TVs on the network while the simulated TV was down (manually added TV, so no name to match).
Both were refused by the identity pin. As a result, rediscovery now only tries same-name/same-ID
candidates for TVs set up from discovery.

### Multiple TVs (2026-11-05)

The real app on the same emulator, with two simulated TVs on the development computer. Three-TV
behavior is covered by `ThreeSimulatedTvsTest`; the emulator could only reach two addresses.

| Scenario | Result |
|---|---|
| Upgrade over the single-TV version | Saved TV, name and settings carried over; opened on the remote |
| Remote screen | Full-width selector with TV icon, name and Change TV, above a compact status |
| Remove the only TV (with confirmation) | Back to first-use setup; PIN kept |
| Add a second TV with the same name | Automatic description "TV 2"; a blank description refused with an explanation |
| Chooser | "Which TV do you want to control?"; tick and the word Selected; status only on the selected TV |
| One tap on another TV | Returned to the remote; new session about 0.3 s later (simulated TV timestamps) |
| Keys after switching | OK reached only TV 1, Volume up only TV 2 |
| Back from the chooser | Selection unchanged |
| OK button position | Identical on both TVs and through Ready, Check home Wi-Fi and Ready again |
| Chooser with Wi-Fi off | Still opens |
| Force-stop and reopen | Returned to the last selected TV; no chooser; no pairing |
| Long name | Two lines on the remote with ellipsis, whole name in the chooser and settings |
| 360x780 dp phone | All controls fit without scrolling at 100 % and 130 % font size; dark theme checked |
| Test the buttons on a non-selected TV | Selected that TV first; keys reached only it |
| Re-pair | New code, session re-established, name kept |
| Remove the selected TV with one left | Other TV selected automatically and connected, pairing intact |

Not run on the emulator, to avoid the app contacting the real TVs on this network: "TV
unavailable" with multi-TV (a manually added TV that stops answering triggers address recovery).
This is covered by `ThreeSimulatedTvsTest`. Choosing a picture was not exercised at all.

## 3. Real-device tests

Pairing and the guided button checks passed on a MINIX U8K-ULTRA and a Xiaomi MiTV-MSSP2, from a
Samsung S26 Ultra. Wake works on the MINIX and not on the MiTV. Details and timings are in
[COMPATIBILITY_REPORT.md](COMPATIBILITY_REPORT.md).

Defects the real TVs found (fixed):

- After the MiTV slept, the remote stayed on Ready for 20 s while presses went nowhere. The limit is
  now 12 s after a standby report, and the status changes to TV is off at once.
- The TV then showed "Check that it is powered and connected to home Wi-Fi", which is misleading
  when the TV was just turned off on purpose.
- Power looked as if it could turn the TV back on. It now says Turn off on a TV that failed the
  wake check.

The new TV is off behavior has not yet been seen on a real TV. It is covered by tests, and the app
was installed on the phone and started, but the sequence needs someone to press the button.

**Type text has not been tried on a real TV.** The automated tests only show the app agrees with the
simulated TV. An attempt to test on the MINIX was stopped at the owner's request before anything was
sent to it, so the owner will run the guided check instead.

The table below is still to be completed (spec 15.2).

| Scenario | Expected | Result |
|---|---|---|
| Reopen after 5 minutes | Automatic readiness | |
| Reopen after overnight inactivity | Automatic recovery or truthful "TV unavailable" | |
| Force-stop and reopen | Saved setup retained | |
| Reboot phone | No routine pairing required | |
| Phone Wi-Fi off and on | Automatic recovery | |
| Restart router | Automatic recovery once communication is restored | |
| Change TV IP address | Rediscovery of the same TV | |
| Restart TV | Automatic recovery | |
| Unplug TV | "TV unavailable", no false Ready | |
| Restore TV power | Automatic recovery when the TV accepts control | |
| Hold Volume + then background app | Repetition stops immediately | |
| Lose connection while holding a direction | No stuck navigation or replay | |
| Tap rapidly during reconnection | No later burst of commands | |
| Revoke pairing on TV | "Ask a family member to reconnect" | |
| Another similarly named TV on the network | No silent device substitution | |
| 50 foreground reopenings | Ready within 2 s in >= 95 % | |
| 20 recovery trials | Ready within 5 s of TV/network reachable in >= 95 % | |
| Senior usability test (spec 15.3) | Tasks completed without explanation | |
| Two or more real TVs saved | Independent pairing; each opens with its own buttons | |
| Switch between real TVs | Connected within a few seconds; keys reach only the selected TV | |
| Switch while holding Volume + | Repetition stops; nothing reaches the other TV | |
| Selected real TV off, other TV on | Stays on the selected TV and shows TV unavailable | |
| Choose a TV picture | Picture shown on the remote and in the chooser | |
| Type text check, each TV and each app used | Text appears in the selected box; nothing is submitted | |
| Box that already holds text | Insert or replace behavior recorded | |
| Accents, non-Latin text, long phrase | Text arrives intact | |
| Tap Send to TV repeatedly | Text appears once | |
| Lose the connection with a draft on screen | Draft kept, nothing sent until tapped again | |
