# Architecture, connection state machine and recovery strategy

## Components (spec section 13)

| Component | Class | Responsibility |
|---|---|---|
| Remote UI | `ui/remote/RemoteScreen`, `RemoteButton` | Renders controls and status. Reports finger down/up. No connection logic. |
| Remote ViewModel | `ui/remote/RemoteViewModel` | Exposes state, forwards presses to the dispatcher. |
| Connection manager | `connection/ConnectionManager` + `ConnectionMachine` | **Only** owner of connection state, lifecycle and recovery. |
| Discovery service | `discovery/NsdTvDiscovery` | mDNS browse for `_androidtvremote2._tcp`. |
| Pairing service | `protocol/atvr2/AtvPairing` | Protocol pairing (TLS + code). |
| Device repository | `data/PrefsDeviceRepository` | All saved TVs and the selection (no secrets). |
| TV manager | `data/TvManager` | Selecting, renaming and removing TVs. |
| Credential store | `data/KeystoreCredentialStore` | Pairing secrets, AES-GCM encrypted with an Android Keystore key. |
| Command dispatcher | `command/CommandDispatcher` | Ordering, repetition, expiry, cancellation of key commands. |
| Protocol adapter | `protocol/atvr2/AtvTransport`, `AtvSession` | TLS control session, handshake, pings, key injection. |
| Diagnostics | `diagnostics/DiagnosticsLog`, `PerformanceTracker` | Sanitized local event log; timing trials. |

The UI never opens sockets, runs discovery or retry loops. The protocol is isolated behind
`TvTransport` / `TvPairing`, so another protocol can be added without touching the UI or the state
machine.

## Connection state machine

`ConnectionMachine.reduce(model, event, now) -> (model, effects)` is a pure function. The
`ConnectionManager` feeds it every signal through one channel, processed by one coroutine, and
executes the returned effects. So there is exactly one writer of connection state, and every
transition is unit-tested without threads or sockets (`ConnectionMachineTest`, 28 tests).

### States

| State | Meaning | Senior sees |
|---|---|---|
| `NotConfigured` | No TV selected | Setup screen, or the chooser if TVs remain |
| `Suspended` | App not visible; no session maintained | (never visible) |
| `Connecting` | Establishing a session after launch / return | Connecting... |
| `Ready(generation, tvId, tvAsleep)` | Fully established session with that TV. `tvAsleep`: the TV reported its screen off | Ready, or TV is off |
| `Recovering` | A Ready session was lost; re-establishing | Reconnecting... |
| `NetworkUnavailable` | No Wi-Fi/Ethernet on the phone | Check home Wi-Fi |
| `TvUnreachable` | Attempts failing for > 6 s; retries continue | TV unavailable |
| `TvOff` | TV reported standby, then left the network; retries continue | TV is off |
| `PairingRequired` | Credentials rejected or identity not confirmed | Family help needed |
| `Unsupported(reason)` | Device speaks something else | Cannot control this TV |

### Events (inputs)

`TargetChanged(tvId)`, `Foreground`, `Background`, `GraceExpired`,
`NetworkChanged(usable, replaced)`, `RecoveryRequested`, `AttemptSucceeded(id)`,
`AttemptFailed(id, failure)`, `SessionLost(generation)`, `SessionStale(generation)`,
`RetryTimer(token)`, `TvAnnounced`, `ScreenChanged(generation, on)`.

### Main transitions

```
NotConfigured --TV selected, foreground--> Connecting
any           --TargetChanged(other TV)--> Connecting            (old session and attempt dropped)
Suspended     --Foreground-----------> Connecting                (one attempt, immediately)
Connecting    --AttemptSucceeded-----> Ready(g+1)
Connecting    --AttemptFailed(unreachable)--> Connecting (retry with backoff)
                                       ... after 6 s of failures --> TvUnreachable (retries continue)
Ready(g)      --SessionLost(g)-------> Recovering --success--> Ready(g+1)
Ready         --Background-----------> Ready, grace timer 30 s --expired--> Suspended (session closed)
Ready(asleep) --SessionLost(g)-------> TvOff --success--> Ready(g+1)
Ready         --Foreground (in grace)-> Ready if the session is fresh, else Recovering
any           --Network lost---------> NetworkUnavailable (session closed)
NetworkUnavailable --Network back----> Recovering (immediate attempt)
any attempting --3 rejections--------> PairingRequired (no automatic retries)
any attempting --Unsupported---------> Unsupported
any            --no TV selected------> NotConfigured
```

### Rules enforced

- **One attempt, one session.** An attempt id is assigned when an attempt starts; at most one is
  in flight. Recovery requests while an attempt runs are ignored.
- **No stale results.** A success or failure whose attempt id is not the current one is discarded.
  A late session from a cancelled attempt is closed, never used (`DiscardSession`).
- **Generations.** Each Ready session gets a new generation number. Session-lost reports and
  queued commands carry their generation, and anything for an old generation is ignored.
- **Ready means established.** The transport returns a session only after the TLS handshake,
  the pinned-identity check and the protocol's configure/set-active exchange have completed. An
  open socket is never treated as Ready.
- **Liveness.** The TV pings connected remotes; the session is considered lost if nothing arrives
  for 20 s (configurable), on any I/O error, or on a failed send. Nothing polls the TV. Once the TV
  reports standby the limit drops to 12 s, because some TVs leave the network seconds later and
  writes to a dead socket appear to succeed.
- **Standby is only claimed on evidence.** `TvOff` needs the TV's own report followed by silence.
  After leaving the app and returning, or after a restart, an unanswering TV is `TvUnreachable`,
  because the app no longer knows why.

## Recovery strategy

### Triggers (spec 7.3)

| Trigger | How it is detected |
|---|---|
| App launch / return to foreground | Activity `onStart` -> `Foreground` |
| Network restored or changed | `ConnectivityManager` callback for Wi-Fi/Ethernet -> `NetworkChanged` |
| Transport disconnection | Session reader ends (EOF, reset, TLS error, idle timeout) -> `SessionLost` |
| Failed command transmission | Dispatcher reports send failure -> `SessionLost` |
| TV restart | Connection drop (`SessionLost`) and the TV's mDNS announcement (`TvAnnounced`) |
| New address for the saved TV | Rediscovery during an attempt; mDNS announcement at another address |

### Retry policy (spec 7.4)

- First attempt immediately.
- After the n-th consecutive failure: 250 ms, 500 ms, 1 s, 2 s, 4 s, 7 s, then 10 s (maximum).
- +/-20 % jitter on every delay so retries do not synchronize.
- Backoff resets after a session that stayed up for 10 s. A session that drops sooner counts as
  a failure, so a flapping TV cannot cause a tight reconnect loop.
- While the TV is unavailable and the app is visible, the app passively listens for the TV's mDNS
  announcement; when it appears, it retries at once instead of waiting for the next backoff step.
- Retries only run while the app is visible. All values are in `ConnectionConfig` and are covered
  by tests.

### Background behavior (spec 7.5)

- On `onStop` (home button, app switch, screen lock): hold-to-repeat stops immediately, pending
  commands are dropped, attempts and retries are cancelled.
- A Ready session is kept for a 30 s grace period so quick app switches return instantly. On
  return, it is reused only if the TV was heard from in the last 15 s; otherwise it is replaced.
- After the grace period the session is closed. No background service is used.

### Changing addresses and identity (spec section 8)

- The saved TV record holds: display name, mDNS service name and device ID (hints), last known
  address and port, protocol, a credential reference, verified capabilities, and last connection time.
- Pairing **pins the TV's public key** (SHA-256 of its certificate key). Every connection checks
  it during the TLS handshake. A device with a different key, even with the same name or at
  the same address, is refused (`IDENTITY_MISMATCH`) and never receives a command.
- Connection order: last known address, then an address the TV announced, then a short
  rediscovery (at most once per 15 s). If the TV was set up from discovery, only candidates with
  the same mDNS name or ID are tried. If it was added by typing an IP address, any Android TV
  found may be tried; the pin still makes substitution impossible.
- A new address is saved only after the pinned identity has been verified there.
- Repeated identity mismatches (3) go to `PairingRequired` (administrator intervention), never to
  "try another TV".

## Command delivery (spec section 9)

- Presses while not Ready are rejected with brief feedback and a recovery request. **Nothing is
  queued**, so nothing can be replayed after reconnection.
- Ready presses go into a bounded queue (8) processed by one coroutine, in order.
- A command older than 600 ms when its turn comes is dropped, not sent late.
- A command for an older session generation is dropped.
- A failed send is **not** retried; pending commands are discarded and recovery starts. The
  next press by the user is the next command. This matters for Power, Mute and OK.
- Hold-to-repeat (arrows, volume): first command immediately, repeats from 400 ms every 150 ms.
  A repeat is skipped while a command is still waiting, so holds cannot pile up. Repetition stops
  on finger up, touch cancel, finger sliding off the button, the button leaving the screen,
  app backgrounding or screen lock, connection loss or session change, and after a 30 s
  safety limit.
- The protocol has key-down/key-up variants (`START_LONG` / `END_LONG`); this app sends
  separate short presses instead, so a lost "key up" can never leave a key stuck on the TV.
- The protocol has no per-key acknowledgement. Diagnostics say "transmitted", never "executed".

## Multiple TVs

Pairing, selection and connection are separate:

- **Pairing** is per TV. Each `SavedTv` has its own credentials, pinned key, address and verified
  capabilities. Internally a TV is its `id`; names are never used to identify a device.
- **Selection** is a persisted choice (`TvLibrary.selectedId`). It changes only when someone
  chooses, or when the selected TV is removed. Reachability never changes it.
- **Connection** always belongs to the selected TV. There is one session at a time, and other
  saved TVs are not probed.

### Switching and safe command routing

`TvManager.select` first cancels hold-to-repeat and discards pending commands, then updates the
selection. The connection manager sees the new target as `TargetChanged`, which closes the old
session, cancels its attempt and retries, and starts one attempt for the new TV.

Three independent checks stop a command reaching the wrong TV:

1. **At the press.** The remote passes the TV it was showing. The dispatcher accepts the press only
   if `Ready.tvId` matches, so in the instant between choosing and the old session closing, a press
   for the new TV cannot go to the old one. The UI also never shows that old session as Ready.
2. **At delivery.** Commands carry both the TV and the session generation;
   `sessionFor(generation, tvId)` returns nothing if either changed.
3. **At the socket.** Every connection verifies the pinned key from that TV's own pairing.

Attempts are bound to a TV id when they start. A result from an earlier attempt no longer matches
the attempt id and is discarded, and any late session is closed. If cancellation lands at the
moment a connection succeeds, the session is closed rather than left open.

### Startup and removal

| Situation | Behavior |
|---|---|
| No saved TVs | Initial setup |
| One or several saved TVs | Remote of the selected TV |
| Selected TV unreachable | Selection kept, problem shown, retries continue |
| Selected TV removed, one left | That TV is selected |
| Selected TV removed, several left | Chooser, with no way back until one is chosen |

### Names

`SavedTv.displayName` is the custom name, else the original name, and is used everywhere.
`originalName` is kept for the administrator. A TV with no usable name gets an editable fallback
(`TV 1`). Duplicates require a subtitle: a default is assigned when pairing, and the name step and
settings refuse a duplicate without a distinguishing one. Renaming does not touch pairing and does
not restart the connection. The selector reserves the height of the tallest saved TV, so switching
does not move the controls.

### Upgrading from the single-TV version

On first launch the old record is converted into a one-TV library that is selected, with its
credential reference kept as its `id`. Pairing, name and capabilities carry over.

## Type text

The phone's own keyboard is used to enter a phrase, which goes to the active text field on the
selected TV. Text entry only: the app never opens an app, selects a field, or submits a search.

- **Protocol.** One `remote_ime_batch_edit` message with the text, `insert = 1` and the two
  counters last received from the TV, as in the open-source client. The TV's feature flags say
  whether it accepts text at all.
- **Capability.** `TEXT_ENTRY` is verified per TV by a guided check. The button only opens the
  screen once that check passed and the TV is connected; otherwise a tap explains why.
- **Binding.** `TextEntryController` is created for one TV id. A send needs the current session
  to belong to that TV. If the selection changes, the screen closes and the draft is cleared.
- **Sending.** Only `send()` transmits. Editing sends nothing, and repeat taps are ignored while a
  send is running. No Enter, Search or OK follows, and pasted line breaks become spaces.
- **Connection loss.** The draft stays on screen and sending is disabled. After reconnecting to
  the same TV nothing is sent until the user taps again. Nothing is queued.
- **Uncertain delivery.** A failed write, or a session that changed during the send, shows
  "Could not confirm delivery. Check the TV before sending again." and is not retried.
- **Truthful result.** The protocol confirms nothing, so the message is "Text sent to the TV."
  and never that it appeared.
- **Privacy.** The draft lives in the controller only, not in saved instance state, so it is gone
  on cancel, success, TV switch and process death. Logs record lengths and counters, never text,
  including text the TV reports from its own field.

**Not yet established:** whether the TV inserts the text or replaces what the field already holds.
This needs a real TV. The app does not try to clear a field or send Backspace.

## Security and privacy (spec section 12)

- TLS with a per-pairing RSA-2048 client certificate. The private key is stored encrypted
  (AES-256-GCM) with a non-exportable Android Keystore key, in `noBackupFilesDir`.
- App backup and device-transfer are disabled, so credentials never leave the phone.
- The pairing code, secrets and key material are never logged. The log redacts labelled
  secrets, long hex/base64 blobs and PEM blocks, and truncates IP and MAC addresses.
- No analytics, no cloud, no account. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`. None are runtime permissions.
- "Remove this TV" deletes that TV's record (identity, address, capabilities), credentials and
  picture. Removing the last TV also deletes the Keystore key.
- TV pictures come from the system photo picker, so no storage permission is requested. A small
  private copy is kept in app storage.
