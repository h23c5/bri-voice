# -*- coding: utf-8 -*-
"""UTAU voicebank -> .v4voice prototype converter (desktop).
Usage: python utau2v4voice.py <bank_dir> <out.v4voice>
Measures vowels a/i/u/e/o from oto.ini + wav, writes the BriVoice pack JSON."""
import sys, os, io, wave, json, zipfile, math
import numpy as np

VOWELS = {
 'a': 'a', 'i': 'i', 'u': 'u', 'e': 'e', 'o': 'o',
 'あ':'a','い':'i','う':'u','え':'e','お':'o',
 'か':'a','き':'i','く':'u','け':'e','こ':'o',
 'さ':'a','し':'i','す':'u','せ':'e','そ':'o',
 'た':'a','ち':'i','つ':'u','て':'e','と':'o',
 'な':'a','に':'i','ぬ':'u','ね':'e','の':'o',
 'は':'a','ひ':'i','ふ':'u','へ':'e','ほ':'o',
 'ま':'a','み':'i','む':'u','め':'e','も':'o',
 'や':'a','ゆ':'u','よ':'o',
 'ら':'a','り':'i','る':'u','れ':'e','ろ':'o',
 'わ':'a','を':'o','ん':'N',
}
TARGET_FORMANTS = {'a':[800,1200,2500],'i':[300,2300,3000],'u':[350,900,2400],'e':[500,1800,2600],'o':[500,900,2400]}

def read_text(path):
    b = open(path,'rb').read()
    if b[:3] == b'\xef\xbb\xbf': return b[3:].decode('utf-8','replace')
    try: return b.decode('utf-8')
    except Exception: return b.decode('shift_jis','replace')

def read_wav(path):
    w = wave.open(path,'rb')
    ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    raw = w.readframes(n); w.close()
    if sw == 2: x = np.frombuffer(raw, dtype='<i2').astype(np.float64)/32768.0
    elif sw == 1: x = (np.frombuffer(raw, dtype='u1').astype(np.float64)-128)/128.0
    else: raise RuntimeError('unsupported wav format '+str(sw))
    if ch > 1: x = x.reshape(-1,ch).mean(axis=1)
    return x, sr

def parse_oto(path):
    entries = []
    for line in read_text(path).splitlines():
        if '=' not in line: continue
        f, rest = line.split('=',1)
        p = rest.split(',')
        if len(p) < 2: continue
        try: off = float(p[1])
        except Exception: off = 0.0
        entries.append({'file':f.strip(),'alias':p[0].strip(),'offset':off})
    return entries

def alias_vowel(alias):
    a = alias.strip().lower()
    for ch in reversed(a.replace('-','').replace(' ','')): 
        if ch in VOWELS: return VOWELS[ch]
    return None

def f0_autocorr(x, sr):
    x = x - np.mean(x)
    if len(x) < 1024: return 0.0
    x = x[:4096]
    ac = np.correlate(x, x, 'full')[len(x)-1:]
    lo, hi = int(sr/800), int(sr/60)
    if hi >= len(ac): hi = len(ac)-1
    if lo >= hi: return 0.0
    k = lo + int(np.argmax(ac[lo:hi]))
    return sr/k if k>0 else 0.0

def spectrum(x, sr):
    n = 1
    while n < len(x): n <<= 1
    w = np.hanning(len(x))
    spec = np.abs(np.fft.rfft(x*w, n))
    freqs = np.fft.rfftfreq(n, 1.0/sr)
    return freqs, spec

FORMANT_WINDOWS = {
 'a': [[600,1100],[900,1700],[2200,3200]],
 'i': [[200,450],[1800,2800],[2500,3500]],
 'u': [[250,550],[600,1200],[2000,3000]],
 'e': [[350,700],[1400,2300],[2300,3200]],
 'o': [[350,700],[700,1200],[2000,3000]],
}
def formants_from_spectrum(freqs, spec, sym):
    k = max(3, len(spec)//200)
    smooth = np.convolve(spec, np.ones(k)/k, 'same')
    out = []
    for wi, (lo, hi) in enumerate(FORMANT_WINDOWS.get(sym, [[250,1200],[800,2500],[2000,3500]])):
        sel = (freqs>=lo)&(freqs<=hi)
        if not np.any(sel):
            out.append(sum((lo,hi))/2); continue
        i = int(np.argmax(smooth[sel]))
        out.append(float(freqs[sel][i]))
    return out

def harmonic_bands(freqs, spec):
    grid = 60.0 * (16000.0/60.0)**(np.arange(64)/63.0)
    db = []
    for g in grid:
        sel = (freqs > g*0.93) & (freqs < g*1.07)
        v = float(np.mean(spec[sel])) if np.any(sel) else 0.0
        db.append(v)
    mx = max(db) or 1.0
    return [round(20*math.log10(max(1e-6,v/mx)),1) for v in db]

def convert(bank_dir, out_path):
    oto = os.path.join(bank_dir,'oto.ini')
    entries = parse_oto(oto) if os.path.exists(oto) else []
    best = {}
    for e in entries:
        sym = alias_vowel(e['alias'])
        if not sym: continue
        wav = os.path.join(bank_dir, e['file'])
        if not os.path.exists(wav): continue
        try: x, sr = read_wav(wav)
        except Exception: continue
        start = int(max(0, e['offset'])*sr/1000.0)
        seg = x[start:start+int(0.20*sr)]
        if len(seg) < 512: seg = x[:int(0.20*sr)]
        if len(seg) < 512: continue
        f0 = f0_autocorr(seg, sr)
        freqs, spec = spectrum(seg, sr)
        f3 = formants_from_spectrum(freqs, spec, sym)
        db = harmonic_bands(freqs, spec)
        best.setdefault(sym, []).append({'f0': round(f0,1), 'formants': f3, 'db': db})
    formants = {}; counts = {}; phon = {}
    for sym, arr in best.items():
        if not arr: continue
        fs = np.array([a['formants'] for a in arr], dtype=float).mean(axis=0)
        formants[sym] = [int(round(v)) for v in fs]
        counts[sym] = len(arr)
        phon[sym] = [{'f0': a['f0'], 'db': a['db'], 'dyn': 0.5} for a in arr[:4]]
    name = os.path.basename(os.path.abspath(bank_dir.rstrip('/\\'))) or 'UTAU'
    data = {
      'format':'v4voice/1','id':'utau-'+name,'name':name,'nameEn':name,'nameJa':name,'lang':0,
      'compID':'utau','source':'UTAU bank '+bank_dir+' measured by utau2v4voice prototype',
      'voice':{'formantScale':1.0,'f1Mul':1.0,'f2Mul':1.0,'f3Mul':1.0,'tiltBias':0.0,'breathBias':0.1,'oqBias':0.0,'vibratoRate':5.4},
      'shelf':[[60,0],[152,0],[386,0],[980,0],[2486,0],[6307,0],[16000,0]],
      'formants':formants,
      'harmonics':{'freqs':[round(60.0*(16000.0/60.0)**(i/63.0),3) for i in range(64)],'phonemes':phon,'noise':{}},
      'stats':{'frames':sum(len(v) for v in best.values()),'f0Median':0,'clusters':len(best),'matchedPhonemes':len(best)},
      'notes':'Prototype: vowels measured from wav via FFT; consonants fall back to defaults.'
    }
    with zipfile.ZipFile(out_path,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('voice.json', json.dumps(data,ensure_ascii=False))
        z.writestr('LICENSE.txt','Generated by BriVoice utau2v4voice prototype for testing.\n')
    return data

if __name__ == '__main__':
    d = convert(sys.argv[1], sys.argv[2])
    print(json.dumps({'name':d['name'],'formants':d['formants'],'phonemes':list(d['harmonics']['phonemes'])},ensure_ascii=False))
