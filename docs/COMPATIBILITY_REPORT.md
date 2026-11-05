# Compatibility report

## Status

Two real devices have been paired and tested by the owner from a Samsung S26 Ultra (Android 17),
using the guided button checks. Results below come from the app's own log on the phone.

| | MINIX U8K-ULTRA | Xiaomi MiTV-MSSP2 |
|---|---|---|
| Advertised name | U8K-ULTRA | Android TV (renamed MiTV in the app) |
| Discovery | Works | Works |
| Pairing | Works, first attempt | Works, first attempt |
| Session (configure / set-active) | Works, about 0.6 s | Works, about 0.4 s |
| Arrows, OK, Back, Home, Volume, Mute | All confirmed | All confirmed |
| Power: standby | Confirmed | Confirmed |
| Power: wake | **Confirmed**, screen on 0.35 s after the press | **Does not work** (see below) |
| Session during standby | Stays connected (33 minutes observed) | TV leaves the network within about 2 s |
| Type text | Not tested | Not tested |

Not yet done on either device: the real-device scenarios in [TEST_RESULTS.md](TEST_RESULTS.md),
wake after overnight standby, removing a pairing on the TV, and restarting or updating the TV.

### Why the MiTV does not wake

From the phone's log, then checks made from a PC on the same network with the TV off:

| Time | Event |
|---|---|
| 21:47:19.152 | Power transmitted |
| 21:47:20.444 | TV reports screen off/standby. This is the last message it ever sent. |
| 21:47:22 to 21:47:37 | Five wake presses written to the socket; TV does not react |
| 21:47:40.446 | Session ends: no data for 20 s, exactly from the last message |
| 21:47:43 | New connection times out, and the TV is gone from discovery |

With the TV off: no reply to ping, and no answer on ports 6466, 6467, 8008, 8009 or 5555. Wake-on-LAN
magic packets to its network MAC address did not wake it, and neither did a Bluetooth
connection attempt from the PC, which may have been out of range of the TV.

So the TV enters a deep sleep with its network switched off, and no command can reach it. The
MINIX stays on the network in standby, which is why the same Power command wakes it.

The likely remedy is a setting on the TV, not a change to the app. A Home Assistant user with this
model reports that enabling **Wake on LAN** in Android **Developer options** stops the deep sleep,
and that **Wake on WLAN** was unstable and turned the TV on at random. That is one user's report
on a wired connection, and it has not been tried on this TV. Steps are in the
[administrator guide](ADMIN_SETUP_GUIDE.md#if-a-tv-will-not-wake). If it works, the TV behaves like
the MINIX and no app change is needed.

Source: [Xiaomi TV integration using Android TV](https://community.home-assistant.io/t/xiaomi-tv-integration-using-android-tv/446648).

## Protocol

**Android TV Remote Service v2**: the protocol the Google TV phone app uses, served by the
"Android TV Remote Service" on Android TV / Google TV devices.

- Discovery: mDNS `_androidtvremote2._tcp`, control port advertised (normally 6466).
- Pairing: TLS on port 6467 with a client certificate. Protobuf messages framed with a varint
  length prefix: request -> option -> configuration; the TV shows a 6-character hex code;
  the client sends SHA-256(client modulus, client exponent, TV modulus, TV exponent, code bytes 2-3).
- Control: TLS on port 6466 with the same client certificate. The TV sends `RemoteConfigure`; the
  client answers. The TV sends `RemoteSetActive`; the client answers. Pings are answered. Keys are
  sent as `RemoteKeyInject` with Android key codes.

The message schema in `app/src/main/proto` follows the open-source `androidtvremote2` client used
by Home Assistant. It is **not** an official Google specification, so each item below has to be
confirmed per device. The last column shows the state on the two devices tested so far.

## Assumptions to confirm on the target TV

| # | Assumption | Where in code | How to confirm | MINIX / MiTV |
|---|---|---|---|---|
| A1 | The TV advertises `_androidtvremote2._tcp` | `NsdTvDiscovery` | TV appears in the setup list | Yes / Yes |
| A2 | Pairing port is 6467 and accepts our self-signed client certificate | `AtvConfig.pairingPort`, `AtvPairing` | Code appears on the TV | Yes / Yes |
| A3 | Pairing message order and fields (service name `androidtvremote2`, hex encoding, 6 symbols, input role) | `AtvPairing.handshake` | Code appears on the TV | Yes / Yes |
| A4 | Secret = SHA-256 over the RSA public-key parts and the last 4 code characters; first byte equals the first 2 code characters | `PairingCode.secret` | Correct code accepted; log shows "Code checksum matches" | Yes / Yes |
| A5 | Control session is usable after the configure / set-active exchange (feature mask 622) | `AtvSession.handle` | Status reaches Ready; buttons work | Yes / Yes |
| A6 | Key codes: DPAD 19-23, BACK 4, HOME 3, VOLUME_UP/DOWN 24/25, VOLUME_MUTE 164, POWER 26 with direction SHORT | `AtvKeyCodes` | Guided button check | Yes / Yes |
| A7 | The TV pings at least every 20 s while idle | `AtvConfig.idleTimeoutMs` | Leave the remote open 5 minutes and check the log for unexpected "no data from TV" endings | Yes (33 min, screen off) / Not yet |
| A8 | An unknown or revoked client is disconnected right after TLS (reported as AUTH_REJECTED) | `AtvSession.finish` | Remove the pairing on the TV; the remote shows "Family help needed" | Not yet / Not yet |
| A9 | The TV's certificate key is stable across restarts and updates (identity pin) | `PinningTrustManager` | Restart and update the TV; the remote reconnects without re-pairing | Not yet / Not yet |
| A10 | Whether the service stays reachable in standby (decides whether wake works) | guided "Power: wake up" check | Short and overnight standby | Stays reachable (short only) / Not reachable |
| A12 | Text in `remote_ime_batch_edit` reaches the selected field | `AtvSession.sendText` | Guided Type text check, in each app used | Not yet / Not yet |
| A13 | Whether text is inserted or replaces the field's contents | `AtvSession.sendText` | Send to a box that already has text | Not yet / Not yet |
| A14 | Accented, non-Latin and long text arrive intact | `AtvSession.sendText` | Send samples and read the field | Not yet / Not yet |
| A11 | Volume keys change the volume with the TV's audio setup (TV speakers, sound bar, HDMI-CEC) | guided "Volume" check | Volume check | Yes / Yes |

## Minimum information needed about the target TV

1. Make and model, and Android TV / Google TV OS version.
2. Whether the TV appears in the setup list (A1), or the IP address used.
3. Audio setup: TV speakers, sound bar (HDMI-ARC/CEC) or AV receiver.
4. The TV's standby / network-standby setting ("Wake on network", "Quick start", eco modes).

## Validation procedure

1. Install the APK on the agreed phone and set up with the target TV
   ([admin guide](ADMIN_SETUP_GUIDE.md)). Record A1-A4.
2. Complete every guided button check, answering truthfully. Record A5, A6, A11.
3. Run the real-device scenarios in [TEST_RESULTS.md](TEST_RESULTS.md#real-device-tests) and fill in the results.
4. Run the standby/wake check after short and overnight standby (A10).
5. Do at least 50 foreground reopenings and 20 recovery trials. Copy Family settings >
   Measured performance and the diagnostic log into the table below.

## Results (fill in for each target TV)

Capabilities are recorded per TV, so copy this table for every TV in the home.

| Field | Value |
|---|---|
| TV make / model | |
| OS / build | |
| Phone model / Android version | |
| App build | 1.0.0 debug |
| Discovery (A1) | |
| Pairing (A2-A4) | |
| Session (A5, A7) | |
| Navigation / OK / Back / Home | |
| Volume / Mute (A11) | |
| Power: standby | |
| Power: wake after 1 min / overnight (A10) | |
| Revoked pairing behavior (A8) | |
| Identity stable after TV restart / update (A9) | |
| Measured performance | |

## Measured on the Samsung S26 Ultra (debug build, small sample)

| Metric | Samples | Target |
|---|---|---|
| Launch to remote visible | 333, 348, 362, 403, 986 ms | 500 ms |
| Cold launch to Ready | 302, 549 ms | 3 s |
| Foreground reopen to Ready | 1, 1152 ms | 2 s |
| Press to transmitted | 1 to 7 ms over 57 presses | Lower bound of the 200 ms target |

These are early samples, not the 50 and 20 trials the spec asks for. Two samples are excluded:
199 s (the first launch included setup, a measuring fault now fixed) and 78 s (a foreground trial
that ran across a period in the background).
| Notes | |
