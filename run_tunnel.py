import os
import re
import sys
import time
import subprocess

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CLOUDFLARED_BIN = r"C:\Program Files (x86)\cloudflared\cloudflared.exe"
if not os.path.exists(CLOUDFLARED_BIN):
    CLOUDFLARED_BIN = "cloudflared"

def git_sync_live_url(new_url):
    """Saves new_url to live_url.txt and automatically pushes to GitHub main branch."""
    live_txt = os.path.join(BASE_DIR, "live_url.txt")
    tunnel_txt = os.path.join(BASE_DIR, "tunnel_url.txt")

    old_url = ""
    if os.path.exists(live_txt):
        try:
            with open(live_txt, "r") as f:
                old_url = f.read().strip()
        except Exception:
            pass

    if old_url == new_url:
        print(f"[*] live_url.txt is already up-to-date ({new_url})")
        return

    # Write new URL to files
    try:
        with open(live_txt, "w") as f:
            f.write(new_url)
        with open(tunnel_txt, "w") as f:
            f.write(new_url)
        print(f"[+] Updated live_url.txt & tunnel_url.txt -> {new_url}")
    except Exception as e:
        print(f"[!] Error writing URL files: {e}")
        return

    # Git commit and push
    try:
        print("[*] Committing and pushing live_url.txt to GitHub...")
        subprocess.run(["git", "add", "live_url.txt", "tunnel_url.txt"], cwd=BASE_DIR, check=True)
        # Using [skip ci] so GitHub Actions doesn't redundantly rebuild the APK on URL change
        commit_res = subprocess.run(
            ["git", "commit", "-m", f"Auto-update live_url.txt: {new_url} [skip ci]"],
            cwd=BASE_DIR,
            capture_output=True,
            text=True
        )
        print(commit_res.stdout.strip())
        push_res = subprocess.run(
            ["git", "push", "origin", "main"],
            cwd=BASE_DIR,
            capture_output=True,
            text=True
        )
        if push_res.returncode == 0:
            print("[✓] Successfully pushed live_url.txt to GitHub! All mobile APKs will now auto-resolve this link.")
        else:
            print(f"[!] Git push warning: {push_res.stderr.strip()}")
    except Exception as e:
        print(f"[!] Git sync error: {e}")

def run_tunnel():
    """Runs cloudflared tunnel and monitors stdout/stderr for quick-tunnel URLs."""
    cmd = [
        CLOUDFLARED_BIN,
        "tunnel",
        "--url", "http://127.0.0.1:5000",
        "--protocol", "http2",
        "--no-autoupdate"
    ]
    print("=" * 65)
    print("  BashLock Cloudflare Auto-Sync Tunnel Runner")
    print(f"  Target: http://127.0.0.1:5000")
    print("=" * 65)

    while True:
        try:
            print(f"[*] Starting Cloudflare tunnel: {' '.join(cmd)}")
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1
            )

            tunnel_url_pattern = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")
            found_url = False

            for line in proc.stdout:
                line_str = line.strip()
                if line_str:
                    print(f"  [cloudflared] {line_str}")

                if not found_url:
                    match = tunnel_url_pattern.search(line_str)
                    if match:
                        url = match.group(0)
                        found_url = True
                        print("\n" + "=" * 65)
                        print(f"  [★] LIVE CLOUDFLARE URL DETECTED: {url}")
                        print("=" * 65 + "\n")
                        git_sync_live_url(url)

            proc.wait()
            print("[!] cloudflared process exited. Restarting in 3 seconds...")
            time.sleep(3)
        except KeyboardInterrupt:
            print("\nStopping tunnel runner...")
            break
        except Exception as e:
            print(f"[!] Tunnel error: {e}. Retrying in 5 seconds...")
            time.sleep(5)

if __name__ == "__main__":
    run_tunnel()
