# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
rows = [["transport.ai","AI 调音","AI Tune","AI 調声"],["ai.ask.title","想让 AI 怎么调？","How should the AI tune it?","AI にどう調整させますか？"],["ai.ask.hint","用一句话描述你的需求，AI 会按你的要求改参数（曲线 / 音符 / 速度 / 轨道）。","Describe what you want in one sentence; the AI then edits the parameters (curves / notes / tempo / track).","一文で要望を書いてください。AI がパラメータ（カーブ／音符／テンポ／トラック）を調整します。"],["ai.ask.placeholder","例如：主歌轻柔一点，副歌更有力，尾音加一点颤音","e.g. softer verses, stronger chorus, a little vibrato on the last note","例：Aメロは柔らかく、サビは力強く、最後の音にビブラート"],["ai.ask.ex1","整体更有感情：副歌力度大一些，句尾加颤音","More expression overall: stronger chorus, vibrato at phrase ends","全体的に表情豊かに：サビは強く、フレーズ末尾にビブラート"],["ai.ask.ex2","修正音准：把偏高的音拉回来，长音加一点自然的滑音","Fix the pitch: pull the sharp notes back, add natural portamento to long notes","音程を修正：高めの音を戻し、長音に自然なポルタメント"],["ai.ask.ex3","更柔和：降低力度起伏，气息声多一点","Softer: flatter dynamics, more breathiness","より柔らかく：強弱の起伏を抑え、息成分を増やす"],["ai.ask.ex4","更有节奏感：让每个音的起音更干脆，缩短句尾","More rhythmic: crisper onsets, shorter phrase endings","よりリズミカルに：アタックを鋭く、フレーズ末尾を短く"],["ai.ask.scope","AI 可以改：12 个参数曲线（含 DYN/PIT/VIB 等）、已有音符的音高/时长/力度/歌词、速度、当前音轨的音量与声像。改动可撤销。","The AI may change: the 12 control curves (DYN/PIT/VIB ...), pitch/duration/velocity/lyrics of existing notes, the tempo, and the current track's volume and pan. Every change can be undone.","AI が変更できるもの：12 本のパラメータカーブ（DYN/PIT/VIB など）、既存音符の音高・長さ・ベロシティ・歌詞、テンポ、現在のトラックの音量とパン。変更は元に戻せます。"],["ai.ask.go","开始调音","Tune now","調整を開始"],["ai.applied.full","AI 已调整：%s（共 %d 项改动）","AI tuned %s (%d changes)","AI が調整しました：%s（変更 %d 件）"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in s: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = s.find('{"ai.title"')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('keys added:', len(add))