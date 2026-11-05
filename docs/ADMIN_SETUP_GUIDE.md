# Administrator setup guide

For the family member who sets up the remote. Takes about 10 minutes. You need to be in front
of the TV, because the TV shows a code you have to type into the phone.

## Before you start

- The TV is switched on and connected to the home Wi-Fi (or network cable).
- The phone is connected to the **same** home Wi-Fi.
- Know which TV you want to control, if there is more than one in the home.

## 1. Install and open the app

Install the APK (see the README) and open **TV Remote**. The screen says
**Set up your TV remote**. Tap **Set up TV**.

## 2. Choose a family PIN

Choose 4 to 8 digits and type them twice. The PIN only protects **Family settings** from
accidental taps. The senior never needs it to use the remote. It is not strong security.

If the PIN is forgotten, the only way back in is to clear the app's data in Android settings and
set the remote up again.

## 3. Find the TV

The app lists the TVs it finds on the network, with the address and the last characters of an ID
under each name, to tell similar names apart. Tap **your** TV. The app never picks a TV by itself.

If your TV is not listed after about 10 seconds:

- check that the TV is on and on the same Wi-Fi as the phone;
- tap **Search again**;
- some routers block device discovery: tap **Enter the TV's address instead** and type the TV's
  IP address. On the TV it is usually under **Settings > Network & Internet > (your network)**.

If you entered the address by hand, the app cannot use the TV's network name to find it again if
the router later gives the TV a new address. Consider reserving the TV's address in the router
("DHCP reservation"), or set up from the list instead.

## 4. Pair

The TV shows a 6-character code (numbers and letters A to F). Type it into the phone and tap
**Connect**. Upper or lower case does not matter.

- **"That code did not match"**: the TV now shows a *new* code. Type the new one.
- **"The phone could not reach this TV"**: check the TV is on and both are on the same Wi-Fi.
- **"This TV did not respond the way the app expects"**: this TV probably does not offer the
  Android TV remote service; the app cannot control it.

The pairing is saved securely on the phone. You do not need to pair again when the app or the
phone restarts.

## 5. Name the TV

The app suggests the name the TV gave during setup. Keep it, or type any name the senior will
recognize; there is no required format, and no need to use room names. This name appears on the
remote, the TV chooser and in settings.

The optional **short description** appears under the name. If another saved TV already has the
same name, a description is required so the two can be told apart. The app fills in a starting
one ("TV 2"); replace it with something meaningful, such as "Upstairs bedroom".

If a TV offers no usable name, it starts as "TV 1", "TV 2", and so on. You can rename it.

## 6. Test the buttons

The app now checks each button on the real TV. For each check:

1. Follow the instruction (for example "Show the TV's home screen").
2. Tap the **Send** button.
3. Watch the TV and answer **Yes, it worked** or **No, nothing happened**.

Answer honestly. Only buttons you confirm are shown as working. Buttons you answer "No" for are
**hidden** from the senior's remote, so they never press something that does nothing.

Notes on specific checks:

- **OK** opens the highlighted item. Highlight something harmless first (Settings, an app).
- **Mute** is sent twice, two seconds apart, so the sound comes back on.
- **Power: standby** switches the TV off. The **Power** button only appears on the senior's
  remote if this check worked.
- **Power: wake up** is tested separately. Leave the TV in standby for at least 30 seconds, then
  tap **Try to wake the TV**. Many TVs switch their network off in standby. If so, the app says
  it cannot wake the TV, and it never promises otherwise. Repeat this check after the TV has been
  in standby **overnight** before relying on it (Family settings > Test the buttons again).

You can tap **Skip this check** or **Finish checks later**. Untested buttons are shown, but you
can test them later.

## 7. Done

The screen says **Your remote is ready. Next time, simply open the app to control the TV.**
Tap **Open remote**. From now on the app opens straight to the remote.

Optional: put the app icon on the senior's home screen, and remove other clutter around it.

## Adding more TVs

On the remote, tap **Family settings**, enter the PIN, and tap **Add and pair a TV**. Follow
steps 3 to 6 above, in front of the new TV. Each TV keeps its own pairing and its own tested
buttons. The newly added TV becomes the selected TV.

TVs that are already saved are marked **Already added** in the list. If you pair a TV that turns
out to be saved already (for example it was added under another address), the app recognizes it,
renews its pairing, and does not create a second entry.

## How the senior changes TV

The large bar at the top of the remote shows which TV the buttons control. Tapping it opens
**Choose a TV**. One tap on a TV selects it and returns to the remote, which connects by itself.
The back button returns without changing anything. No pairing or PIN is needed to switch.

The app always reopens on the last selected TV, and it never switches TV by itself, even if the
selected TV is off and another one is on.

## Family settings (later)

On the remote, tap **Family settings** (bottom right) and enter the PIN. There you can:

- **Manage TVs**: see all saved TVs, **Add and pair a TV**, or tap a TV to:
  - change its **name** and **short description** (this never affects the pairing; leaving the
    name blank goes back to the original name);
  - see its **original device name**, address and when it last connected;
  - choose or remove a **picture** shown instead of the TV icon;
  - see which buttons were tested, and **Test the buttons** (this selects that TV);
  - **Re-pair this TV** (name and test results are kept);
  - **Remove this TV** (other TVs are not affected);
- switch **vibration** (on by default), **button sounds** (off by default) and **keep the screen
  on while the remote is open** (off by default);
- see measured **performance** on this phone and the **diagnostic log** (Copy log / Clear log);
- **Change PIN**.

If the selected TV is removed and only one TV is left, that TV is selected. If several are left,
the app asks which TV to control the next time the remote opens.

## Type text

The remote has a **Type text** button, so a search word can be typed on the phone instead of letter
by letter on the TV. It is off for each TV until it has been tested there:

1. On the TV, open a search screen and select the search box.
2. On the phone, open **Family settings**, tap the TV, and tap **Test text entry only**.
3. Tap **Send the word TEST** and answer whether it appeared. Choose **No** if it did not.

Search boxes differ between apps, so try the apps the senior really uses and note any that do not
work. Also try it with words already in the box, to see whether the TV adds to them or replaces them.

To use it: select the box on the TV first, tap **Type text**, type, and tap **Send to TV**. The app
sends the text only. It does not press Enter or start the search; the senior does that with the
remote. Do not use it for passwords. Typed text is not stored or logged.

## If a TV will not wake

Some TVs switch their network off completely in standby. Nothing can reach them then, so the app
can turn them off but not back on. If the **Power: wake up** check fails, the remote's button says
**Turn off** instead of Power, and the TV must be turned on with its own remote or button.

On a Xiaomi TV it may help to keep the network on in standby. This has been reported by another
owner of a MiTV-MSSP2 and has not been confirmed on this TV:

1. On the TV, open **Settings > Device Preferences > About** and press **Build** seven times to
   unlock Developer options. Menu names can differ between TV software versions.
2. In **Developer options**, turn on **Wake on LAN**, if the option is there.
3. Leave **Wake on WLAN** off at first: it was reported to turn the TV on at random.
4. If you can, connect the TV with a network cable; the report that worked used a wired connection.
5. In Family settings, tap the TV, tap **Test the buttons**, and repeat the two Power checks.
   Test again after an overnight standby before relying on it.

The setting may use a little more electricity in standby.

## What the senior sees

| Status on the remote | Meaning | What to do |
|---|---|---|
| Ready | Connected; buttons work. | Nothing. |
| Connecting... / Reconnecting... | Connecting after opening the app, or after a short interruption. | Nothing; it is automatic. |
| Check home Wi-Fi | The phone is not on Wi-Fi. | Reconnect the phone to Wi-Fi. |
| TV is off | The TV itself reported standby. If it stays connected the remote says to press Power; otherwise it says to use the TV's own remote. The app reconnects by itself once the TV is on. | Turn the TV on. |
| TV unavailable | The TV does not answer: off at the wall, in deep standby, or Wi-Fi problem. The app keeps trying. | Switch the TV on with its own remote or button. |
| Family help needed | The TV refused this phone's pairing (for example the pairing was removed on the TV, or the TV was reset or replaced). | Family settings > tap the TV > Re-pair this TV. |

Another TV can still be chosen in any of these states. Buttons never move around when the status
changes. When the TV cannot be reached, buttons are
dimmed with an outline; pressing one explains why nothing happens. The press is **not** saved
and sent later.
