package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.SystemClock;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The OK menu (menu redesign, phase 1): a transport bar docked at the bottom, with cards above it.
 *
 * Bar, left to right: now playing | Previous, Next, Favourite, Hold | Shuffle, Find, Search, Settings | time | Hide
 * (alone on the far right, so it is not pressed by accident). Focus starts on Favourite. A label fades in above
 * the focused button. Cards: Settings (time per preset, Smooth or Sharp with the live frame rate, then rows to
 * Live and advanced and to About and licences), Live and advanced, Find (today's style pages) and the About viewer.
 *
 * Everything is built once, except the Find rows, which are built once per Find page. Changes while the menu is
 * open (a new preset, a toggled setting, download progress, the clock) only rewrite values in place, so focus
 * never jumps and nothing flickers. Nothing moves or scales over the visuals: panels fade, and hidden ones are GONE.
 *
 * Remote: Left/Right along the bar, Up into the open card, Down back to the button that opened it, OK presses,
 * Back closes the innermost card, then the bar.
 */
final class MenuPanel {
    interface Host {
        File currentPreset();
        boolean isLocked();
        /** The style or list now playing. */
        String category();
        /** Uptime ms when the current preset started. */
        long shownAt();
        void setHold(boolean on);
        /** Previous (-1) or next (+1) preset. */
        void step(int delta);
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
        /** Average frames per second over the last 30 s; 0 when not known yet. */
        float fps();
        /** The render height the visuals run at now. */
        int pictureHeight();
        /** "Show what's playing" changed. */
        void displayChanged();
    }

    static final int NONE = 0, SETTINGS = 1, ADVANCED = 2, ABOUT = 3, FIND = 4;
    private static final long FADE_MS = 140;

    private final Activity act;
    private final Settings settings;
    private final PresetLibrary library;
    private final Host host;

    private final View root, scrim, bar, barTime, barHint;
    private final TextView barTitle, barStyle, elapsed, total;
    private final ProgressBar progress;
    private final ViewGroup cardSettings, cardAdvanced, cardFind, about;
    private final LinearLayout findRows;

    private Button prev, next, fav, hold, shuffle, find, search, settingsButton, hide;
    private final List<Button> buttons = new ArrayList<>();

    /** The open card (NONE: just the bar), and the Find page (null: the list of styles). */
    private int panel = NONE;
    private String findPage;
    private int findReturnFocus;

    /** Rewrites one value in place. Static ones (bar, cards) are made once; Find's per page. */
    private final List<Runnable> values = new ArrayList<>();
    private final List<Runnable> findValues = new ArrayList<>();
    /** Settings card views that need their state refreshed. */
    private final List<TextView> durationChips = new ArrayList<>();
    private TextView smooth, sharp, fpsNow;
    private View rowLive, rowAbout;

    /** A bar button: the focusable cell with its icon, label above it, and optional dot or badge. */
    private static final class Button {
        FrameLayout cell;
        ImageView icon;
        TextView tip;
        View dot;
        TextView badge;
    }

    MenuPanel(Activity act, Settings settings, PresetLibrary library, Host host) {
        this.act = act;
        this.settings = settings;
        this.library = library;
        this.host = host;
        root = act.findViewById(R.id.menu);
        scrim = act.findViewById(R.id.menu_scrim);
        bar = act.findViewById(R.id.bar);
        barTime = act.findViewById(R.id.bar_time);
        barHint = act.findViewById(R.id.bar_hint);
        barTitle = act.findViewById(R.id.bar_title);
        barStyle = act.findViewById(R.id.bar_style);
        elapsed = act.findViewById(R.id.bar_elapsed);
        total = act.findViewById(R.id.bar_total);
        progress = act.findViewById(R.id.bar_progress);
        cardSettings = act.findViewById(R.id.card_settings);
        cardAdvanced = act.findViewById(R.id.card_advanced);
        cardFind = act.findViewById(R.id.card_find);
        about = act.findViewById(R.id.about);
        findRows = act.findViewById(R.id.find_rows);
        buildBar();
        buildSettingsCard();
        buildAdvancedCard();
        buildAbout();
    }

    boolean isOpen() {
        return root.getVisibility() == View.VISIBLE;
    }

    /** The open card, for tests and the activity. */
    int panel() {
        return panel;
    }

    void open() {
        panel = NONE;
        cardSettings.setVisibility(View.GONE);
        cardAdvanced.setVisibility(View.GONE);
        cardFind.setVisibility(View.GONE);
        about.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        markOpen(null);
        fadeIn(root);  // VISIBLE first: a hidden view cannot take focus, and refresh() skips a closed menu
        refresh();
        tick();
        fav.cell.requestFocus();
        barTitle.setSelected(true);  // long names scroll
    }

    void close() {
        root.animate().cancel();
        root.setVisibility(View.GONE);
        barTitle.setSelected(false);
        panel = NONE;
    }

    /** Back key: closes the innermost card. Returns false when the bar itself should close. */
    boolean back() {
        switch (panel) {
            case ABOUT:
                showPanel(SETTINGS, rowAbout);
                return true;
            case ADVANCED:
                showPanel(SETTINGS, rowLive);
                return true;
            case FIND:
                if (findPage != null) {
                    findPage = null;
                    buildFind(findReturnFocus);
                    return true;
                }
                closePanel(find);
                return true;
            case SETTINGS:
                closePanel(settingsButton);
                return true;
            default:
                return false;
        }
    }

    /**
     * Keys before the views get them (menu open). Down from a card goes back to the button that opened it, not to
     * whichever bar button happens to sit below. Returns true when the key was used.
     */
    boolean handleKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getKeyCode() != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (panel == NONE || panel == ABOUT) return false;
        View focused = act.getCurrentFocus();
        if (focused == null || isInside(focused, bar)) return false;
        View next = focused.focusSearch(View.FOCUS_DOWN);
        if (next != null && !isInside(next, bar)) return false;
        owner(panel).cell.requestFocus();
        return true;
    }

    /** Something changed (preset, hold, download progress, settings): rewrite the values in place. */
    void refresh() {
        if (!isOpen()) return;
        for (Runnable r : values) r.run();
        for (Runnable r : findValues) r.run();
    }

    /** Once a second while open: the time per preset and the live frame rate. */
    void tick() {
        if (!isOpen()) return;
        if (host.isLocked()) {
            setIfChanged(elapsed, "");
            setIfChanged(total, "held");
            progress.setProgress(0);
        } else {
            int dur = settings.durationSec();
            long sec = BarText.elapsed(host.shownAt(), SystemClock.uptimeMillis(), dur);
            setIfChanged(elapsed, BarText.clock(sec));
            setIfChanged(total, BarText.clock(dur));
            progress.setProgress((int) (sec * 1000 / dur));
        }
        if (fpsNow != null) setIfChanged(fpsNow, pictureNow());
    }

    private static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(s, t.getText())) t.setText(s);
    }

    // --- panels ---

    private Button owner(int p) {
        return p == FIND ? find : settingsButton;
    }

    private void showPanel(int p, View focus) {
        panel = p;
        cardSettings.setVisibility(p == SETTINGS ? View.VISIBLE : View.GONE);
        cardAdvanced.setVisibility(p == ADVANCED ? View.VISIBLE : View.GONE);
        cardFind.setVisibility(p == FIND ? View.VISIBLE : View.GONE);
        about.setVisibility(p == ABOUT ? View.VISIBLE : View.GONE);
        // The viewer covers the screen: the bar under it would only cost frames.
        bar.setVisibility(p == ABOUT ? View.GONE : View.VISIBLE);
        scrim.setVisibility(p == ABOUT ? View.GONE : View.VISIBLE);
        barTime.setVisibility(p == NONE ? View.VISIBLE : View.GONE);
        barHint.setVisibility(p == NONE ? View.GONE : View.VISIBLE);
        markOpen(p == NONE ? null : owner(p));
        View card = p == SETTINGS ? cardSettings : p == ADVANCED ? cardAdvanced : p == FIND ? cardFind
                : p == ABOUT ? about : null;
        refresh();
        tick();
        if (card != null) fadeIn(card);
        if (focus != null) focus.requestFocus();
    }

    private void closePanel(Button back) {
        showPanel(NONE, back.cell);
    }

    private void markOpen(Button open) {
        for (Button b : buttons) b.cell.setActivated(b == open);
        barTime.setVisibility(open == null ? View.VISIBLE : View.GONE);
        barHint.setVisibility(open == null ? View.GONE : View.VISIBLE);
    }

    private static void fadeIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
    }

    private static boolean isInside(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return v == ancestor;
    }

    // --- bar ---

    private void buildBar() {
        LinearLayout row = act.findViewById(R.id.bar_buttons);
        // Previous and Next stay on the bar, but focus starts on Favourite: the remote's Left/Right already
        // step presets, so the bar opens on what the remote cannot do.
        prev = addButton(row, R.id.b_prev, R.drawable.ic_prev, v -> host.step(-1));
        next = addButton(row, R.id.b_next, R.drawable.ic_next, v -> host.step(1));
        fav = addButton(row, R.id.b_fav, R.drawable.ic_star, v -> {
            File p = host.currentPreset();
            if (p == null) return;
            settings.setFavourite(p.getName(), !isFav());
            changed();
        });
        hold = addButton(row, R.id.b_hold, R.drawable.ic_pin, v -> host.setHold(!host.isLocked()));
        View sep = new View(act);
        sep.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(1), dp(20));
        sp.leftMargin = sp.rightMargin = dp(6);
        row.addView(sep, sp);
        shuffle = addButton(row, R.id.b_shuffle, R.drawable.ic_shuffle, v -> {
            settings.cycleShuffle();
            changed();
        });
        find = addButton(row, R.id.b_find, R.drawable.ic_find, v -> {
            if (panel == FIND) {
                closePanel(find);
                return;
            }
            findPage = null;
            showPanel(FIND, null);
            buildFind(0);
        });
        search = addButton(row, R.id.b_search, R.drawable.ic_search, v -> {
            // Phase 2: the four-row keyboard and voice. Until then the label says so.
            search.tip.setText("Search: coming soon");
        });
        settingsButton = addButton(row, R.id.b_settings, R.drawable.ic_settings, v -> {
            if (panel == SETTINGS) closePanel(settingsButton);
            else showPanel(SETTINGS, durationChips.get(durationIndex()));
        });
        hide = addButton(act.findViewById(R.id.bar_hide_slot), R.id.b_hide, R.drawable.ic_hide,
                v -> host.hideCurrent());

        values.add(() -> {
            File current = host.currentPreset();
            setIfChanged(barTitle, current == null ? "" : library.title(current));
            setIfChanged(barStyle, current == null ? "" : BarText.styleLine(library.styleOf(current),
                    library.subStyleOf(current), host.category(), host.isLocked(), settings.shuffle()));
            boolean f = isFav();
            fav.icon.setImageResource(f ? R.drawable.ic_star_filled : R.drawable.ic_star);
            fav.dot.setVisibility(f ? View.VISIBLE : View.GONE);
            setIfChanged(fav.tip, f ? "Favourite: yes" : "Favourite");
            boolean h = host.isLocked();
            hold.dot.setVisibility(h ? View.VISIBLE : View.GONE);
            setIfChanged(hold.tip, h ? "Hold: on, this preset stays" : "Hold this preset");
            int s = settings.shuffle();
            String badge = BarText.shuffleBadge(s);
            shuffle.badge.setVisibility(badge == null ? View.GONE : View.VISIBLE);
            if (badge != null) setIfChanged(shuffle.badge, badge);
            shuffle.icon.setAlpha(s == Settings.SHUFFLE_OFF ? 0.55f : 1f);
            setIfChanged(shuffle.tip, BarText.shuffleTip(s));
            setIfChanged(prev.tip, "Previous");
            setIfChanged(next.tip, "Next");
            setIfChanged(find.tip, "Find: styles and favourites");
            setIfChanged(settingsButton.tip, "Settings");
            setIfChanged(hide.tip, "Hide this preset");
        });
        search.tip.setText("Search");
        search.cell.setOnFocusChangeListener(tipFader(search, () -> search.tip.setText("Search")));
    }

    private Button addButton(ViewGroup parent, int id, int icon, View.OnClickListener click) {
        Button b = new Button();
        FrameLayout cell = new FrameLayout(act);
        cell.setId(id);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        cell.setBackgroundResource(R.drawable.bar_button);
        cell.setOnClickListener(click);
        b.cell = cell;

        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(act.getColorStateList(R.color.on_focus));
        iv.setDuplicateParentStateEnabled(true);
        cell.addView(iv, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        b.icon = iv;

        View dot = new View(act);
        dot.setBackgroundResource(R.drawable.dot);
        dot.setDuplicateParentStateEnabled(true);
        dot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(3), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = dp(2);
        cell.addView(dot, dl);
        b.dot = dot;

        TextView badge = new TextView(act);
        badge.setBackgroundResource(R.drawable.badge_bg);
        badge.setTextColor(act.getColorStateList(R.color.badge_text));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(2), 0, dp(2), 0);
        badge.setIncludeFontPadding(false);
        badge.setDuplicateParentStateEnabled(true);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        bl.topMargin = dp(1);
        bl.rightMargin = -dp(2);
        cell.addView(badge, bl);
        b.badge = badge;

        // The label sits above the button, wider than it: a fixed-width holder centred on the cell, drawn outside
        // it (clipChildren off up the tree). It fades in on focus and is GONE otherwise.
        FrameLayout holder = new FrameLayout(act);
        holder.setClipChildren(false);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dp(260), dp(22), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = -dp(29);
        cell.addView(holder, hl);
        TextView tip = new TextView(act);
        tip.setBackgroundResource(R.drawable.tip_bg);
        tip.setTextColor(0xFF111113);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTypeface(Typeface.DEFAULT_BOLD);
        tip.setSingleLine(true);
        tip.setPadding(dp(8), dp(2), dp(8), dp(2));
        tip.setVisibility(View.GONE);
        holder.addView(tip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        b.tip = tip;
        cell.setOnFocusChangeListener(tipFader(b, null));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.leftMargin = lp.rightMargin = dp(3);
        if (parent instanceof LinearLayout) {
            parent.addView(cell, lp);
        } else {
            parent.addView(cell, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        }
        buttons.add(b);
        return b;
    }

    private static View.OnFocusChangeListener tipFader(Button b, Runnable onBlur) {
        return (v, has) -> {
            b.tip.animate().cancel();
            if (has) {
                fadeIn(b.tip);
            } else {
                b.tip.setVisibility(View.GONE);
                if (onBlur != null) onBlur.run();
            }
        };
    }

    // --- settings card ---

    private void buildSettingsCard() {
        ViewGroup c = cardSettings;
        c.addView(header("Playback"));

        LinearLayout each = line("Each preset");
        for (int sec : Settings.DURATIONS) {
            TextView chip = chip(Settings.durationLabel(sec), v -> {
                settings.setDuration(sec);
                changed();
            });
            durationChips.add(chip);
            each.addView(chip, chipParams());
        }
        c.addView(each, lineParams());

        LinearLayout pic = line("Picture");
        smooth = chip("Smooth", v -> pick(Settings.AUTO));
        smooth.setId(R.id.chip_smooth);
        sharp = chip("Sharp", v -> pick(Settings.AUTO_SHARP));
        sharp.setId(R.id.chip_sharp);
        pic.addView(smooth, chipParams());
        pic.addView(sharp, chipParams());
        fpsNow = text("", 12, 0xFFA4A4AA);
        fpsNow.setGravity(Gravity.END);
        pic.addView(fpsNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(pic, lineParams());

        LinearLayout go = new LinearLayout(act);
        go.setOrientation(LinearLayout.HORIZONTAL);
        rowLive = goRow(R.id.row_live, R.drawable.ic_settings, "Live and advanced",
                v -> showPanel(ADVANCED, firstFocusable(cardAdvanced)));
        rowAbout = goRow(R.id.row_about, R.drawable.ic_info, "About and licences", v -> showPanel(ABOUT, about));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(26), 1f);
        gl.rightMargin = dp(5);
        go.addView(rowLive, gl);
        LinearLayout.LayoutParams gr = new LinearLayout.LayoutParams(0, dp(26), 1f);
        gr.leftMargin = dp(5);
        go.addView(rowAbout, gr);
        LinearLayout.LayoutParams gp = lineParams();
        gp.topMargin = dp(8);
        c.addView(go, gp);

        values.add(() -> {
            for (int i = 0; i < durationChips.size(); i++) {
                durationChips.get(i).setActivated(i == durationIndex());
            }
            smooth.setActivated(settings.renderHeight() == Settings.AUTO);
            sharp.setActivated(settings.renderHeight() == Settings.AUTO_SHARP);
        });
    }

    private void pick(int picture) {
        settings.setPicture(picture);
        host.resolutionChanged();
        refresh();
    }

    private int durationIndex() {
        int i = 0;
        while (i < Settings.DURATIONS.length - 1 && Settings.DURATIONS[i] < settings.durationSec()) i++;
        return i;
    }

    /** The average frame rate of the last 30 s and the size now, so the choice shows its effect. */
    private String pictureNow() {
        String now = BarText.fps(host.fps(), host.pictureHeight());
        return settings.isAuto() ? now : "Fixed " + settings.renderHeight() + "p" + (now.isEmpty() ? "" : "  ·  " + now);
    }

    private LinearLayout line(String label) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label, 12, 0xFFA4A4AA);
        l.addView(t, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22));
        lp.rightMargin = dp(4);
        return lp;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView t = text(label, 12, 0);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), 0, dp(9), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    private View goRow(int id, int icon, String label, View.OnClickListener click) {
        LinearLayout r = row(click);
        r.setId(id);
        r.setBackgroundResource(R.drawable.chip_bg);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(act.getColorStateList(R.color.on_focus));
        iv.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(12), dp(12));
        il.rightMargin = dp(5);
        r.addView(iv, il);
        r.addView(label(label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(dim("›"));
        return r;
    }

    // --- live and advanced ---

    private void buildAdvancedCard() {
        LinearLayout switching = column("Switching");
        toggle(switching, "Switch on big beats", settings::beatCuts, on -> {
            settings.setBeatCuts(on);
            changed();
        });
        // No "Switch on song change": the app always switches on a new song, with no setting for it yet.

        LinearLayout presets = column("Presets");
        toggle(presets, "Skip heavy presets", settings::skipHeavy, on -> {
            settings.setSkipHeavy(on);
            changed();
        });
        toggle(presets, "Skip deaf presets", settings::skipDeaf, on -> {
            settings.setSkipDeaf(on);
            changed();
        });
        valueRow(presets, View.NO_ID, "Unhide all",
                () -> settings.hiddenCount() == 0 ? "None hidden" : settings.hiddenCount() + " hidden", v -> {
                    if (settings.hiddenCount() == 0) return;
                    settings.unhideAll();
                    changed();
                });
        if (!library.hasFullPack) {
            valueRow(presets, View.NO_ID, "Get all 9,795", () -> {
                String status = host.packStatus();
                return status != null ? status : String.format("%,d now  ·  33 MB", library.all.size());
            }, v -> host.downloadPack());
        }

        LinearLayout adv = column("Advanced");
        valueRow(adv, R.id.fixed_size, "Fixed picture size",
                () -> settings.isAuto() ? "Off" : settings.renderHeight() + "p", v -> {
                    settings.setPicture(Settings.nextFixed(settings.renderHeight()));
                    host.resolutionChanged();
                    refresh();
                });
        valueRow(adv, View.NO_ID, "Re-test sizes", () -> {
            int n = settings.learnedCount() + settings.tooSlowCount();
            return n == 0 ? "Nothing learned" : String.format("%,d learned", n);
        }, v -> {
            settings.forgetLearned();
            host.resolutionChanged();
            refresh();
        });
        toggle(adv, "Show what's playing", settings::showPlaying, on -> {
            settings.setShowPlaying(on);
            host.displayChanged();
            refresh();
        });

        cardAdvanced.addView(switching, colParams());
        cardAdvanced.addView(vsep(), new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        cardAdvanced.addView(presets, colParams());
        cardAdvanced.addView(vsep(), new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        cardAdvanced.addView(adv, colParams());
    }

    private LinearLayout column(String title) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(6), 0, dp(6), 0);
        c.addView(header(title));
        return c;
    }

    private LinearLayout.LayoutParams colParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View vsep() {
        View v = new View(act);
        v.setBackgroundColor(0x1AFFFFFF);
        return v;
    }

    private interface Toggle {
        void set(boolean on);
    }

    private interface Flag {
        boolean get();
    }

    private interface Text {
        String get();
    }

    private void toggle(LinearLayout parent, String label, Flag flag, Toggle set) {
        LinearLayout r = row(v -> set.set(!flag.get()));
        r.addView(label(label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View sw = new View(act);
        sw.setBackgroundResource(R.drawable.switch_bg);
        sw.setDuplicateParentStateEnabled(true);  // the row's activated state is the switch's on
        r.addView(sw, new LinearLayout.LayoutParams(dp(26), dp(15)));
        parent.addView(r, rowParams());
        values.add(() -> r.setActivated(flag.get()));
    }

    private void valueRow(LinearLayout parent, int id, String label, Text value, View.OnClickListener click) {
        LinearLayout r = row(click);
        if (id != View.NO_ID) r.setId(id);
        r.addView(label(label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = dim("");
        r.addView(v);
        parent.addView(r, rowParams());
        values.add(() -> setIfChanged(v, value.get()));
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        lp.topMargin = dp(4);
        return lp;
    }

    // --- find (phase 1: today's style pages) ---

    private void buildFind(int focusIndex) {
        findRows.removeAllViews();
        findValues.clear();
        if (findPage != null) buildStylePage(findPage);
        else buildStyleList();
        refresh();
        act.findViewById(R.id.find_scroll).scrollTo(0, 0);
        int i = Math.max(0, Math.min(focusIndex, findRows.getChildCount() - 1));
        View target = findRows.getChildAt(i);
        while (target != null && !target.isFocusable()) target = findRows.getChildAt(++i);
        if (target != null) target.requestFocus();
    }

    private void buildStyleList() {
        findRows.addView(header("Styles  ·  " + String.format("%,d", library.all.size()) + " presets"));
        findRow("★ Favourites", () -> settings.favouriteCount() == 0 ? "None yet"
                : settings.favouriteCount() + "  ·  ▲ ▼ to play", v -> toast(
                "Close the menu and press ▲ or ▼ to reach ★ Favourites."));
        for (String style : library.categories) {
            if (style.equals(PresetLibrary.ALL)) continue;
            findRow(style, () -> styleValue(style), v -> {
                findReturnFocus = findRows.indexOfChild(v);
                findPage = style;
                buildFind(0);
            });
        }
    }

    private void buildStylePage(String style) {
        findRow("‹  " + style, () -> "Back", v -> back());
        findRow("Play this style", () -> settings.isStyleOn(style) ? "On" : "Off", v -> {
            if (settings.isStyleOn(style) && enabledStyles() <= 1) {
                toast("Keep at least one style on.");
                return;
            }
            settings.setStyle(style, !settings.isStyleOn(style));
            changed();
        });
        TreeMap<String, List<File>> subs = library.subs.get(style);
        if (subs == null) return;
        findRows.addView(header(subs.size() + (subs.size() == 1 ? " sub-style" : " sub-styles")));
        for (Map.Entry<String, List<File>> e : subs.entrySet()) {
            String sub = e.getKey();
            int n = e.getValue().size();
            findRow(sub, () -> settings.isSubOn(style, sub) ? "On  ·  " + n : "Off", v -> {
                settings.setSub(style, sub, !settings.isSubOn(style, sub));
                changed();
            });
        }
    }

    private void findRow(String label, Text value, View.OnClickListener click) {
        LinearLayout r = row(click);
        r.addView(label(label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = dim("");
        r.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        lp.topMargin = dp(3);
        findRows.addView(r, lp);
        findValues.add(() -> setIfChanged(v, value.get()));
    }

    // --- about ---

    /** Credits and licences. projectM is LGPL-2.1: say so, link its source and our changes to it. The licence
     *  texts ship in assets/licences. */
    private void buildAbout() {
        ViewGroup a = act.findViewById(R.id.about_rows);
        TextView title = text("About and licences", 18, 0xFFECECEE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        a.addView(title);
        TextView back = text("Back returns to settings", 12, 0xFF8A8A90);
        back.setPadding(0, dp(2), 0, dp(8));
        a.addView(back);
        aboutLine(a, "Visualizer " + versionName(), "MIT licence");
        aboutLine(a, "Source", "github.com/sinisk3r/tv-visualizer");
        a.addView(header("Built with"));
        aboutLine(a, "projectM 4.1.8", "LGPL-2.1");
        aboutLine(a, "Engine", "github.com/projectM-visualizer");
        aboutLine(a, "Changes", "patches/projectm in our source");
        a.addView(header("Presets and art"));
        aboutLine(a, "Presets", "Cream of the Crop, by Jason Fletcher");
        aboutLine(a, "Preset authors", "Named in each preset's title");
        aboutLine(a, "Textures", "MilkDrop texture pack");
        aboutLine(a, "App icon", "AI-generated");
        a.addView(header("Privacy"));
        aboutLine(a, "Audio", "Only used to draw frames; never recorded, stored or sent");
    }

    private void aboutLine(ViewGroup parent, String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        r.addView(text(label, 13, 0xFFECECEE), new LinearLayout.LayoutParams(dp(220),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(text(value, 13, 0xFFA4A4AA), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r);
    }

    private String versionName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // --- values ---

    private boolean isFav() {
        File p = host.currentPreset();
        return p != null && settings.isFavourite(p.getName());
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

    private LinearLayout row(View.OnClickListener click) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackgroundResource(R.drawable.card_row);
        r.setPadding(dp(7), 0, dp(7), 0);
        r.setOnClickListener(click);
        return r;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, 0);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    private TextView dim(String s) {
        TextView t = text(s, 12, 0);
        t.setTextColor(act.getColorStateList(R.color.on_focus_dim));
        t.setDuplicateParentStateEnabled(true);
        t.setPadding(dp(8), 0, 0, 0);
        return t;
    }

    private TextView header(String title) {
        TextView h = text(title.toUpperCase(java.util.Locale.ROOT), 12, 0xFF8A8A90);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.04f);
        h.setPadding(dp(4), dp(4), dp(4), dp(2));
        return h;
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

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
