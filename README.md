# BashLock Cloud - Prototype Testing Console (Step 2)

Commercial Multi-Tenant SaaS platform for Mobile Shops selling Android Smartphones on Installments (EMI).

---

## 1. Quick Start (Chalaane Ka Tareeqa)

1. Is folder mein command prompt / terminal khol kar yeh command run karein:
   ```cmd
   python app.py
   ```
   *Ya phir direct `start.bat` par double-click karein.*
2. Apne browser mein open karein:
   ```
   http://127.0.0.1:5000
   ```

---

## 2. Features Included in this Prototype (Step 2)

* **Dual-Engine Architecture (Live GCP AMAPI + High-Fidelity Simulator):**
  * Agar aapke paas abhi Google Cloud Service Account nahi hai, toh system **Simulator Mode** mein chalta hai taake aap instantly QR generation aur UI controls test kar sakein.
  * Jaise hi aap GCP se download shuda `service_account.json` file is folder mein rakhte hain, system automatic **Live Google Android Management API (AMAPI)** par switch ho jata hai.
* **6-Tap Zero-Touch Provisioning QR Generator:**
  * Android Enterprise standard payload create karta hai (`CloudDeviceAdminReceiver` + Enrollment Token).
  * Base64 PNG QR code generate karta hai jise factory-state Android phone par direct scan kiya ja sakta hai.
* **Real-time Remote Lock / Unlock Console:**
  * 1-Click Toggle: Device ko instant `LOCKED` (Kiosk Freeze) ya `UNLOCKED` state mein shift karta hai.
  * Tamper protection policies (Factory reset block, ADB block, Safe mode block, USB data block) visually integrate hain.
* **Offline Dynamic Unlock PIN Generator:**
  * Customer agar internet band kar de aur cash shop par de, toh backend HMAC-SHA256 Challenge PIN calculate karta hai jo locked phone keypad par enter karke offline unlock provide karta hai.

---

## 3. Real Android Smartphone Testing Guide (Single Phone Prototype)

1. Test phone ko **Factory Reset** karein.
2. Phone on hone par jab pehli screen (**"Hi there" / "Start" / "Welcome"**) aaye, wahan kisi bhi khali jagah par **lagatar 6 martaba tap (6-tap)** karein.
3. Hidden QR Camera open hoga.
4. Phone ko Wi-Fi se connect karein aur BashLock console se generate shuda QR scan karein.
5. Android OS background mein automatic Google Cloud DPC download karega aur phone Device Owner ban jayega.
6. Console se **LOCK** button dabane par phone instant screen lock / kiosk restrict ho jayega.
