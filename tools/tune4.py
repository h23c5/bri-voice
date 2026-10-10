# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

# 1) BRE is an offset from neutral 64, not an absolute amount: 64 used to mean 'half breath',
#    which put an audible puff in front of every single note
rep('    v[1] = param(part, "BRE", tick, 64) / 64.0;', "    // 64 is neutral: anything below it adds no breath at all (the parameter is an offset, not a\n    // level). Reading it as a level put a puff of air in front of every note.\n    v[1] = clamp((param(part, \"BRE\", tick, 64) - 64.0) / 63.0, 0.0, 1.0);", 'BRE offset')

# 2) consonants carry the articulation: -12 dB made every syllable a mush
rep("                case PhonemeDef.T_FRIC:\n                case PhonemeDef.T_AFFRIC:\n                case PhonemeDef.T_ASPIR:\n                    g *= 0.25 * vm.consonantGain;\n                    break;", "                case PhonemeDef.T_FRIC:\n                case PhonemeDef.T_AFFRIC:\n                    g *= 0.45 * vm.consonantGain;   // was 0.25: too quiet to identify the syllable\n                    break;\n                case PhonemeDef.T_ASPIR:\n                    g *= 0.35 * vm.consonantGain;\n                    break;", 'fricative level')
rep('                    g *= 0.60 * vm.consonantGain;',
    '                    g *= 0.75 * vm.consonantGain;   // nasals/liquids/glides: keep them audible', 'sonorant level')

# 3) the glottal noise floor on every vowel was another source of hiss
rep('            float noise = src.noiseOnly(breath * 0.5 + 0.05);',
    '            float noise = src.noiseOnly(breath * 0.5 + 0.02);', 'noise floor')

# 4) glide between notes that are only slightly apart, not just touching ones
rep('        if (a - pe <= 0.02 && porMs > 3) {',
    '        if (a - pe <= 0.06 && porMs > 3) {      // also glide across small gaps', 'glide window')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderer patched')