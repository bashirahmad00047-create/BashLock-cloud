import subprocess, urllib.request, zipfile, io, os, json, time, sys

def get_github_token():
    p = subprocess.Popen(['git', 'credential', 'fill'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate('protocol=https\nhost=github.com\n')
    for line in out.splitlines():
        if line.startswith('password='):
            return line.split('=', 1)[1]
    return None

token = get_github_token()
headers = {
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'BashLock-Downloader'
}
if token:
    headers['Authorization'] = f'Bearer {token}'

run_id = sys.argv[1] if len(sys.argv) > 1 else '42829387502'
print(f"Tracking workflow run {run_id}...", flush=True)

while True:
    try:
        req = urllib.request.Request(
            f'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/runs/{run_id}',
            headers=headers
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode())
        status = data.get('status')
        conclusion = data.get('conclusion')
        print(f"Run {run_id} status: {status}, conclusion: {conclusion}", flush=True)
        if status == 'completed':
            if conclusion != 'success':
                print(f"Workflow failed with conclusion: {conclusion}", flush=True)
                sys.exit(1)
            break
    except Exception as e:
        print(f"Poll notice: {e}, retrying in 5s...", flush=True)
    time.sleep(10)

print("Workflow completed successfully! Fetching artifacts...", flush=True)
while True:
    try:
        req = urllib.request.Request(
            f'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/runs/{run_id}/artifacts',
            headers=headers
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode())
        break
    except Exception as e:
        print(f"Artifact list retry: {e}", flush=True)
        time.sleep(5)

artifacts = data.get('artifacts', [])
if not artifacts:
    print("No artifacts found!", flush=True)
    sys.exit(1)

artifact = artifacts[0]
artifact_id = artifact['id']
name = artifact['name']
size = artifact['size_in_bytes']
print(f"Found artifact {artifact_id} ({name}), size: {size} bytes", flush=True)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(NoRedirect)
url = f'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/artifacts/{artifact_id}/zip'
req = urllib.request.Request(url, headers=headers)

redirect_url = None
try:
    opener.open(req)
except urllib.error.HTTPError as e:
    if e.code in (301, 302, 303, 307):
        redirect_url = e.headers['Location']

if not redirect_url:
    print("Could not obtain download URL", flush=True)
    sys.exit(1)

print("Downloading artifact zip from blob storage...", flush=True)
zip_path = "artifact_download.zip"
if os.path.exists(zip_path):
    try:
        os.remove(zip_path)
    except Exception:
        pass

# Use curl.exe for high-speed robust download from Azure blob
curl_cmd = ['curl.exe', '-fSL', '-o', zip_path, redirect_url]
res = subprocess.run(curl_cmd)
if res.returncode != 0 or not os.path.exists(zip_path) or os.path.getsize(zip_path) == 0:
    print("curl failed, falling back to chunked urllib...", flush=True)
    req2 = urllib.request.Request(redirect_url, headers={'User-Agent': 'BashLock-Downloader'})
    with urllib.request.urlopen(req2, timeout=120) as resp, open(zip_path, 'wb') as out_f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            out_f.write(chunk)

print(f"Downloaded {os.path.getsize(zip_path)} bytes. Extracting APK...", flush=True)
with zipfile.ZipFile(zip_path) as z:
    for filename in z.namelist():
        print("Zip entry:", filename, flush=True)
        if filename.endswith('.apk'):
            apk_data = z.read(filename)
            targets = [
                'bashlock.apk',
                'app-debug.apk',
                os.path.join('BashLock_APK', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
            ]
            for t in targets:
                os.makedirs(os.path.dirname(os.path.abspath(t)), exist_ok=True)
                with open(t, 'wb') as f:
                    f.write(apk_data)
                print(f"Wrote {len(apk_data)} bytes to {t}", flush=True)

try:
    os.remove(zip_path)
except Exception:
    pass

print("All APK files updated successfully!", flush=True)
