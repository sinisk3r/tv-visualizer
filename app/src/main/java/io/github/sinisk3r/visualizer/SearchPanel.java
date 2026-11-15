package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Search card (menu redesign, phase 2; mockup screens 3 and 4), opened from the bar's Search button.
 *
 * Left to right: four rows of letters (or the number pad, swapped in place by "123" / "abc"), the mic on the
 * keyboard's right edge with the 123 key under it, then the results. Right from the mic column (or the pad's number)
 * enters the results, Up/Down scroll them, Left returns to the key you came from, OK plays.
 *
 * Every view is built once. Typing rewrites the field and a fixed set of result rows in place (the rows are a window
 * over the matches), so nothing is rebuilt, focus never jumps and long lists cost no views. Matching lives in
 * {@link SearchIndex}, the pad's rules in {@link Keypad}.
 *
 * Voice uses the system's speech input (RecognizerIntent): it runs in its own activity and hands back text, so the
 * app needs no extra permission. When the TV has none, the mic is hidden.
 */
final class SearchPanel {
    interface Host {
        void play(File preset);
        /** The pad's 0 / 0n / 00 / 00n targets: open the list or play its line. */
        void openList(Keypad.Target target);
    }

    static final int REQ_SPEECH = 41;
    private static final int ROWS = 5;
    private static final String[] LETTERS = {"abcdefg", "hijklmn", "opqrstu", "vwxyz"};

    private final Activity act;
    private final PresetLibrary library;
    private final Host host;
    private final LinearLayout card;

    private SearchIndex index;
    private String query = "";
    private final Keypad keypad = new Keypad();
    private boolean padMode;
    private List<SearchIndex.Entry> results = Collections.emptyList();
    private int offset;

    private TextView field, found, resultsTitle, resultsSub, more;
    private TextView padDigits, padTarget, padHint;
    private View letters, pad, micColumn, mic, padPlay, mode, firstLetter, firstDigit;
    private final List<View> resultRows = new ArrayList<>();
    private final List<TextView[]> resultTexts = new ArrayList<>();
    /** The key focus returns to on Left from the results. */
    private View lastKey;

    SearchPanel(Activity act, PresetLibrary library, Host host) {
        this.act = act;
        this.library = library;
        this.host = host;
        card = act.findViewById(R.id.card_search);
        build();
    }

    View card() {
        return card;
    }

    /** The card was just made visible: focus the mic (fastest way in), or the first key without one. */
    void shown() {
        if (index == null) index = buildIndex();
        update();
        View first = padMode ? firstDigit : mic.getVisibility() == View.VISIBLE ? mic : firstLetter;
        first.requestFocus();
    }

    private SearchIndex buildIndex() {
        List<SearchIndex.Entry> entries = new ArrayList<>(library.all.size());
        for (File f : library.all) {
            entries.add(new SearchIndex.Entry(f, library.numberOf(f), PresetLibrary.displayName(f),
                    library.styleOf(f)));
        }
        return new SearchIndex(entries);
    }

    /** Keys while the card is open, before the views get them. Returns true when the key was used. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // The remote's digits type on the pad; a keyboard's letters on the letters (adb input text, too).
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (down) typeDigit(code - KeyEvent.KEYCODE_0, true);
            return true;
        }
        if (!padMode && (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z || code == KeyEvent.KEYCODE_SPACE
                || code == KeyEvent.KEYCODE_DEL)) {
            if (down) {
                if (code == KeyEvent.KEYCODE_DEL) query = SearchIndex.deleted(query);
                else query = SearchIndex.typed(query, code == KeyEvent.KEYCODE_SPACE ? ' '
                        : (char) ('a' + code - KeyEvent.KEYCODE_A));
                update();
            }
            return true;
        }
        if (!down) return false;
        View focused = act.getCurrentFocus();
        if (focused == null || !isInside(focused, card)) return false;
        int row = resultRows.indexOf(focused);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row >= 0) return true;
                if (focused == mic || focused == mode || focused == padPlay) {
                    if (visibleRows() > 0) resultRows.get(0).requestFocus();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row < 0) return false;
                backToKeys();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row < 0) return false;
                if (row > 0) resultRows.get(row - 1).requestFocus();
                else if (offset > 0) {
                    offset--;
                    showResults();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < 0) return false;
                if (row < visibleRows() - 1) resultRows.get(row + 1).requestFocus();
                else if (offset + ROWS < results.size()) {
                    offset++;
                    showResults();
                }
                return true;
            default:
                return false;
        }
    }

    /** Voice came back: the words fill the query (letters mode). */
    void onSpeech(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        String q = SearchIndex.normalize(said.get(0));
        query = q.length() > SearchIndex.MAX_QUERY ? q.substring(0, SearchIndex.MAX_QUERY) : q;
        if (padMode) setPadMode(false, mic);
        update();
    }

    // --- state to views ---

    private void update() {
        if (index == null) return;
        if (padMode) {
            Keypad.Target t = keypad.target();
            results = t.kind == Keypad.Kind.PRESET ? index.byNumber(t.number) : Collections.emptyList();
        } else {
            results = index.search(query);
        }
        offset = 0;
        setIfChanged(field, query);
        setIfChanged(found, query.isEmpty() ? "" : results.size() == 1 ? "1 found"
                : String.format("%,d found", results.size()));
        if (padMode) showPad();
        showResults();
    }

    private void showPad() {
        String digits = keypad.digits();
        Keypad.Target t = keypad.target();
        setIfChanged(padDigits, digits + "_");
        String target, hint;
        switch (t.kind) {
            case PRESET: {
                File f = library.byNumber(t.number);
                boolean later = f == null && library.numberNeedsFullPack(t.number);
                target = f != null ? PresetLibrary.displayName(f) : later ? "In the full pack" : "No preset " + t.number;
                hint = f != null ? "OK plays " + t.number : later ? "Get all: Settings" : "";
                break;
            }
            case NONE:
                target = "Type a number";
                hint = "0 is Favourites";
                break;
            default:
                target = t.label();
                hint = "OK opens " + t.label();
        }
        setIfChanged(padTarget, target);
        setIfChanged(padHint, hint);
    }

    private void showResults() {
        for (int i = 0; i < ROWS; i++) {
            int at = offset + i;
            View r = resultRows.get(i);
            if (at >= results.size()) {
                // INVISIBLE, not GONE: the card keeps its height while typing. The card itself is shown anyway.
                r.setVisibility(View.INVISIBLE);
                continue;
            }
            SearchIndex.Entry e = results.get(at);
            TextView[] t = resultTexts.get(i);
            setIfChanged(t[0], e.number > 0 ? Integer.toString(e.number) : "");
            setIfChanged(t[1], e.name);
            setIfChanged(t[2], e.style);
            r.setVisibility(View.VISIBLE);
        }
        int below = results.size() - offset - ROWS;
        setIfChanged(more, below > 0 ? String.format("▼ %,d more", below) : offset > 0 ? "▲ more above" : "");
        Keypad.Target t = keypad.target();
        if (padMode) {
            setIfChanged(resultsTitle, t.kind == Keypad.Kind.PRESET ? "Channel " + t.number
                    : t.kind == Keypad.Kind.NONE ? "Channels" : t.label());
            setIfChanged(resultsSub, results.isEmpty() ? (t.kind == Keypad.Kind.NONE ? "0 Favourites  ·  00 Playlists"
                    : "") : "and the numbers that start with it  ·  ▶ Right to enter");
        } else {
            setIfChanged(resultsTitle, "Results");
            setIfChanged(resultsSub, query.isEmpty() ? "Type or speak a name" : results.isEmpty()
                    ? "No presets match" : "across all styles  ·  ▶ Right to enter");
        }
        // The focused row may have gone (fewer matches): go back to the keys.
        View focused = act.getCurrentFocus();
        int row = resultRows.indexOf(focused);
        if (row >= 0 && resultRows.get(row).getVisibility() != View.VISIBLE) backToKeys();
    }

    private int visibleRows() {
        return Math.max(0, Math.min(ROWS, results.size() - offset));
    }

    private void backToKeys() {
        View k = lastKey;
        if (k == null || !k.isShown()) k = padMode ? padPlay : mic.getVisibility() == View.VISIBLE ? mic : mode;
        k.requestFocus();
    }

    // --- keys ---

    private void typeDigit(int d, boolean fromRemote) {
        boolean added = keypad.add(d);
        if (!padMode) {
            // From the remote the focus is on a letter that is about to go: put it on the number.
            setPadMode(true, fromRemote ? padPlay : null);
        } else if (added) {
            update();
        }
    }

    /** Swaps the letters and the pad in place. Focus goes to focus, or to the swap key in the other mode. */
    private void setPadMode(boolean on, View focus) {
        padMode = on;
        letters.setVisibility(on ? View.GONE : View.VISIBLE);
        ((View) field.getParent()).setVisibility(on ? View.GONE : View.VISIBLE);
        micColumn.setVisibility(on ? View.GONE : View.VISIBLE);
        pad.setVisibility(on ? View.VISIBLE : View.GONE);
        lastKey = null;
        update();
        if (focus != null) focus.requestFocus();
    }

    // --- building ---

    private void build() {
        // Keys: the field over the letters, or the pad with its number display.
        LinearLayout keys = new LinearLayout(act);
        keys.setOrientation(LinearLayout.VERTICAL);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.field_bg);
        box.setPadding(dp(9), 0, dp(9), 0);
        field = text("", 14, 0xFFECECEE);
        field.setTypeface(Typeface.DEFAULT_BOLD);
        field.setEllipsize(TextUtils.TruncateAt.START);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View caret = new View(act);
        caret.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(1), dp(15));
        cl.leftMargin = dp(1);
        box.addView(caret, cl);
        box.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        found = text("", 12, 0xFF8A8A90);
        box.addView(found);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(7 * 26 + 6 * 3), dp(28));
        bl.bottomMargin = dp(6);
        keys.addView(box, bl);

        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < LETTERS.length; r++) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (char c : LETTERS[r].toCharArray()) {
                View k = key(String.valueOf(c), 14, v -> {
                    query = SearchIndex.typed(query, c);
                    update();
                });
                if (firstLetter == null) firstLetter = k;
                line.addView(k, keyParams(26, 20, line.getChildCount() > 0));
            }
            if (r == LETTERS.length - 1) {
                line.addView(key("\u2423", 14, v -> {  // open box: space
                    query = SearchIndex.typed(query, ' ');
                    update();
                }), keyParams(26, 20, true));
                line.addView(iconKey(R.drawable.ic_back, null, v -> {
                    query = SearchIndex.deleted(query);
                    update();
                }), keyParams(26, 20, true));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) lp.topMargin = dp(3);
            grid.addView(line, lp);
        }
        letters = grid;
        keys.addView(grid);

        pad = buildPad();
        pad.setVisibility(View.GONE);
        keys.addView(pad);
        card.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The mic on the keyboard's right edge, 123 under it.
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        mic = iconKey(R.drawable.ic_mic, "Speak", v -> listen());
        mic.setId(R.id.s_mic);
        col.addView(mic, new LinearLayout.LayoutParams(dp(48), 0, 1f));
        mode = key("123", 12, v -> setPadMode(true, firstDigit));
        mode.setId(R.id.s_mode);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(26));
        ml.topMargin = dp(4);
        col.addView(mode, ml);
        micColumn = col;
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        colp.leftMargin = dp(9);
        card.addView(col, colp);
        if (speechIntent().resolveActivity(act.getPackageManager()) == null) mic.setVisibility(View.GONE);

        View sep = new View(act);
        sep.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        sl.leftMargin = sl.rightMargin = dp(9);
        card.addView(sep, sl);

        card.addView(buildResults(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View buildPad() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] rows = {"123", "456", "789"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            if (r < 3) {
                for (char c : rows[r].toCharArray()) {
                    int d = c - '0';
                    View k = key(String.valueOf(c), 16, v -> typeDigit(d, false));
                    if (firstDigit == null) firstDigit = k;
                    line.addView(k, keyParams(38, 26, line.getChildCount() > 0));
                }
            } else {
                line.addView(key("abc", 12, v -> setPadMode(false, mode)), keyParams(38, 26, false));
                line.addView(key("0", 16, v -> typeDigit(0, false)), keyParams(38, 26, true));
                line.addView(iconKey(R.drawable.ic_back, null, v -> {
                    keypad.delete();
                    update();
                }), keyParams(38, 26, true));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) lp.topMargin = dp(3);
            grid.addView(line, lp);
        }
        p.addView(grid);

        // The number typed and where it leads; OK on it plays (or opens the list).
        LinearLayout show = new LinearLayout(act);
        show.setOrientation(LinearLayout.VERTICAL);
        show.setGravity(Gravity.CENTER_VERTICAL);
        show.setId(R.id.s_pad_play);
        show.setFocusable(true);
        show.setClickable(true);
        show.setBackgroundResource(R.drawable.card_row);
        show.setPadding(dp(10), dp(4), dp(10), dp(4));
        show.setOnClickListener(v -> playPad());
        show.setOnFocusChangeListener((v, has) -> {
            padTarget.setSelected(has);
            if (has) lastKey = v;
        });
        padDigits = text("_", 30, 0);
        padDigits.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        padDigits.setTextColor(act.getColorStateList(R.color.on_focus));
        padDigits.setDuplicateParentStateEnabled(true);
        show.addView(padDigits);
        padTarget = text("", 12, 0);
        padTarget.setTextColor(act.getColorStateList(R.color.on_focus));
        padTarget.setDuplicateParentStateEnabled(true);
        marquee(padTarget);
        show.addView(padTarget, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        padHint = text("", 12, 0);
        padHint.setTextColor(act.getColorStateList(R.color.on_focus_dim));
        padHint.setDuplicateParentStateEnabled(true);
        show.addView(padHint);
        padPlay = show;
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(7 * 26 + 6 * 3 + 48 + 9 - 3 * 38 - 2 * 3 - 8),
                ViewGroup.LayoutParams.MATCH_PARENT);
        sl.leftMargin = dp(8);
        p.addView(show, sl);
        return p;
    }

    private View buildResults() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(7), 0, dp(7), 0);
        resultsTitle = text("Results", 12, 0xFFECECEE);
        resultsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(resultsTitle);
        resultsSub = text("", 12, 0xFF8A8A90);
        resultsSub.setPadding(dp(8), 0, 0, 0);
        head.addView(resultsSub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

        for (int i = 0; i < ROWS; i++) {
            final int at = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.card_row);
            row.setPadding(dp(7), 0, dp(7), 0);
            if (i == 0) row.setId(R.id.s_result0);
            TextView num = text("", 12, 0);
            num.setTypeface(Typeface.MONOSPACE);
            num.setTextColor(act.getColorStateList(R.color.on_focus_dim));
            num.setDuplicateParentStateEnabled(true);
            row.addView(num, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView name = text("", 12, 0);
            name.setTextColor(act.getColorStateList(R.color.on_focus));
            name.setDuplicateParentStateEnabled(true);
            marquee(name);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView style = text("", 12, 0);
            style.setTextColor(act.getColorStateList(R.color.on_focus_dim));
            style.setDuplicateParentStateEnabled(true);
            style.setPadding(dp(8), 0, 0, 0);
            style.setMaxWidth(dp(110));
            row.addView(style);
            row.setOnClickListener(v -> {
                if (offset + at < results.size()) host.play(results.get(offset + at).file);
            });
            // Long names scroll only while focused.
            row.setOnFocusChangeListener((v, has) -> name.setSelected(has));
            row.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(21));
            lp.topMargin = dp(2);
            r.addView(row, lp);
            resultRows.add(row);
            resultTexts.add(new TextView[]{num, name, style});
        }
        more = text("", 12, 0xFF8A8A90);
        more.setPadding(dp(7), dp(2), dp(7), 0);
        r.addView(more);
        return r;
    }

    private void playPad() {
        Keypad.Target t = keypad.target();
        if (t.kind == Keypad.Kind.PRESET) {
            File f = library.byNumber(t.number);
            if (f != null) host.play(f);
        } else if (t.kind != Keypad.Kind.NONE) {
            host.openList(t);
        }
    }

    // --- voice ---

    private static Intent speechIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a preset name")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    private void listen() {
        try {
            act.startActivityForResult(speechIntent(), REQ_SPEECH);
        } catch (ActivityNotFoundException | SecurityException e) {
            mic.setVisibility(View.GONE);
            firstLetter.requestFocus();
        }
    }

    // --- views ---

    private View key(String label, int sp, View.OnClickListener click) {
        TextView t = text(label, sp, 0);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setEllipsize(null);
        keyLike(t, click);
        return t;
    }

    private View iconKey(int icon, String label, View.OnClickListener click) {
        LinearLayout k = new LinearLayout(act);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(act.getColorStateList(R.color.on_focus));
        iv.setDuplicateParentStateEnabled(true);
        int size = label != null ? 20 : 13;
        k.addView(iv, new LinearLayout.LayoutParams(dp(size), dp(size)));
        if (label != null) {
            TextView t = text(label, 12, 0);
            t.setTextColor(act.getColorStateList(R.color.on_focus_dim));
            t.setDuplicateParentStateEnabled(true);
            t.setPadding(0, dp(3), 0, 0);
            t.setGravity(Gravity.CENTER);
            k.addView(t);
        }
        keyLike(k, click);
        return k;
    }

    private void keyLike(View v, View.OnClickListener click) {
        v.setBackgroundResource(R.drawable.key_bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            if (has) lastKey = view;
        });
    }

    private LinearLayout.LayoutParams keyParams(int w, int h, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        if (gap) lp.leftMargin = dp(3);
        return lp;
    }

    private static void marquee(TextView t) {
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontalFadingEdgeEnabled(true);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(s, t.getText())) t.setText(s);
    }

    private static boolean isInside(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return v == ancestor;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
