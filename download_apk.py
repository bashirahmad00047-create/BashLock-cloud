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

run_id = sys.argv[1] if len(sys.argv) > 1 else None
if not run_id:
    # Wait for the newly triggered workflow run
    for _ in range(10):
        req = urllib.request.Request(
            'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/runs?per_page=1',
            headers=headers
        )
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
        latest = data['workflow_runs'][0]
        run_id = str(latest['id'])
        if latest.get('status') in ['in_progress', 'queued']:
            break
        time.sleep(3)

print(f"Tracking workflow run {run_id}...")

while True:
    req = urllib.request.Request(
        f'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/runs/{run_id}',
        headers=headers
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
    status = data.get('status')
    conclusion = data.get('conclusion')
    print(f"Run {run_id} status: {status}, conclusion: {conclusion}", flush=True)
    if status == 'completed':
        if conclusion != 'success':
            print(f"Workflow failed with conclusion: {conclusion}", flush=True)
            sys.exit(1)
        break
    time.sleep(10)

print("Workflow completed successfully! Fetching artifacts...", flush=True)
req = urllib.request.Request(
    f'https://api.github.com/repos/bashirahmad00047-create/BashLock-cloud/actions/runs/{run_id}/artifacts',
    headers=headers
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode())

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
req2 = urllib.request.Request(redirect_url, headers={'User-Agent': 'BashLock-Downloader'})
with urllib.request.urlopen(req2) as resp:
    zip_bytes = resp.read()

print(f"Downloaded {len(zip_bytes)} bytes. Extracting APK...", flush=True)
with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
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

print("All APK files updated successfully!", flush=True)
