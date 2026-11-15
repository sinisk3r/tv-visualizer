package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Find (mockup A2, screen 2): lists on the left (Favourites, the playlists 001-004, Styles), the focused list's
 * presets on the right with their numbers, and a preview of the focused preset: a frame this TV saved when it
 * played (ThumbGrabber), or a neutral placeholder. Each list has Play (in order) and Shuffle (this set).
 * Favourites shows its whole styles as chips above its single presets.
 *
 * Built once; a new list or scrolling only rewrites text (ListRows). The card fades in and is GONE when closed.
 * MenuPanel shows it as the Find card and passes it keys first.
 */
final class FindPanel {
    interface Host {
        /** Plays a list as the current set (PlayOrder), from `first` or its start. */
        void playList(String name, List<File> presets, File first);

        /** Plays a style or Favourites, from `first` or its start. */
        void playCategory(String category, File first);

        /** Closes the menu (after OK plays something). */
        void dismiss();
    }

    /** Lists on the left: 0 Favourites, 1-4 the playlists (SmartLists index + 1), 5 Styles. */
    static final int FAVOURITES = 0, STYLES = 5;
    private static final String[] SIDE = {"Favourites", SmartLists.NAMES[0], SmartLists.NAMES[1],
            SmartLists.NAMES[2], SmartLists.NAMES[3], "Styles"};
    private static final int CHIPS = 4;
    private static final int ROWS = 6;

    private final Activity act;
    private final Settings settings;
    private final PresetLibrary library;
    private final SmartLists lists;
    private final File thumbs;
    private final Host host;
    private Consumer<String> openStyle = s -> { };

    private final FrameLayout card;
    private final ListRows side, content;
    private final TextView title, empty, caption, noPicture;
    private final TextView[] styleChips = new TextView[CHIPS];
    private final TextView range, shuffle, play;
    private final ImageView picture;
    private final View preview;

    /** The list on the right and what it holds (presets, or style names for Styles). */
    private int list = FAVOURITES;
    private List<File> items = Collections.emptyList();
    private List<String> styles = Collections.emptyList();
    private boolean quarter;  // Most watched: three months instead of one
    private String previewOf;

    FindPanel(Activity act, Settings settings, PresetLibrary library, SmartLists lists, File thumbs, Host host) {
        this.act = act;
        this.settings = settings;
        this.library = library;
        this.lists = lists;
        this.thumbs = thumbs;
        this.host = host;

        card = new FrameLayout(act);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(10), dp(7), dp(10), dp(8));
        card.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.leftMargin = cl.rightMargin = dp(48);
        cl.bottomMargin = dp(78);
        ((ViewGroup) act.findViewById(R.id.menu)).addView(card, cl);

        LinearLayout cols = new LinearLayout(act);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(cols);

        side = new ListRows(act, SIDE.length, 22, i -> openList(), this::select);
        for (View r : side.rows) r.setBackgroundResource(R.drawable.list_side_row);
        side.setList(SIDE.length, new ListRows.Binder() {
            public String number(int i) { return i == FAVOURITES ? "0" : i == STYLES ? "" : ZeroEntry.playlistNumber(i - 1); }
            public String name(int i) { return SIDE[i]; }
            public String value(int i) { return sideValue(i); }
        });
        cols.addView(side.column, new LinearLayout.LayoutParams(dp(165), ViewGroup.LayoutParams.WRAP_CONTENT));

        View sep = new View(act);
        sep.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        sl.leftMargin = sl.rightMargin = dp(11);
        cols.addView(sep, sl);

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Head: the list's name, Favourites' style chips, then Month/3 months (Most watched), Shuffle, Play.
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        title = ListRows.text(act, "", 12, 0xFFECECEE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = dp(8);
        head.addView(title, tl);
        for (int i = 0; i < CHIPS; i++) {
            int slot = i;
            styleChips[i] = chip("", v -> {
                List<String> favStyles = favouriteStyles();
                if (slot < favStyles.size()) {
                    host.playCategory(favStyles.get(slot), null);
                    host.dismiss();
                }
            });
            head.addView(styleChips[i], chipParams());
        }
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        range = chip("", v -> {
            quarter = !quarter;
            select(list);
        });
        head.addView(range, chipParams());
        shuffle = chip("Shuffle", v -> playAll(Settings.SHUFFLE_SET));
        head.addView(shuffle, chipParams());
        play = chip("Play in order", v -> playAll(Settings.SHUFFLE_OFF));
        head.addView(play, chipParams());

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(3);
        right.addView(body, bl);

        FrameLayout rowsBox = new FrameLayout(act);
        body.addView(rowsBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content = new ListRows(act, ROWS, 34, this::pick, this::preview);
        rowsBox.addView(content.column);
        empty = ListRows.text(act, "", 12, 0xFFA4A4AA);
        empty.setSingleLine(false);
        empty.setPadding(dp(7), dp(6), dp(7), 0);
        rowsBox.addView(empty);

        LinearLayout pv = new LinearLayout(act);
        pv.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(192), ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = dp(9);
        pl.topMargin = dp(2);
        body.addView(pv, pl);
        preview = pv;
        FrameLayout frame = new FrameLayout(act);
        frame.setBackgroundResource(R.drawable.preview_bg);
        frame.setClipToOutline(true);
        pv.addView(frame, new LinearLayout.LayoutParams(dp(192), dp(108)));
        picture = new ImageView(act);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setVisibility(View.GONE);
        frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        noPicture = ListRows.text(act, "No picture yet", 12, 0xFF74747A);
        frame.addView(noPicture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        caption = ListRows.text(act, "", 12, 0xFF74747A);
        caption.setPadding(0, dp(4), 0, 0);
        pv.addView(caption);
    }

    View view() {
        return card;
    }

    /** OK on a style (Styles list) opens its page in MenuPanel. */
    void setOpenStyle(Consumer<String> open) {
        openStyle = open;
    }

    /**
     * Fills the card for list `which` and focuses it: the list's row on the left, or (`focusContent`) the row of
     * `focusItem` on the right. Call once the card is VISIBLE (a hidden view cannot take focus).
     */
    void show(int which, boolean focusContent, int focusItem) {
        side.bind();  // counts may have changed since the last time
        select(which);
        if (focusContent && content.window.size() > 0) content.focusItem(focusItem);
        else side.focusItem(which);
    }

    /**
     * Keys first, while Find is open: Up and Down scroll the long lists at their ends. Left, Right and Up never
     * leave the card (Right past Play would land on the bar's Hide); Down to the bar is MenuPanel's.
     */
    boolean handleKey(KeyEvent e) {
        View focused = act.getCurrentFocus();
        if (content.handleKey(e, focused)) return true;
        if (e.getAction() != KeyEvent.ACTION_DOWN || focused == null || !isInside(focused, card)) return false;
        int dir;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            default: return false;
        }
        View next = focused.focusSearch(dir);
        return next == null || !isInside(next, card);
    }

    private static boolean isInside(View v, View ancestor) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    // --- lists ---

    private void select(int which) {
        if (which < 0) return;
        list = which;
        for (int s = 0; s < side.rows.length; s++) side.rows[s].setActivated(side.itemOf(side.rows[s]) == which);
        long now = System.currentTimeMillis();
        styles = Collections.emptyList();
        if (which == FAVOURITES) {
            items = favourites();
        } else if (which == STYLES) {
            items = Collections.emptyList();
            styles = styleNames();
        } else if (which == SmartLists.MOST_WATCHED + 1) {
            items = lists.mostWatched(now, quarter ? SmartLists.QUARTER_DAYS : SmartLists.MONTH_DAYS);
        } else {
            items = lists.playlist(which - 1, now);
        }
        ListRows.setIfChanged(title, SIDE[which]);
        List<String> favStyles = which == FAVOURITES ? favouriteStyles() : Collections.emptyList();
        for (int i = 0; i < CHIPS; i++) {
            boolean on = i < favStyles.size();
            styleChips[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                String label = "★ " + favStyles.get(i);
                if (i == CHIPS - 1 && favStyles.size() > CHIPS) label += "  +" + (favStyles.size() - CHIPS);
                ListRows.setIfChanged(styleChips[i], label);
            }
        }
        range.setVisibility(which == SmartLists.MOST_WATCHED + 1 ? View.VISIBLE : View.GONE);
        ListRows.setIfChanged(range, quarter ? "3 months" : "Month");
        boolean playable = which != STYLES;
        shuffle.setVisibility(playable ? View.VISIBLE : View.GONE);
        play.setVisibility(playable ? View.VISIBLE : View.GONE);
        preview.setVisibility(playable ? View.VISIBLE : View.GONE);

        int size = which == STYLES ? styles.size() : items.size();
        content.setList(size, new ListRows.Binder() {
            public String number(int i) {
                if (list == STYLES) return "";
                if (list == FAVOURITES) return ZeroEntry.favouriteNumber(i);
                int n = library.numberOf(items.get(i));
                return n > 0 ? String.valueOf(n) : "";
            }
            public String name(int i) {
                return list == STYLES ? styles.get(i) : PresetLibrary.displayName(items.get(i));
            }
            public String value(int i) {
                if (list == STYLES) return (settings.isFavouriteStyle(styles.get(i)) ? "★  " : "")
                        + String.format("%,d  ›", library.byCategory.get(styles.get(i)).size());
                return library.styleOf(items.get(i));
            }
        });
        empty.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        if (size == 0) ListRows.setIfChanged(empty, emptyText(which));
        // Right from the list column goes to the first row, or to Play when the list is empty.
        View target = size > 0 ? content.rows[0] : playable ? shuffle : null;
        int targetId = target == null ? View.NO_ID : idOf(target);
        for (View r : side.rows) r.setNextFocusRightId(targetId);
        View back = side.rows[which];
        for (View r : content.rows) r.setNextFocusLeftId(back.getId());
        if (size > 0 && playable) preview(0);
        else showPicture(null, "");
    }

    private String emptyText(int which) {
        switch (which) {
            case FAVOURITES:
                return favouriteStyles().isEmpty() ? "No favourites yet. ★ on the bar adds the preset playing; a "
                        + "whole style can be a favourite from its page under Styles." : "Only whole styles so far: "
                        + "Play and Shuffle play them.";
            case SmartLists.FEATURED + 1:
                return "Featured is being picked. It comes with an update.";
            case SmartLists.MOST_WATCHED + 1:
                return "Nothing watched long enough yet " + (quarter ? "in three months." : "this month.")
                        + " A preset counts after 10 s with the menu closed.";
            case SmartLists.NEW_TO_YOU + 1:
                return "You have seen every preset. Hidden ones are left out.";
            case SmartLists.RECENT + 1:
                return "Nothing played yet. A preset counts after 10 s with the menu closed.";
            default:
                return "";
        }
    }

    private String sideValue(int i) {
        switch (i) {
            case FAVOURITES: {
                int n = settings.favouritesInOrder().size() + settings.favouriteStyles().size();
                return n == 0 ? "" : String.valueOf(n);
            }
            case SmartLists.FEATURED + 1:
                return lists.hasFeatured() ? String.valueOf(lists.featured().size()) : "soon";
            case SmartLists.MOST_WATCHED + 1:
                return quarter ? "3 mo" : "month";
            case SmartLists.NEW_TO_YOU + 1:
            case SmartLists.RECENT + 1:
                return String.format("%,d", lists.playlist(i - 1, System.currentTimeMillis()).size());
            case STYLES:
                return String.valueOf(styleNames().size());
            default:
                return "";
        }
    }

    private List<File> favourites() {
        List<File> out = new ArrayList<>();
        for (String n : settings.favouritesInOrder()) {
            File f = library.byName(n);
            if (f != null) out.add(f);
        }
        return out;
    }

    private List<String> favouriteStyles() {
        List<String> out = new ArrayList<>();
        for (String s : styleNames()) if (settings.isFavouriteStyle(s)) out.add(s);
        return out;
    }

    private List<String> styleNames() {
        List<String> out = new ArrayList<>();
        for (String c : library.categories) if (!c.equals(PresetLibrary.ALL)) out.add(c);
        return out;
    }

    // --- actions ---

    /** OK on a list on the left: into its rows. */
    private void openList() {
        View first = content.first();
        if (first != null) first.requestFocus();
        else if (shuffle.getVisibility() == View.VISIBLE) shuffle.requestFocus();
    }

    /** OK on a row: plays the list from that preset (Styles: opens the style's page). */
    private void pick(int i) {
        if (i < 0) return;
        if (list == STYLES) {
            openStyle.accept(styles.get(i));
            return;
        }
        File f = items.get(i);
        if (list == FAVOURITES) host.playCategory(PresetLibrary.FAVOURITES, f);
        else host.playList(SIDE[list], items, f);
        host.dismiss();
    }

    /** Play (in order) or Shuffle (this set) on the whole list; sets the bar's Shuffle to match. */
    private void playAll(int mode) {
        if (list == FAVOURITES) {
            if (items.isEmpty() && favouriteStyles().isEmpty()) {
                toast("No favourites yet.");
                return;
            }
            settings.setShuffle(mode);
            host.playCategory(PresetLibrary.FAVOURITES, null);
        } else {
            if (items.isEmpty()) {
                toast(list == SmartLists.FEATURED + 1 ? "Featured is not picked yet." : "Nothing in this list yet.");
                return;
            }
            settings.setShuffle(mode);
            host.playList(SIDE[list], items, null);
        }
        host.dismiss();
    }

    // --- preview ---

    private void preview(int i) {
        if (i < 0 || i >= items.size()) return;
        File f = items.get(i);
        int n = library.numberOf(f);
        String num = list == FAVOURITES ? ZeroEntry.favouriteNumber(i) + "  ·  " : "";
        showPicture(f, num + (n > 0 ? "channel " + n + "  ·  " : "") + "OK plays");
    }

    /** The saved frame, or the placeholder when this TV has not played the preset 10 s yet. */
    private void showPicture(File preset, String text) {
        ListRows.setIfChanged(caption, text);
        String key = preset == null ? null : preset.getName();
        if (key != null && key.equals(previewOf)) return;
        previewOf = key;
        Bitmap bmp = null;
        if (key != null) {
            File jpg = ThumbGrabber.file(thumbs, key);
            if (jpg.isFile()) bmp = BitmapFactory.decodeFile(jpg.getPath());  // 320x180, ~15 KB: quick
        }
        picture.setImageBitmap(bmp);
        picture.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
        noPicture.setVisibility(bmp != null ? View.GONE : View.VISIBLE);
    }

    // --- views ---

    private TextView chip(String label, View.OnClickListener click) {
        TextView t = ListRows.text(act, label, 12, 0);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), 0, dp(9), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setId(View.generateViewId());
        return t;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22));
        lp.leftMargin = dp(4);
        return lp;
    }

    private static int idOf(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
