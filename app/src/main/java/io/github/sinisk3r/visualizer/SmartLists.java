package io.github.sinisk3r.visualizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The playlists of Find and the 0-list, numbered 001 to 004: Featured (assets/featured.txt), Most watched (last
 * month or three months), New to you (never or rarely shown) and Recently played, the last three from
 * {@link WatchHistory}. Hidden presets and presets not on this TV (full pack not downloaded) are left out.
 * Plain logic, tested on the JVM.
 */
final class SmartLists {
    static final int FEATURED = 0, MOST_WATCHED = 1, NEW_TO_YOU = 2, RECENT = 3;
    static final String[] NAMES = {"Featured", "Most watched", "New to you", "Recently played"};
    /** Most watched and Recently played stop here; New to you can hold the whole library. */
    static final int LIMIT = 50;
    static final int MONTH_DAYS = 30, QUARTER_DAYS = 91;

    interface Source {
        /** Every preset in library order. */
        List<File> all();

        /** The preset with this file name, or null when it is not on this TV. */
        File byName(String name);

        boolean hidden(File f);
    }

    private final Source source;
    private final WatchHistory history;
    /** File names from assets/featured.txt; null when the file is not there (Featured not picked yet). */
    private final List<String> featured;

    SmartLists(Source source, WatchHistory history, List<String> featured) {
        this.source = source;
        this.history = history;
        this.featured = featured;
    }

    /** False until assets/featured.txt ships: Featured shows "not picked yet" and cannot play. */
    boolean hasFeatured() {
        return featured != null && !featured.isEmpty();
    }

    /** A playlist by its index (0: Featured ... 3: Recently played). Most watched looks back a month here. */
    List<File> playlist(int index, long nowMs) {
        switch (index) {
            case FEATURED: return featured();
            case MOST_WATCHED: return mostWatched(nowMs, MONTH_DAYS);
            case NEW_TO_YOU: return newToYou();
            case RECENT: return recent();
            default: return Collections.emptyList();
        }
    }

    List<File> featured() {
        return featured == null ? Collections.emptyList() : files(featured);
    }

    List<File> mostWatched(long nowMs, int days) {
        return files(history.mostWatched(nowMs, days, LIMIT));
    }

    List<File> recent() {
        return files(history.recentlyPlayed(LIMIT));
    }

    List<File> newToYou() {
        List<String> names = new ArrayList<>();
        for (File f : source.all()) if (!source.hidden(f)) names.add(f.getName());
        return files(history.newToYou(names));
    }

    /** File names to presets on this TV, hidden ones and duplicates left out. */
    List<File> files(Collection<String> names) {
        List<File> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String n : names) {
            File f = source.byName(n);
            if (f != null && !source.hidden(f) && seen.add(n)) out.add(f);
        }
        return out;
    }

    /** assets/featured.txt: one preset file name per line; blank lines and "#" comments skipped. */
    static List<String> parseFeatured(Reader in) throws IOException {
        List<String> out = new ArrayList<>();
        BufferedReader b = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        for (String line; (line = b.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(line);
        }
        return out;
    }

    /**
     * Favourites in the order they were added (so 01, 02 ... stay put when one is added): the saved order, kept to
     * what is still a favourite, then any favourite missing from it (older installs) by name.
     */
    static List<String> favouriteOrder(List<String> saved, Set<String> favourites) {
        List<String> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String n : saved) if (favourites.contains(n) && seen.add(n)) out.add(n);
        List<String> rest = new ArrayList<>();
        for (String n : favourites) if (!seen.contains(n)) rest.add(n);
        Collections.sort(rest);
        out.addAll(rest);
        return out;
    }
}
