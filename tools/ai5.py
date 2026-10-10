# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
rows = [["menu.tools.ai","AI 助手 / 外部接口（实验）","AI assistant / external API (experimental)","AI アシスタント／外部 API（実験）"],["ai.title","AI 助手（实验功能）","AI assistant (experimental)","AI アシスタント（実験機能）"],["ai.experimental","实验功能，默认关闭。开启后本机才会联网调用你填写的接口；外部控制接口只监听 127.0.0.1，不对外网开放。","Experimental and off by default. Only after you switch it on does the app call the endpoint you enter; the external control interface listens on 127.0.0.1 only and is never exposed to the network.","実験機能です（初期状態はオフ）。オンにした場合のみ、入力したエンドポイントへ通信します。外部制御APIは 127.0.0.1 のみで待ち受け、外部ネットワークには公開しません。"],["ai.enable","启用 AI 自动调参","Enable AI auto-tuning","AI 自動調整を有効にする"],["ai.baseUrl","接口地址（OpenAI 兼容，如 https://api.openai.com/v1）","Endpoint (OpenAI-compatible, e.g. https://api.openai.com/v1)","エンドポイント（OpenAI 互換、例：https://api.openai.com/v1）"],["ai.model","模型名称","Model name","モデル名"],["ai.key","API Key（只保存在本机）","API key (stored on this device only)","API キー（端末内のみに保存）"],["ai.strength","调整幅度：%d%%（越大改动越明显）","Edit strength: %d%% (higher means bigger changes)","調整の強さ：%d%%（大きいほど変化が大きい）"],["ai.ext.enable","启用外部控制接口（实验，仅本机）","Enable external control API (experimental, local only)","外部制御 API を有効にする（実験・ローカルのみ）"],["ai.ext.port","端口","Port","ポート"],["ai.ext.running","外部控制接口已启动：http://127.0.0.1:%d","External control API running: http://127.0.0.1:%d","外部制御 API 起動中：http://127.0.0.1:%d"],["ai.ext.stopped","外部控制接口未启动","External control API stopped","外部制御 API は停止中"],["ai.ext.failed","外部控制接口启动失败：%s","External control API failed to start: %s","外部制御 API の起動に失敗：%s"],["ai.save","保存并应用","Save and apply","保存して適用"],["ai.tune","立即自动调参","Tune now","今すぐ自動調整"],["ai.saved","AI 设置已保存","AI settings saved","AI 設定を保存しました"],["ai.off","AI 调参尚未启用（工具 → AI 实验功能）","AI tuning is off (Tools > AI, experimental)","AI 調整は無効です（ツール → AI 実験機能）"],["ai.busy","AI 正在调参，请稍候","AI is already tuning","AI が調整中です"],["ai.asking","正在请求 AI 调参…","Asking the model…","モデルに問い合わせています…"],["ai.applied","AI 已调整 %d 个参数（共 %d 个控制点）","AI tuned %d parameters (%d control points)","AI が %d 個のパラメータを調整しました（制御点 %d 個）"],["ai.failed","AI 调参失败：%s","AI tuning failed: %s","AI 調整に失敗：%s"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in s: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = s.find('{"menu.tools.orientation"')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('keys added:', len(add))