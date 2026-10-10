# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
if 'import android.widget.ScrollView;' not in s:
    s = s.replace('import android.widget.SeekBar;', 'import android.widget.ScrollView;' + chr(10) + 'import android.widget.SeekBar;', 1)
    print('ScrollView imported')
s = s.replace('final AlertDialog d = Dialogs.builder(this)', 'final AlertDialog d = com.dsh.v4tune.ui.Dialogs.builder(this)', 1)
print('Dialogs qualified')
io.open(p, 'w', encoding='utf-8', newline='').write(s)