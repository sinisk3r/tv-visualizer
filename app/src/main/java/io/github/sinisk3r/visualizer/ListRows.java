package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * A column of list rows (number, name, a dim value at the end) over a {@link RowWindow}: a fixed set of row views
 * made once; scrolling and new lists only rewrite their text. Used by Find and the 0-list. Sizes follow mockup A2 at
 * half size in dp (1 dp = 2 px on a 1080p TV).
 */
final class ListRows {
    /** What row `item` shows: number, name, value. */
    interface Binder {
        String number(int item);
        String name(int item);
        String value(int item);
    }

    final LinearLayout column;
    final RowWindow window;
    final View[] rows;
    private final TextView[] nums, names, values;
    private final TextView more;
    private Binder binder;
    private final IntConsumer focus;
    private final Activity act;

    /** `click` gets the item OK was pressed on; `focus` (may be null) the item a row now shows with focus. */
    ListRows(Activity act, int visible, int numberWidthDp, IntConsumer click, IntConsumer focus) {
        this.act = act;
        this.focus = focus;
        window = new RowWindow(visible);
        column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        rows = new View[visible];
        nums = new TextView[visible];
        names = new TextView[visible];
        values = new TextView[visible];
        for (int i = 0; i < visible; i++) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setFocusable(true);
            r.setClickable(true);
            r.setBackgroundResource(R.drawable.card_row);
            r.setPadding(dp(7), 0, dp(7), 0);
            r.setTag(i);
            r.setId(View.generateViewId());
            r.setOnClickListener(v -> click.accept(itemOf(v)));
            if (focus != null) r.setOnFocusChangeListener((v, has) -> {
                if (has) focus.accept(itemOf(v));
            });
            TextView n = text(act, "", 12, 0);
            n.setTextColor(act.getColorStateList(R.color.on_focus_dim));
            n.setTypeface(Typeface.MONOSPACE);
            n.setGravity(Gravity.END);
            n.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(numberWidthDp),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.rightMargin = dp(7);
            r.addView(n, nl);
            TextView nm = text(act, "", 12, 0);
            nm.setTextColor(act.getColorStateList(R.color.on_focus));
            nm.setDuplicateParentStateEnabled(true);
            r.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = text(act, "", 12, 0);
            v.setTextColor(act.getColorStateList(R.color.on_focus_dim));
            v.setDuplicateParentStateEnabled(true);
            v.setPadding(dp(8), 0, 0, 0);
            r.addView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
            lp.topMargin = dp(2);
            column.addView(r, lp);
            rows[i] = r;
            nums[i] = n;
            names[i] = nm;
            values[i] = v;
        }
        more = text(act, "", 12, 0xFF74747A);
        more.setPadding(dp(7), dp(2), 0, 0);
        column.addView(more);
    }

    /** A new list: back to the top, rows rewritten. */
    void setList(int size, Binder b) {
        binder = b;
        window.reset(size);
        bind();
    }

    /** Rewrites the rows for the window now. Empty rows and the "more" line are INVISIBLE, not GONE: they sit
     *  inside a card that is shown anyway, and keeping their space keeps the card's height (and its top edge) the
     *  same from list to list. An INVISIBLE row cannot take focus. */
    void bind() {
        for (int s = 0; s < rows.length; s++) {
            int item = window.item(s);
            rows[s].setVisibility(item < 0 ? View.INVISIBLE : View.VISIBLE);
            if (item < 0) continue;
            setIfChanged(nums[s], binder.number(item));
            setIfChanged(names[s], binder.name(item));
            setIfChanged(values[s], binder.value(item));
        }
        int below = window.below();
        more.setVisibility(below > 0 ? View.VISIBLE : View.INVISIBLE);
        if (below > 0) setIfChanged(more, String.format("▼ %,d more", below));
    }

    /** The item a row view shows, or -1. */
    int itemOf(View row) {
        Object tag = row.getTag();
        return tag instanceof Integer ? window.item((Integer) tag) : -1;
    }

    /** Shows item `i` and focuses its row. */
    void focusItem(int i) {
        if (window.size() == 0) return;
        int slot = window.show(i);
        bind();
        rows[slot].requestFocus();
    }

    /** Up/Down on a row of this column: scrolls the window at its ends. True when the key was used. */
    boolean handleKey(KeyEvent e, View focused) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_DPAD_DOWN && k != KeyEvent.KEYCODE_DPAD_UP) return false;
        Object tag = focused == null ? null : focused.getTag();
        if (!(tag instanceof Integer) || focused.getParent() != column) return false;
        if (!window.scroll((Integer) tag, k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) return false;
        bind();
        if (focus != null) focus.accept(itemOf(focused));  // same row, new item: the preview follows
        return true;
    }

    boolean contains(View v) {
        return v != null && v.getParent() == column;
    }

    /** The first row, for focus from elsewhere; null when the list is empty. */
    View first() {
        return window.size() > 0 ? rows[0] : null;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(s, t.getText())) t.setText(s);
    }

    static TextView text(Activity act, String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
