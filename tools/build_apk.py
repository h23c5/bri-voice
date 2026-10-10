#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gradle-free Android APK build pipeline for 歌姬调音 V4.

aapt2 compile -> aapt2 link -> javac -> d8 -> package -> zipalign -> apksigner
"""
import argparse, os, re, shutil, subprocess, sys, zipfile, glob, time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
APP = os.path.join(PROJ, "app")
BUILD = os.path.join(PROJ, "build")
DIST = os.path.join(PROJ, "dist")

SDK = os.environ.get("ANDROID_SDK", r"C:\AndroidDev\sdk")
JDK = os.environ.get("JAVA_HOME", r"C:\AndroidDev\jdk-17.0.20.1+1")
BT_VER = os.environ.get("BT_VERSION", "34.0.0")
PLATFORM = os.environ.get("ANDROID_PLATFORM", "android-34")

PKG = "com.dsh.v4tune"
APP_NAME = "V4Tune"


def find_build_tools():
    bt = os.path.join(SDK, "build-tools")
    if not os.path.isdir(bt):
        sys.exit("build-tools not found under " + bt)
    if os.path.isdir(os.path.join(bt, BT_VER)):
        return os.path.join(bt, BT_VER)
    dirs = sorted(os.listdir(bt))
    if not dirs:
        sys.exit("no build-tools installed")
    return os.path.join(bt, dirs[-1])


BT = find_build_tools()
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
ANDROID_JAR = os.path.join(SDK, "platforms", PLATFORM, "android.jar")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAR = os.path.join(JDK, "bin", "jar.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")
KS = os.path.join(PROJ, "tools", "debug.keystore")


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JDK
    e["PATH"] = os.path.join(JDK, "bin") + os.pathsep + e.get("PATH", "")
    return e


def run(cmd, cwd=None, quiet=False):
    if not quiet:
        print("  $ " + " ".join('"%s"' % c if " " in str(c) else str(c) for c in cmd))
    p = subprocess.run(cmd, cwd=cwd, env=env(), capture_output=True, text=True, errors="replace")
    if p.returncode != 0:
        print(p.stdout[-8000:])
        print(p.stderr[-8000:])
        sys.exit("command failed (%d): %s" % (p.returncode, " ".join(map(str, cmd))))
    if p.stdout.strip() and not quiet:
        out = p.stdout.strip()
        print("    " + out[-2000:].replace("\n", "\n    "))
    return p.stdout


def java_sources():
    srcs = []
    for root, _d, files in os.walk(os.path.join(APP, "src")):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    gen = os.path.join(BUILD, "gen")
    for root, _d, files in os.walk(gen):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    return srcs


def make_keystore():
    if os.path.exists(KS):
        return
    os.makedirs(os.path.dirname(KS), exist_ok=True)
    run([KEYTOOL, "-genkeypair", "-keystore", KS, "-alias", "v4tune", "-storepass", "android123",
         "-keypass", "android123", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-dname", "CN=V4Tune, OU=DSH, O=DSH, L=CN, S=CN, C=CN"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true", help="build release-signed apk")
    ap.add_argument("--name", default="V4Tune.apk")
    ap.add_argument("--skip-tests", action="store_true")
    args = ap.parse_args()

    t0 = time.time()
    print("[1/7] clean")
    for d in ("res", "gen", "classes", "dex"):
        shutil.rmtree(os.path.join(BUILD, d), ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(DIST, exist_ok=True)
    for f in ("base.apk", "classes.jar", "unsigned.apk", "aligned.apk"):
        fp = os.path.join(BUILD, f)
        if os.path.exists(fp):
            os.remove(fp)

    print("[2/7] aapt2 compile resources")
    res_zip = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip])

    print("[3/7] aapt2 link")
    base_apk = os.path.join(BUILD, "base.apk")
    gen = os.path.join(BUILD, "gen")
    os.makedirs(gen, exist_ok=True)
    assets = os.path.join(APP, "assets")
    link = [AAPT2, "link", "-o", base_apk, "-I", ANDROID_JAR,
            "--manifest", os.path.join(APP, "AndroidManifest.xml"),
            res_zip, "--java", gen,
            "--min-sdk-version", "21", "--target-sdk-version", "34",
            "--version-code", "51", "--version-name", "7.0",
            "--no-version-vectors"]
    if os.path.isdir(assets):
        link += ["-A", assets]
    run(link)

    print("[4/7] javac")
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes, exist_ok=True)
    srcs = java_sources()
    if not srcs:
        sys.exit("no java sources")
    argfile = os.path.join(BUILD, "sources.txt")
    with open(argfile, "w", encoding="utf-8") as fh:
        for s in srcs:
            fh.write('"%s"\n' % s.replace("\\", "\\\\"))
    run([JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-nowarn",
         "-Xlint:none", "-cp", ANDROID_JAR, "-d", classes, "@" + argfile])
    print("    %d source files" % len(srcs))

    print("[5/7] d8 -> classes.dex")
    cjar = os.path.join(BUILD, "classes.jar")
    run([JAR, "cf", cjar, "-C", classes, "."], quiet=True)
    dexdir = os.path.join(BUILD, "dex")
    os.makedirs(dexdir, exist_ok=True)
    d8 = os.path.join(BT, "d8.bat")
    run([d8, "--release", "--min-api", "21", "--lib", ANDROID_JAR, "--output", dexdir, cjar])

    print("[6/7] package apk")
    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(base_apk, "r") as zin, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zout.writestr(zi, data)
        with open(os.path.join(dexdir, "classes.dex"), "rb") as fh:
            zout.writestr("classes.dex", fh.read(), zipfile.ZIP_DEFLATED)

    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-p", "-f", "4", unsigned, aligned])

    print("[7/7] sign")
    make_keystore()
    out = os.path.join(DIST, args.name)
    run([APKSIGNER, "sign", "--ks", KS, "--ks-pass", "pass:android123", "--key-pass", "pass:android123",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--out", out, aligned])
    run([APKSIGNER, "verify", "--print-certs", out], quiet=True)
    size = os.path.getsize(out)
    print("\nOK -> %s  (%.2f MB, %.1fs)" % (out, size / 1048576.0, time.time() - t0))


if __name__ == "__main__":
    main()
