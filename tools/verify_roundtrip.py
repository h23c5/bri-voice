# -*- coding: utf-8 -*-
"""Independent verification: compare our written .vsqx against the original file
element-by-element (note data + control curves + tempo), and validate against the
official Yamaha XSDs that ship with VOCALOID4."""
import os, sys, glob
import xml.etree.ElementTree as ET

IN = sys.argv[1] if len(sys.argv) > 1 else r'build\fmt_in'
OUT = sys.argv[2] if len(sys.argv) > 2 else r'build\fmt_out'
XSD4 = r'E:\V4\vsq4.xsd'
XSD3 = r'E:\V4\vsq3.xsd'

def ns_of(root):
    t = root.tag
    return t[:t.index('}') + 1] if '}' in t else ''

def loc(el):
    return el.tag.split('}')[-1]

def note_key(n, ns):
    def g(name, dflt=''):
        e = n.find(ns + name)
        return e.text.strip() if e is not None and e.text else dflt
    v4 = n.find(ns + 't') is not None
    if v4:
        return (g('t'), g('dur'), g('n'), g('v'), g('y'))
    return (g('posTick'), g('durTick'), g('noteNum'), g('velocity'), g('lyric'))

def collect(root):
    ns = ns_of(root)
    v4 = loc(root) == 'vsq4'
    res = {'notes': [], 'ccs': [], 'tempos': []}
    for tr in root.iter(ns + 'vsTrack'):
        for part in list(tr.findall(ns + 'vsPart')) + list(tr.findall(ns + 'musicalPart')):
            pt = part.find(ns + ('t' if v4 else 'posTick'))
            ppos = int(pt.text) if pt is not None and pt.text else 0
            for n in part.findall(ns + 'note'):
                res['notes'].append(note_key(n, ns))
            for cc in part.findall(ns + 'cc'):
                t = cc.find(ns + 't'); v = cc.find(ns + 'v')
                if t is not None and v is not None:
                    res['ccs'].append((t.text.strip(), v.get('id'), v.text.strip()))
            for mc in part.findall(ns + 'mCtrl'):
                t = mc.find(ns + 'posTick'); a = mc.find(ns + 'attr')
                if t is not None and a is not None:
                    res['ccs'].append((str(ppos + int(t.text)), a.get('id'), a.text.strip()))
    for tp in root.iter(ns + 'tempo'):
        t = tp.find(ns + ('t' if v4 else 'posTick')); v = tp.find(ns + ('v' if v4 else 'bpm'))
        if t is not None and v is not None:
            res['tempos'].append((t.text.strip(), v.text.strip()))
    return res

def xsd_validate(path, xsd):
    try:
        from lxml import etree
    except ImportError:
        return None, 'lxml missing'
    try:
        schema = etree.XMLSchema(etree.parse(xsd))
        doc = etree.parse(path)
        if schema.validate(doc):
            return True, ''
        return False, '\n'.join('  line %d: %s' % (e.line, e.message) for e in schema.error_log[:6])
    except Exception as e:
        return False, 'validator error: %s' % e

files = sorted(glob.glob(os.path.join(IN, '*.vsqx')))
ok = fail = 0
for f in files:
    base = os.path.basename(f)
    o = os.path.join(OUT, base)
    if not os.path.exists(o):
        print('MISSING OUTPUT', base); fail += 1; continue
    try:
        a = collect(ET.parse(f).getroot())
        b = collect(ET.parse(o).getroot())
    except Exception as e:
        print('PARSE FAIL', base, e); fail += 1; continue
    msgs = []
    if len(a['notes']) != len(b['notes']):
        msgs.append('note count %d -> %d' % (len(a['notes']), len(b['notes'])))
    else:
        diff = [i for i in range(len(a['notes'])) if a['notes'][i] != b['notes'][i]]
        if diff:
            msgs.append('%d note diffs, first: %s -> %s' % (len(diff), a['notes'][diff[0]], b['notes'][diff[0]]))
    ka = {}
    for t, i, v in a['ccs']:
        ka.setdefault(i, []).append((int(t), v))
    kb = {}
    for t, i, v in b['ccs']:
        kb.setdefault(i, []).append((int(t), v))
    for k in ka:
        ka[k].sort()
    for k in kb:
        kb[k].sort()
    if set(ka) != set(kb):
        msgs.append('curve ids %s -> %s' % (sorted(ka), sorted(kb)))
    else:
        for k in sorted(ka):
            if ka[k] != kb[k]:
                only_a = [p for p in ka[k] if p not in kb[k]][:2]
                only_b = [p for p in kb[k] if p not in ka[k]][:2]
                msgs.append('curve %s: %d pts -> %d pts, missing %s extra %s' % (k, len(ka[k]), len(kb[k]), only_a, only_b))
    if a['tempos'] != b['tempos']:
        msgs.append('tempo %s -> %s' % (a['tempos'], b['tempos']))
    xsd = XSD4 if loc(ET.parse(o).getroot()) == 'vsq4' else XSD3
    v, err = xsd_validate(o, xsd)
    if v is False:
        msgs.append('XSD INVALID: ' + err)
    elif v is None:
        msgs.append('XSD not checked: ' + err)
    if msgs:
        fail += 1
        print('DIFF %-42s %s' % (base[:42], ' | '.join(msgs)))
    else:
        ok += 1
        print('OK   %-42s notes=%d cc=%d xsd=valid' % (base[:42], len(b['notes']), len(b['ccs'])))
print()
print('round-trip + XSD: %d OK, %d problems' % (ok, fail))
sys.exit(1 if fail else 0)
