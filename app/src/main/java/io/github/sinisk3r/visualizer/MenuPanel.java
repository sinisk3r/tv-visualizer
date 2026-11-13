package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * The OK menu: a panel on the right with hold, hide, timing and style switches. Built in code because
 * its rows depend on which styles exist. Remote: Up/Down moves, OK toggles, Left/Right changes the time,
 * Back closes.
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
        /** Picture quality changed: resize the render surface. */
        void resolutionChanged();
        /** Music loudness 0..1, for the art to breathe with. */
        float level();
    }

    private final Activity act;
    private final View root;
    private final LinearLayout rows;
    private final TextView nowPlaying;
    private final Settings settings;
    private final PresetLibrary library;
    private final Host host;

    MenuPanel(Activity act, Settings settings, PresetLibrary library, Host host) {
        this.act = act;
        this.settings = settings;
        this.library = library;
        this.host = host;
        root = act.findViewById(R.id.menu);
        rows = act.findViewById(R.id.menu_rows);
        nowPlaying = act.findViewById(R.id.menu_now);
        art = act.findViewById(R.id.menu_art);
        int id = act.getResources().getIdentifier("menu_art", "drawable", act.getPackageName());
        if (id != 0) art.setImageResource(id);
    }

    private final android.widget.ImageView art;

    boolean isOpen() {
        return root.getVisibility() == View.VISIBLE;
    }

    void open() {
        build(0);
        root.setAlpha(0f);
        root.setTranslationX(-dp(40));
        root.setVisibility(View.VISIBLE);
        root.animate().alpha(1f).translationX(0).setDuration(180).start();
        if (art.getDrawable() != null) {
            art.setVisibility(View.VISIBLE);
            art.postOnAnimation(breathe);
        }
    }

    /** The art drifts and brightens a little with the music, one step per display frame while open. */
    private final Runnable breathe = new Runnable() {
        private float smooth;

        @Override
        public void run() {
            if (!isOpen()) return;
            smooth += (host.level() - smooth) * 0.15f;
            double t = android.os.SystemClock.uptimeMillis() / 1000.0;
            art.setAlpha(0.14f + 0.16f * smooth);
            float s = 1.04f + 0.03f * smooth;
            art.setScaleX(s);
            art.setScaleY(s);
            art.setTranslationY((float) (Math.sin(t * 0.6) * dp(6)));
            art.postOnAnimation(this);
        }
    };

    void close() {
        root.animate().alpha(0f).translationX(-dp(40)).setDuration(150)
                .withEndAction(() -> root.setVisibility(View.GONE)).start();
    }

    /** The playing preset changed (or its hold state): refresh the top rows in place. */
    void refresh() {
        if (!isOpen()) return;
        View focused = rows.findFocus();
        build(focused == null ? 0 : rows.indexOfChild(focused));
    }

    private void build(int focusIndex) {
        rows.removeAllViews();
        File current = host.currentPreset();
        nowPlaying.setText(current == null ? "" : PresetLibrary.displayName(current));

        boolean held = host.isLocked();
        addRow("Hold this preset", held ? "On · forever" : "Off", v -> {
            host.setHold(!host.isLocked());
        }, null);
        addRow("Hide this preset", "Skip it from now on", v -> host.hideCurrent(), null);
        addRow("Time per preset", "◀  " + Settings.durationLabel(settings.durationSec()) + "  ▶", v -> {
            settings.stepDuration(1);
            changed();
        }, (v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                settings.stepDuration(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                changed();
                return true;
            }
            return false;
        });
        addRow("Picture", "◀  " + pictureLabel() + "  ▶", v -> {
            settings.stepPicture(settings.renderHeight() >= 1080 ? -99 : 1);
            host.resolutionChanged();
            refresh();
        }, (v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                settings.stepPicture(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                host.resolutionChanged();
                refresh();
                return true;
            }
            return false;
        });
        addRow("Switch on big beats", settings.beatCuts() ? "On" : "Off", v -> {
            settings.setBeatCuts(!settings.beatCuts());
            changed();
        }, null);

        addHeader("Styles");
        for (String style : library.categories) {
            if (style.equals(PresetLibrary.ALL)) continue;
            List<File> list = library.byCategory.get(style);
            int count = list == null ? 0 : list.size();
            boolean on = settings.isStyleOn(style);
            addRow(style, on ? "On  ·  " + count : "Off", v -> {
                if (settings.isStyleOn(style) && enabledStyles() <= 1) {
                    toast("Keep at least one style on.");
                    return;
                }
                settings.setStyle(style, !settings.isStyleOn(style));
                changed();
            }, null);
        }

        if (settings.isAuto() && settings.learnedCount() > 0) {
            addHeader("");
            addRow("Re-test picture sizes", settings.learnedCount() + " learned", v -> {
                settings.forgetLearned();
                host.resolutionChanged();
                refresh();
            }, null);
        }

        int hidden = settings.hiddenCount();
        if (hidden > 0) {
            addHeader("");
            addRow("Unhide all", hidden + " hidden", v -> {
                settings.unhideAll();
                changed();
            }, null);
        }

        int i = Math.max(0, Math.min(focusIndex, rows.getChildCount() - 1));
        View target = rows.getChildAt(i);
        if (target != null && !target.isFocusable()) target = rows.getChildAt(i + 1);
        if (target != null) target.requestFocus();
    }

    private String pictureLabel() {
        if (!settings.isAuto()) return settings.renderHeight() + "p";
        File p = host.currentPreset();
        return p == null ? "Auto" : "Auto · " + settings.learnedHeight(p.getName()) + "p";
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

    private void addRow(String label, String value, View.OnClickListener click, View.OnKeyListener keys) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.menu_row);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setOnClickListener(click);
        if (keys != null) row.setOnKeyListener(keys);
        row.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(120).start());

        TextView l = text(label, 17, 0xFFFFFFFF);
        l.setTextColor(act.getColorStateList(R.color.menu_label));
        l.setDuplicateParentStateEnabled(true);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = text(value, 15, 0xCCFFFFFF);
        r.setTextColor(act.getColorStateList(R.color.menu_value));
        r.setDuplicateParentStateEnabled(true);
        row.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(4);
        rows.addView(row, lp);
    }

    private void addHeader(String title) {
        TextView h = text(title.toUpperCase(), 12, 0x99FFFFFF);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.12f);
        h.setPadding(dp(18), dp(16), dp(18), dp(4));
        rows.addView(h);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        return t;
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /** Lets the activity scroll the list back to the top when the menu opens. */
    static void scrollTop(ScrollView s) {
        s.scrollTo(0, 0);
    }
}
