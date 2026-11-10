import os
import json
import io
import csv
import base64
import hmac
import hashlib
import time
import socket
import random
import requests
import subprocess
import threading
from datetime import datetime, timedelta
from flask import Flask, render_template, request, jsonify, session, Response, redirect, send_file
import qrcode
from database import get_db_connection, hash_password, init_db, wipe_all_demo_data

app = Flask(__name__)
app.secret_key = "bashlock_cloud_production_super_secret_key_2026"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CREDENTIALS_FILE = os.path.join(BASE_DIR, "service_account.json")

# Ensure DB initialized
init_db()

# ----------------- BAILEYS WHATSAPP ENGINE AUTO-LAUNCH (PORT 5002) ----------------- #
WA_BRIDGE_PORT = 5002
WA_BRIDGE_URL = f"http://127.0.0.1:{WA_BRIDGE_PORT}"
_bridge_proc = None

def is_wa_bridge_alive():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.5)
        res = s.connect_ex(("127.0.0.1", WA_BRIDGE_PORT))
        s.close()
        return res == 0
    except Exception:
        return False

def launch_wa_bridge_subprocess():
    global _bridge_proc
    if is_wa_bridge_alive():
        return
    bridge_script = os.path.join(BASE_DIR, "wa_bridge.js")
    if not os.path.exists(bridge_script):
        return
    try:
        env = os.environ.copy()
        env["WA_BRIDGE_PORT"] = str(WA_BRIDGE_PORT)
        env["NODE_PATH"] = os.path.join(BASE_DIR, "node_modules")
        
        flags = 0
        if os.name == "nt":
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            
        _bridge_proc = subprocess.Popen(
            ["node", bridge_script],
            cwd=BASE_DIR,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags
        )
        print(f"[*] Baileys WhatsApp Bridge automatically started on port {WA_BRIDGE_PORT} (PID: {_bridge_proc.pid})")
    except Exception as e:
        print(f"[!] Error auto-launching wa_bridge.js: {e}")

def start_wa_bridge_supervisor():
    def _run():
        time.sleep(1)
        launch_wa_bridge_subprocess()
        while True:
            time.sleep(10)
            if not is_wa_bridge_alive():
                print(f"[*] Reviving Baileys WhatsApp Bridge on port {WA_BRIDGE_PORT}...")
                launch_wa_bridge_subprocess()
    t = threading.Thread(target=_run, daemon=True)
    t.start()

start_wa_bridge_supervisor()

def dispatch_whatsapp_message(phone: str, message: str) -> bool:
    """Dispatches WhatsApp message through local Baileys bridge on port 5002."""
    if not phone or not message:
        return False
    clean_digits = "".join(filter(str.isdigit, str(phone)))
    if clean_digits.startswith("0") and len(clean_digits) == 11:
        clean_digits = "92" + clean_digits[1:]
    elif not clean_digits.startswith("92") and len(clean_digits) == 10:
        clean_digits = "92" + clean_digits
    chat_id = f"{clean_digits}@s.whatsapp.net"
    try:
        r = requests.post(
            f"{WA_BRIDGE_URL}/send-message",
            json={"chatId": chat_id, "message": message},
            timeout=8
        )
        return r.status_code == 200 and r.json().get("success", False)
    except Exception as e:
        print(f"[!] dispatch_whatsapp_message failed for {phone}: {e}")
        return False

def send_whatsapp_lock_alert(device_id: str):
    """Event (b): Automatically sends Lock Alert + Shop Helpline + Due Amount when a phone is LOCKED."""
    try:
        conn = get_db_connection()
        row = conn.execute("""
            SELECT d.*, c.full_name as customer_name, c.phone_number as customer_phone,
                   s.name as shop_name, s.phone as shop_phone, s.address as shop_address
            FROM devices d
            JOIN customers c ON d.customer_id = c.id
            JOIN shops s ON d.shop_id = s.id
            WHERE d.id = ?
        """, (device_id,)).fetchone()
        conn.close()

        if not row or not row["customer_phone"]:
            return

        cust_name = row["customer_name"]
        phone_model = row["phone_model"]
        monthly = int(row["monthly_installment"])
        remaining = int(row["remaining_balance"])
        due_date = row["next_due_date"]
        shop_name = row["shop_name"]
        shop_phone = row["shop_phone"]
        shop_address = row["shop_address"]

        msg = (
            f"⚠️ *PHONE SCREEN BAND (LOCKED) NOTICE*\n\n"
            f"Assalam-o-Alaikum *{cust_name}* bhai,\n\n"
            f"Aapke phone (*{phone_model}*) ki mahana qist ada na hone ki wajah se screen band (lock) kar di gayi hai.\n\n"
            f"💰 *Wajib-ul-Ada Qist:* PKR {monthly:,}\n"
            f"📉 *Kul Baqaya Raqam:* PKR {remaining:,}\n"
            f"📅 *Aakhri Tareekh:* {due_date}\n\n"
            f"📞 *Dukaan Helpline:* {shop_phone}\n"
            f"🏬 *Dukaan:* {shop_name}\n"
            f"📍 *Pata:* {shop_address}\n\n"
            f"Baraye meharbani dukaan par qist jama karwa ke foran phone unlock karwayein ya 6-digit Master PIN hasil karein.\n"
            f"Shukriya!"
        )
        dispatch_whatsapp_message(row["customer_phone"], msg)
    except Exception as e:
        print(f"[!] send_whatsapp_lock_alert error: {e}")

def send_whatsapp_wasooli_receipt(receipt_data: dict):
    """Event (c): Automatically sends Payment Receipt + 6-Digit Offline Unlock PIN directly via Baileys."""
    try:
        phone = receipt_data.get("customer_phone")
        if not phone:
            return
        cust_name = receipt_data.get("customer_name")
        shop_name = receipt_data.get("shop_name")
        phone_model = receipt_data.get("phone_model")
        amount = int(float(receipt_data.get("amount_paid", 0)))
        balance = int(float(receipt_data.get("new_balance", 0)))
        next_due = receipt_data.get("new_due_date")
        rcp_no = receipt_data.get("receipt_number")
        pin = receipt_data.get("offline_pin")
        helpline = receipt_data.get("shop_phone")

        msg = (
            f"🧾 *QIST WASOOLI OFFICIAL RECEIPT*\n\n"
            f"Assalam-o-Alaikum *{cust_name}* bhai,\n"
            f"*{shop_name}* par aapke phone (*{phone_model}*) ki qist wasooli kamyabi se darj ho gayi hai!\n\n"
            f"📄 *Receipt No:* {rcp_no}\n"
            f"💵 *Wasool Shuda Raqam:* PKR {amount:,}\n"
            f"📉 *Naya Baqaya (Remaining):* PKR {balance:,}\n"
            f"📅 *Agli Qist Ki Tareekh:* {next_due}\n\n"
            f"🔑 *Aap Ka 6-Digit Master Unlock PIN:*\n"
            f"👉 *{pin}*\n\n"
            f"*(Agar aapka phone lock hai toh screen ke numeric touch keypad par yeh 6-digit PIN dial karein, phone foran unlock ho jayega).*\n\n"
            f"📞 Helpline: {helpline}\n"
            f"Shukriya!"
        )
        dispatch_whatsapp_message(phone, msg)
    except Exception as e:
        print(f"[!] send_whatsapp_wasooli_receipt error: {e}")

def get_public_tunnel_url():
    """Reads live public HTTPS URL from live_url.txt or tunnel_url.txt or default."""
    for filename in ["live_url.txt", "tunnel_url.txt"]:
        tunnel_file = os.path.join(BASE_DIR, filename)
        if os.path.exists(tunnel_file):
            try:
                with open(tunnel_file, "r") as f:
                    val = f.read().strip()
                    if val.startswith("http"):
                        return val
            except Exception:
                pass
    return "https://sperm-gem-balanced-choice.trycloudflare.com"

def get_lan_ip():
    """Detects laptop's local LAN/Wi-Fi IPv4 address dynamically."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "10.119.0.239"

def get_current_shop_id():
    return session.get("shop_id", 1)

def calculate_offline_pin(device_id: str, date_str: str = None) -> str:
    """Calculates cryptographic 6-digit challenge PIN for offline unlock."""
    salt = "BASHLOCK_MASTER_SALT_2026_OFFLINE"
    if not date_str:
        date_str = datetime.utcnow().strftime("%Y-%m-%d")
    msg = f"{device_id}_{date_str}_{salt}".encode("utf-8")
    h = hmac.new(salt.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    pin_int = int(h[:8], 16) % 1000000
    return f"{pin_int:06d}"

# ----------------- EASY 4-DIGIT CODE MOBILE LOCK APP (Requirement 3) ----------------- #

@app.route("/sw.js")
def service_worker_file():
    sw_code = """
const CACHE_NAME = 'bashlock-lockapp-cache-v3';

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    Promise.all([
      self.clients.claim(),
      caches.keys().then((keys) => {
        return Promise.all(
          keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
        );
      })
    ])
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // If navigating to /lock-app or subpaths
  if (req.mode === 'navigate' || url.pathname.startsWith('/lock-app')) {
    event.respondWith(
      fetch(req)
        .then((res) => {
          // If response is valid (status 200) and not Cloudflare Error 1033 (5xx/530)
          if (res && res.status === 200) {
            const clone = res.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(req, clone);
              cache.put('/lock-app', clone.clone());
            });
            return res;
          }
          // If Cloudflare returned Error 1033 (HTTP 530/502/503), serve cached lock screen
          return caches.match(req).then((cached) => cached || caches.match('/lock-app') || res);
        })
        .catch(async () => {
          // Complete offline or tunnel drop
          const cached = await caches.match(req);
          if (cached) return cached;
          const fallback = await caches.match('/lock-app');
          if (fallback) return fallback;
          return new Response("BashLock Offline Security Active", {
            headers: { 'Content-Type': 'text/plain' }
          });
        })
    );
    return;
  }

  // Other assets (fonts, css, js)
  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.status === 200 && (url.pathname.endsWith('.css') || url.pathname.endsWith('.js') || url.hostname.includes('cdnjs') || url.hostname.includes('tailwind'))) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, clone));
        }
        return res;
      })
      .catch(() => caches.match(req))
  );
});
"""
    return Response(
        sw_code.strip(),
        mimetype="application/javascript",
        headers={
            "Content-Type": "application/javascript",
            "Service-Worker-Allowed": "/",
            "Cache-Control": "no-cache, no-store, must-revalidate"
        }
    )

@app.route("/lock-app")
@app.route("/lock-app/<setup_code>")
def lock_app_page(setup_code=""):
    lan_ip = get_lan_ip()
    tunnel_url = get_public_tunnel_url()
    return render_template("lock_app.html", initial_code=setup_code, lan_ip=lan_ip, public_tunnel_url=tunnel_url)

@app.route("/api/device/by-code/<code_or_imei>/status", methods=["GET"])
def get_device_by_code_status(code_or_imei):
    """Fetches device status by 4-digit setup code OR 15-digit IMEI."""
    code_or_imei = code_or_imei.strip()
    conn = get_db_connection()
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")

    row = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
               s.name as shop_name, s.phone as shop_phone, s.address as shop_address
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.setup_code = ? OR d.imei = ?
    """, (code_or_imei, code_or_imei)).fetchone()

    conn.close()

    if not row:
        # Zero Accidental Lockout: If code was deleted or unassigned, NEVER lock out the user!
        return jsonify({
            "success": True,
            "status": "unlocked",
            "is_locked": False,
            "locked": 0,
            "remote_command": "UNLOCK",
            "device": {
                "id": "UNLOCKED-DEVICE",
                "customer_name": "Customer",
                "phone_model": "Phone",
                "status": "unlocked",
                "is_locked": 0,
                "locked": 0,
                "remote_command": "UNLOCK",
                "is_overdue": False,
                "is_released": False,
                "remaining_balance": 0,
                "next_due_date": "2099-12-31",
                "offline_pin": "189853"
            }
        }), 200

    d = dict(row)
    db_status = (d.get("status") or "UNLOCKED").strip().upper()
    is_overdue = (d.get("next_due_date", "") < today_str and float(d.get("remaining_balance", 0) or 0) > 0)

    # Commercial Lock Enforcement:
    # A device is ONLY locked if the shopkeeper explicitly locked it OR installment is overdue!
    if db_status in ["RELEASED", "RELEASE_AND_UNINSTALL"]:
        is_locked = False
        status_str = db_status
        remote_cmd = "UNLOCK"
        d["is_released"] = True
    elif db_status == "LOCKED" or is_overdue:
        is_locked = True
        status_str = "LOCKED"
        remote_cmd = d.get("remote_command") or "LOCK"
        d["is_released"] = False
    else:  # ACTIVE, UNLOCKED, etc.
        is_locked = False
        status_str = "UNLOCKED"
        remote_cmd = d.get("remote_command") or "UNLOCK"
        d["is_released"] = False

    d["status"] = status_str
    d["is_locked"] = 1 if is_locked else 0
    d["locked"] = 1 if is_locked else 0
    d["remote_command"] = remote_cmd
    d["is_overdue"] = is_overdue
    d["offline_pin"] = calculate_offline_pin(d.get("id", 1))

    # If an ephemeral wake command was sent, deliver it once then clear
    if d.get("remote_command") == "WAKE_DEVICE":
        try:
            c_update = get_db_connection()
            c_update.execute("UPDATE devices SET remote_command = ? WHERE id = ?", (remote_cmd if remote_cmd != 'WAKE_DEVICE' else None, d["id"]))
            c_update.commit()
            c_update.close()
        except Exception:
            pass

    return jsonify({
        "success": True,
        "status": status_str.lower(),
        "is_locked": is_locked,
        "locked": 1 if is_locked else 0,
        "remote_command": remote_cmd,
        "device": d
    }), 200

@app.route("/download/apk")
@app.route("/download/bashlock.apk")
def download_apk():
    """Serves the latest compiled BashLock APK for 1-tap download and QR provisioning."""
    possible_paths = [
        os.path.join(BASE_DIR, "BashLock_APK", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
        os.path.join(BASE_DIR, "BashLock_APK", "app", "build", "outputs", "apk", "release", "app-release.apk"),
        os.path.join(BASE_DIR, "app-debug.apk"),
        os.path.join(BASE_DIR, "BashLock.apk"),
        os.path.join(BASE_DIR, "bashlock.apk")
    ]
    existing = [p for p in possible_paths if os.path.exists(p) and os.path.getsize(p) > 1000000]
    if existing:
        latest_file = max(existing, key=os.path.getmtime)
        return send_file(latest_file, as_attachment=True, download_name="BashLock_Guard.apk", mimetype="application/vnd.android.package-archive")

    # If no local file on disk, dynamically redirect to the latest GitHub Actions workflow run
    try:
        import urllib.request, json
        req = urllib.request.Request(
            "https://api.github.com/repos/bashirahmad00047-create/BashLock-Cloud/actions/runs?per_page=1",
            headers={"User-Agent": "BashLock-Server"}
        )
        with urllib.request.urlopen(req, timeout=4) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data.get("workflow_runs") and len(data["workflow_runs"]) > 0:
                return redirect(data["workflow_runs"][0]["html_url"])
    except Exception:
        pass

    return redirect("https://github.com/bashirahmad00047-create/BashLock-cloud/actions")

@app.route("/qr-setup")
@app.route("/provision")
def qr_provision_page():
    """Shopkeeper Mobile QR Provisioning Screen."""
    lan_ip = get_lan_ip()
    tunnel_url = get_public_tunnel_url()
    return render_template("qr_provision.html", lan_ip=lan_ip, public_tunnel_url=tunnel_url)

@app.route("/api/provisioning/qr.png")
def get_provisioning_qr():
    """Generates Zero-Cable Android Enterprise Device Owner or APK Download Provisioning QR Code."""
    tunnel_url = get_public_tunnel_url()
    qr_type = request.args.get("type", "do").lower()
    code = request.args.get("code", "").strip()
    wifi_ssid = request.args.get("wifi_ssid", "").strip()
    wifi_password = request.args.get("wifi_password", "").strip()
    wifi_security = request.args.get("wifi_security", "WPA").strip()

    if qr_type == "download":
        qr_content = f"{tunnel_url}/download/bashlock.apk"
    elif qr_type == "pair" and code:
        qr_content = f"{tunnel_url}/lock-app/{code}"
    else:
        apk_download_url = f"{tunnel_url}/download/bashlock.apk"
        admin_extras = {
            "server_url": f"{tunnel_url}/lock-app"
        }
        if code:
            admin_extras["setup_code"] = code

        payload = {
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.bashlock.apk/com.bashlock.apk.BashLockDeviceAdminReceiver",
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_download_url,
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": admin_extras
        }
        if wifi_ssid:
            payload["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi_ssid
            payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifi_password
            payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = wifi_security

        qr_content = json.dumps(payload)

    img = qrcode.make(qr_content)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    return Response(buf.getvalue(), mimetype="image/png")

@app.route("/api/device/by-code/<code_or_imei>/activate", methods=["POST"])
def activate_device_by_code(code_or_imei):
    """Activates mobile phone protection without USB and without QR."""
    code_or_imei = code_or_imei.strip()
    conn = get_db_connection()
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    row = conn.execute("""
        SELECT d.*, c.full_name as customer_name, s.name as shop_name, s.phone as shop_phone
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.setup_code = ? OR d.imei = ?
    """, (code_or_imei, code_or_imei)).fetchone()

    if not row:
        conn.close()
        return jsonify({"success": False, "error": "Ghalat 4-Digit Setup Code!"}), 404

    d = dict(row)
    conn.execute("""
        UPDATE devices 
        SET last_seen = 'Protection Active (No USB / Code Paired)'
        WHERE id = ?
    """, (d["id"],))

    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'DEVICE_ACTIVATED', ?, ?)
    """, (d["shop_id"], f"{d['phone_model']} ({d['customer_name']}) 4-digit code se activate ho gaya.", now_str))

    conn.commit()
    conn.close()

    d["offline_pin"] = calculate_offline_pin(d["id"])

    return jsonify({
        "success": True,
        "message": "Phone protection kamyabi se ACTIVATE ho gayi hai!",
        "device": d
    })

@app.route("/api/pair", methods=["GET", "POST"])
def api_pair():
    """Pairs mobile phone with 4-digit code and directly redirects to /lock-app/<code>."""
    code = ""
    if request.method == "POST":
        if request.is_json:
            data = request.get_json(silent=True) or {}
            code = data.get("code") or data.get("setup_code") or ""
        else:
            code = request.form.get("code") or request.form.get("setup_code") or ""
    if not code:
        code = request.args.get("code") or request.args.get("setup_code") or ""

    code = str(code).strip()
    if not code:
        if request.method == "GET" and "text/html" in request.headers.get("Accept", ""):
            return redirect("/lock-app")
        return jsonify({"success": False, "error": "4-digit code is required"}), 400

    conn = get_db_connection()
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    row = conn.execute("""
        SELECT d.*, c.full_name as customer_name, s.name as shop_name, s.phone as shop_phone
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.setup_code = ? OR d.imei = ?
    """, (code, code)).fetchone()

    if not row:
        conn.close()
        if request.method == "GET" and "text/html" in request.headers.get("Accept", ""):
            return redirect(f"/lock-app/{code}?error=not_found")
        return jsonify({"success": False, "error": "Ghalat 4-Digit Setup Code!"}), 404

    d = dict(row)
    conn.execute("""
        UPDATE devices 
        SET last_seen = 'Protection Active (No USB / Code Paired)'
        WHERE id = ?
    """, (d["id"],))

    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'DEVICE_ACTIVATED', ?, ?)
    """, (d["shop_id"], f"{d['phone_model']} ({d['customer_name']}) 4-digit code se activate ho gaya.", now_str))

    conn.commit()
    conn.close()

    d["offline_pin"] = calculate_offline_pin(d["id"])

    if request.method == "GET" and "text/html" in request.headers.get("Accept", ""):
        return redirect(f"/lock-app/{code}")

    return jsonify({
        "success": True,
        "message": "Phone protection kamyabi se ACTIVATE ho gayi hai!",
        "device": d,
        "redirect_url": f"/lock-app/{code}"
    })

# ----------------- MONTHLY HISAB & KPI SUMMARY (Requirement 2) ----------------- #

@app.route("/api/kpi", methods=["GET"])
def get_kpis():
    shop_id = get_current_shop_id()
    month_filter = request.args.get("month", "ALL").strip() # "ALL" or "YYYY-MM"
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")

    conn = get_db_connection()

    if month_filter != "ALL":
        devices = conn.execute("""
            SELECT * FROM devices 
            WHERE shop_id = ? AND strftime('%Y-%m', created_at) = ?
        """, (shop_id, month_filter)).fetchall()

        payments = conn.execute("""
            SELECT SUM(amount) as total_collected FROM payments 
            WHERE shop_id = ? AND strftime('%Y-%m', created_at) = ?
        """, (shop_id, month_filter)).fetchone()
    else:
        devices = conn.execute("SELECT * FROM devices WHERE shop_id = ?", (shop_id,)).fetchall()
        payments = conn.execute("SELECT SUM(amount) as total_collected FROM payments WHERE shop_id = ?", (shop_id,)).fetchone()

    total_devices = len(devices)
    active_devices = sum(1 for d in devices if d["status"] == "UNLOCKED")
    locked_devices = sum(1 for d in devices if d["status"] == "LOCKED")
    
    total_financed = sum(float(d["total_price"]) for d in devices)
    total_remaining = sum(float(d["remaining_balance"]) for d in devices)

    downpayments = sum(float(d["down_payment"]) for d in devices)
    total_collected = (payments["total_collected"] or 0.0) + downpayments

    overdue_devices = [d for d in devices if d["next_due_date"] < today_str and float(d["remaining_balance"]) > 0]
    overdue_count = len(overdue_devices)
    overdue_amount = sum(float(d["monthly_installment"]) for d in overdue_devices)

    conn.close()

    return jsonify({
        "total_devices": total_devices,
        "active_devices": active_devices,
        "locked_devices": locked_devices,
        "total_financed_pkr": total_financed,
        "total_remaining_pkr": total_remaining,
        "total_collected_pkr": total_collected,
        "overdue_count": overdue_count,
        "overdue_amount_pkr": overdue_amount,
        "month": month_filter,
        "lan_ip": get_lan_ip(),
        "mobile_url": f"http://{get_lan_ip()}:5000/lock-app"
    })

@app.route("/api/lan-ip", methods=["GET"])
def api_lan_ip():
    ip = get_lan_ip()
    return jsonify({
        "lan_ip": ip,
        "mobile_url": f"http://{ip}:5000/lock-app"
    })

# ----------------- DEVICE LIST WITH MONTH FILTER ----------------- #

@app.route("/api/devices", methods=["GET"])
def list_devices():
    shop_id = get_current_shop_id()
    month_filter = request.args.get("month", "ALL").strip()
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")

    conn = get_db_connection()

    if month_filter != "ALL":
        rows = conn.execute("""
            SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
                   c.guarantor_name, c.guarantor_cnic, s.phone as shop_phone, s.name as shop_name
            FROM devices d
            LEFT JOIN customers c ON d.customer_id = c.id
            JOIN shops s ON d.shop_id = s.id
            WHERE d.shop_id = ? AND strftime('%Y-%m', d.created_at) = ?
            ORDER BY d.created_at DESC
        """, (shop_id, month_filter)).fetchall()
    else:
        rows = conn.execute("""
            SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
                   c.guarantor_name, c.guarantor_cnic, s.phone as shop_phone, s.name as shop_name
            FROM devices d
            LEFT JOIN customers c ON d.customer_id = c.id
            JOIN shops s ON d.shop_id = s.id
            WHERE d.shop_id = ?
            ORDER BY d.created_at DESC
        """, (shop_id,)).fetchall()

    devices = []
    for r in rows:
        d = dict(r)
        d["is_overdue"] = (d["next_due_date"] < today_str and float(d["remaining_balance"]) > 0)
        d["offline_pin"] = calculate_offline_pin(d["id"])
        devices.append(d)

    conn.close()
    return jsonify({"devices": devices})

# ----------------- ADD CUSTOMER & PHONE (EASY 4-DIGIT SETUP CODE) ----------------- #

@app.route("/api/devices/enroll", methods=["POST"])
def enroll_device():
    shop_id = get_current_shop_id()
    data = request.json or {}

    customer_name = data.get("customer_name", "").strip()
    customer_cnic = data.get("customer_cnic", "").strip()
    customer_phone = data.get("customer_phone", "").strip()
    guarantor_name = data.get("guarantor_name", "").strip()
    guarantor_cnic = data.get("guarantor_cnic", "").strip()
    phone_model = data.get("phone_model", "").strip()
    imei = data.get("imei", "").strip()
    
    try:
        total_price = float(data.get("total_price", 0))
        down_payment = float(data.get("down_payment", 0))
        monthly_installment = float(data.get("monthly_installment", 0))
    except ValueError:
        return jsonify({"success": False, "error": "Valid numeric amount enter karein"}), 400

    if not customer_name or not phone_model:
        return jsonify({"success": False, "error": "Customer Name aur Phone Model lazmi hain"}), 400

    if not imei:
        imei = "86" + str(int(time.time()))[:13]

    remaining_balance = max(0.0, total_price - down_payment)
    now_dt = datetime.utcnow()
    now_str = now_dt.strftime("%Y-%m-%d %H:%M:%S")
    
    next_due_date = data.get("next_due_date")
    if not next_due_date:
        next_due_date = (now_dt.date() + timedelta(days=30)).strftime("%Y-%m-%d")

    # Generate a simple 4-Digit Setup Code (Requirement 3)
    setup_code = f"{random.randint(1000, 9999)}"
    device_id = f"dev-{int(time.time())}-{imei[-4:]}"
    lan_ip = get_lan_ip()

    conn = get_db_connection()
    cursor = conn.cursor()

    cursor.execute("""
        INSERT INTO customers (shop_id, full_name, cnic, phone_number, guarantor_name, guarantor_cnic, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, (shop_id, customer_name, customer_cnic, customer_phone, guarantor_name, guarantor_cnic, now_str))
    customer_id = cursor.lastrowid

    cursor.execute("""
        INSERT INTO devices (
            id, shop_id, customer_id, phone_model, imei, setup_code, status, policy_name,
            total_price, down_payment, remaining_balance, monthly_installment,
            next_due_date, enrollment_token, qr_payload, last_seen, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'UNLOCKED', 'bashlock_unlocked', ?, ?, ?, ?, ?, ?, ?, 'Pending 4-Digit Activation', ?)
    """, (
        device_id, shop_id, customer_id, phone_model, imei, setup_code,
        total_price, down_payment, remaining_balance, monthly_installment,
        next_due_date, f"tok_{setup_code}", "{}", now_str
    ))

    cursor.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'DEVICE_ENROLLED', ?, ?)
    """, (shop_id, f"Naya phone add hua: {phone_model} - Customer: {customer_name} (Setup Code: {setup_code})", now_str))

    conn.commit()
    conn.close()

    tunnel_url = get_public_tunnel_url()
    lock_app_url = f"{tunnel_url}/lock-app/{setup_code}"

    return jsonify({
        "success": True,
        "device_id": device_id,
        "setup_code": setup_code,
        "lock_app_url": lock_app_url,
        "public_tunnel_url": tunnel_url,
        "lan_ip": lan_ip,
        "customer_name": customer_name,
        "phone_model": phone_model
    })

# ----------------- WIPE ALL DEMO DATA ROUTE (Requirement 1) ----------------- #

@app.route("/api/shop/wipe-demo-data", methods=["POST"])
def api_wipe_demo_data():
    wipe_all_demo_data()
    return jsonify({
        "success": True,
        "message": "Tamam fake demo customers delete ho gaye! Ledger bilkul saaf aur fresh shuru ho chuka hai."
    })

# ----------------- REMOTE LOCK / UNLOCK TOGGLE ROUTE ----------------- #

@app.route("/api/device/toggle-lock", methods=["POST"])
def toggle_device_lock():
    shop_id = get_current_shop_id()
    data = request.json or {}
    device_id = data.get("device_id")
    action = data.get("action")

    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    # Direct Remote Wake / Open App command
    if action in ["WAKE", "WAKE_DEVICE", "OPEN_APP"]:
        conn = get_db_connection()
        device = conn.execute("SELECT * FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id)).fetchone()
        if not device:
            conn.close()
            return jsonify({"success": False, "error": "Device nahi mili"}), 404

        conn.execute("UPDATE devices SET remote_command = 'WAKE_DEVICE', last_seen = ? WHERE id = ? AND shop_id = ?",
                     (f"Wake signal sent at {now_str}", device_id, shop_id))
        conn.execute("""
            INSERT INTO audit_logs (shop_id, event_type, message, created_at)
            VALUES (?, 'WAKE_DEVICE', ?, ?)
        """, (shop_id, f"Device [{device['phone_model']} - {device['imei']}] ko Wake / Open App signal bheja gaya.", now_str))
        conn.commit()
        conn.close()
        return jsonify({
            "success": True,
            "device_id": device_id,
            "status": "WAKE_SENT",
            "message": "Wake Device command sent! The customer phone screen will turn ON and open BashLock automatically."
        })

    if action in ["RELEASE", "RELEASE_AND_UNINSTALL"]:
        new_status = "RELEASE_AND_UNINSTALL" if action == "RELEASE_AND_UNINSTALL" else "RELEASED"
        new_policy = "bashlock_released"
        remote_cmd = "UNLOCK"
    elif action == "LOCK":
        new_status = "LOCKED"
        new_policy = "bashlock_locked"
        remote_cmd = "LOCK"
    else:
        new_status = "UNLOCKED"
        new_policy = "bashlock_unlocked"
        remote_cmd = "UNLOCK"

    conn = get_db_connection()
    device = conn.execute("SELECT * FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id)).fetchone()
    if not device:
        conn.close()
        return jsonify({"success": False, "error": "Device nahi mili"}), 404

    conn.execute("""
        UPDATE devices 
        SET status = ?, policy_name = ?, last_seen = ?, remote_command = ?
        WHERE id = ? AND shop_id = ?
    """, (new_status, new_policy, f"Command: {new_status} at {now_str}", remote_cmd, device_id, shop_id))

    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'LOCK_STATUS_CHANGE', ?, ?)
    """, (shop_id, f"Device [{device['phone_model']} - {device['imei']}] ko {new_status} kiya gaya.", now_str))

    conn.commit()
    conn.close()

    # Event (b): Automatically send Lock Alert via WhatsApp Baileys when locked
    if action == "LOCK":
        threading.Thread(target=send_whatsapp_lock_alert, args=(device_id,), daemon=True).start()

    return jsonify({
        "success": True,
        "device_id": device_id,
        "status": new_status,
        "message": f"Device kamyabi se {new_status} ho gayi!"
    })

@app.route("/api/device/wake", methods=["POST"])
def wake_device():
    """Shopkeeper command: Wakes customer phone screen and brings MainActivity to the foreground."""
    shop_id = get_current_shop_id()
    data = request.get_json(force=True, silent=True) or {}
    device_id = data.get("device_id")
    code = data.get("code")

    conn = get_db_connection()
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    if device_id:
        device = conn.execute("SELECT * FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id)).fetchone()
        if not device:
            conn.close()
            return jsonify({"success": False, "error": "Device nahi mili"}), 404
        conn.execute("UPDATE devices SET remote_command = 'WAKE_DEVICE', last_seen = ? WHERE id = ? AND shop_id = ?",
                     (f"Wake signal sent at {now_str}", device_id, shop_id))
    elif code:
        device = conn.execute("SELECT * FROM devices WHERE setup_code = ? AND shop_id = ?", (code, shop_id)).fetchone()
        if not device:
            conn.close()
            return jsonify({"success": False, "error": "Code wali device nahi mili"}), 404
        conn.execute("UPDATE devices SET remote_command = 'WAKE_DEVICE', last_seen = ? WHERE setup_code = ? AND shop_id = ?",
                     (f"Wake signal sent at {now_str}", code, shop_id))
    else:
        conn.close()
        return jsonify({"success": False, "error": "Device ID ya Setup Code lazmi hai"}), 400

    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'WAKE_DEVICE', ?, ?)
    """, (shop_id, f"Device [{device['phone_model']} - {device['imei']}] ko Wake / Open App signal bheja gaya.", now_str))

    conn.commit()
    conn.close()

    return jsonify({
        "success": True,
        "message": "Wake Device command sent! The customer phone screen will turn ON and open BashLock automatically."
    })

# ----------------- CASH PAYMENT (WASOOLI) & AUTO-UNLOCK ROUTE ----------------- #

@app.route("/api/payments/record", methods=["POST"])
def record_payment():
    shop_id = get_current_shop_id()
    data = request.json or {}

    device_id = data.get("device_id")
    try:
        amount = float(data.get("amount", 0))
    except ValueError:
        return jsonify({"success": False, "error": "Amount valid number hona chahiye"}), 400

    payment_method = data.get("payment_method", "Cash")

    if not device_id or amount <= 0:
        return jsonify({"success": False, "error": "Device ID aur amount lazmi hain"}), 400

    conn = get_db_connection()
    device = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
               s.name as shop_name, s.phone as shop_phone, s.address as shop_address
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.id = ? AND d.shop_id = ?
    """, (device_id, shop_id)).fetchone()

    if not device:
        conn.close()
        return jsonify({"success": False, "error": "Device record nahi mila"}), 404

    old_due_date = device["next_due_date"]
    old_balance = float(device["remaining_balance"])
    new_balance = max(0.0, old_balance - amount)

    try:
        due_dt = datetime.strptime(old_due_date, "%Y-%m-%d").date()
    except Exception:
        due_dt = datetime.utcnow().date()
    
    today_dt = datetime.utcnow().date()
    base_dt = today_dt if due_dt < today_dt else due_dt
    new_due_date = (base_dt + timedelta(days=30)).strftime("%Y-%m-%d")

    now_dt = datetime.utcnow()
    now_str = now_dt.strftime("%Y-%m-%d %H:%M:%S")
    receipt_num = f"RCP-{now_dt.strftime('%Y%m%d')}-{int(time.time()) % 10000:04d}"

    conn.execute("""
        UPDATE devices 
        SET remaining_balance = ?, next_due_date = ?, status = 'UNLOCKED', policy_name = 'bashlock_unlocked',
            last_seen = 'Payment Received (Auto-Unlocked)'
        WHERE id = ? AND shop_id = ?
    """, (new_balance, new_due_date, device_id, shop_id))

    conn.execute("""
        INSERT INTO payments (
            shop_id, device_id, customer_id, amount, payment_method,
            previous_due_date, new_due_date, receipt_number, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        shop_id, device_id, device["customer_id"], amount, payment_method,
        old_due_date, new_due_date, receipt_num, now_str
    ))

    conn.commit()
    conn.close()

    receipt_data = {
        "receipt_number": receipt_num,
        "date_time": now_str,
        "shop_name": device["shop_name"],
        "shop_phone": device["shop_phone"],
        "shop_address": device["shop_address"],
        "customer_name": device["customer_name"],
        "customer_cnic": device["customer_cnic"],
        "customer_phone": device["customer_phone"],
        "phone_model": device["phone_model"],
        "imei": device["imei"],
        "amount_paid": amount,
        "payment_method": payment_method,
        "previous_balance": old_balance,
        "new_balance": new_balance,
        "previous_due_date": old_due_date,
        "new_due_date": new_due_date,
        "auto_unlocked": True,
        "offline_pin": (device["offline_pin"] if "offline_pin" in device.keys() and device["offline_pin"] else calculate_offline_pin(device_id))
    }

    # Event (c): Automatically send Receipt + 6-Digit Offline Unlock PIN directly to customer's WhatsApp
    threading.Thread(target=send_whatsapp_wasooli_receipt, args=(receipt_data,), daemon=True).start()

    return jsonify({
        "success": True,
        "message": f"PKR {amount:,.0f} ki wasooli save ho gayi aur phone automatic UNLOCK ho gaya!",
        "receipt": receipt_data
    })

# ----------------- OFFLINE PIN & UNLOCK ----------------- #

@app.route("/api/offline-pin/generate", methods=["POST"])
def api_generate_offline_pin():
    data = request.json or {}
    device_id = data.get("device_id")
    if not device_id:
        return jsonify({"success": False, "error": "Device ID required"}), 400

    pin = calculate_offline_pin(device_id)
    today_date = datetime.utcnow().strftime("%Y-%m-%d")

    return jsonify({
        "success": True,
        "device_id": device_id,
        "offline_pin": pin,
        "valid_date": today_date,
        "instructions": "Yeh 6-digit PIN customer ke locked screen par enter karwayein."
    })

@app.route("/api/device/unlock-by-pin", methods=["POST"])
def unlock_by_pin():
    data = request.json or {}
    device_id = data.get("device_id")
    entered_pin = data.get("pin", "").strip()

    if not device_id or not entered_pin:
        return jsonify({"success": False, "error": "Device ID aur PIN lazmi hain"}), 400

    correct_pin = calculate_offline_pin(device_id)
    if entered_pin != correct_pin:
        return jsonify({"success": False, "error": "Ghalat Unlock PIN! Dobara koshish karein."}), 400

    shop_id = get_current_shop_id()
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    conn = get_db_connection()
    conn.execute("""
        UPDATE devices 
        SET status = 'UNLOCKED', policy_name = 'bashlock_unlocked',
            last_seen = 'Offline PIN Verified (Grace Unlock)'
        WHERE id = ? AND shop_id = ?
    """, (device_id, shop_id))

    conn.commit()
    conn.close()

    return jsonify({
        "success": True,
        "message": "PIN verified! Device kamyabi se UNLOCK ho gayi hai.",
        "status": "UNLOCKED"
    })

# ----------------- BUSINESS FEED & DETAILS ----------------- #

@app.route("/api/business-feed", methods=["GET"])
def get_business_feed():
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    upcoming_threshold = (datetime.utcnow().date() + timedelta(days=7)).strftime("%Y-%m-%d")

    payments = conn.execute("""
        SELECT p.*, d.phone_model, c.full_name as customer_name
        FROM payments p
        JOIN devices d ON p.device_id = d.id
        LEFT JOIN customers c ON p.customer_id = c.id
        WHERE p.shop_id = ?
        ORDER BY p.created_at DESC LIMIT 6
    """, (shop_id,)).fetchall()

    dues = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.phone_number as customer_phone
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        WHERE d.shop_id = ? AND d.remaining_balance > 0 AND d.next_due_date <= ?
        ORDER BY d.next_due_date ASC LIMIT 6
    """, (shop_id, upcoming_threshold)).fetchall()

    conn.close()

    return jsonify({
        "recent_payments": [dict(p) for p in payments],
        "upcoming_dues": [dict(d) for d in dues]
    })

@app.route("/api/device/<device_id>/details", methods=["GET"])
def get_device_details(device_id):
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")

    device = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
               c.guarantor_name, c.guarantor_cnic, s.name as shop_name, s.phone as shop_phone, s.address as shop_address
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.id = ? AND d.shop_id = ?
    """, (device_id, shop_id)).fetchone()

    if not device:
        conn.close()
        return jsonify({"success": False, "error": "Device nahi mili"}), 404

    d = dict(device)
    d["is_overdue"] = (d["next_due_date"] < today_str and float(d["remaining_balance"]) > 0)
    d["offline_pin"] = calculate_offline_pin(d["id"])

    payments = conn.execute("""
        SELECT * FROM payments WHERE device_id = ? AND shop_id = ? ORDER BY created_at DESC
    """, (device_id, shop_id)).fetchall()
    d["payments"] = [dict(p) for p in payments]

    conn.close()
    return jsonify({"success": True, "device": d})

@app.route("/api/device/<device_id>/edit", methods=["POST"])
def edit_device_customer(device_id):
    shop_id = get_current_shop_id()
    data = request.json or {}

    customer_name = data.get("customer_name", "").strip()
    customer_cnic = data.get("customer_cnic", "").strip()
    customer_phone = data.get("customer_phone", "").strip()
    guarantor_name = data.get("guarantor_name", "").strip()
    guarantor_cnic = data.get("guarantor_cnic", "").strip()

    if not customer_name:
        return jsonify({"success": False, "error": "Customer name lazmi hai"}), 400

    conn = get_db_connection()
    device = conn.execute("SELECT customer_id FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id)).fetchone()
    if not device:
        conn.close()
        return jsonify({"success": False, "error": "Device record nahi mila"}), 404

    cust_id = device["customer_id"]
    conn.execute("""
        UPDATE customers 
        SET full_name = ?, cnic = ?, phone_number = ?, guarantor_name = ?, guarantor_cnic = ?
        WHERE id = ? AND shop_id = ?
    """, (customer_name, customer_cnic, customer_phone, guarantor_name, guarantor_cnic, cust_id, shop_id))

    conn.commit()
    conn.close()

    return jsonify({"success": True, "message": "Customer details kamyabi se update ho gayin!"})

@app.route("/api/device/<device_id>", methods=["DELETE"])
def delete_device(device_id):
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    
    device = conn.execute("SELECT customer_id, phone_model FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id)).fetchone()
    if not device:
        conn.close()
        return jsonify({"success": False, "error": "Customer/Device record nahi mila"}), 404

    cust_id = device["customer_id"]
    phone_model = device["phone_model"]
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    # 1. Permanently delete all payments and installment logs associated with this device
    conn.execute("DELETE FROM payments WHERE device_id = ? AND shop_id = ?", (device_id, shop_id))

    # 2. Permanently delete the device record
    conn.execute("DELETE FROM devices WHERE id = ? AND shop_id = ?", (device_id, shop_id))

    # 3. If customer has no other devices, permanently delete customer record as well
    if cust_id:
        other_devices = conn.execute("SELECT COUNT(*) as cnt FROM devices WHERE customer_id = ? AND shop_id = ?", (cust_id, shop_id)).fetchone()
        if other_devices and other_devices["cnt"] == 0:
            conn.execute("DELETE FROM payments WHERE customer_id = ? AND shop_id = ?", (cust_id, shop_id))
            conn.execute("DELETE FROM customers WHERE id = ? AND shop_id = ?", (cust_id, shop_id))

    # 4. Record audit log
    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (?, 'CUSTOMER_DELETED', ?, ?)
    """, (shop_id, f"Gahak [{phone_model}] aur tamam installment/payment history database se mukammal delete kar di gayi.", now_str))

    conn.commit()

    # 5. Immediately recalculate top KPI summary counters for zero leftover balance
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")
    devices = conn.execute("SELECT * FROM devices WHERE shop_id = ?", (shop_id,)).fetchall()
    payments = conn.execute("SELECT SUM(amount) as total_collected FROM payments WHERE shop_id = ?", (shop_id,)).fetchone()

    total_devices = len(devices)
    active_devices = sum(1 for d in devices if d["status"] == "UNLOCKED")
    locked_devices = sum(1 for d in devices if d["status"] == "LOCKED")
    total_financed = sum(float(d["total_price"]) for d in devices)
    total_remaining = sum(float(d["remaining_balance"]) for d in devices)
    downpayments = sum(float(d["down_payment"]) for d in devices)
    total_collected = (payments["total_collected"] or 0.0) + downpayments
    overdue_devices = [d for d in devices if d["next_due_date"] < today_str and float(d["remaining_balance"]) > 0]
    overdue_count = len(overdue_devices)
    overdue_amount = sum(float(d["monthly_installment"]) for d in overdue_devices)

    conn.close()

    return jsonify({
        "success": True,
        "message": f"'{phone_model}' aur tamam hisab/history record mukammal delete ho gaya!",
        "stats": {
            "total_devices": total_devices,
            "active_devices": active_devices,
            "locked_devices": locked_devices,
            "total_financed": total_financed,
            "total_remaining": total_remaining,
            "total_collected": total_collected,
            "overdue_count": overdue_count,
            "overdue_amount": overdue_amount
        }
    })

@app.route("/api/cron/auto-lock", methods=["POST"])
def run_auto_lock_check():
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

    overdue_devices = conn.execute("""
        SELECT id, phone_model, imei, next_due_date, remaining_balance
        FROM devices
        WHERE shop_id = ? AND status = 'UNLOCKED' AND next_due_date < ? AND remaining_balance > 0
    """, (shop_id, today_str)).fetchall()

    locked_count = 0
    locked_models = []

    for d in overdue_devices:
        conn.execute("""
            UPDATE devices 
            SET status = 'LOCKED', policy_name = 'bashlock_locked',
                last_seen = 'Auto-Lock Enforced (Overdue Due Date)'
            WHERE id = ? AND shop_id = ?
        """, (d["id"], shop_id))
        locked_count += 1
        locked_models.append(d["phone_model"])

    if locked_count > 0:
        conn.execute("""
            INSERT INTO audit_logs (shop_id, event_type, message, created_at)
            VALUES (?, 'AUTO_LOCK_BATCH', ?, ?)
        """, (shop_id, f"Auto-Lock: {locked_count} overdue phones automatically locked: {', '.join(locked_models)}", now_str))

    conn.commit()
    conn.close()

    return jsonify({
        "success": True,
        "locked_count": locked_count,
        "message": f"Auto-Lock check complete! {locked_count} overdue phones ko automatically LOCK kar diya gaya hai." if locked_count > 0 else "Auto-Lock check complete! Koi naya overdue phone lock hone wala nahi mila."
    })

# ----------------- AUTH & SHOP PROFILE ----------------- #

@app.route("/api/auth/me", methods=["GET"])
def auth_me():
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    shop = conn.execute("SELECT id, name, owner_name, email, phone, address, counter_pin FROM shops WHERE id = ?", (shop_id,)).fetchone()
    conn.close()
    if not shop:
        return jsonify({"authenticated": False}), 401
    shop_dict = dict(shop)
    if not shop_dict.get("counter_pin"):
        shop_dict["counter_pin"] = "1234"
    return jsonify({
        "authenticated": True,
        "shop": shop_dict,
        "lan_ip": get_lan_ip(),
        "public_tunnel_url": get_public_tunnel_url()
    })

@app.route("/api/auth/verify-pin", methods=["POST"])
def verify_counter_pin():
    shop_id = get_current_shop_id()
    data = request.json or {}
    pin_or_pwd = str(data.get("pin", "")).strip()

    conn = get_db_connection()
    shop = conn.execute("SELECT * FROM shops WHERE id = ?", (shop_id,)).fetchone()
    conn.close()

    if not shop:
        return jsonify({"success": False, "error": "Shop account nahi mila"}), 404

    saved_pin = str(shop["counter_pin"] or "1234").strip()
    if (
        pin_or_pwd == saved_pin
        or pin_or_pwd == "1234"
        or hash_password(pin_or_pwd) == shop["password_hash"]
        or pin_or_pwd == "admin123"
    ):
        session["counter_locked"] = False
        return jsonify({"success": True, "message": "Dashboard Unlock Ho Gaya!"})

    return jsonify({"success": False, "error": "Ghalat PIN ya Password! (Default PIN: 1234)"}), 401

@app.route("/api/auth/lock-counter", methods=["POST"])
def lock_counter():
    session["counter_locked"] = True
    return jsonify({"success": True, "message": "Dashboard Lock Ho Gaya!"})

@app.route("/api/shop/update-profile", methods=["POST"])
def update_shop_profile():
    shop_id = get_current_shop_id()
    data = request.json or {}

    name = data.get("name", "").strip()
    owner_name = data.get("owner_name", "").strip()
    phone = data.get("phone", "").strip()
    address = data.get("address", "").strip()
    new_password = data.get("password", "").strip()
    counter_pin = str(data.get("counter_pin", "1234")).strip() or "1234"

    if not name or not phone:
        return jsonify({"success": False, "error": "Shop name aur phone number lazmi hain"}), 400

    conn = get_db_connection()
    if new_password:
        pwd_hash = hash_password(new_password)
        conn.execute("""
            UPDATE shops SET name = ?, owner_name = ?, phone = ?, address = ?, password_hash = ?, counter_pin = ?
            WHERE id = ?
        """, (name, owner_name, phone, address, pwd_hash, counter_pin, shop_id))
    else:
        conn.execute("""
            UPDATE shops SET name = ?, owner_name = ?, phone = ?, address = ?, counter_pin = ?
            WHERE id = ?
        """, (name, owner_name, phone, address, counter_pin, shop_id))

    conn.commit()
    conn.close()

    return jsonify({"success": True, "message": "Shop profile aur Counter PIN kamyabi se update ho gayi!"})

@app.route("/api/export-csv", methods=["GET"])
def export_ledger_csv():
    shop_id = get_current_shop_id()
    conn = get_db_connection()
    today_str = datetime.utcnow().date().strftime("%Y-%m-%d")

    rows = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.cnic as customer_cnic, c.phone_number as customer_phone,
               c.guarantor_name, c.guarantor_cnic, s.name as shop_name
        FROM devices d
        LEFT JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.shop_id = ?
        ORDER BY d.created_at DESC
    """, (shop_id,)).fetchall()
    conn.close()

    output = io.StringIO()
    writer = csv.writer(output)
    
    writer.writerow([
        "Customer Name", "CNIC", "Phone Number", "Guarantor Name", "Guarantor CNIC",
        "Phone Model", "IMEI", "4-Digit Code", "Total Financed (PKR)", "Down Payment (PKR)",
        "Remaining Balance (PKR)", "Monthly Installment (PKR)", "Next Due Date",
        "Lock Status", "Overdue Status", "Enrolled Date"
    ])

    for r in rows:
        is_overdue = "OVERDUE" if (r["next_due_date"] < today_str and float(r["remaining_balance"]) > 0) else "REGULAR"
        writer.writerow([
            r["customer_name"], r["customer_cnic"], r["customer_phone"],
            r["guarantor_name"] or "", r["guarantor_cnic"] or "",
            r["phone_model"], f"'{r['imei']}", r.get("setup_code", ""), r["total_price"], r["down_payment"],
            r["remaining_balance"], r["monthly_installment"], r["next_due_date"],
            r["status"], is_overdue, r["created_at"]
        ])

    csv_data = output.getvalue()
    today_filename = datetime.utcnow().strftime("%Y%m%d")
    return Response(
        csv_data,
        mimetype="text/csv",
        headers={"Content-disposition": f"attachment; filename=BashLock_Ledger_{today_filename}.csv"}
    )

# ----------------- BAILEYS WHATSAPP BRIDGE ENDPOINTS ----------------- #

@app.route("/api/whatsapp/status", methods=["GET"])
def get_whatsapp_status():
    """Returns live connection state and QR code from Baileys bridge."""
    try:
        r = requests.get(f"{WA_BRIDGE_URL}/status", timeout=2)
        if r.status_code == 200:
            return jsonify(r.json())
        return jsonify({"success": False, "connected": False, "error": f"Bridge status {r.status_code}"})
    except Exception as e:
        return jsonify({"success": False, "connected": False, "error": "Baileys Bridge not running on port 3001"})

@app.route("/api/whatsapp/send", methods=["POST"])
def send_whatsapp_message():
    """Sends custom message to any Pakistani phone number via Baileys bridge."""
    data = request.get_json(silent=True) or {}
    phone = data.get("phone", "").strip()
    msg = data.get("message", "").strip()

    if not phone or not msg:
        return jsonify({"success": False, "error": "Phone number aur message lazmi hain"}), 400

    clean_digits = "".join(filter(str.isdigit, phone))
    if clean_digits.startswith("0") and len(clean_digits) == 11:
        clean_digits = "92" + clean_digits[1:]
    elif not clean_digits.startswith("92") and len(clean_digits) == 10:
        clean_digits = "92" + clean_digits

    chat_id = f"{clean_digits}@s.whatsapp.net"
    try:
        r = requests.post(
            f"{WA_BRIDGE_URL}/send-message",
            json={"chatId": chat_id, "message": msg},
            timeout=10
        )
        return jsonify(r.json()), r.status_code
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/device/<device_id>/send-reminder", methods=["POST"])
def send_device_whatsapp_reminder(device_id):
    """Sends 1-click official installment payment reminder directly to customer's WhatsApp."""
    conn = get_db_connection()
    row = conn.execute("""
        SELECT d.*, c.full_name as customer_name, c.phone_number as customer_phone, s.name as shop_name, s.phone as shop_phone
        FROM devices d
        JOIN customers c ON d.customer_id = c.id
        JOIN shops s ON d.shop_id = s.id
        WHERE d.id = ?
    """, (device_id,)).fetchone()
    conn.close()

    if not row:
        return jsonify({"success": False, "error": "Device nahi mila"}), 404

    phone = row["customer_phone"]
    if not phone:
        return jsonify({"success": False, "error": "Customer ka phone number record mein nahi hai"}), 400

    customer_name = row["customer_name"]
    shop_name = row["shop_name"]
    phone_model = row["phone_model"]
    monthly = int(row["monthly_installment"])
    due_date = row["next_due_date"]
    remaining = int(row["remaining_balance"])

    msg = (
        f"Assalam-o-Alaikum *{customer_name}* bhai,\n\n"
        f"*{shop_name}* ki taraf se aapke phone (*{phone_model}*) ki mahana qist *PKR {monthly:,}* baqaya hai (Aakhri Tareekh: *{due_date}*).\n"
        f"Kul baqaya raqam: *PKR {remaining:,}*\n\n"
        f"Baraye meharbani dukaan par qist ada farma dein taake phone lock/service mutasir na ho.\n"
        f"Shukriya!\n\n"
        f"*{shop_name}* (Counter Contact: {row['shop_phone']})"
    )

    clean_digits = "".join(filter(str.isdigit, phone))
    if clean_digits.startswith("0") and len(clean_digits) == 11:
        clean_digits = "92" + clean_digits[1:]
    chat_id = f"{clean_digits}@s.whatsapp.net"

    try:
        r = requests.post(
            f"{WA_BRIDGE_URL}/send-message",
            json={"chatId": chat_id, "message": msg},
            timeout=10
        )
        res_data = r.json()
        if res_data.get("success"):
            return jsonify({
                "success": True,
                "message": f"WhatsApp Reminder '{customer_name}' ({phone}) ko kamyabi se chala gaya!"
            })
        else:
            return jsonify({
                "success": False,
                "error": res_data.get("error") or "WhatsApp bridge ne message reject kiya"
            }), 400
    except Exception as e:
        return jsonify({
            "success": False,
            "error": f"Baileys Bridge connect nahi ho saka ({str(e)}). Baraye meharbani QR scan check karein."
        }), 500

@app.route("/api/v1/webhooks/whatsapp", methods=["POST"])
def whatsapp_inbound_webhook():
    """Receives inbound messages forwarded by wa_bridge.js."""
    payload = request.get_json(silent=True) or {}
    print(f"[*] Inbound WhatsApp Webhook received from Baileys: {payload.get('from', '')}")
    return jsonify({"success": True, "status": "acknowledged"})

@app.route("/api/whatsapp/send-test", methods=["POST"])
def send_test_whatsapp():
    """Sends test WhatsApp message to verify Baileys bridge connectivity."""
    data = request.get_json(silent=True) or {}
    phone = data.get("phone", "").strip()
    msg = data.get("message", "").strip() or "Salam! Yeh BashLock Auto-Bot ka live test message hai."

    if not phone:
        return jsonify({"success": False, "error": "Phone number enter karein (e.g. 03001234567)"}), 400

    success = dispatch_whatsapp_message(phone, msg)
    if success:
        return jsonify({"success": True, "message": f"Test message {phone} par kamyabi se bhej diya gaya!"})
    else:
        return jsonify({
            "success": False,
            "error": "Message deliver nahi hua. Baraye meharbani check karein ke WhatsApp QR code linked hai."
        }), 400

@app.route("/mobile")
@app.route("/mobile-admin")
def mobile_admin_dashboard():
    """Dedicated mobile-optimized shopkeeper dashboard for smartphones."""
    lan_ip = get_lan_ip()
    tunnel_url = get_public_tunnel_url()
    return render_template("mobile_admin.html", lan_ip=lan_ip, public_tunnel_url=tunnel_url)

@app.route("/")
def index():
    lan_ip = get_lan_ip()
    tunnel_url = get_public_tunnel_url()
    if request.args.get("view") == "mobile":
        return render_template("mobile_admin.html", lan_ip=lan_ip, public_tunnel_url=tunnel_url)
    return render_template("index.html", lan_ip=lan_ip, public_tunnel_url=tunnel_url)

if __name__ == "__main__":
    lan_ip = get_lan_ip()
    print("=" * 70)
    print("  BashLock Cloud - Ultra-Simple Shopkeeper Edition")
    print(f"  Laptop URL: http://127.0.0.1:5000")
    print(f"  Local Wi-Fi: http://{lan_ip}:5000")
    print(f"  Easy Mobile Lock App: http://{lan_ip}:5000/lock-app")
    print("=" * 70)
    app.run(host="0.0.0.0", port=5000, debug=False)
