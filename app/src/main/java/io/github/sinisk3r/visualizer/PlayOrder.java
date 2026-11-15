package io.github.sinisk3r.visualizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The play order: which styles can be picked, the presets of the current style in the order they play, and where
 * we are in it. Filters (hidden, heavy, deaf, styles and sub-styles switched off) and the shuffle come from a
 * {@link Source}, so this is plain logic, tested on the JVM; VizRenderer shows what it picks.
 */
final class PlayOrder {
    /** What the order is built from: the library plus the user's settings. */
    interface Source {
        /** Every style in library order, {@link PresetLibrary#ALL} first. */
        List<String> styles();

        /** The style's presets in library order, unfiltered. */
        List<File> presets(String style);

        /** Every preset, for favourites. */
        List<File> all();

        boolean styleOn(String style);

        boolean hidden(File f);

        boolean favourite(File f);

        /** Hidden, heavy, deaf or its sub-style switched off: not played from this style. */
        boolean skipped(String style, File f);

        /** {@link Settings#SHUFFLE_OFF}, {@link Settings#SHUFFLE_SET} or {@link Settings#SHUFFLE_ALL}. */
        int shuffleMode();

        List<File> shuffle(List<File> list);
    }

    private final Source source;
    private List<String> categories = new ArrayList<>();
    private String category;
    private List<File> order = new ArrayList<>();
    private int position = -1;

    PlayOrder(Source source, String startCategory) {
        this.source = source;
        this.category = startCategory;
    }

    String category() {
        return category;
    }

    List<String> categories() {
        return categories;
    }

    int size() {
        return order.size();
    }

    File current() {
        return position >= 0 && position < order.size() ? order.get(position) : null;
    }

    /** The preset after the current one (wraps), or null when there is only one. */
    File next() {
        return order.size() > 1 ? order.get(Math.floorMod(position + 1, order.size())) : null;
    }

    /** Presets a style plays, filters applied, in library order. */
    List<File> visible(String cat) {
        List<File> out = new ArrayList<>();
        if (PresetLibrary.FAVOURITES.equals(cat)) {
            // Favourites play even from styles that are switched off: a star is a stronger choice.
            for (File f : source.all()) {
                if (source.favourite(f) && !source.hidden(f)) out.add(f);
            }
        } else if (PresetLibrary.ALL.equals(cat)) {
            for (String c : source.styles()) {
                if (!c.equals(PresetLibrary.ALL) && source.styleOn(c)) addVisible(c, out);
            }
        } else {
            addVisible(cat, out);
        }
        return out;
    }

    private void addVisible(String cat, List<File> out) {
        for (File f : source.presets(cat)) {
            if (!source.skipped(cat, f)) out.add(f);
        }
    }

    /**
     * Play order for a style under the bar's Shuffle setting. Off: library order. This set: shuffled within the
     * style (what the app always did). Everything: shuffled across every style that is on, whatever style is picked.
     * TODO(menu phase 2): changing style with Everything asks "Shuffle this style only?" (docs/next.md B).
     */
    private List<File> ordered(String cat) {
        switch (source.shuffleMode()) {
            case Settings.SHUFFLE_OFF: return visible(cat);
            case Settings.SHUFFLE_ALL: return source.shuffle(visible(PresetLibrary.ALL));
            default: return source.shuffle(visible(cat));
        }
    }

    /** Recomputes styles and order after a settings change; keeps `keepPath` first if it is still allowed. */
    void rebuild(String keepPath) {
        List<String> cats = new ArrayList<>();
        cats.add(PresetLibrary.ALL);
        if (!visible(PresetLibrary.FAVOURITES).isEmpty()) cats.add(PresetLibrary.FAVOURITES);
        for (String c : source.styles()) {
            if (!c.equals(PresetLibrary.ALL) && source.styleOn(c) && !visible(c).isEmpty()) cats.add(c);
        }
        categories = cats;
        if (!categories.contains(category)) category = PresetLibrary.ALL;
        order = ordered(category);
        position = -1;
        if (keepPath != null) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).getPath().equals(keepPath)) {
                    order.add(0, order.remove(i));
                    position = 0;
                    break;
                }
            }
        }
    }

    /** A held preset stays on even if a filter (heavy, deaf, a style switched off) would skip it. */
    void hold(File preset) {
        if (current() == null) {
            order.add(0, preset);
            position = 0;
        }
    }

    /** Jumps to this preset (typed channel number). If the order skips it (filters, another style), it is slotted
     *  in after the current one, so Next and Back still work from there. */
    void play(File preset) {
        int i = -1;
        for (int k = 0; k < order.size(); k++) {
            if (order.get(k).getPath().equals(preset.getPath())) { i = k; break; }
        }
        if (i < 0) {
            i = Math.min(position + 1, order.size());
            order.add(i, preset);
        }
        position = i;
    }

    /** Next (+1) or previous (-1) preset; wraps around. Null when nothing is left to play. */
    File step(int delta) {
        if (order.isEmpty()) return null;
        position = Math.floorMod(position + delta, order.size());
        return order.get(position);
    }

    /** Next or previous style; the order starts over (position before the first preset). */
    void changeCategory(int delta) {
        int i = Math.max(0, categories.indexOf(category));
        category = categories.get(Math.floorMod(i + delta, categories.size()));
        order = ordered(category);
        position = -1;
    }

    /** Takes the current preset out of this session's order (too slow); the next step moves on. Returns it, or
     *  null when it is the only one left (it stays). */
    File dropCurrent() {
        File bad = current();
        if (bad == null || order.size() <= 1) return null;
        order.remove(position);
        position--;
        return bad;
    }
}
