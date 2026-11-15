package io.github.sinisk3r.visualizer;

/**
 * A long list shown in a fixed number of row views (Find, the 0-list): which items the rows show now. Scrolling
 * rewrites the rows' text; the views are never rebuilt, so focus stays put and nothing flickers. Plain logic,
 * tested on the JVM.
 */
final class RowWindow {
    final int rows;
    private int size;
    private int offset;

    RowWindow(int rows) {
        this.rows = rows;
    }

    /** A new list: back to the top. */
    void reset(int size) {
        this.size = Math.max(0, size);
        offset = 0;
    }

    int size() {
        return size;
    }

    int offset() {
        return offset;
    }

    /** The item row `slot` shows, or -1 when the row is empty (GONE). */
    int item(int slot) {
        int i = offset + slot;
        return slot >= 0 && slot < rows && i < size ? i : -1;
    }

    /** Scrolls the least needed to show item `i` and returns its row. */
    int show(int i) {
        i = Math.max(0, Math.min(i, size - 1));
        if (i < offset) offset = i;
        else if (i >= offset + rows) offset = i - rows + 1;
        return i - offset;
    }

    /**
     * Down (+1) or Up (-1) from row `slot`. Returns true when the window scrolled (the focus stays on the same
     * row, which now shows the next item); false when the focus should move on as usual.
     */
    boolean scroll(int slot, int delta) {
        int i = offset + slot + delta;
        if (i < 0 || i >= size) return false;
        if (delta > 0 && slot == rows - 1) {
            offset++;
            return true;
        }
        if (delta < 0 && slot == 0 && offset > 0) {
            offset--;
            return true;
        }
        return false;
    }

    /** Items below the last row ("▼ 8 more"). */
    int below() {
        return Math.max(0, size - offset - rows);
    }
}
