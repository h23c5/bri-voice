# -*- coding: utf-8 -*-
"""[v3.7] every voicebank carries the BriVoice name: "<singer> BV"."""
import io, json, os, shutil, zipfile

root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

RENAME = {
    # key: substring that identifies the pack by its current name
    "luotianyi": {"name": "\u6d1b\u5929\u4f9d BV", "nameEn": "Luo Tianyi BV", "nameJa": "\u6d1b\u5929\u4f9d BV"},
    "miku":      {"name": "Miku BV", "nameEn": "Miku BV", "nameJa": "\u30df\u30af BV"},
}

def target_for(old_name, old_en):
    blob = ((old_name or "") + " " + (old_en or "")).lower()
    if "miku" in blob or "\u521d\u97f3" in blob or "\u30df\u30af" in blob:
        return RENAME["miku"]
    return RENAME["luotianyi"]

def rewrite(path):
    if not os.path.isfile(path):
        return None
    tmp = path + ".tmp"
    with zipfile.ZipFile(path, "r") as zin:
        names = zin.namelist()
        if "voice.json" not in names:
            return None
        raw = zin.read("voice.json").decode("utf-8")
        m = json.loads(raw)
        new = target_for(m.get("name"), m.get("nameEn"))
        before = m.get("name")
        if before == new["name"] and m.get("nameEn") == new["nameEn"]:
            return ("unchanged", before, m.get("id"))
        m["name"] = new["name"]
        m["nameEn"] = new["nameEn"]
        m["nameJa"] = new["nameJa"]
        blob = json.dumps(m, ensure_ascii=False, indent=1).encode("utf-8")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in names:
                data = blob if n == "voice.json" else zin.read(n)
                zout.writestr(n, data)
    os.replace(tmp, path)
    return ("renamed", before, m.get("id"))

targets = [
    os.path.join(root, "build", "voicepacks", "luotianyi_chn_meng_bank.v4voice"),
    os.path.join(root, "build", "voicepacks", "miku_v4_chn_bank.v4voice"),
    os.path.join(root, "app", "assets", "singers", "luotianyi_chn_meng_bank.v4voice"),
]
for t in targets:
    if not os.path.isfile(t):
        # find whatever packs exist
        d = os.path.dirname(t)
        if os.path.isdir(d):
            for f in os.listdir(d):
                if f.endswith(".v4voice"):
                    r = rewrite(os.path.join(d, f))
                    if r: print(os.path.basename(f), "->", r)
        continue
    r = rewrite(t)
    print(os.path.basename(t), "->", r)

# the converter must produce these names from now on
p = os.path.join(root, "tools", "voicebank_pack.py")
s = io.open(p, encoding="utf-8").read()
for old, new in (("Luo Tianyi V4_Meng", "\u6d1b\u5929\u4f9d BV"), ("MIKU V4 Chinese", "Miku BV")):
    s = s.replace(old, new)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("converter names updated")
