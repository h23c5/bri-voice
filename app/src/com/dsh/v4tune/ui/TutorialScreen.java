package com.dsh.v4tune.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tutorial reader: a chapter rail on the left, a page-by-page body on the right.
 *
 * <p>Three ways of moving around, all over the same parsed text:
 * the left rail jumps to a chapter, the page box / arrows jump to an absolute page number
 * across the whole book, and the search box finds a keyword and opens the page it sits on
 * with every hit highlighted.</p>
 */
public final class TutorialScreen {

    /** how much text one page may hold before it is split */
    private static final int PAGE_LINES = 24;
    private static final int PAGE_CHARS = 1400;
    private static final int SEARCH_MAX = 60;

    private static final class Page {
        int chapter;          // index into chapters
        String title = "";
        String text = "";
        int from;             // char offset in the book
    }

    private static final class Hit {
        int page;
        String chapter = "";
        String snippet = "";
    }

    private final Activity a;
    private final String book;
    private final ArrayList<String> chapterTitles = new ArrayList<String>();
    private final ArrayList<Integer> chapterStarts = new ArrayList<Integer>();
    private final ArrayList<Page> pages = new ArrayList<Page>();

    private Dialog dialog;
    private ScrollView railScrollView;
    private LinearLayout railList;
    private View activeRow;
    private TextView railHint;
    private TextView titleView, pageInfo, content;
    private EditText searchBox, pageBox;
    private String query = "";
    private int curPage = 0;

    public TutorialScreen(Activity activity, String tutorialText) {
        this.a = activity;
        this.book = tutorialText == null ? "" : tutorialText;
        parse();
    }

    // ------------------------------------------------------------------ parsing

    private void parse() {
        String[] lines = book.split("\n", -1);
        int[] lineOffsets = new int[lines.length + 1];
        int acc = 0;
        for (int i = 0; i < lines.length; i++) {
            lineOffsets[i] = acc;
            acc += lines[i].length() + 1;
        }
        lineOffsets[lines.length] = acc;

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("【") && t.indexOf("】") > 1) {
                chapterTitles.add(t);
                chapterStarts.add(Integer.valueOf(lineOffsets[i]));
            }
        }
        if (chapterTitles.isEmpty()) return;

        for (int c = 0; c < chapterTitles.size(); c++) {
            int fromLine = lineOf(lineOffsets, chapterStarts.get(c).intValue(), lines.length);
            int toLine = (c + 1 < chapterTitles.size())
                    ? lineOf(lineOffsets, chapterStarts.get(c + 1).intValue(), lines.length)
                    : lines.length;
            int start = fromLine;
            while (start < toLine) {
                int end = start, chars = 0;
                while (end < toLine && end - start < PAGE_LINES) {
                    if (end > start && chars + lines[end].length() > PAGE_CHARS) break;
                    chars += lines[end].length() + 1;
                    end++;
                }
                if (end == start) end = start + 1;
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    if (i > start) sb.append('\n');
                    sb.append(lines[i]);
                }
                Page p = new Page();
                p.chapter = c;
                p.title = chapterTitles.get(c);
                p.text = sb.toString();
                p.from = lineOffsets[start];
                pages.add(p);
                start = end;
            }
        }
    }

    private static int lineOf(int[] offsets, int charOffset, int lineCount) {
        for (int i = 0; i < lineCount; i++) {
            if (offsets[i] >= charOffset) return i;
        }
        return lineCount;
    }

    // ------------------------------------------------------------------ layout

    public void show() {
        dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawableResource(android.R.color.black);
        dialog.setContentView(build());
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        if (!pages.isEmpty()) showPage(0);
    }

    public void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Throwable ignored) { }
            dialog = null;
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG_DEEP);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // ------------------------------------------------- top bar
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(a);
        name.setText("教程");
        name.setTextColor(Theme.TEXT);
        name.setTextSize(19f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name);

        View spacer = new View(a);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        pageInfo = new TextView(a);
        pageInfo.setTextColor(Theme.TEXT_DIM);
        pageInfo.setTextSize(12.5f);
        pageInfo.setPadding(0, 0, dp(10), 0);
        head.addView(pageInfo);

        head.addView(small("上一页", new View.OnClickListener() {
            public void onClick(View v) { showPage(curPage - 1); }
        }));
        pageBox = new EditText(a);
        pageBox.setSingleLine(true);
        pageBox.setTextColor(Theme.TEXT);
        pageBox.setTextSize(12.5f);
        pageBox.setHint("页码");
        pageBox.setGravity(Gravity.CENTER);
        pageBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pageBox.setBackground(Theme.card(a, Theme.SURFACE));
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(dp(64), dp(34));
        pblp.setMargins(dp(6), 0, dp(6), 0);
        head.addView(pageBox, pblp);
        head.addView(small("跳转", new View.OnClickListener() {
            public void onClick(View v) { jumpToTypedPage(); }
        }));
        head.addView(small("下一页", new View.OnClickListener() {
            public void onClick(View v) { showPage(curPage + 1); }
        }));
        head.addView(small("关闭", new View.OnClickListener() {
            public void onClick(View v) { dismiss(); }
        }));
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ------------------------------------------------- body
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        root.addView(body, bodyLp);

        // left: search + chapter rail
        LinearLayout rail = new LinearLayout(a);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackground(Theme.card(a, Theme.PANEL_DK));
        rail.setPadding(dp(10), dp(10), dp(10), dp(8));
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(dp(280),
                ViewGroup.LayoutParams.MATCH_PARENT);
        railLp.rightMargin = dp(10);
        body.addView(rail, railLp);

        railHint = new TextView(a);
        railHint.setText("章节");
        railHint.setTextColor(Theme.TEXT_DIM);
        railHint.setTextSize(11.5f);
        railHint.setPadding(dp(2), 0, 0, dp(6));
        rail.addView(railHint);

        searchBox = new EditText(a);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索教程：WAV / PIT / 导出");
        searchBox.setTextColor(Theme.TEXT);
        searchBox.setHintTextColor(Theme.TEXT_DIM);
        searchBox.setTextSize(12.5f);
        searchBox.setBackground(Theme.card(a, Theme.SURFACE));
        searchBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        rail.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            public void afterTextChanged(Editable e) {
                query = e.toString().trim();
                fillRail();
            }
        });

        ScrollView railScroll = new ScrollView(a);
        railScrollView = railScroll;
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rslp.topMargin = dp(8);
        rail.addView(railScroll, rslp);
        railList = new LinearLayout(a);
        railList.setOrientation(LinearLayout.VERTICAL);
        railScroll.addView(railList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // right: title + page
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        body.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        titleView = new TextView(a);
        titleView.setTextColor(Theme.ACCENT);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(dp(4), 0, 0, dp(6));
        right.addView(titleView);

        ScrollView pageScroll = new ScrollView(a);
        right.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new TextView(a);
        content.setTextColor(Theme.TEXT);
        content.setTextSize(13.5f);
        content.setLineSpacing(dp(3), 1f);
        content.setTextIsSelectable(true);
        content.setPadding(dp(4), dp(2), dp(4), dp(16));
        pageScroll.addView(content);

        fillRail();
        return root;
    }

    private Button small(String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(Theme.TEXT);
        b.setBackground(Theme.button(a, false));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------------ rail

    /** chapter buttons, replaced by search hits while a query is active */
    private void fillRail() {
        if (railList == null) return;
        railList.removeAllViews();
        activeRow = null;
        if (query.length() == 0) {
            railHint.setText("章节 · " + chapterTitles.size());
            for (int i = 0; i < chapterTitles.size(); i++) {
                final int chapter = i;
                View row = railRow(chapterTitles.get(i), "", isChapterOpen(chapter),
                        new View.OnClickListener() {
                            public void onClick(View v) { showPage(firstPageOf(chapter)); }
                        });
                if (isChapterOpen(chapter) && activeRow == null) activeRow = row;
                railList.addView(row);
            }
            scrollRailToActive();
            return;
        }
        List<Hit> hits = search(query);
        railHint.setText(hits.isEmpty() ? ("没有找到「" + query + "」")
                : ("找到 " + hits.size() + " 处 · 点一条跳过去"));
        railList.addView(railRow("← 返回章节列表", "", false, new View.OnClickListener() {
            public void onClick(View v) { searchBox.setText(""); }
        }));
        for (int i = 0; i < hits.size(); i++) {
            final Hit h = hits.get(i);
            View row = railRow(shorten(h.chapter), (h.page + 1) + " 页 · " + h.snippet,
                    h.page == curPage, new View.OnClickListener() {
                        public void onClick(View v) { showPage(h.page); }
                    });
            if (h.page == curPage && activeRow == null) activeRow = row;
            railList.addView(row);
        }
        // in search mode the "back to chapters" row must stay visible, so no auto-scroll here
        if (railScrollView != null) railScrollView.scrollTo(0, 0);
    }

    /** keeps the current chapter visible when the rail is longer than the screen */
    private void scrollRailToActive() {
        if (railScrollView == null || activeRow == null) return;
        railScrollView.post(new Runnable() {
            public void run() {
                int top = activeRow.getTop();
                int limit = Math.max(0, railList.getHeight() - railScrollView.getHeight());
                railScrollView.scrollTo(0, Math.min(limit, Math.max(0, top - dp(8))));
            }
        });
    }

    private View railRow(String title, String sub, boolean active, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.card(a, active ? Theme.SURFACE_HI : Theme.SURFACE));
        box.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(5));
        box.setLayoutParams(lp);
        box.setClickable(true);
        box.setOnClickListener(l);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(active ? Theme.ACCENT : Theme.TEXT);
        t.setTextSize(12.5f);
        box.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(Theme.TEXT_DIM);
            s.setTextSize(10.5f);
            s.setPadding(0, dp(2), 0, 0);
            box.addView(s);
        }
        return box;
    }

    private boolean isChapterOpen(int chapter) {
        return !pages.isEmpty() && curPage >= 0 && curPage < pages.size()
                && pages.get(curPage).chapter == chapter;
    }

    private String shorten(String s) {
        if (s.length() <= 18) return s;
        return s.substring(0, 17) + "…";
    }

    private int firstPageOf(int chapter) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).chapter == chapter) return i;
        return 0;
    }

    // ------------------------------------------------------------------ search

    private List<Hit> search(String q) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        String hay = book.toLowerCase(Locale.ROOT);
        String needle = q.toLowerCase(Locale.ROOT);
        if (needle.length() == 0) return out;
        int from = 0;
        while (out.size() < SEARCH_MAX) {
            int at = hay.indexOf(needle, from);
            if (at < 0) break;
            Hit h = new Hit();
            h.page = pageAt(at);
            Page p = pages.get(h.page);
            h.chapter = p.title;
            h.snippet = snippetAt(at, needle.length());
            boolean dup = false;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).page == h.page && out.get(i).snippet.equals(h.snippet)) { dup = true; break; }
            }
            if (!dup) out.add(h);
            from = at + Math.max(1, needle.length());
        }
        return out;
    }

    private int pageAt(int offset) {
        for (int i = pages.size() - 1; i >= 0; i--) {
            if (pages.get(i).from <= offset) return i;
        }
        return 0;
    }

    private String snippetAt(int at, int len) {
        int from = Math.max(0, at - 14);
        int to = Math.min(book.length(), at + len + 18);
        String s = book.substring(from, to).replace('\n', ' ').trim();
        return s;
    }

    // ------------------------------------------------------------------ pages

    private void showPage(int index) {
        if (pages.isEmpty()) return;
        if (index < 0) index = 0;
        if (index >= pages.size()) index = pages.size() - 1;
        curPage = index;
        Page p = pages.get(index);
        int inChapter = 0, chapterPages = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).chapter == p.chapter) {
                if (i < index) inChapter++;
                chapterPages++;
            }
        }
        titleView.setText(p.title + (chapterPages > 1 ? ("    (" + (inChapter + 1) + "/" + chapterPages + ")") : ""));
        pageInfo.setText("第 " + (index + 1) + " / " + pages.size() + " 页");
        content.setText(highlight(p.text, query));
        if (pageBox != null) pageBox.setText(String.valueOf(index + 1));
        fillRail();
    }

    private CharSequence highlight(String text, String q) {
        if (q == null || q.length() == 0) return text;
        String hay = text.toLowerCase(Locale.ROOT);
        String needle = q.toLowerCase(Locale.ROOT);
        SpannableString sp = new SpannableString(text);
        int from = 0;
        while (true) {
            int at = hay.indexOf(needle, from);
            if (at < 0) break;
            sp.setSpan(new BackgroundColorSpan(0x6635C8FF), at, at + needle.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = at + needle.length();
        }
        return sp;
    }

    private void jumpToTypedPage() {
        try {
            int n = Integer.parseInt(pageBox.getText().toString().trim());
            showPage(n - 1);
        } catch (Throwable ignored) {
            pageBox.setText(String.valueOf(curPage + 1));
        }
    }

    private int dp(float v) { return Theme.dpi(a, v); }
}
