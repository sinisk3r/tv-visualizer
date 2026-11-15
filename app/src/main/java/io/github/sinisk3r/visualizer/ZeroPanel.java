package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The 0-list (mockup A2, screen 0b): pressing 0 with the menu closed opens favourites numbered 01, 02 ... and the
 * playlists 001 Featured, 002 Most watched, 003 New to you, 004 Recently played. More digits jump to a line
 * (ZeroEntry), the arrows work, OK plays. "0" then OK opens Find on Favourites, "00" then OK on the playlists.
 * No timer while it is open: it closes after 10 s without a key, or with Back. Presets 1 to 9,791 still jump
 * straight (ChannelEntry), unchanged.
 *
 * Shown by MenuPanel as a card above the bar; built once, rows only rewritten (ListRows).
 */
final class ZeroPanel {
    interface Host extends FindPanel.Host {
        /** Opens Find on list `which` (FindPanel.FAVOURITES, or a playlist 1-4). */
        void openFind(int which);
    }

    static final long IDLE_MS = 10_000;
    private static final int FAV_ROWS = 5;

    private final Activity act;
    private final Settings settings;
    private final PresetLibrary library;
    private final SmartLists lists;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable idle;

    private final FrameLayout card;
    private final TextView typed, note;
    private final ListRows favs, playlists;
    private final ZeroEntry entry = new ZeroEntry();
    /** The arrows moved since the last digit: OK then plays the focused line, not the typed code. */
    private boolean moved;
    private List<File> favourites = new ArrayList<>();

    ZeroPanel(Activity act, Settings settings, PresetLibrary library, SmartLists lists, Host host) {
        this.act = act;
        this.settings = settings;
        this.library = library;
        this.lists = lists;
        this.host = host;
        idle = host::dismiss;

        card = new FrameLayout(act);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(10), dp(7), dp(10), dp(8));
        card.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.leftMargin = cl.rightMargin = dp(48);
        cl.bottomMargin = dp(78);
        ((ViewGroup) act.findViewById(R.id.menu)).addView(card, cl);

        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        card.addView(v);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        typed = ListRows.text(act, "0_", 15, 0xFFECECEE);
        typed.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(7);
        head.addView(typed, tl);
        note = ListRows.text(act, "", 12, 0xFF74747A);
        head.addView(note, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        v.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        LinearLayout cols = new LinearLayout(act);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        v.addView(cols);

        LinearLayout left = column("Favourites  ·  0 + number");
        favs = new ListRows(act, FAV_ROWS, 18, this::playFavourite, null);
        left.addView(favs.column);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ll.rightMargin = dp(11);
        cols.addView(left, ll);

        LinearLayout right = column("Playlists  ·  00 + number");
        playlists = new ListRows(act, SmartLists.NAMES.length, 22, this::playPlaylist, null);
        right.addView(playlists.column);
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    View view() {
        return card;
    }

    /** Fills the lists and focuses the first favourite (or Featured). Call once the card is VISIBLE. */
    void open() {
        entry.reset();
        moved = false;
        favourites = new ArrayList<>();
        for (String n : settings.favouritesInOrder()) {
            File f = library.byName(n);
            if (f != null) favourites.add(f);
        }
        favs.setList(favourites.size(), new ListRows.Binder() {
            public String number(int i) { return ZeroEntry.favouriteNumber(i); }
            public String name(int i) { return PresetLibrary.displayName(favourites.get(i)); }
            public String value(int i) { return ""; }
        });
        long now = System.currentTimeMillis();
        int[] counts = new int[SmartLists.NAMES.length];
        for (int i = 0; i < counts.length; i++) counts[i] = lists.playlist(i, now).size();
        playlists.setList(SmartLists.NAMES.length, new ListRows.Binder() {
            public String number(int i) { return ZeroEntry.playlistNumber(i); }
            public String name(int i) { return i == SmartLists.MOST_WATCHED ? "Most watched this month" : SmartLists.NAMES[i]; }
            public String value(int i) {
                if (i == SmartLists.FEATURED && !lists.hasFeatured()) return "soon";
                return String.format("%,d", counts[i]);
            }
        });
        // Up/Down stay in a column; Left/Right cross between them.
        if (favourites.isEmpty()) {
            ListRows.setIfChanged(note, "No favourites yet  ·  ★ on the bar adds one  ·  OK plays  ·  Back closes");
            playlists.focusItem(0);
        } else {
            ListRows.setIfChanged(note, "type more digits, or use the arrows  ·  OK plays  ·  Back closes");
            favs.focusItem(0);
        }
        ListRows.setIfChanged(typed, entry.shown());
        kick();
    }

    /** Favourite n (1-based) or playlist n from elsewhere (Search's number pad): plays it like OK here does.
     *  Returns false when there is nothing to play (no such favourite, or an empty list). */
    boolean playCode(boolean playlist, int n) {
        if (playlist) {
            int i = n - 1;
            if (i < 0 || i >= SmartLists.NAMES.length || lists.playlist(i, System.currentTimeMillis()).isEmpty()) {
                return false;
            }
            playPlaylist(i);
            return true;
        }
        List<String> names = settings.favouritesInOrder();
        if (n < 1 || n > names.size()) return false;
        File f = library.byName(names.get(n - 1));
        if (f == null) return false;
        host.playCategory(PresetLibrary.FAVOURITES, f);
        host.dismiss();
        return true;
    }

    /** The menu closed: no idle timer left behind. */
    void closed() {
        ui.removeCallbacks(idle);
    }

    /** Keys while the 0-list is open (Back is MenuPanel's). True when used. */
    boolean handleKey(KeyEvent e) {
        kick();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isDigitOrOk(e.getKeyCode());
        int k = e.getKeyCode();
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            jump(entry.add(k - KeyEvent.KEYCODE_0));
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            ok();
            return true;
        }
        View focused = act.getCurrentFocus();
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            moved = true;
            if (favs.handleKey(e, focused)) return true;
            // Down at the bottom must not fall into the bar: the 0-list keeps the focus.
            View next = focused == null ? null : focused.focusSearch(direction(k));
            return next == null || !isInside(next, card);
        }
        return false;
    }

    private static boolean isDigitOrOk(int k) {
        return (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static int direction(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            default: return View.FOCUS_RIGHT;
        }
    }

    /** A digit: show the code and move the focus to the line it names. */
    private void jump(ZeroEntry.Target t) {
        moved = false;
        ListRows.setIfChanged(typed, entry.shown());
        switch (t.kind) {
            case FAVOURITES:
                if (!favourites.isEmpty()) favs.focusItem(0);
                ListRows.setIfChanged(note, "OK opens Favourites in Find");
                break;
            case PLAYLISTS:
                playlists.focusItem(0);
                ListRows.setIfChanged(note, "OK opens the playlists in Find");
                break;
            case FAVOURITE:
                if (t.index < favourites.size()) {
                    favs.focusItem(t.index);
                    ListRows.setIfChanged(note, "OK plays favourite " + ZeroEntry.favouriteNumber(t.index));
                } else {
                    ListRows.setIfChanged(note, "No favourite " + entry.typed().substring(1));
                }
                break;
            case PLAYLIST:
                if (t.index < SmartLists.NAMES.length) {
                    playlists.focusItem(t.index);
                    ListRows.setIfChanged(note, "OK plays " + SmartLists.NAMES[t.index]);
                } else {
                    ListRows.setIfChanged(note, "No playlist " + entry.typed().substring(2));
                }
                break;
        }
    }

    /** OK: after arrows, the focused line; otherwise the typed code. */
    private void ok() {
        View focused = act.getCurrentFocus();
        if (moved) {
            if (focused != null && isInside(focused, card)) focused.performClick();
            return;
        }
        ZeroEntry.Target t = entry.target();
        switch (t.kind) {
            case FAVOURITES:
                host.openFind(FindPanel.FAVOURITES);
                break;
            case PLAYLISTS:
                host.openFind(SmartLists.FEATURED + 1);
                break;
            case FAVOURITE:
                if (t.index < favourites.size()) playFavourite(t.index);
                break;
            case PLAYLIST:
                if (t.index < SmartLists.NAMES.length) playPlaylist(t.index);
                break;
        }
    }

    private void playFavourite(int i) {
        if (i < 0 || i >= favourites.size()) return;
        host.playCategory(PresetLibrary.FAVOURITES, favourites.get(i));
        host.dismiss();
    }

    private void playPlaylist(int i) {
        if (i < 0) return;
        List<File> l = lists.playlist(i, System.currentTimeMillis());
        if (l.isEmpty()) {
            ListRows.setIfChanged(note, i == SmartLists.FEATURED ? "Featured is not picked yet"
                    : SmartLists.NAMES[i] + " is empty so far: a preset counts after 10 s with the menu closed");
            return;
        }
        host.playList(SmartLists.NAMES[i], l, null);
        host.dismiss();
    }

    private void kick() {
        ui.removeCallbacks(idle);
        ui.postDelayed(idle, IDLE_MS);
    }

    private LinearLayout column(String title) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView h = ListRows.text(act, title.toUpperCase(java.util.Locale.ROOT), 12, 0xFF8A8A90);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.04f);
        h.setPadding(dp(7), dp(4), 0, dp(2));
        c.addView(h);
        return c;
    }

    private static boolean isInside(View v, View ancestor) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
