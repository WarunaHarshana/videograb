import subprocess, json, sys

url = sys.argv[1] if len(sys.argv) > 1 else 'https://www.fullporn.xxx/videos/93805582/wanna-get-fucked-like-a-whore-lily-lous-hardcore-lesson/'

r = subprocess.run(['yt-dlp', '--no-download', '-j', url], capture_output=True, text=True, timeout=30)
print('STDERR:', r.stderr[:500] if r.stderr else 'none')
if r.returncode == 0 and r.stdout.strip():
    try:
        d = json.loads(r.stdout)
        print('Title:', d.get('title', '?'))
        print('Extractor:', d.get('extractor', '?'))
        print('Formats:', len(d.get('formats', [])))
        for f in d.get('formats', [])[:10]:
            print(f"  {f.get('format_id')}: {f.get('ext')} {f.get('height','?')}p url={f.get('url','')[:80]}")
    except:
        print('JSON parse error')
        print('STDOUT:', r.stdout[:500])
else:
    print('Return code:', r.returncode)
    print('STDOUT:', r.stdout[:500] if r.stdout else 'empty')
