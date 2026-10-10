package com.dsh.v4tune.model;

import java.util.ArrayList;

public class Track {
    public int no = 0;
    public String name = "Track";
    public String comment = "";
    public final ArrayList<Part> parts = new ArrayList<Part>();
    // mixer (vsUnit)
    public int vol = 0, pan = 0, inGain = 0, sendLevel = 0;
    public boolean mute = false, solo = false, sendEnable = false;

    public Part partAt(long tick) {
        for (Part p : parts) if (tick >= p.pos && tick < p.end()) return p;
        return null;
    }

    public Track copy() {
        Track t = new Track();
        t.no = no; t.name = name; t.comment = comment;
        t.vol = vol; t.pan = pan; t.inGain = inGain; t.sendLevel = sendLevel;
        t.mute = mute; t.solo = solo; t.sendEnable = sendEnable;
        for (Part p : parts) t.parts.add(p.copy());
        return t;
    }
}
