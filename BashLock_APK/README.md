# 📱 BashLock_APK - Android Native Kiosk WebView Wrapper

Ready-to-build Android Studio project jo BashLock Cloud ke `/lock-app` ko customer ke phone par as a Kiosk Lock Screen chalata hai.

---

## 🌟 Key Features Implemented:
1. **Dynamic Remote URL Auto-Discovery (Lifetime 1-Time Install):** App start hote hi GitHub se `live_url.txt` fetch kar ke current live Cloudflare URL par auto-connect ho jati hai. Tunnel URL badalne par APK dobara install karne ki zaroorat nahi rehti!
2. **Kiosk Lock Mode (`startLockTask()`):** Jab device lock state mein hoti hai, yeh phone ko kiosk mode mein pin kar deta hai.
3. **`SYSTEM_ALERT_WINDOW` Overlay Guard:** Top status bar aur notification shade ke upar invisible floating barrier banata hai taake customer quick settings pull down na kar sake.
4. **Hardware Button Suppression:** Back, Home aur Recent Apps button complete block ho jate hain.
5. **`BashLockDeviceAdminReceiver` Anti-Uninstall:** Android Settings mein ja kar koi bhi customer app ko direct delete ya uninstall nahi kar sakta.

---

## 🛠️ How to Build the APK:

### Option 1: Android Studio (Recommended)
1. Android Studio open karein.
2. **Open Existing Project** par click karein aur yeh folder select karein:
   `C:\Users\It Hub\Documents\BashLock-Cloud\BashLock_APK`
3. Gradle sync complete hone ke baad menu se click karein:
   **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**
4. APK yahan create ho jayegi:
   `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Command Line (Gradle)
```bash
cd "C:\Users\It Hub\Documents\BashLock-Cloud\BashLock_APK"
./gradlew assembleDebug
```

---

## 🔐 Advanced Device Owner Mode (Optional - Zero Escape):
Agar aap phone ko counter par fresh setup kar rahe hon (Factory Reset phone), toh ADB se complete un-escapable Device Owner bana sakte hain:
```bash
adb shell dpm set-device-owner com.bashlock.apk/.BashLockDeviceAdminReceiver
```
Is se customer phone ko safe mode, factory reset ya status bar pull-down kisi bhi tarah escape nahi kar sakega.
