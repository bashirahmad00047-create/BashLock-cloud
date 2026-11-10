import urllib.request, json, sys, traceback

BASE_URL = "http://127.0.0.1:5000"
results = []

def record(test_name, success, detail):
    status = "PASS" if success else "FAIL"
    print(f"[{status}] {test_name}: {detail}")
    results.append({"test": test_name, "success": success, "detail": detail})

print("==================================================")
print("   RUNNING BASHLOCK CLOUD COMPLETE TEST SUITE     ")
print("==================================================")

# 0. Enroll Test Device
try:
    req = urllib.request.Request(f"{BASE_URL}/api/test/enroll-test-device", data=b"{}", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
    assert data["success"] is True
    device_id = data["device_id"]
    setup_code = data["setup_code"]
    record("Test Setup (Enroll Test Device)", True, f"Enrolled {device_id} with setup code {setup_code}")
except Exception as e:
    record("Test Setup (Enroll Test Device)", False, f"{e}\n{traceback.format_exc()}")
    sys.exit(1)

# Test A & B: POST /api/pair with valid code and verify response
try:
    pair_payload = json.dumps({
        "setup_code": setup_code,
        "phone_model": "Galaxy S24 (Test Lab)",
        "imei": "861234567890047"
    }).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/pair", data=pair_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        assert resp.status == 200
        pair_data = json.loads(resp.read().decode())
    assert pair_data.get("success") is True
    dev = pair_data.get("device", {})
    assert dev.get("id") == device_id
    assert dev.get("setup_code") == setup_code
    record("Test A & B (Pairing & Complete Device Info)", True, f"HTTP 200, success=True, device_id={dev.get('id')}, status={dev.get('status')}")
except Exception as e:
    record("Test A & B (Pairing & Complete Device Info)", False, f"{e}\n{traceback.format_exc()}")

# Test C: Confirm dashboard returns device via GET /api/devices
try:
    with urllib.request.urlopen(f"{BASE_URL}/api/devices") as resp:
        devs_data = json.loads(resp.read().decode())
    devices = devs_data.get("devices", [])
    found = any(d.get("id") == device_id for d in devices)
    assert found is True
    record("Test C (Dashboard GET /api/devices confirmation)", True, f"Found device {device_id} in {len(devices)} enrolled devices")
except Exception as e:
    record("Test C (Dashboard GET /api/devices confirmation)", False, f"{e}\n{traceback.format_exc()}")

# Test D: Remote Band (LOCK)
try:
    lock_payload = json.dumps({"device_id": device_id, "action": "LOCK"}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/device/toggle-lock", data=lock_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        lock_resp = json.loads(resp.read().decode())
    assert lock_resp.get("success") is True
    
    # Check device status polling endpoint
    with urllib.request.urlopen(f"{BASE_URL}/api/device/by-code/{setup_code}/status") as resp:
        status_data = json.loads(resp.read().decode())
    top_status = status_data.get("status", "").upper()
    cmd = status_data.get("remote_command", "").upper()
    assert top_status in ["LOCKED"] or cmd == "LOCK"
    record("Test D (Remote Band / LOCK Action)", True, f"Server confirmed: status={top_status}, remote_command={cmd}")
except Exception as e:
    record("Test D (Remote Band / LOCK Action)", False, f"{e}\n{traceback.format_exc()}")

# Test E: Remote UNLOCK
try:
    unlock_payload = json.dumps({"device_id": device_id, "action": "UNLOCK"}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/device/toggle-lock", data=unlock_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        unlock_resp = json.loads(resp.read().decode())
    assert unlock_resp.get("success") is True
    
    with urllib.request.urlopen(f"{BASE_URL}/api/device/by-code/{setup_code}/status") as resp:
        status_data = json.loads(resp.read().decode())
    top_status = status_data.get("status", "").upper()
    cmd = status_data.get("remote_command", "").upper()
    assert top_status in ["UNLOCKED"] or cmd == "UNLOCK"
    record("Test E (Remote UNLOCK Action)", True, f"Server confirmed: status={top_status}, remote_command={cmd}")
except Exception as e:
    record("Test E (Remote UNLOCK Action)", False, f"{e}\n{traceback.format_exc()}")

# Test F: Payment / Wasooli recording
try:
    payment_payload = json.dumps({
        "device_id": device_id,
        "amount": 10000,
        "payment_method": "Cash",
        "notes": "Automated QA Installment 1"
    }).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/payments/record", data=payment_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        pay_resp = json.loads(resp.read().decode())
    assert pay_resp.get("success") is True
    new_bal = pay_resp.get("remaining_balance")
    assert new_bal == 30000
    
    with urllib.request.urlopen(f"{BASE_URL}/api/device/by-code/{setup_code}/status") as resp:
        status_data = json.loads(resp.read().decode())
    dev_info = status_data.get("device", {})
    assert dev_info.get("remaining_balance") == 30000
    assert status_data.get("remote_command") == "UNLOCK"
    record("Test F (Payment / Wasooli Recording)", True, f"Recorded 10,000 Rs. New balance: 30,000 Rs. Remote command: UNLOCK")
except Exception as e:
    record("Test F (Payment / Wasooli Recording)", False, f"{e}\n{traceback.format_exc()}")

# Test G: Verify NOC is NOT generated when remaining_balance > 0
try:
    with urllib.request.urlopen(f"{BASE_URL}/api/device/by-code/{setup_code}/status") as resp:
        status_data = json.loads(resp.read().decode())
    dev_info = status_data.get("device", {})
    rem_bal = dev_info.get("remaining_balance")
    status = dev_info.get("status")
    # NOC should NOT be present
    assert rem_bal > 0
    assert status != "RELEASED"
    record("Test G (No False NOC while balance > 0)", True, f"Balance is {rem_bal} Rs > 0. Device status is {status} (Active Khata, No False NOC).")
except Exception as e:
    record("Test G (No False NOC while balance > 0)", False, f"{e}\n{traceback.format_exc()}")

# Test H: Verify NOC IS generated when remaining_balance == 0
try:
    # Pay remaining 30,000
    payment_payload = json.dumps({
        "device_id": device_id,
        "amount": 30000,
        "payment_method": "Cash",
        "notes": "Full Clearance Payment"
    }).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/payments/record", data=payment_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        pay_resp = json.loads(resp.read().decode())
    assert pay_resp.get("success") is True
    assert pay_resp.get("remaining_balance") == 0
    
    with urllib.request.urlopen(f"{BASE_URL}/api/device/by-code/{setup_code}/status") as resp:
        status_data = json.loads(resp.read().decode())
    dev_info = status_data.get("device", {})
    assert dev_info.get("remaining_balance") == 0
    assert dev_info.get("status") in ["RELEASED", "UNLOCKED"]
    record("Test H (Legitimate NOC Clearance when balance == 0)", True, f"Balance is 0. Status: {dev_info.get('status')}. Remote Command: {status_data.get('remote_command')}")
except Exception as e:
    record("Test H (Legitimate NOC Clearance when balance == 0)", False, f"{e}\n{traceback.format_exc()}")

# Test I: Offline PIN & Recovery verification
try:
    # Request offline PIN generation
    pin_payload = json.dumps({"device_id": device_id}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/offline-pin/generate", data=pin_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        pin_resp = json.loads(resp.read().decode())
    assert pin_resp.get("success") is True
    generated_pin = pin_resp.get("offline_pin")
    assert len(str(generated_pin)) in [4, 6]
    
    # Verify PIN unlock endpoint
    verify_payload = json.dumps({"device_id": device_id, "pin": str(generated_pin)}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/device/unlock-by-pin", data=verify_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        verify_resp = json.loads(resp.read().decode())
    assert verify_resp.get("success") is True
    record("Test I (Offline Recovery PIN Generation & Unlock)", True, f"Generated OTP PIN {generated_pin} verified successfully.")
except Exception as e:
    record("Test I (Offline Recovery PIN Generation & Unlock)", False, f"{e}\n{traceback.format_exc()}")

print("==================================================")
all_pass = all(r["success"] for r in results)
print(f"OVERALL RESULT: {'ALL TESTS PASSED (9/9)' if all_pass else 'SOME TESTS FAILED'}")
print("==================================================")
if not all_pass:
    sys.exit(1)
