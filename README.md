# TV Remote

One APK, two roles. Phone = touchpad/keyboard controller. Android TV = receiver (accessibility service + cursor).

## Build
Push to GitHub (branch `main`). Actions builds it: repo > Actions > Build APK > artifact `tvremote-apk`.

## TV setup
1. Sideload the APK on the TV (Downloader app, or `adb install`).
2. Open the app. It opens in receiver mode automatically.
3. If Accessibility won't enable (greyed out): Activity Launcher > Settings > App info > TV Remote > menu > "Allow restricted settings".
4. Settings > Accessibility > TV Remote > turn ON.
5. The app screen shows the TV's IP and PIN. Give the TV a fixed IP in your router.

## Phone setup
Install the same APK. Enter the TV IP + PIN once; it reconnects automatically after that.

## Protocol (TCP :8765, one JSON per line)
hello {t,pin} -> ok | bad. Then: move {dx,dy}, click, long, scroll {d:+1|-1}, key {k}, text {s}.
Keys: back home recents backspace enter up down left right center.
