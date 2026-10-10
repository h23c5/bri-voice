# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
old = '''                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void packHelp() {'''
new = '''                    }
                });
    }

    private void packHelp() {'''
if old not in s: raise SystemExit('tail not found')
s = s.replace(old, new, 1)
# localise the nested confirm while we are here
s = s.replace('.setTitle("删除声库")', '.setTitle(L.t("dlg.pack.del.confirm.title"))', 1)
s = s.replace('.setMessage("确定删除「" + singerName(m) + "」吗？\\n只会删除这个包文件，不影响内置歌姬。")', '.setMessage(L.t("dlg.pack.del.confirm.msg", singerName(m)))', 1)
s = s.replace('.setPositiveButton("删除", new DialogInterface.OnClickListener() {', '.setPositiveButton(L.t("dlg.delete"), new DialogInterface.OnClickListener() {', 1)
s = s.replace('.setNegativeButton("取消", null)', '.setNegativeButton(L.t("dlg.cancel"), null)')
s = s.replace('status(ok ? ("已删除声库: " + singerName(m)) : "删除失败");', 'status(ok ? L.t("status.pack.deleted", singerName(m)) : L.t("status.pack.del.fail"));', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('tail fixed + confirm localised')