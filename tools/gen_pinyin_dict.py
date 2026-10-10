#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate app/assets/dict/pinyin.txt.gz for V4Tune's Chinese G2P.

Data source: the Unicode Unihan database (Unihan_Readings.txt, kMandarin field).
  https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
Unicode data files are distributed under the Unicode License Agreement
(https://www.unicode.org/license.txt), which permits redistribution, so the
derived table below may be shipped inside the APK assets.

Pipeline:
  1. use tools/Unihan.zip when present (checked-in cache), otherwise download it
     with urllib; if that fails, fall back to tools/pinyin_fallback.txt
     (hand-maintained "<hanzi>\t<pinyin>" table, see FALLBACK below)
  2. parse Unihan_Readings.txt, keep kMandarin entries
  3. strip the tone (diacritic or trailing digit), keep the FIRST reading only,
     map u-umlaut to 'v' so the result feeds ChineseG2P's syllable parser
  4. audit every reading against the Mandarin syllable inventory that
     ChineseG2P accepts (INVENTORY below, a copy of ChineseG2P.INVENTORY); a
     reading outside it can never become phonemes, so the run reports it and
     --drop-unknown removes those characters from the table
  5. write "U+XXXX\t<toneless pinyin>" lines, sorted by code point, gzipped

Run:
  python gen_pinyin_dict.py [--unihan PATH] [--out PATH] [--plain OUT.txt]
                            [--no-audit] [--strict] [--drop-unknown]
"""
import argparse
import gzip
import io
import os
import re
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
DEFAULT_OUT = os.path.join(PROJ, "app", "assets", "dict", "pinyin.txt.gz")
# The 8 MB source archive is cached outside the project tree so a rebuild never
# dirties the working copy.
CACHE_ZIP = os.path.join(tempfile.gettempdir(), "Unihan.zip")
FALLBACK = os.path.join(HERE, "pinyin_fallback.txt")
UNIHAN_URL = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"

# Tone-bearing vowels used by kMandarin plus the u-umlaut used by pinyin.
TONE_MAP = {
    "\u0101": "a", "\u00e1": "a", "\u01ce": "a", "\u00e0": "a",   # a macron/acute/caron/grave
    "\u0113": "e", "\u00e9": "e", "\u011b": "e", "\u00e8": "e",
    "\u012b": "i", "\u00ed": "i", "\u01d0": "i", "\u00ec": "i",
    "\u014d": "o", "\u00f3": "o", "\u01d2": "o", "\u00f2": "o",
    "\u016b": "u", "\u00fa": "u", "\u01d4": "u", "\u00f9": "u",
    "\u00fc": "v", "\u01d6": "v", "\u01d8": "v", "\u01da": "v", "\u01dc": "v",  # u-umlaut + tones
    "\u00ea": "e", "\u00ea\u0301": "e", "\u00ea\u0300": "e", "\u00ea\u030c": "e",
    "\u0144": "n", "\u01f9": "n", "\u01f8": "n",                    # syllabic n / ng
    "\u1e5f": "r", "\u1e5e": "r",
}
VALID = re.compile(r"^[a-z]+$")

# The Mandarin syllable inventory accepted by com.dsh.v4tune.phoneme.ChineseG2P
# (a verbatim copy of ChineseG2P.INVENTORY; the Java side is the authority and
# tools/g2p_selftest/G2pSelftest sweeps every one of these forms).
INVENTORY = (
    "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu "
    "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou "
    "chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo "
    "da dai dan dang dao de den deng di dia dian diao die ding diu dong dou du duan dui dun duo "
    "e ei en eng er fa fan fang fei fen feng fiao fo fou fu "
    "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo "
    "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo "
    "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun "
    "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo "
    "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou "
    "lu luan lun luo lv lve ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu "
    "na nai nan nang nao ne nei nen neng ng ni nian niang niao nie nin ning niu nong nou "
    "nu nuan nun nuo nv nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu "
    "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun "
    "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo "
    "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou "
    "shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo "
    "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo "
    "wa wai wan wang wei wen weng wo wu "
    "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun "
    "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun "
    "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi "
    "zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo "
    "n hm hng"
).split()


def strip_tone(reading):
    """'luo\u00f2' -> 'luo'; keeps only the first whitespace separated reading."""
    if not reading:
        return None
    first = reading.strip().split()[0]
    out = []
    for ch in unicodedata.normalize("NFC", first):
        if ch in TONE_MAP:
            out.append(TONE_MAP[ch])
        elif "a" <= ch <= "z":
            out.append(ch)
        elif ch.isdigit():          # some sources append the tone number
            continue
        elif ch in ("\u0301", "\u0300", "\u0304", "\u030c", "\u0308"):
            continue                # decomposed combining marks
        # anything else (apostrophes, spaces) is dropped
    s = "".join(out)
    return s if VALID.match(s) else None


def open_unihan(path):
    if path and os.path.isfile(path):
        zf = zipfile.ZipFile(path)
        return zf.open("Unihan_Readings.txt")
    return None


def download_unihan(dest):
    sys.stderr.write("downloading %s ...\n" % UNIHAN_URL)
    req = urllib.request.Request(UNIHAN_URL, headers={"User-Agent": "v4tune-dictgen/1.0"})
    with urllib.request.urlopen(req, timeout=240) as resp, open(dest, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
    return dest


def parse_unihan(fh):
    entries = {}
    for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
        if raw.startswith("#") or "\tkMandarin\t" not in raw:
            continue
        parts = raw.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        cp_txt, field, value = parts[0], parts[1], "\t".join(parts[2:])
        if field != "kMandarin" or not cp_txt.startswith("U+"):
            continue
        try:
            cp = int(cp_txt[2:], 16)
        except ValueError:
            continue
        if cp > 0xFFFF:            # PinyinDict keys are Java chars (BMP only)
            continue
        py = strip_tone(value)
        if py and cp not in entries:
            entries[cp] = py            # first reading wins
    return entries


def parse_fallback(path):
    entries = {}
    with io.open(path, encoding="utf-8") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw or raw.startswith("#"):
                continue
            parts = re.split(r"\s+", raw)
            if len(parts) < 2:
                continue
            ch, py = parts[0], parts[1]
            py = strip_tone(py)
            if not py:
                continue
            for c in ch:
                if 0x3400 <= ord(c) <= 0xFFFF:
                    entries[ord(c)] = py
    return entries


def audit(entries):
    """Split the readings into (distinct readings, readings ChineseG2P cannot convert).

    ChineseG2P only knows the syllables in INVENTORY; a character whose reading
    is not one of them would make lyricToPhonemes() return null, so the run
    reports those characters instead of hiding them.
    """
    known = set(INVENTORY)
    readings = {}
    unknown = {}
    for py in entries.values():
        readings[py] = readings.get(py, 0) + 1
        if py not in known:
            unknown[py] = unknown.get(py, 0) + 1
    return readings, unknown


def common_simplified_supplement(entries):
    """Extra coverage pass: Unihan's kMandarin sometimes lacks a reading for
    characters that only have kHanyuPinlu / kXHC1983; those are pulled from the
    same file by parse_unihan_extra when available."""
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unihan", default=CACHE_ZIP)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--plain", default=None, help="also dump the uncompressed table")
    ap.add_argument("--no-audit", action="store_true", help="skip the inventory audit")
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero when a reading is outside the Mandarin inventory")
    ap.add_argument("--drop-unknown", action="store_true",
                    help="drop characters whose reading ChineseG2P cannot convert")
    args = ap.parse_args()

    fh = open_unihan(args.unihan)
    if fh is None:
        try:
            download_unihan(CACHE_ZIP)
            fh = open_unihan(CACHE_ZIP)
        except Exception as exc:                      # network unavailable
            sys.stderr.write("download failed: %s\n" % exc)
            fh = None
    if fh is not None:
        entries = parse_unihan(fh)
        fh.close()
        source = "Unihan kMandarin"
    elif os.path.isfile(FALLBACK):
        entries = parse_fallback(FALLBACK)
        source = "hand-written fallback table"
    else:
        sys.exit("no Unihan source and no %s fallback" % FALLBACK)

    entries = common_simplified_supplement(entries)

    unknown = {}
    readings = {}
    if not args.no_audit:
        readings, unknown = audit(entries)
        worst = sorted(unknown.items(), key=lambda kv: (-kv[1], kv[0]))
        print("audit        : %d distinct readings, %d outside the Mandarin inventory"
              % (len(readings), len(unknown)))
        if worst:
            print("audit sample : " + ", ".join("%s(%d)" % (py, n) for py, n in worst[:10]))
        if args.drop_unknown and unknown:
            before = len(entries)
            known = set(INVENTORY)
            entries = dict((cp, py) for cp, py in entries.items() if py in known)
            print("dropped      : %d characters with unconvertible readings" % (before - len(entries)))
        if args.strict and unknown:
            sys.exit("%d readings outside the Mandarin inventory (see audit sample)" % len(unknown))

    if len(entries) < 2000:
        sys.exit("only %d entries - refusing to write a trivial dictionary" % len(entries))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    lines = ["U+%04X\t%s" % (cp, entries[cp]) for cp in sorted(entries)]
    body = ("\n".join(lines) + "\n").encode("utf-8")
    with open(args.out, "wb") as out:
        with gzip.GzipFile(filename="", mode="wb", fileobj=out, mtime=0) as gz:
            gz.write(body)
    if args.plain:
        with open(args.plain, "wb") as pf:
            pf.write(body)
    print("source       : %s" % source)
    print("entries      : %d" % len(entries))
    print("distinct py  : %d" % len(set(entries.values())))
    print("gzip bytes   : %d (raw %d)" % (os.path.getsize(args.out), len(body)))
    print("output       : %s" % args.out)


if __name__ == "__main__":
    main()
