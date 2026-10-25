# Простой ТВ (Simple TV)

A replacement home screen for a Google TV stick. Black screen, a big clock, six giant buttons in a row, Russian labels. Built for Grandma and Grandpa.

```
 Суббота, 31 октября                                         19:12
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│[Knicks]│ │[Chelsea│ │ КАНАЛ  │ │ КАНАЛ  │ │        │ │        │
│ logo]  │ │ logo]  │ │   7    │ │   4    │ │  RTVI  │ │  НТВ   │
│ИГРА ИДЁТ│ │Сб 31 окт│ │        │ │        │ │        │ │        │
└────────┘ └────────┘ └────────┘ └────────┘ └────────┘ └────────┘
```

## What each button does

| Button | Opens |
|---|---|
| **Knicks logo** | Tonight's game. If it's on a channel you've linked (ABC, NBC, ESPN... in YouTube TV), goes straight to that channel, playing. Otherwise opens the app that has it: Prime, Peacock, ESPN, or DAZN (MSG). No game on? Says (in Russian) when the next game is. |
| **Chelsea logo** | Same idea. Otherwise: Peacock (Premier League), Paramount+ (Champions League, Carabao Cup), ESPN (FA Cup). No game on? Same: says when the next match is. |
| **КАНАЛ 7 / КАНАЛ 4** | ABC / NBC live in YouTube TV, already playing (opened by link). |
| **RTVI / НТВ** | RTVI US / НТВ America in eTVnet. eTVnet has no channel links, so a built-in helper clicks Прямой эфир → the channel for them. Switch it on once: Settings → System → Accessibility → Простой ТВ. |

**The team buttons are the smart ones.** They download ESPN's schedule every 6 hours. Starting 1 hour before kickoff the tile pulses in team colors, says "Игра в 19:30 / Нажмите ОК" (Game at 7:30 / Press OK), and the cursor jumps onto it automatically. During the game it says "ИГРА ИДЁТ" (Game on). Between games it shows the next game's day and time.

**What "straight to the game" can and can't do.** Live channels (YouTube TV, and NBC/ABC/ESPN games on them) open already playing, with one click. Games that are only on Prime / Peacock / Paramount+ open that app, but Android doesn't let one app press "Watch" inside another, so they'd still need to pick the game there.

Other details:
- Team logos download once from ESPN and are saved on the stick.
- Back button does nothing on the home screen, so they can't get lost. Home button always comes back here (once you've turned on the home-screen switch in Step 3).
- If an app won't open, a red banner says "Не получается открыть. Позвоните Максу." (Can't open it. Call Max.)
- **Secret admin door for you:** press **UP 7 times** in a row to open Android Settings.

---

## Step 1. Turn this folder into an app file (APK)

Pick one.

### Option A: Android Studio (easiest)
1. Install Android Studio (free) from developer.android.com/studio.
2. **File → Open** → pick this `simple-tv` folder. Let it finish "syncing" (a few minutes the first time).
3. **Build → Generate App Bundles or APKs → Generate APKs**.
4. Your file is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: GitHub builds it for you (no installs)
1. Create a **private** repo on GitHub and push this folder to it (Claude Code on your laptop can do this: "push this folder to a new private GitHub repo").
2. Go to the repo's **Actions** tab. The "Build APK" job runs automatically.
3. When it's green, click it and download **SimpleTV** at the bottom. That's your APK.

Every future change you push rebuilds it automatically.

> Keep the repo private. It contains the signing key (`app/simpletv.jks`), which is what lets updates install on top of the old version.

---

## Step 2. Test it on YOUR Chromecast first

You'll need `adb` on your laptop. It comes with Android Studio, or install "Android Platform Tools" alone (on Mac: `brew install android-platform-tools`).

1. **Unlock developer mode on the Chromecast:** Settings → System → About → scroll to **Android TV OS build** → click it **7 times**.
2. Settings → System → **Developer options** → turn on **USB debugging** (and **Wireless debugging** if you see it).
3. Find its IP: Settings → Network & Internet → your Wi-Fi → IP address (like `192.168.1.42`).
4. On your laptop (same Wi-Fi):
   ```
   adb connect 192.168.1.42:5555
   ```
   A box pops up on the TV. Pick **Always allow** → OK.
   (If your device only has "Wireless debugging," open it on the TV, choose "Pair device with pairing code," then run `adb pair <ip>:<port>` with the code it shows, then `adb connect <ip>:<port>`.)
5. Install:
   ```
   adb install -r SimpleTV.apk
   ```
6. Open **Простой ТВ** from your apps row. Click around. Its home-screen switch is off by default, so your Home button and home screen stay exactly as they are.
7. Done testing? `adb uninstall com.maxgendler.simpletv` removes it completely.

---

## Step 3. Set up their stick at your apartment

1. **Set up the stick** like normal with your phone. Use your Google account or make one for them.
2. **Install and sign in** from the Play Store: their live-TV app (**YouTube TV** for now), **Prime Video**, **Peacock**, **Paramount+**, **DAZN**. Sign into each one now (much easier than at their place).
3. **Unlock developer mode + USB debugging** (same as Step 2).
4. **Install the app:** `adb connect <their stick's ip>:5555` then `adb install -r SimpleTV.apk`
5. **Make it THE home screen**: turn on its home-screen switch, then turn off Google's home screen:
   ```
   adb shell pm enable com.maxgendler.simpletv/.HomeScreen
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   ```
   Press **Home** on the remote. You should see the six buttons. If it asks which home app to use, pick **Простой ТВ → Always**.
6. **Switch on the eTVnet helper** (makes RTVI / НТВ open straight to the channel): Settings → System → **Accessibility** → **Простой ТВ** → on → OK. Sign into **eTVnet** first.
7. **Other settings worth changing** (press UP 7 times to get to Settings):
   - Display & Sound → **HDMI-CEC** → on. The stick will turn the TV on and switch to itself.
   - System → **Ambient mode / Screensaver** → turn off or set to clock, so nothing confusing pops up.
   - Accounts → Google TV → turn on **Apps only mode** (backup, in case Google's home ever comes back).

**To undo everything later:**
```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm disable com.maxgendler.simpletv/.HomeScreen
```

> A big Google update can occasionally turn their home screen back on. If Grandma suddenly sees the Google screen, just rerun the two `disable-user` lines.

---

## Step 4. At their place (about 20 min)

1. **Samsung TV:** turn on **Anynet+ (HDMI-CEC)** in the TV's settings, and turn off "Start with Smart Hub Home" so the TV goes straight to the stick's input when it turns on.
2. Plug in the stick, connect it to **their Wi-Fi**.
3. Open **DAZN** once and play the MSG channel to confirm it sees them as in-market.
4. Press every button once to confirm it opens.
5. **Remote:** put tape over the shortcut buttons (YouTube, Netflix, etc.) and the Input button. They only need **Power, Volume, arrows, OK, Back, Home**.

---

## Step 5. Fix things from home later (optional)

1. Install **Tailscale** from the Play Store on their stick and sign in. Install Tailscale on your laptop too, same account.
2. From anywhere: `adb connect <stick's Tailscale IP>:5555`
3. Now you can push updates (`adb install -r SimpleTV.apk`) without driving over.

---

## Changing things

Everything adjustable is in **`app/src/main/java/com/maxgendler/simpletv/Config.java`**:
- `CHANNELS`: the four channel buttons. Each has a label and a **live link**. To get a link: on a computer open tv.youtube.com → Live → start the channel → copy the address bar.
- `SPORTS_ON_CABLE`: live links for ABC / NBC / ESPN / USA / MSG, so team games on those channels open straight to the channel.
- `CABLE`: which live-TV app the channel buttons use (YouTube TV now; switch to their real provider later).
- How early the team buttons start glowing (default 1 hour), and each team's everyday app.
- The secret admin press count

Change it, push to GitHub (it builds the APK), reinstall with `adb install -r SimpleTV.apk`.

**Testing a link before putting it in the app:** this does exactly what the button does:
```
adb shell am start -a android.intent.action.VIEW -d "https://tv.youtube.com/watch/PASTE_ID" com.google.android.youtube.tvunplugged
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Channel buttons are faded out | The live-TV app isn't installed, or its package name is different. Run `adb shell pm list packages \| grep -i youtube` and put the real name in `CABLE` in `Config.java`. |
| "Канал ещё не настроен" | That channel's link is still empty in `Config.java`. |
| Channel button opens the app but not the channel | The app didn't understand that link. Try another link format with the `adb shell am start` line above. |
| A team button shows no next game | No internet, or ESPN changed its schedule file. Until it loads, the button just says there's no game. |
| Stuck in an app | Home button always comes back. |
| Need Android settings | Press UP 7 times on the home screen. |
