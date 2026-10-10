#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fill update.json for the in-app updater (see app/src/com/dsh/v4tune/update/Updater.java).

Steps for a release:
  1. build the APK:            python tools/build_apk.py
  2. run this script:          python tools/publish_update.py --apk dist/BriVoice.apk \
                                 --version-code 52 --version-name 7.1 --notes "what changed"
  3. upload the APK to a Gitee release (web UI) and use its download URL, or pass --url
  4. commit update.json        (the app reads it from the raw endpoint)
"""
import argparse, hashlib, io, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "update.json")


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
        	h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', default=os.path.join(ROOT, 'dist', 'BriVoice.apk'))
    ap.add_argument('--version-code', type=int, required=True)
    ap.add_argument('--version-name', required=True)
    ap.add_argument('--url', default=None, help='release download URL; default: the Gitee release pattern')
    ap.add_argument('--notes', default='')
    a = ap.parse_args()

    if not os.path.isfile(a.apk):
        sys.exit('apk not found: %s (build it with tools/build_apk.py)' % a.apk)
    url = a.url or ('https://gitee.com/h23c5/bri-voice/releases/download/v%s/BriVoice-%s.apk'
                    % (a.version_name, a.version_name))
    doc = {
        'versionCode': a.version_code,
        'versionName': a.version_name,
        'apkUrl': url,
        'sha256': sha256(a.apk),
        'notes': a.notes,
    }
    with io.open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('wrote %s' % OUT)
    print('  versionCode %d  versionName %s' % (a.version_code, a.version_name))
    print('  apkUrl   %s' % url)
    print('  sha256   %s' % doc['sha256'])
    print('  apk size %.1f KB' % (os.path.getsize(a.apk) / 1024.0))
    print('')
    print('next: upload %s to the Gitee release and make sure apkUrl matches.' % os.path.basename(a.apk))


if __name__ == '__main__':
    main()
