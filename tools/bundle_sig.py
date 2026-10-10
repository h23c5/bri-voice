# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)
if 'bundledSignature' in s:
    print('already patched')
else:
    rep('    private void installBundledVoicebanks() {', "    /**\n     * Signature of a bundled voicebank: its length plus a CRC of its bytes.\n     *\n     * The installer used to remember only THAT a bundled bank had been installed, so a rebuilt bank\n     * (v6.3 halves the vowel formant deviation) never reached a device that had installed the old one.\n     * Storing the signature re-installs only when the shipped file actually changed; a user who deletes\n     * the bank keeps it deleted, because the stored signature stays put.\n     */\n    private String bundledSignature(String name) {\n        java.io.InputStream in = null;\n        try {\n            in = getAssets().open(\"singers/\" + name);\n            java.util.zip.CRC32 crc = new java.util.zip.CRC32();\n            byte[] buf = new byte[8192];\n            long total = 0;\n            int n;\n            while ((n = in.read(buf)) > 0) {\n                crc.update(buf, 0, n);\n                total += n;\n            }\n            return total + \":\" + crc.getValue();\n        } catch (Throwable t) {\n            return null;\n        } finally {\n            try { if (in != null) in.close(); } catch (Throwable ignored) { }\n        }\n    }\n" + '    private void installBundledVoicebanks() {', 'helper')
    rep('                String flag = "builtin_" + n;', '                String flag = "builtin_sig_" + n;', 'flag name')
    rep('                if (getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean(flag, false)) continue;',
        '                String sig = bundledSignature(n);' + chr(10) +
        '                String storedSig = getSharedPreferences("brivoice", MODE_PRIVATE).getString(flag, null);' + chr(10) +
        '                if (sig != null && sig.equals(storedSig)) continue;', 'signature check')
    rep('                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putBoolean(flag, true).apply();',
        '                if (sig != null) {' + chr(10) +
        '                    getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(flag, sig).apply();' + chr(10) +
        '                }', 'signature store')
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('done')