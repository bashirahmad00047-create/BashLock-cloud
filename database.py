import sqlite3
import os
import hashlib
from datetime import datetime

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bashlock.db")

def get_db_connection():
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA busy_timeout = 30000;")
    conn.execute("PRAGMA foreign_keys = ON;")
    return conn

def hash_password(password: str) -> str:
    return hashlib.sha256(password.encode("utf-8")).hexdigest()

def init_db():
    conn = get_db_connection()
    cursor = conn.cursor()

    cursor.executescript("""
    CREATE TABLE IF NOT EXISTS shops (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        owner_name TEXT NOT NULL,
        email TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        phone TEXT NOT NULL,
        address TEXT NOT NULL,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS customers (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
        full_name TEXT NOT NULL,
        cnic TEXT NOT NULL,
        phone_number TEXT NOT NULL,
        guarantor_name TEXT,
        guarantor_cnic TEXT,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
        customer_id INTEGER REFERENCES customers(id) ON DELETE SET NULL,
        phone_model TEXT NOT NULL,
        imei TEXT NOT NULL,
        setup_code TEXT,
        status TEXT NOT NULL DEFAULT 'UNLOCKED',
        policy_name TEXT NOT NULL DEFAULT 'bashlock_unlocked',
        total_price REAL NOT NULL,
        down_payment REAL NOT NULL,
        remaining_balance REAL NOT NULL,
        monthly_installment REAL NOT NULL,
        next_due_date TEXT NOT NULL,
        enrollment_token TEXT,
        qr_payload TEXT,
        last_seen TEXT NOT NULL,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS payments (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        customer_id INTEGER REFERENCES customers(id) ON DELETE SET NULL,
        amount REAL NOT NULL,
        payment_method TEXT NOT NULL DEFAULT 'Cash',
        previous_due_date TEXT,
        new_due_date TEXT,
        receipt_number TEXT UNIQUE NOT NULL,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS audit_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        shop_id INTEGER REFERENCES shops(id) ON DELETE CASCADE,
        event_type TEXT NOT NULL,
        message TEXT NOT NULL,
        created_at TEXT NOT NULL
    );
    """)

    # Ensure setup_code column exists
    try:
        cursor.execute("ALTER TABLE devices ADD COLUMN setup_code TEXT")
    except sqlite3.OperationalError:
        pass

    # Ensure remote_command column exists
    try:
        cursor.execute("ALTER TABLE devices ADD COLUMN remote_command TEXT")
    except sqlite3.OperationalError:
        pass

    # Ensure enrollment lifecycle columns exist
    for col, col_type in [
        ("enrollment_status", "TEXT DEFAULT 'ACTIVATED'"),
        ("enrollment_expires_at", "TEXT"),
        ("customer_consent", "TEXT DEFAULT 'GRANTED'"),
        ("consent_timestamp", "TEXT"),
    ]:
        try:
            cursor.execute(f"ALTER TABLE devices ADD COLUMN {col} {col_type}")
        except sqlite3.OperationalError:
            pass

    # Ensure audit_logs detailed columns exist
    for col, col_type in [
        ("device_id", "TEXT"),
        ("customer_id", "INTEGER"),
        ("actor", "TEXT DEFAULT 'SHOPKEEPER'"),
        ("details", "TEXT"),
    ]:
        try:
            cursor.execute(f"ALTER TABLE audit_logs ADD COLUMN {col} {col_type}")
        except sqlite3.OperationalError:
            pass

    # Ensure setup_code has an index to prevent cross-talk and speed up lookups
    try:
        cursor.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_setup_code ON devices(setup_code) WHERE setup_code IS NOT NULL AND setup_code != ''")
    except sqlite3.OperationalError:
        pass

    try:
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_devices_imei ON devices(imei)")
    except sqlite3.OperationalError:
        pass

    # Ensure counter_pin column exists in shops table
    try:
        cursor.execute("ALTER TABLE shops ADD COLUMN counter_pin TEXT DEFAULT '1234'")
    except sqlite3.OperationalError:
        pass

    # Ensure default shop exists
    cursor.execute("SELECT id FROM shops WHERE email = ?", ("shop@bashlock.com",))
    shop = cursor.fetchone()
    if not shop:
        now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        cursor.execute("""
            INSERT INTO shops (name, owner_name, email, password_hash, phone, address, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (
            "Al-Madina Mobile Centre",
            "Haji Muhammad Aslam",
            "shop@bashlock.com",
            hash_password("admin123"),
            "0300-8451234",
            "Shop #14, Hall Road Commercial Market, Lahore",
            now_str
        ))

    conn.commit()
    conn.close()

def log_audit_event(shop_id: int, event_type: str, message: str, device_id: str = None, customer_id: int = None, actor: str = "SHOPKEEPER", details: dict = None, conn=None):
    """Inserts a structured, immutable audit log entry."""
    import json
    close_when_done = False
    try:
        if conn is None:
            conn = get_db_connection()
            close_when_done = True
        now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        details_str = json.dumps(details) if isinstance(details, dict) else (str(details) if details else None)
        conn.execute("""
            INSERT INTO audit_logs (shop_id, event_type, message, created_at, device_id, customer_id, actor, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (shop_id, event_type, message, now_str, device_id, customer_id, actor, details_str))
        if close_when_done:
            conn.commit()
            conn.close()
    except Exception as e:
        print(f"[!] log_audit_event error: {e}")
        if close_when_done and conn:
            try:
                conn.close()
            except Exception:
                pass

def wipe_all_demo_data():
    """Wipes all fake customers, devices, payments and audit logs so ledger starts completely clean."""
    conn = get_db_connection()
    conn.execute("DELETE FROM payments;")
    conn.execute("DELETE FROM devices;")
    conn.execute("DELETE FROM customers;")
    conn.execute("DELETE FROM audit_logs;")
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
    conn.execute("""
        INSERT INTO audit_logs (shop_id, event_type, message, created_at)
        VALUES (1, 'FRESH_CLEAN_START', 'Tamam fake demo customers delete ho gaye. Clean ledger shuru ho chuka hai.', ?)
    """, (now_str,))
    conn.commit()
    conn.close()

if __name__ == "__main__":
    init_db()
    wipe_all_demo_data()
    print("Database ready and all demo data wiped clean!")
