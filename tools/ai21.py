# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
blk = root + r'\tools\ai_ui_methods.txt'
s = io.open(blk, encoding='utf-8').read()
# my fluent Dialogs.Builder does not exist - use the AlertDialog builder we already have
old = '''        new com.dsh.v4tune.ui.Dialogs.Builder(this)
                .title(L.t("ai.ask.title"))
                .view(sc)
                .positive(L.t("ai.ask.go"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String req = input.getText().toString().trim();
                        if (req.length() == 0) req = examples[0];
                        runAiTune(req);
                    }
                })
                .negative(L.t("dlg.cancel"), null)
                .show();'''
new = '''        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("ai.ask.title"))
                .setView(sc)
                .setPositiveButton(L.t("ai.ask.go"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String req = input.getText().toString().trim();
                        if (req.length() == 0) req = examples[0];
                        runAiTune(req);
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();'''
if old not in s: raise SystemExit('dialog block not found')
s = s.replace(old, new, 1)
io.open(blk, 'w', encoding='utf-8', newline='').write(s)
print('dialog block normalised')

# insert into MainActivity
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
if 'private void aiAskDialog()' in t:
    print('already inserted')
else:
    anchor = '    /** the experimental panel: switches, endpoint, key, port, manual tune button */'
    if anchor not in t: raise SystemExit('aiDialog anchor missing')
    t = t.replace(anchor, s + anchor, 1)
    # the field
    t = t.replace('    private boolean pendingPlay;', '    private boolean pendingPlay;' + chr(10) + '    /** the workspace shortcut, only visible while the AI is on */' + chr(10) + '    private Button aiBtn;', 1)
    # the button itself, right after 从头播放
    btnold = '''        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {'''
    btnnew = '''        aiBtn = smallButton(L.t("transport.ai"), new View.OnClickListener() {
            public void onClick(View v) { aiAskDialog(); }
        }, true);
        aiBtn.setVisibility(View.GONE);
        transport.addView(aiBtn);
        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {'''
    if btnold not in t: raise SystemExit('transport anchor missing')
    t = t.replace(btnold, btnnew, 1)
    # the old no-arg tuner becomes a wrapper, and the callers keep working
    oldcall = '                    String reply = ' + chr(39) + chr(39)
    t = t.replace('            if ("aiTune".equals(action)) {', '            if ("aiTune".equals(action)) {', 1)
    t = t.replace('                runAiTune();' + chr(10) + '                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "started", Boolean.TRUE,', '                runAiTune(null);' + chr(10) + '                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "started", Boolean.TRUE,', 1)
    t = t.replace('                runAiTune();' + chr(10) + '                d.dismiss();', '                runAiTune(null);' + chr(10) + '                d.dismiss();', 1)
    # drop the superseded no-arg implementation
    i = t.find('    /** asks the model, validates the answer, applies it on the UI thread */')
    if i > 0:
        j = t.find('    /** the quick-tune sheet', i)
        if j < 0: j = t.find('    /** the experimental panel', i)
        if j > i:
            t = t[:i] + t[j:]
            print('old runAiTune removed')
    # keep the button in step with the setting
    t = t.replace('        } else if (extApi != null) {' + chr(10) + '            extApi.stop();' + chr(10) + '            extApi = null;' + chr(10) + '            status(L.t("ai.ext.stopped"));' + chr(10) + '        }' + chr(10) + '    }',
                  '        } else if (extApi != null) {' + chr(10) + '            extApi.stop();' + chr(10) + '            extApi = null;' + chr(10) + '            status(L.t("ai.ext.stopped"));' + chr(10) + '        }' + chr(10) + '        updateAiButton();' + chr(10) + '    }', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('MainActivity patched')