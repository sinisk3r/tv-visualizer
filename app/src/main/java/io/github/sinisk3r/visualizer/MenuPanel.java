package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The OK menu: a panel on the left. Two pages: the main page (hold, favourite, hide, timing, picture, styles,
 * presets) and one page per style with its sub-styles.
 *
 * Rows are built once per page. Anything that changes while the menu is open (download progress, a new preset,
 * a toggled setting) only rewrites the value text of existing rows, so focus never jumps and nothing flickers.
 * No animations move or scale views: the panel sits over a SurfaceView, and moving layers there got clipped.
 *
 * Remote: Up/Down moves, OK toggles or opens, Left/Right changes a value, Back leaves a style page or closes.
 */
final class MenuPanel {
    interface Host {
        File currentPreset();
        boolean isLocked();
        void setHold(boolean on);
        /** Hides the playing preset and moves on. */
        void hideCurrent();
        /** Settings changed: re-filter and apply timing. */
        void settingsChanged();
        /** Picture choice changed: re-apply the render size. */
        void resolutionChanged();
        /** Starts the full-pack download (no-op while one runs). */
        void downloadPack();
        /** "Downloading 34%" or an error while/after a download; null when idle. */
        String packStatus();
    }

    private final Activity act;
    private final View root;
    private final LinearLayout rows;
    private final TextView nowPlaying;
    private final Settings settings;
    private final PresetLibrary library;
    private final Host host;

    /** null = main page; otherwise the style whose sub-styles are shown. */
    private String page;
    private int returnFocus;
    /** Value text of every row on the current page, with how to compute it. */
    private final List<Binding> bindings = new ArrayList<>();

    private static final class Binding {
        final TextView value;
        final Supplier<String> valueText;

        Binding(TextView value, Supplier<String> valueText) {
            this.value = value;
            this.valueText = valueText;
        }
    }

    MenuPanel(Activity act, Settings settings, PresetLibrary library, Host host) {
        this.act = act;
        this.settings = settings;
        this.library = library;
        this.host = host;
        root = act.findViewById(R.id.menu);
        rows = act.findViewById(R.id.menu_rows);
        nowPlaying = act.findViewById(R.id.menu_now);
    }

    boolean isOpen() {
        return root.getVisibility() == View.VISIBLE;
    }

    void open() {
        page = null;
        root.animate().cancel();
        root.setAlpha(0f);
        root.setVisibility(View.VISIBLE);
        build(0);  // after VISIBLE: a hidden view cannot take focus
        root.animate().alpha(1f).setDuration(140).withLayer().start();
    }

    void close() {
        root.animate().cancel();
        root.setVisibility(View.GONE);
    }

    /** Back key: leave a style page first. Returns false when the menu should close. */
    boolean back() {
        if (page == null) return false;
        page = null;
        build(returnFocus);
        return true;
    }

    /** Something changed (preset, hold, download progress, settings): rewrite the values in place. */
    void refresh() {
        if (!isOpen()) return;
        File current = host.currentPreset();
        setIfChanged(nowPlaying, current == null ? "" : PresetLibrary.displayName(current));
        for (Binding b : bindings) setIfChanged(b.value, b.valueText.get());
    }

    private static void setIfChanged(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    // --- pages ---

    private void build(int focusIndex) {
        rows.removeAllViews();
        bindings.clear();
        if (page != null) buildStylePage(page);
        else buildMain();
        refresh();
        act.findViewById(R.id.menu_scroll).scrollTo(0, 0);

        int i = Math.max(0, Math.min(focusIndex, rows.getChildCount() - 1));
        View target = rows.getChildAt(i);
        while (target != null && !target.isFocusable()) target = rows.getChildAt(++i);
        if (target != null) target.requestFocus();
    }

    private void buildMain() {
        addRow("Hold this preset", () -> host.isLocked() ? "On · forever" : "Off",
                v -> host.setHold(!host.isLocked()), null);
        addRow("★ Favourite", () -> isFav() ? "Yes" : "No", v -> {
            File p = host.currentPreset();
            if (p == null) return;
            settings.setFavourite(p.getName(), !isFav());
            changed();
        }, null);
        addRow("Hide this preset", () -> "Skip it from now on", v -> host.hideCurrent(), null);
        addRow("Time per preset", () -> "◀  " + Settings.durationLabel(settings.durationSec()) + "  ▶", v -> {
            settings.stepDuration(1);
            changed();
        }, arrows(d -> {
            settings.stepDuration(d);
            changed();
        }));
        addRow("Picture", () -> "◀  " + pictureLabel() + "  ▶", v -> {
            settings.stepPicture(settings.renderHeight() >= 1080 ? -99 : 1);
            host.resolutionChanged();
            refresh();
        }, arrows(d -> {
            settings.stepPicture(d);
            host.resolutionChanged();
            refresh();
        }));
        addRow("Switch on big beats", () -> settings.beatCuts() ? "On" : "Off", v -> {
            settings.setBeatCuts(!settings.beatCuts());
            changed();
        }, null);
        addRow("Skip heavy presets", () -> settings.skipHeavy() ? "On  ·  " + heavyCount() + " skipped" : "Off", v -> {
            settings.setSkipHeavy(!settings.skipHeavy());
            changed();
        }, null);

        addHeader("Styles  ·  " + String.format("%,d", library.all.size()) + " presets");
        for (String style : library.categories) {
            if (style.equals(PresetLibrary.ALL)) continue;
            addRow(style, () -> styleValue(style), v -> {
                returnFocus = rows.indexOfChild(v);
                page = style;
                build(0);
            }, null);
        }

        addHeader("Presets");
        if (!library.hasFullPack) {
            addRow("Get all 9,795 presets", () -> {
                String status = host.packStatus();
                return status != null ? status : "33 MB download";
            }, v -> host.downloadPack(), null);
        }
        addRow("Favourites", () -> settings.favouriteCount() == 0 ? "None yet"
                : settings.favouriteCount() + "  ·  ▲ ▼ to play", v -> toast(
                "Close the menu and press ▲ or ▼ to reach ★ Favourites."), null);
        addRow("Unhide all", () -> settings.hiddenCount() == 0 ? "None hidden" : settings.hiddenCount() + " hidden",
                v -> {
                    if (settings.hiddenCount() == 0) return;
                    settings.unhideAll();
                    changed();
                }, null);
        addRow("Re-test picture sizes", () -> settings.learnedCount() + settings.tooSlowCount() == 0
                ? "Nothing learned" : settings.learnedCount() + settings.tooSlowCount() + " learned", v -> {
            settings.forgetLearned();
            host.resolutionChanged();
            refresh();
        }, null);
    }

    private void buildStylePage(String style) {
        addRow("‹  " + style, () -> "Back", v -> back(), null);
        addRow("Play this style", () -> settings.isStyleOn(style) ? "On" : "Off", v -> {
            if (settings.isStyleOn(style) && enabledStyles() <= 1) {
                toast("Keep at least one style on.");
                return;
            }
            settings.setStyle(style, !settings.isStyleOn(style));
            changed();
        }, null);
        TreeMap<String, List<File>> subs = library.subs.get(style);
        if (subs == null) return;
        addHeader(subs.size() + (subs.size() == 1 ? " sub-style" : " sub-styles"));
        for (Map.Entry<String, List<File>> e : subs.entrySet()) {
            String sub = e.getKey();
            int n = e.getValue().size();
            addRow(sub, () -> settings.isSubOn(style, sub) ? "On  ·  " + n : "Off", v -> {
                settings.setSub(style, sub, !settings.isSubOn(style, sub));
                changed();
            }, null);
        }
    }

    // --- values ---

    /** Presets in the library that "Skip heavy presets" leaves out: listed heavy or measured too slow here. */
    private String heavyCount() {
        int n = 0;
        for (File f : library.all) {
            if (library.heavy.contains(f.getName()) || settings.isTooSlow(f.getName())) n++;
        }
        return String.format("%,d", n);
    }

    private boolean isFav() {
        File p = host.currentPreset();
        return p != null && settings.isFavourite(p.getName());
    }

    private String pictureLabel() {
        if (!settings.isAuto()) return settings.renderHeight() + "p";
        File p = host.currentPreset();
        return p == null ? "Auto" : "Auto · " + settings.learnedHeight(p.getName()) + "p";
    }

    private String styleValue(String style) {
        if (!settings.isStyleOn(style)) return "Off  ›";
        int on = 0;
        TreeMap<String, List<File>> subs = library.subs.get(style);
        if (subs != null) {
            for (Map.Entry<String, List<File>> e : subs.entrySet()) {
                if (settings.isSubOn(style, e.getKey())) on += e.getValue().size();
            }
        }
        int total = library.byCategory.get(style).size();
        return (on == total ? String.format("%,d", total) : String.format("%,d of %,d", on, total)) + "  ›";
    }

    private int enabledStyles() {
        int n = 0;
        for (String c : library.categories) if (!c.equals(PresetLibrary.ALL) && settings.isStyleOn(c)) n++;
        return n;
    }

    private void changed() {
        host.settingsChanged();
        refresh();
    }

    // --- views ---

    private interface Step {
        void by(int delta);
    }

    /** Left/Right on a value row change it in place. */
    private static View.OnKeyListener arrows(Step step) {
        return (v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                step.by(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            return false;
        };
    }

    private void addRow(String label, Supplier<String> value, View.OnClickListener click, View.OnKeyListener keys) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.menu_row);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setOnClickListener(click);
        if (keys != null) row.setOnKeyListener(keys);

        TextView l = text(label, 17);
        l.setTextColor(act.getColorStateList(R.color.menu_label));
        l.setDuplicateParentStateEnabled(true);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = text("", 15);
        r.setTextColor(act.getColorStateList(R.color.menu_value));
        r.setDuplicateParentStateEnabled(true);
        r.setPadding(dp(12), 0, 0, 0);
        row.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bindings.add(new Binding(r, value));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(4);
        rows.addView(row, lp);
    }

    private void addHeader(String title) {
        TextView h = text(title.toUpperCase(), 12);
        h.setTextColor(0x99FFFFFF);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.12f);
        h.setPadding(dp(18), dp(16), dp(18), dp(4));
        rows.addView(h);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
