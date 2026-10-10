package com.dsh.v4tune.model;

/** A vVoice entry (singer slot) as stored in the project file. */
public class Voice {
    public int bs = 0, pc = 0;
    public String compID = "";     // voicebank component id
    public String name = "";       // display name as stored in the project
    public int lang = 0;           // 0 = japanese, 1 = english, 2 = chinese (V4)
    public int bre = 0, bri = 0, cle = 0, gen = 0, ope = 0;   // vVoiceParam, -127..127

    public Voice copy() {
        Voice v = new Voice();
        v.bs = bs; v.pc = pc; v.compID = compID; v.name = name; v.lang = lang;
        v.bre = bre; v.bri = bri; v.cle = cle; v.gen = gen; v.ope = ope;
        return v;
    }
}
