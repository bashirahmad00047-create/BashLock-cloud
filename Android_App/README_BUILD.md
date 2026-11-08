# 📱 BashLock Android Native App Wrapper (APK Project)

Yeh Android Studio ka complete Native Java project hai jo BashLock Cloud ke liye banaya gaya hai. Yeh customer ke smartphone par install hokar installment device lock policy ko 100% enforce karta hai.

---

## 🏗️ Architecture & Core Components

| Component | File Path | Detail |
| :--- | :--- | :--- |
| **Main WebView Activity** | [`MainActivity.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/MainActivity.java) | Hardware-accelerated WebView jo live server ya public HTTPS URL load karta hai. Offline fallback bhi mojood hai. |
| **High-Security Lock Kiosk** | [`LockActivity.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/LockActivity.java) | Full-screen Immersive Sticky screen jo back, home aur recent apps ko block karta hai aur 6-digit PIN keypad offer karta hai. |
| **24/7 Background Service** | [`LockGuardService.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/LockGuardService.java) | Foreground Service jo background mein server API har 10 seconds check karta hai. Lock hote hi LockActivity ko front par le aata hai. |
| **Reboot Auto-Start** | [`BootReceiver.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/BootReceiver.java) | Phone switch off ho kar on hone par foran guard service aur lock state ko revive karta hai. |
| **Device Admin Policy** | [`BashLockAdminReceiver.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/BashLockAdminReceiver.java) | Customer ko app uninstall karne se rokta hai aur remote hardware lock execute karta hai. |
| **JavaScript Bridge** | [`WebAppInterface.java`](file:///C:/Users/It%20Hub/Documents/BashLock-Cloud/Android_App/app/src/main/java/com/bashlock/app/WebAppInterface.java) | Web code aur Android native APIs ke darmiyan data sync karta hai (`savePairingData`, `triggerNativeLock`, etc.). |

---

## 🛠️ APK Kaise Banayein (Build Instructions)

### Tareeqa 1: Android Studio (Recommended)
1. **Android Studio** open karein.
2. **Open Existing Project** par click karein aur yeh folder select karein:  
   `C:\Users\It Hub\Documents\BashLock-Cloud\Android_App`
3. Gradle sync complete hone dein (1-2 minutes).
4. Menu se **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)** dabayein.
5. Build complete hone par `app/build/outputs/apk/debug/app-debug.apk` generate ho jayegi.

### Tareeqa 2: Command Line (Gradle)
Agar system par Android SDK aur Java installed hon, toh terminal mein:
```bash
cd "C:\Users\It Hub\Documents\BashLock-Cloud\Android_App"
./gradlew assembleDebug
```

---

## 📲 Customer Ke Mobile Par Install & Setup
1. `app-debug.apk` ko customer ke phone mein copy karein aur install karein.
2. Pehli dafa open karne par **"Activate Device Administrator"** permission grant karein.
3. Dukaan-daar ka 4-digit setup code (e.g. `4819`) enter karein.
4. Phone protect ho jayega!

### 🔒 Lock & Master PIN Testing:
- Dashboard se **"Band (LOCK)"** dabane par phone foran lock ho jayega.
- Offline hone par bhi customer screen ke keypad par **`189853`** dial karke phone ko unlock kar sakta hai.
