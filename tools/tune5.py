# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

# 1) the bank's breath bias is a tint, not a level: at full strength it put a puff of air in
#    front of every note
rep('        hv.setBreath(clamp(pv[1] + vm.breathBias, 0, 1) * 0.5);',
    '        hv.setBreath(clamp(pv[1] + vm.breathBias * 0.35, 0, 1) * 0.5);', 'harmonic breath')
rep('            double breath = clamp(bre + vm.breathBias, 0, 1);',
    '            double breath = clamp(bre + vm.breathBias * 0.35, 0, 1);', 'formant breath')

# 2) consonants carry the articulation again (0.25 was -12 dB: inaudible)
rep("                case PhonemeDef.T_FRIC:\n                case PhonemeDef.T_AFFRIC:\n                case PhonemeDef.T_ASPIR:\n                    g *= 0.25 * vm.consonantGain;\n                    break;", "                case PhonemeDef.T_FRIC:\n                case PhonemeDef.T_AFFRIC:\n                    g *= 0.45 * vm.consonantGain;    // -12 dB made syllables unrecognisable\n                    break;\n                case PhonemeDef.T_ASPIR:\n                    g *= 0.35 * vm.consonantGain;\n                    break;", 'fricative level')
rep('                    g *= 0.60 * vm.consonantGain;',
    '                    g *= 0.75 * vm.consonantGain;    // nasals/liquids/glides stay audible', 'sonorant level')

# 3) glide across slightly separated notes too
rep('        if (a - pe <= 0.02 && porMs > 3) {',
    '        if (a - pe <= 0.06 && porMs > 3) {       // also glide across small gaps', 'glide window')

# 4) the gap-breath layer was the loudest offender: shorter, quieter, and reduced by BRE
rep('                int len = Math.min((int) (0.22 * fs), gap);',
    '                int len = Math.min((int) (0.12 * fs), gap);', 'breath gap length')
rep('                    out[idx] += (float) (nz.next() * e * e * 0.06 * vm.breathSoundAmount);',
    '                    out[idx] += (float) (nz.next() * e * e * 0.018 * Math.min(0.5, vm.breathSoundAmount));',
    'breath gap level')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderer patched')