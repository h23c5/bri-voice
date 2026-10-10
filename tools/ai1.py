# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ai\Json.java'
s = io.open(p, encoding='utf-8').read()
if 'class Raw' not in s:
    s = s.replace('    public static String obj(Object... kv) {',
'''    /** a pre-built JSON fragment, written verbatim instead of being quoted */
    public static final class Raw {
        final String s;
        Raw(String s) { this.s = s; }
    }

    public static Raw raw(String s) { return new Raw(s == null ? "null" : s); }

    public static String obj(Object... kv) {''', 1)
    s = s.replace('            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));' + chr(10) + '            else b.append(quote(String.valueOf(v)));' + chr(10) + '        }' + chr(10) + '        return b.append(\'}\').toString();',
                  '            else if (v instanceof Raw) b.append(((Raw) v).s);' + chr(10) +
                  '            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));' + chr(10) +
                  '            else b.append(quote(String.valueOf(v)));' + chr(10) + '        }' + chr(10) + '        return b.append(\'}\').toString();', 1)
    s = s.replace('            if (v == null) b.append("null");' + chr(10) + '            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));' + chr(10) + '            else b.append(quote(String.valueOf(v)));' + chr(10) + '        }' + chr(10) + '        return b.append(\'[\').toString();',
                  '            if (v == null) b.append("null");' + chr(10) +
                  '            else if (v instanceof Raw) b.append(((Raw) v).s);' + chr(10) +
                  '            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));' + chr(10) +
                  '            else b.append(quote(String.valueOf(v)));' + chr(10) + '        }' + chr(10) + '        return b.append(\'[\').toString();', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('Raw added:', 'class Raw' in io.open(p, encoding='utf-8').read())