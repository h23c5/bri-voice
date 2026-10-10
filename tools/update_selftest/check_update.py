#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Consistency check for the in-app updater: update.json must describe a real release.

  python tools/update_selftest/check_update.py [--apk dist/BriVoice.apk]

Checks
  1. update.json parses and carries versionCode / versionName / apkUrl
  2. an sha256, when present, is a 64 character hex string
  3. when a matching APK is on disk, its hash equals the recorded one
  4. how the manifest relates to the build in this tree
"""
import argparse, hashlib, io, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, 'update.json')
checks = fails = 0


def check(what, ok, detail=''):
    global checks, fails
    checks += 1
    if ok:
        print('  PASS %s %s' % (what, detail))
    else:
        fails += 1
        print('  FAIL %s %s' % (what, detail))


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', default=os.path.join(ROOT, 'dist', 'BriVoice.apk'))
    a = ap.parse_args()

    check('update.json exists', os.path.isfile(MANIFEST), MANIFEST)
    raw = io.open(MANIFEST, encoding='utf-8').read()
    doc = None
    try:
        doc = json.loads(raw)
        check('update.json parses', True)
    except Exception as e:
        check('update.json parses', False, str(e))
    if not doc:
        return finish()

    vc = doc.get('versionCode')
    vn = doc.get('versionName') or ''
    url = doc.get('apkUrl') or ''
    digest = (doc.get('sha256') or '').strip().lower()
    check('versionCode is a positive integer', isinstance(vc, int) and vc > 0, str(vc))
    check('versionName is set', bool(vn), vn)
    check('apkUrl starts with https', url.startswith('https://'), url[:60])
    check('sha256 empty or 64 hex chars', digest == '' or bool(re.fullmatch('[0-9a-f]{64}', digest)), digest[:16] + ('...' if digest else '(empty)'))

    # the build in this tree
    man = io.open(os.path.join(ROOT, 'app', 'AndroidManifest.xml'), encoding='utf-8').read()
    mvc = int(re.search(r'android:versionCode="(\d+)"', man).group(1))
    mvn = re.search(r'android:versionName="([^"]+)"', man).group(1)
    print('  info this tree builds %s (versionCode %d)' % (mvn, mvc))
    print('  info the manifest describes %s (versionCode %s)' % (vn, vc))
    if vc <= mvc:
        print('  note manifest is not newer than the build -> the app reports "up to date"' )
    else:
        print('  note manifest is NEWER than this build -> installed 7.x would offer the update')

    if os.path.isfile(a.apk):
        have = sha256(a.apk)
        print('  info apk %s  sha256 %s' % (os.path.basename(a.apk), have[:16] + '...'))
        if digest and vn == mvn:
            check('recorded sha256 matches the apk', digest == have, '' if digest == have else 'recorded ' + digest[:16] + ' != actual ' + have[:16])
        elif digest:
            print('  note manifest describes %s, this tree builds %s - hash comparison skipped' % (vn, mvn))
        else:
            print('  note no sha256 recorded yet - run tools/publish_update.py before releasing')
    else:
        print('  note no apk at %s (build with tools/build_apk.py)' % a.apk)
    return finish()


def finish():
    print('')
    if fails:
        print('RESULT: FAIL %d/%d checks' % (checks - fails, checks))
        return 1
    print('RESULT: PASS %d/%d checks' % (checks, checks))
    return 0


if __name__ == '__main__':
    sys.exit(main())
