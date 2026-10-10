import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.dsh.v4tune.format.MidiReader;
import com.dsh.v4tune.format.Vsq2Reader;
import com.dsh.v4tune.format.Vsq2Writer;
import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Desktop acceptance test for the legacy VOCALOID2 ".vsq" support and the MIDI importer.
 *
 * <pre>
 * a)  build a two track Song (chinese + japanese lyrics, tempo map, time signatures, curves),
 *     write it as .vsq, read it back, compare every field (documented differences are listed)
 * a2) same for a chinese only song: verifies the GBK code page path
 * b)  import every real .mid under E:\自己作品, print tracks / notes / tempo map / tick range
 * c)  write an imported MIDI song as .vsq and read it back (round trip)
 * d)  print PASS / FAIL and exit non zero on failure
 * </pre>
 *
 * Differences that are accepted by design (printed as "note", never counted as failures):
 * Song.schema (2 marks a legacy document), Voice.name / Voice.lang (a .vsq stores only the
 * component id), part structure (the legacy format has no parts) and - for MIDI imports -
 * Song.masterName and the part names.
 */
public class LegacySelftest {

    static int checks;
    static int failures;
    static int diffs;
    static final StringBuilder REPORT = new StringBuilder();
    static final String MIDI_ROOT = "E:\\自己作品";

    public static void main(String[] args) throws Exception {
        File out = new File("build/legacyout");
        if (!out.isDirectory() && !out.mkdirs()) {
            System.out.println("cannot create " + out.getAbsolutePath());
        }

        System.out.println("=== a) .vsq write -> read round trip (2 tracks, CN + JP lyrics) ===");
        Song song = buildSong();
        File f = new File(out, "selftest.vsq");
        Vsq2Writer.writeFile(song, f);
        System.out.println("  wrote " + f.getAbsolutePath() + "  " + f.length() + " bytes, charset="
                + Vsq2Writer.lastCharset);
        printHead(f, 52);
        Song back = Vsq2Reader.readFile(f);
        System.out.println("  read  charset=" + Vsq2Reader.lastCharset + " header=\"" + Vsq2Reader.lastHeader
                + "\" version=" + Vsq2Reader.lastVersion + " preMeasure=" + Vsq2Reader.lastPreMeasure
                + " singleTrackLayout=" + Vsq2Reader.lastWasSingleTrack);
        compareSong("a", song, back, true);
        check("a: 2 tracks survive", back.tracks.size() == 2, "tracks=" + back.tracks.size());
        check("a: notes survive", noteCount(back) == noteCount(song), "notes=" + noteCount(back));
        check("a: legacy charset chosen", Vsq2Reader.lastCharset.equals("MS932")
                || Vsq2Reader.lastCharset.equals("GBK"), Vsq2Reader.lastCharset);

        System.out.println();
        System.out.println("=== a2) chinese only song: GBK path ===");
        Song cn = buildChineseSong();
        File f2 = new File(out, "selftest_cn.vsq");
        Vsq2Writer.writeFile(cn, f2);
        System.out.println("  wrote " + f2.length() + " bytes, charset=" + Vsq2Writer.lastCharset);
        Song cnBack = Vsq2Reader.readFile(f2);
        System.out.println("  read  charset=" + Vsq2Reader.lastCharset);
        compareSong("a2", cn, cnBack, false);
        check("a2: GBK code page chosen", "GBK".equals(Vsq2Writer.lastCharset)
                && "GBK".equals(Vsq2Reader.lastCharset),
                "write=" + Vsq2Writer.lastCharset + " read=" + Vsq2Reader.lastCharset);
        check("a2: chinese lyric intact", !cnBack.tracks.isEmpty()
                && !cnBack.tracks.get(0).parts.get(0).notes.isEmpty()
                && "爱".equals(cnBack.tracks.get(0).parts.get(0).notes.get(0).lyric),
                "lyric=" + (cnBack.tracks.isEmpty() ? "-" : cnBack.tracks.get(0).parts.get(0).notes.get(0).lyric));

        System.out.println();
        System.out.println("=== a3) genuine VOCALOID2 layout (single track, no [Track...] section) ===");
        Song legacy = readLegacySample(out, "legacy_v2_sample.vsq", "MS932", "ら", "4 a", "き", "k i");
        info("single track layout = " + Vsq2Reader.lastWasSingleTrack);
        check("a3: one track", legacy.tracks.size() == 1, "tracks=" + legacy.tracks.size());
        check("a3: two notes", noteCount(legacy) == 2, "notes=" + noteCount(legacy));
        check("a3: track name from [Common] Name", legacy.tracks.size() == 1
                && "Voice1".equals(legacy.tracks.get(0).name), legacy.tracks.isEmpty() ? "-" : legacy.tracks.get(0).name);
        check("a3: lyric and phoneme from the L0 handle", noteCount(legacy) == 2
                && "ら".equals(legacy.tracks.get(0).parts.get(0).notes.get(0).lyric)
                && "4 a".equals(legacy.tracks.get(0).parts.get(0).notes.get(0).phoneme),
                noteCount(legacy) == 2 ? legacy.tracks.get(0).parts.get(0).notes.get(0).lyric + "/"
                        + legacy.tracks.get(0).parts.get(0).notes.get(0).phoneme : "-");
        check("a3: singer component id", !legacy.voices.isEmpty()
                && "ABCDEFGHIJKLMNOP".equals(legacy.voices.get(0).compID),
                legacy.voices.isEmpty() ? "-" : legacy.voices.get(0).compID);
        check("a3: default tempo 120 when [MasterTrack] is absent", !legacy.tempos.isEmpty()
                && legacy.tempos.get(0)[1] == 12000, tempoText(legacy));
        check("a3: note ticks stay absolute", noteCount(legacy) == 2
                && legacy.tracks.get(0).parts.get(0).notes.get(1).pos == 1920L,
                noteCount(legacy) == 2 ? String.valueOf(legacy.tracks.get(0).parts.get(0).notes.get(1).pos) : "-");

        System.out.println();
        System.out.println("=== a4) genuine VOCALOID2 layout written in GBK (bytes are valid Shift-JIS) ===");
        Song legacyCn = readLegacySample(out, "legacy_v2_cn.vsq", "GBK", "爱", "ai", "这", "ts 7");
        check("a4: GBK detected although the bytes decode as Shift-JIS", "GBK".equals(Vsq2Reader.lastCharset),
                Vsq2Reader.lastCharset);
        check("a4: chinese lyric survives", noteCount(legacyCn) == 2
                && "爱".equals(legacyCn.tracks.get(0).parts.get(0).notes.get(0).lyric),
                noteCount(legacyCn) == 2 ? legacyCn.tracks.get(0).parts.get(0).notes.get(0).lyric : "-");

        System.out.println();
        System.out.println("=== b) MIDI import: every .mid under " + MIDI_ROOT + " ===");
        List<File> mids = new ArrayList<File>();
        collect(new File(MIDI_ROOT), mids, 0);
        Collections.sort(mids, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });
        System.out.println("  found " + mids.size() + " file(s)");
        check("b: at least one .mid found", !mids.isEmpty(), "count=" + mids.size());
        Song firstImported = null;
        for (int i = 0; i < mids.size(); i++) {
            File m = mids.get(i);
            Song s;
            try {
                s = MidiReader.readFile(m);
            } catch (Exception e) {
                check("b: import " + m.getName(), false, e.toString());
                continue;
            }
            int n = noteCount(s);
            long lo = Long.MAX_VALUE;
            long hi = 0;
            for (Track t : s.tracks) {
                for (Part p : t.parts) {
                    for (Note nt : p.notes) {
                        lo = Math.min(lo, nt.pos);
                        hi = Math.max(hi, nt.end());
                    }
                }
            }
            System.out.println("  " + m.getName());
            System.out.println("    path      : " + m.getAbsolutePath());
            System.out.println("    format=" + MidiReader.lastFormat + " division=" + MidiReader.lastDivision
                    + " smpte=" + MidiReader.lastWasSmpte + " mtrk=" + MidiReader.lastTrackCount);
            System.out.println("    tracks=" + s.tracks.size() + " notes=" + n
                    + " tickRange=[" + (n == 0 ? 0 : lo) + ".." + hi + "]");
            System.out.println("    tempos=" + tempoText(s) + "  timeSigs=" + sigText(s));
            check("b: " + m.getName() + " has notes", n > 0, "notes=" + n);
            check("b: " + m.getName() + " has a tempo map", !s.tempos.isEmpty(), "tempos=" + s.tempos.size());
            check("b: " + m.getName() + " tempo at tick 0", s.tempos.get(0)[0] == 0, "first=" + s.tempos.get(0)[0]);
            boolean okDur = true;
            boolean okKey = true;
            for (Track t : s.tracks) {
                for (Part p : t.parts) {
                    for (Note nt : p.notes) {
                        if (nt.dur < 1) okDur = false;
                        if (nt.key < 0 || nt.key > 127) okKey = false;
                    }
                }
            }
            check("b: " + m.getName() + " durations >= 1", okDur, "");
            check("b: " + m.getName() + " keys in 0..127", okKey, "");
            if (firstImported == null && n > 0) firstImported = s;
        }
        if (firstImported == null) firstImported = Song.empty();
        check("b: His Theme.mid note count plausible", plausibleHisTheme(mids), "see printed counts");

        System.out.println();
        System.out.println("=== c) imported MIDI -> .vsq -> read back ===");
        File f3 = new File(out, "from_midi.vsq");
        Vsq2Writer.writeFile(firstImported, f3);
        Song midiBack = Vsq2Reader.readFile(f3);
        System.out.println("  wrote " + f3.length() + " bytes (" + Vsq2Writer.lastCharset + "), read with "
                + Vsq2Reader.lastCharset);
        int before = noteCount(firstImported);
        int after = noteCount(midiBack);
        check("c: note count", before == after, before + " -> " + after);
        compareTracksAndNotes("c", firstImported, midiBack);
        check("c: tempo map", sameTempos(firstImported, midiBack),
                tempoText(firstImported) + " -> " + tempoText(midiBack));
        check("c: time signatures", sameSigs(firstImported, midiBack),
                sigText(firstImported) + " -> " + sigText(midiBack));

        System.out.println();
        System.out.println("checks=" + checks + " failures=" + failures + " field diffs=" + diffs);
        if (diffs > 0 || failures > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ------------------------------------------------------------------ genuine V2 file

    /**
     * Writes the exact layout of a real VOCALOID2 file (the vsqwrite.exe template: no [Track...],
     * no [MasterTrack], one singer event and two notes with lyric handles) as Shift-JIS bytes and
     * reads it back with the production reader.
     */
    static Song readLegacySample(File dir, String fileName, String charset, String l1, String p1,
                                 String l2, String p2) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("VOCALOID2 Project File1.0\n");
        b.append("[Common]\nVersion=DSB301\nName=Voice1\nColor=181,162,123\nDynamicsMode=1\nPlayMode=1\n");
        b.append("[Master]\nPreMeasure=1\n");
        b.append("[Mixer]\nMasterFeder=0\nMasterPanpot=0\nMasterMute=0\nOutputMode=0\nTracks=1\n");
        b.append("Feder0=0\nPanpot0=0\nMute0=0\nSolo0=0\n");
        b.append("[EventList]\n0=ID#0000,ID#0001\n1920=ID#0002\n2400=EOS\n");
        b.append("[ID#0000]\nType=Singer\nIconHandle=h#0000\n");
        b.append("[ID#0001]\nType=Anote\nLength=480\nNote#=60\nDynamics=64\nPMBendDepth=8\n");
        b.append("PMBendLength=0\nPMbPortamentoUse=0\nDEMdecGainRate=50\nDEMaccent=50\nLyricHandle=h#0001\n");
        b.append("[ID#0002]\nType=Anote\nLength=480\nNote#=62\nDynamics=64\nPMBendDepth=8\n");
        b.append("PMBendLength=0\nPMbPortamentoUse=0\nDEMdecGainRate=50\nDEMaccent=50\nLyricHandle=h#0002\n");
        b.append("[h#0000]\nIconID=$07010000\nIDS=ABCDEFGHIJKLMNOP\nOriginal=0\nCaption=\nLength=1\n");
        b.append("Language=0\nProgram=0\n");
        b.append("[h#0001]\nL0=\"").append(l1).append("\",\"").append(p1).append("\",1.000000,0,0\n");
        b.append("[h#0002]\nL0=\"").append(l2).append("\",\"").append(p2).append("\",1.000000,0,0\n");
        b.append("[DynamicsBPList]\n0=64\n480=90\n");
        java.io.File f = new java.io.File(dir, fileName);
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        o.write(b.toString().getBytes(java.nio.charset.Charset.forName(charset)));
        o.close();
        Song s = Vsq2Reader.readFile(f);
        System.out.println("  read " + f.length() + " bytes, charset=" + Vsq2Reader.lastCharset
                + " header=\"" + Vsq2Reader.lastHeader + "\" version=" + Vsq2Reader.lastVersion);
        return s;
    }

    // ------------------------------------------------------------------ test songs

    static Song buildSong() {
        Song s = new Song();
        s.masterName = "LegacySelftest";
        Voice v1 = new Voice();
        v1.bs = 0;
        v1.pc = 0;
        v1.compID = "BK8H76TAEHXWSKDB";
        v1.name = v1.compID;
        Voice v2 = new Voice();
        v2.bs = 1;
        v2.pc = 0;
        v2.compID = "BETDB8W6KWZPYEB9";
        v2.name = v2.compID;
        s.voices.add(v1);
        s.voices.add(v2);
        s.tempos.add(new long[]{0, 12000});
        s.tempos.add(new long[]{1920, 14000});
        s.tempos.add(new long[]{7680, 9000});
        s.timeSigs.add(new int[]{0, 4, 4});
        s.timeSigs.add(new int[]{3840, 3, 4});

        Track t1 = new Track();
        t1.no = 0;
        t1.name = "主唱_中文";
        t1.comment = "chinese";
        t1.vol = 5;
        t1.pan = 64;
        Part p1 = new Part();
        p1.pos = 0;
        p1.name = t1.name;
        p1.comment = t1.comment;
        p1.bs = 0;
        p1.pc = 0;
        p1.notes.add(note(0, 480, 60, 64, "你", "n i", 0, 0));
        p1.notes.add(note(480, 480, 62, 80, "好", "x AU", 1, 0));
        p1.notes.add(note(960, 960, 64, 100, "吗", "m a", 0, 1));
        p1.notes.add(vibNote(1920, 960, 67, 90, "爱", "ai", 1, 66, 70, 58));
        p1.notes.add(note(2880, 240, 65, 70, "的", "d 7", 1, 1));
        p1.ctrls.put("DYN", curve(64, 0, 127, new double[][]{{0, 64}, {480, 90}, {960, 40}}));
        p1.ctrls.put("PIT", curve(0, -8192, 8191, new double[][]{{0, 0}, {240, -1000}, {480, 2000}}));
        p1.ctrls.put("OPE", curve(127, 0, 127, new double[][]{{0, 127}, {480, 64}}));
        p1.dur = 3120;
        t1.parts.add(p1);

        Track t2 = new Track();
        t2.no = 1;
        t2.name = "Harmony JP";
        t2.comment = "日本語";
        Part p2 = new Part();
        p2.pos = 0;
        p2.name = t2.name;
        p2.comment = t2.comment;
        p2.bs = 1;
        p2.pc = 0;
        p2.notes.add(note(0, 480, 55, 64, "ら", "4 a", 0, 0));
        p2.notes.add(note(480, 480, 57, 64, "き", "k i", 0, 0));
        p2.notes.add(vibNote(960, 480, 59, 64, "ゅ", "4 u", 1, 66, 64, 50));
        p2.ctrls.put("BRE", curve(0, 0, 127, new double[][]{{0, 0}, {480, 20}}));
        p2.ctrls.put("PBS", curve(2, 0, 24, new double[][]{{0, 2}}));
        p2.ctrls.put("GEN", curve(64, 0, 127, new double[][]{{0, 64}, {480, 100}}));
        p2.dur = 1440;
        t2.parts.add(p2);
        s.tracks.add(t1);
        s.tracks.add(t2);
        return s;
    }

    static Song buildChineseSong() {
        Song s = new Song();
        s.masterName = "中文测试";
        Voice v = new Voice();
        v.bs = 4;
        v.pc = 1;
        v.compID = "BCXNNPJJQZRQZ8B5";
        v.name = v.compID;
        v.lang = 2;
        s.voices.add(v);
        s.tempos.add(new long[]{0, 10000});
        s.timeSigs.add(new int[]{0, 4, 4});
        Track t = new Track();
        t.no = 0;
        t.name = "人声";
        t.comment = "中文";
        Part p = new Part();
        p.pos = 0;
        p.name = t.name;
        p.comment = t.comment;
        p.bs = 4;
        p.pc = 1;
        p.notes.add(note(0, 480, 62, 64, "爱", "ai", 0, 0));
        p.notes.add(note(480, 480, 64, 64, "这", "ts 7", 0, 0));
        p.dur = 960;
        t.parts.add(p);
        s.tracks.add(t);
        return s;
    }

    static Note note(long pos, long dur, int key, int vel, String lyric, String ph, int rise, int fall) {
        Note n = new Note();
        n.pos = pos;
        n.dur = dur;
        n.key = key;
        n.vel = vel;
        n.lyric = lyric;
        n.phoneme = ph;
        n.style.put("bendDep", 8.0);
        n.style.put("bendLen", 0.0);
        n.style.put("decay", 50.0);
        n.style.put("accent", 50.0);
        n.style.put("risePort", (double) rise);
        n.style.put("fallPort", (double) fall);
        return n;
    }

    static Note vibNote(long pos, long dur, int key, int vel, String lyric, String ph, int type,
                        int len, int dep, int rate) {
        Note n = note(pos, dur, key, vel, lyric, ph, 0, 0);
        n.style.put("vibType", (double) type);
        n.style.put("vibLen", (double) len);
        ControlCurve d = new ControlCurve(64, 0, 127);
        d.putRaw(0, dep);
        n.ctrls.put("vibDep", d);
        ControlCurve r = new ControlCurve(50, 0, 127);
        r.putRaw(0, rate);
        n.ctrls.put("vibRate", r);
        return n;
    }

    static ControlCurve curve(double def, double min, double max, double[][] pts) {
        ControlCurve c = new ControlCurve(def, min, max);
        for (int i = 0; i < pts.length; i++) c.putRaw((long) pts[i][0], pts[i][1]);
        return c;
    }

    // ------------------------------------------------------------------ comparison

    static void compareSong(String label, Song a, Song b, boolean voices) {
        if (!a.masterName.equals(b.masterName)) {
            diff(label + ": masterName \"" + a.masterName + "\" -> \"" + b.masterName + "\"");
        }
        if (a.schema != b.schema) {
            info(label + ": schema " + a.schema + " -> " + b.schema + " (legacy document marker)");
        }
        if (!sameTempos(a, b)) diff(label + ": tempo map " + tempoText(a) + " -> " + tempoText(b));
        if (!sameSigs(a, b)) diff(label + ": timeSigs " + sigText(a) + " -> " + sigText(b));
        if (a.tracks.size() != b.tracks.size()) {
            diff(label + ": track count " + a.tracks.size() + " -> " + b.tracks.size());
        }
        int nt = Math.min(a.tracks.size(), b.tracks.size());
        for (int i = 0; i < nt; i++) {
            Track x = a.tracks.get(i);
            Track y = b.tracks.get(i);
            String t = label + ": track[" + i + "]";
            if (!x.name.equals(y.name)) diff(t + " name \"" + x.name + "\" -> \"" + y.name + "\"");
            if (!x.comment.equals(y.comment)) diff(t + " comment \"" + x.comment + "\" -> \"" + y.comment + "\"");
            if (x.vol != y.vol) diff(t + " vol " + x.vol + " -> " + y.vol);
            if (x.pan != y.pan) diff(t + " pan " + x.pan + " -> " + y.pan);
            if (x.mute != y.mute) diff(t + " mute " + x.mute + " -> " + y.mute);
            if (x.solo != y.solo) diff(t + " solo " + x.solo + " -> " + y.solo);
            if (x.parts.size() != 1) {
                info(t + ": " + x.parts.size() + " parts -> " + y.parts.size()
                        + " parts (the legacy format has no parts, they are merged)");
            }
            if (y.parts.isEmpty()) {
                diff(t + " has no part after read");
                continue;
            }
            Part xp = x.parts.get(0);
            Part yp = y.parts.get(0);
            if (x.parts.size() == 1 && xp.dur != yp.dur) diff(t + " part dur " + xp.dur + " -> " + yp.dur);
            List<Note> an = new ArrayList<Note>();
            for (int k = 0; k < x.parts.size(); k++) an.addAll(x.parts.get(k).notes);
            Collections.sort(an, new Comparator<Note>() {
                public int compare(Note m, Note n) {
                    if (m.pos != n.pos) return m.pos < n.pos ? -1 : 1;
                    return m.key < n.key ? -1 : (m.key > n.key ? 1 : 0);
                }
            });
            List<Note> bn = new ArrayList<Note>(yp.notes);
            if (an.size() != bn.size()) diff(t + " note count " + an.size() + " -> " + bn.size());
            int nn = Math.min(an.size(), bn.size());
            for (int k = 0; k < nn; k++) {
                Note m = an.get(k);
                Note n = bn.get(k);
                String q = t + " note[" + k + "]";
                if (m.pos != n.pos) diff(q + " pos " + m.pos + " -> " + n.pos);
                if (m.dur != n.dur) diff(q + " dur " + m.dur + " -> " + n.dur);
                if (m.key != n.key) diff(q + " key " + m.key + " -> " + n.key);
                if (m.vel != n.vel) diff(q + " vel " + m.vel + " -> " + n.vel);
                if (!m.lyric.equals(n.lyric)) diff(q + " lyric \"" + m.lyric + "\" -> \"" + n.lyric + "\"");
                if (!m.phoneme.equals(n.phoneme)) {
                    diff(q + " phoneme \"" + m.phoneme + "\" -> \"" + n.phoneme + "\"");
                }
                compareCurve(q + " vibDep", m.ctrls.get("vibDep"), n.ctrls.get("vibDep"));
                compareCurve(q + " vibRate", m.ctrls.get("vibRate"), n.ctrls.get("vibRate"));
                compareStyle(q, m, n, "vibType");
                compareStyle(q, m, n, "vibLen");
                compareStyle(q, m, n, "bendDep");
                compareStyle(q, m, n, "bendLen");
                compareStyle(q, m, n, "decay");
                compareStyle(q, m, n, "accent");
                compareStyle(q, m, n, "risePort");
                compareStyle(q, m, n, "fallPort");
            }
            for (String key : xp.ctrls.keySet()) {
                compareCurve(t + " curve " + key, xp.ctrls.get(key), yp.ctrls.get(key));
            }
            if (voices) {
                if (xp.bs != yp.bs) diff(t + " singer bs " + xp.bs + " -> " + yp.bs);
                if (xp.pc != yp.pc) diff(t + " singer pc " + xp.pc + " -> " + yp.pc);
            }
        }
        if (voices) {
            if (a.voices.size() != b.voices.size()) {
                diff(label + ": voice count " + a.voices.size() + " -> " + b.voices.size());
            }
            int nv = Math.min(a.voices.size(), b.voices.size());
            for (int i = 0; i < nv; i++) {
                Voice x = a.voices.get(i);
                Voice y = b.voices.get(i);
                String t = label + ": voice[" + i + "]";
                if (x.bs != y.bs) diff(t + " bs " + x.bs + " -> " + y.bs);
                if (x.pc != y.pc) diff(t + " pc " + x.pc + " -> " + y.pc);
                if (!x.compID.equals(y.compID)) diff(t + " compID \"" + x.compID + "\" -> \"" + y.compID + "\"");
                if (!x.name.equals(y.name)) info(t + " name \"" + x.name + "\" -> \"" + y.name + "\" (not stored)");
                if (x.lang != y.lang) info(t + " lang " + x.lang + " -> " + y.lang + " (inferred)");
            }
        }
        checks++;
        if (REPORT.length() > 0) {
            System.out.print(REPORT);
            REPORT.setLength(0);
        } else {
            System.out.println("  " + label + ": all compared fields identical");
        }
    }

    static void compareTracksAndNotes(String label, Song a, Song b) {
        if (a.tracks.size() != b.tracks.size()) {
            diff(label + ": track count " + a.tracks.size() + " -> " + b.tracks.size());
        }
        int nt = Math.min(a.tracks.size(), b.tracks.size());
        for (int i = 0; i < nt; i++) {
            Track x = a.tracks.get(i);
            Track y = b.tracks.get(i);
            int nx = x.parts.isEmpty() ? 0 : x.parts.get(0).notes.size();
            int ny = y.parts.isEmpty() ? 0 : y.parts.get(0).notes.size();
            if (nx != ny) {
                diff(label + ": track[" + i + "] notes " + nx + " -> " + ny);
                continue;
            }
            if (nx == 0) continue;
            List<Note> an = new ArrayList<Note>(x.parts.get(0).notes);
            List<Note> bn = new ArrayList<Note>(y.parts.get(0).notes);
            for (int k = 0; k < an.size(); k++) {
                Note m = an.get(k);
                Note n = bn.get(k);
                String q = label + ": track[" + i + "] note[" + k + "]";
                if (m.pos != n.pos) diff(q + " pos " + m.pos + " -> " + n.pos);
                if (m.dur != n.dur) diff(q + " dur " + m.dur + " -> " + n.dur);
                if (m.key != n.key) diff(q + " key " + m.key + " -> " + n.key);
                if (m.vel != n.vel) diff(q + " vel " + m.vel + " -> " + n.vel);
            }
        }
    }

    static void compareCurve(String what, ControlCurve a, ControlCurve b) {
        if (a == null && b == null) return;
        if (a == null || b == null) {
            diff(what + " " + (a == null ? "absent" : "present") + " -> " + (b == null ? "absent" : "present"));
            return;
        }
        if (a.points().size() != b.points().size()) {
            diff(what + " points " + a.points().size() + " -> " + b.points().size());
            return;
        }
        for (int i = 0; i < a.points().size(); i++) {
            if (a.points().get(i).t != b.points().get(i).t || a.points().get(i).v != b.points().get(i).v) {
                diff(what + " point[" + i + "] (" + a.points().get(i).t + "," + a.points().get(i).v + ") -> ("
                        + b.points().get(i).t + "," + b.points().get(i).v + ")");
            }
        }
    }

    static void compareStyle(String what, Note a, Note b, String key) {
        if (!a.style.containsKey(key) && !b.style.containsKey(key)) return;
        double x = a.st(key, 0);
        double y = b.st(key, 0);
        if (x != y) diff(what + " style " + key + " " + x + " -> " + y);
    }

    static boolean sameTempos(Song a, Song b) {
        if (a.tempos.size() != b.tempos.size()) return false;
        for (int i = 0; i < a.tempos.size(); i++) {
            if (a.tempos.get(i)[0] != b.tempos.get(i)[0] || a.tempos.get(i)[1] != b.tempos.get(i)[1]) return false;
        }
        return true;
    }

    static boolean sameSigs(Song a, Song b) {
        if (a.timeSigs.size() != b.timeSigs.size()) return false;
        for (int i = 0; i < a.timeSigs.size(); i++) {
            if (a.timeSigs.get(i)[0] != b.timeSigs.get(i)[0]
                    || a.timeSigs.get(i)[1] != b.timeSigs.get(i)[1]
                    || a.timeSigs.get(i)[2] != b.timeSigs.get(i)[2]) return false;
        }
        return true;
    }

    static String tempoText(Song s) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < s.tempos.size() && i < 12; i++) {
            if (i > 0) b.append(", ");
            b.append(s.tempos.get(i)[0]).append('@').append(s.tempos.get(i)[1] / 100.0);
        }
        if (s.tempos.size() > 12) b.append(", ...(").append(s.tempos.size()).append(')');
        return b.append('}').toString();
    }

    static String sigText(Song s) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < s.timeSigs.size() && i < 12; i++) {
            if (i > 0) b.append(", ");
            b.append(s.timeSigs.get(i)[0]).append(' ').append(s.timeSigs.get(i)[1]).append('/')
                    .append(s.timeSigs.get(i)[2]);
        }
        return b.append('}').toString();
    }

    static int noteCount(Song s) {
        int n = 0;
        for (Track t : s.tracks) for (Part p : t.parts) n += p.notes.size();
        return n;
    }

    // ------------------------------------------------------------------ helpers

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what + (detail.length() > 0 ? "  [" + detail + "]" : ""));
        } else {
            failures++;
            System.out.println("  FAIL " + what + "  [" + detail + "]");
        }
    }

    static void diff(String msg) {
        diffs++;
        if (REPORT.length() < 8000) REPORT.append("  DIFF ").append(msg).append('\n');
    }

    static void info(String msg) {
        System.out.println("  note " + msg);
    }

    static void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isDirectory()) collect(f, out, depth + 1);
            else if (f.getName().toLowerCase().endsWith(".mid")) out.add(f);
        }
    }

    static boolean plausibleHisTheme(List<File> mids) {
        for (int i = 0; i < mids.size(); i++) {
            if (!"His Theme.mid".equalsIgnoreCase(mids.get(i).getName())) continue;
            try {
                Song s = MidiReader.readFile(mids.get(i));
                int n = noteCount(s);
                System.out.println("    (His Theme.mid: " + n + " notes, " + s.tracks.size()
                        + " tracks, vsqx reference 416 notes)");
                return n >= 400 && n <= 20000;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    static void printHead(File f, int lines) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
            in.close();
            String text = Vsq2Reader.decode(all.toByteArray());
            int start = 0;
            for (int i = 0; i < lines; i++) {
                int e = text.indexOf(10, start);
                if (e < 0) break;
                String line = text.substring(start, e);
                start = e + 1;
                if (line.length() > 0 && line.charAt(line.length() - 1) == 13) {
                    line = line.substring(0, line.length() - 1);
                }
                System.out.println("    | " + line);
            }
        } catch (Exception e) {
            System.out.println("    (cannot dump: " + e + ")");
        }
    }
}
