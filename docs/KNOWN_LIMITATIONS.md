# Known limitations

## Not yet validated

- **Only two real devices tested so far**, by their owner, with short sessions: a MINIX U8K-ULTRA
  and a Xiaomi MiTV-MSSP2. Protocol details are not an official specification, so each new TV
  needs its own checks. See [COMPATIBILITY_REPORT.md](COMPATIBILITY_REPORT.md).
- **Performance targets have only early samples on real hardware**, not the trial counts the spec
  asks for. The app records real trials in Family settings > Measured performance.
- **Real-device scenarios (spec 15.2) and the senior usability test (15.3) are not done.**

## Protocol and TV behavior

- Only Android TV / Google TV devices with the Android TV Remote Service v2. TVs running other
  systems (Samsung Tizen, LG webOS, Roku, Fire TV, ...) are not supported.
- **Power on is not guaranteed.** Many TVs switch their network off in standby. Waking is
  recorded as a separate capability and only claimed if the guided check confirmed it. A TV that
  is switched off at the wall can never be controlled.
- **The Xiaomi MiTV-MSSP2 cannot be woken from the phone with its current settings.** It leaves the
  network about 2 s after standby. Its button says Turn off, and it must be turned on with its own
  remote. Wake-on-LAN packets did not wake it. Bluetooth wake was tried only from a PC that may
  have been out of range, and the app has no Bluetooth or Wake-on-LAN wake feature.
- For up to 12 s after a TV reports standby, a press may be written to a connection that is already
  dead. The remote shows TV is off during that time, and non-Power buttons look unavailable.
- Unless the TV reported standby just before going silent, the app cannot tell "TV in deep standby"
  from "TV unplugged" from "network problem"; all show as "TV unavailable". This includes reopening
  the app later while the TV is off.
- The protocol does not confirm key execution. "Transmitted" in the log means the bytes left the
  phone.
- Volume and mute depend on the TV's audio routing. With a sound bar or receiver they may only
  work through HDMI-CEC, if at all. The guided check reveals this.
- Hold-to-repeat sends repeated short presses (timing 400 / 150 ms, to be tuned on the TV),
  not the protocol's long-press mode. Some TV menus react differently to a real long press.
- Liveness relies on the TV pinging roughly every few seconds (20 s idle timeout). If the
  target TV pings less often, raise `AtvConfig.idleTimeoutMs`.
- If the TV's certificate key changes (factory reset, possibly some system updates), the app
  refuses it as a different device and asks for a family member. This is deliberate.

## Network

- The phone and TV must be on the same local network; no remote or cloud control.
- mDNS discovery can be blocked by some routers, mesh systems, guest networks or "AP / client
  isolation". Manual address entry is the fallback.
- For a TV **added by address**, finding it again after its address changes depends on the
  discovery list. The app may then briefly contact other Android TVs on the network (TLS only;
  they are refused by the identity pin and receive no commands). A DHCP reservation for the TV
  avoids this.
- Android 17 introduces a "local network" permission for apps that target it. This app targets
  API 35 and does not request it. Raising the target SDK later will require handling that permission.
- Sockets are bound to the Wi-Fi/Ethernet network. Discovery uses the system default network
  (NsdManager limitation before API 34).

## Multiple TVs

- The chooser shows a status only for the selected TV. Other TVs are deliberately not probed, so
  the app cannot say whether they are on until one is selected.
- Adding a TV makes it the selected TV, because its buttons are tested next.
- Testing a TV's buttons selects that TV. It stays selected afterwards.
- Names are limited to 100 characters as a safety limit. The remote shows two lines; the chooser
  and settings show the whole name.
- A TV saved by the single-TV version has no stored key fingerprint until it is re-paired, so the
  "already saved" check cannot recognize it before that.

## Type text

- **Not yet tried on a real TV.** It stays off for each TV until the guided check passes.
- **Insert or replace is unknown.** Whether text is added to what the field holds, or replaces it,
  has not been observed. It may differ between apps.
- The open-source client this follows warns that sending may not work as expected while the TV's
  on-screen keyboard is showing. Some apps draw their own keyboard and may ignore it.
- The app cannot tell whether a text box is selected on the TV, so the senior must select it first.
  It also cannot confirm the text arrived: "Text sent" means it left the phone.
- Limited to 500 characters. Line breaks are replaced with spaces. Not for passwords.
- Position values count characters rather than UTF-16 units, following the open-source client. How
  TVs treat emoji is untested.

## App

- One phone, and one TV controlled at a time. Switching closes the previous session, so each
  switch takes a fresh connection. Optional controls (numbers, channels, text, voice,
  apps, touchpad, media keys) are not included (spec 4.3).
- The family PIN cannot be recovered. Clearing the app's data resets everything, including the
  pairing.
- The release build is signed with the local development key, so it updates a debug install and
  keeps its pairings. It is not fit for distribution: that needs a real key, and moving to one means
  uninstalling first, which loses pairings. R8 shrinking is disabled (keep rules are prepared).
- A release build cannot be read with `adb run-as`. Use Family settings > Copy log for diagnostics.
- "Remote visible" timing is measured from process start to the first drawn frame of the remote,
  a little after the user's tap.
