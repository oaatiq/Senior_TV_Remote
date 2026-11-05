# Sanitized diagnostic-log examples

The diagnostic log is stored only on the phone. Administrators can view it and copy it from
Family settings > Diagnostics. Before anything is written, `Redactor` removes pairing codes and
other labelled secrets, PEM blocks, long hex and base64 strings, and truncates IPv4 addresses
(`192.168.1.x`), IPv6 addresses and MAC addresses.

All examples are **real output** from this app. Examples 1 to 3 use the **simulated** TV; example 4
is a real TV.

## Example 1: the real app on the Android emulator

Recorded on the emulator with the installed APK. Scenario: the app starts after a different TV
took over the saved address and is refused; the administrator forgets the TV and pairs again,
mistyping the first code; then the TV is unplugged, powered back on, and restarted.

```
21:25:56.461 [app] Process started
21:25:56.493 [network] Local network available
21:25:56.500 [state] NotConfigured -> Suspended (configuration hasTv=true)
21:25:56.637 [state] Suspended -> Connecting (Foreground)
21:25:57.271 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:26:01.290 [connection] Rediscovery found 2 other candidate(s)
21:26:01.815 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:26:02.324 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:26:03.109 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:26:04.074 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:26:04.075 [state] Connecting -> PairingRequired (attempt failed: IDENTITY_MISMATCH, TLS: SSLHandshakeException)
21:26:23.346 [state] PairingRequired -> NotConfigured (configuration hasTv=false)
21:26:23.409 [admin] Saved TV, credentials and capability results removed
21:26:54.205 [pairing] Starting pairing with 10.0.2.x
21:26:55.773 [pairing] TV accepted pairing request; code should now be visible on the TV
21:27:00.456 [pairing] Code checksum does not match local computation
21:27:00.469 [pairing] TV rejected the code
21:27:00.494 [pairing] Starting pairing with 10.0.2.x
21:27:01.626 [pairing] TV accepted pairing request; code should now be visible on the TV
21:27:12.039 [pairing] Code checksum matches local computation
21:27:12.060 [pairing] TV accepted the code; pairing complete
21:27:12.307 [setup] Paired and saved TV "Simulated TV"
21:27:12.311 [state] NotConfigured -> Connecting (configuration hasTv=true)
21:27:12.899 [transport] Configure/set-active exchange complete
21:27:12.900 [transport] Session established with FakeVendor FakeModel
21:27:12.902 [state] Connecting -> Ready#1 (AttemptSucceeded)
21:27:12.905 [transport] TV reports screen on
21:27:53.010 [transport] Session ended: closed by TV
21:27:53.012 [state] Ready#1 -> Recovering (session lost: closed by TV)
21:27:55.054 [transport] Connect failed in TCP: ConnectException -> UNREACHABLE
21:27:59.061 [connection] Rediscovery found 2 other candidate(s)
21:27:59.576 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:28:00.199 [transport] Connect failed in TLS: SSLHandshakeException -> IDENTITY_MISMATCH
21:28:00.199 [state] Recovering -> TvUnreachable (attempt failed: UNREACHABLE, TCP: ConnectException)
21:28:02.470 [transport] Connect failed in TCP: ConnectException -> UNREACHABLE
21:28:05.015 [transport] Connect failed in TCP: ConnectException -> UNREACHABLE
21:28:08.521 [transport] Configure/set-active exchange complete
21:28:08.521 [transport] Session established with FakeVendor FakeModel
21:28:08.523 [state] TvUnreachable -> Ready#2 (AttemptSucceeded)
21:28:08.523 [transport] TV reports screen on
21:28:21.692 [transport] Session ended: closed by TV
21:28:21.694 [state] Ready#2 -> Recovering (session lost: closed by TV)
21:28:22.263 [transport] Configure/set-active exchange complete
21:28:22.263 [transport] Session established with FakeVendor FakeModel
21:28:22.265 [state] Recovering -> Ready#3 (AttemptSucceeded)
21:28:22.266 [transport] TV reports screen on
```

How to read it:

- `IDENTITY_MISMATCH`: a device answered, but its key is not the paired TV's key. It is never
  controlled. The "2 other candidates" are two real TVs on the development network; both were
  refused at the TLS handshake. (This was a manually added TV with no name to match. TVs set up
  from the discovery list only try candidates with the same name or ID.)
- `Ready#n`: the session generation. Commands queued for an older generation are dropped.
- Neither pairing code appears; only whether its checksum matched.
- Addresses are truncated (`10.0.2.x`).

## Example 2: full stack, automated end-to-end test

Written by `EndToEndSimulatedTvTest` to `app/build/reports/diagnostic-example.log`. The test
deliberately logs `code=<the real code>` and asserts that it comes out redacted.

```
22:31:56.484 [pairing] Starting pairing with 127.0.0.x
22:31:56.584 [pairing] TV accepted pairing request; code should now be visible on the TV
22:31:56.584 [setup] Administrator typed code=<redacted>
22:31:56.584 [pairing] Code checksum matches local computation
22:31:56.585 [pairing] TV accepted the code; pairing complete
22:31:56.587 [network] Local network available
22:31:56.588 [state] NotConfigured -> Connecting (configuration hasTv=true)
22:31:56.598 [transport] Configure/set-active exchange complete
22:31:56.598 [transport] Session established with FakeVendor FakeModel
22:31:56.598 [transport] TV reports screen on
22:31:56.598 [state] Connecting -> Ready#1 (AttemptSucceeded)
22:31:56.600 [command] DOWN transmitted in 1 ms (TV gives no per-key confirmation)
22:31:56.600 [command] OK transmitted in 2 ms (TV gives no per-key confirmation)
22:31:56.601 [transport] Session ended: closed by TV
22:31:56.601 [state] Ready#1 -> Recovering (session lost: closed by TV)
22:31:56.914 [transport] Configure/set-active exchange complete
22:31:56.914 [transport] Session established with FakeVendor FakeModel
22:31:56.914 [state] Recovering -> Ready#2 (AttemptSucceeded)
22:31:56.914 [transport] TV reports screen on
22:31:56.915 [command] HOME transmitted in 1 ms (TV gives no per-key confirmation)
22:31:56.916 [transport] Session ended: closed by TV
22:31:56.916 [state] Ready#2 -> Recovering (session lost: closed by TV)
22:31:57.521 [transport] Session setup failed: AUTH_REJECTED (closed by TV)
22:31:58.405 [transport] Session setup failed: AUTH_REJECTED (closed by TV)
22:31:58.405 [state] Recovering -> PairingRequired (attempt failed: AUTH_REJECTED, closed by TV)
```

The final lines show a pairing removed on the TV. The TV hangs up right after TLS on two
consecutive attempts (the test uses a threshold of 2; the app default is 3), so the app moves to
the administrator-required state instead of retrying forever or showing a false "Ready".

## Example 3: choosing, re-pairing and removing TVs

Recorded on the emulator with two simulated TVs. Testing a TV that was not selected switches to it
first; re-pairing replaces the credentials and reconnects; removing the selected TV moves to the
only TV left.

```
21:08:41.016 [selection] Selected TV "Simulated TV"
21:08:41.017 [transport] Session ended: closed by app
21:08:41.017 [state] Ready#2 -> Connecting (TV selected)
21:08:41.097 [transport] Configure/set-active exchange complete
21:08:41.101 [transport] Session established with FakeVendor FakeModel
21:08:41.103 [state] Connecting -> Ready#3 (AttemptSucceeded)
21:08:45.376 [command] DOWN transmitted in 2 ms (TV gives no per-key confirmation)
21:08:46.280 [command] UP transmitted in 4 ms (TV gives no per-key confirmation)
21:08:49.741 [setup] Check "Up and Down": YES
21:09:09.211 [pairing] Starting pairing with 127.0.0.x
21:09:09.437 [pairing] TV accepted pairing request; code should now be visible on the TV
21:09:15.719 [pairing] Code checksum matches local computation
21:09:15.727 [pairing] TV accepted the code; pairing complete
21:09:15.840 [transport] Session ended: closed by app
21:09:15.842 [state] Ready#3 -> Connecting (TV selected)
21:09:15.865 [setup] Renewed pairing of TV "Simulated TV"
21:09:16.005 [state] Connecting -> Ready#4 (AttemptSucceeded)
21:09:34.254 [admin] Removed TV "Simulated TV" with its pairing and test results
21:09:34.255 [transport] Session ended: closed by app
21:09:34.255 [state] Ready#4 -> Connecting (TV selected)
21:09:34.791 [state] Connecting -> Ready#5 (AttemptSucceeded)
```

## Example 4: a real TV that leaves the network in standby

From the Samsung phone with the Xiaomi MiTV, before the standby improvements. The TV reports standby,
then goes silent. Wake presses are written to the connection without error, because the phone
cannot know yet that the TV has gone; the session only ends when the silence limit is reached.

```
21:47:19.152 [command] POWER transmitted in 6 ms (TV gives no per-key confirmation)
21:47:20.444 [transport] TV reports screen off/standby
21:47:20.550 [setup] Check "Power: standby": YES
21:47:22.351 [command] POWER transmitted in 3 ms (TV gives no per-key confirmation)
21:47:32.332 [command] POWER transmitted in 3 ms (TV gives no per-key confirmation)
21:47:39.179 [setup] Check "Power: wake up": NO
21:47:40.446 [transport] Session ended: no data from TV for 20000 ms
21:47:40.448 [state] Ready#2 -> Recovering (session lost: no data from TV for 20000 ms)
21:47:43.462 [transport] Connect failed in TCP: SocketTimeoutException -> UNREACHABLE
21:47:47.468 [connection] Rediscovery found 0 other candidate(s)
21:47:47.469 [state] Recovering -> TvUnreachable (attempt failed: UNREACHABLE, TCP: SocketTimeoutException)
```

Compare a TV that stays on the network (the MINIX), where the same command wakes it at once:

```
22:21:33.350 [command] POWER transmitted in 3 ms (TV gives no per-key confirmation)
22:21:33.874 [transport] TV reports screen off/standby
22:21:37.622 [command] POWER transmitted in 5 ms (TV gives no per-key confirmation)
22:21:37.969 [transport] TV reports screen on
```

## Exported log format

"Copy log" adds a header with the measured-performance summary:

```
Senior TV Remote diagnostics (sanitized: no codes, keys or full addresses)

Measured performance:
  Launch to remote visible: n=..., median=...ms, p95=...ms, max=...ms, within 500ms: x/n
  Cold launch to Ready: ...
  Foreground reopen to Ready: ...
  Recovery to Ready: ...
  Press to command transmitted: ...

2026-11-04 21:27:12.902 [state] Connecting -> Ready#1 (AttemptSucceeded)
...
```
