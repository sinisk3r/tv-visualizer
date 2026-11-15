package io.github.sinisk3r.visualizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds presets by name words or by channel number, for the Search panel. No Android types, so it is tested on
 * the JVM. Names are matched as stored (not masked), so a masked word still matches what was typed or said.
 */
final class SearchIndex {
    /** Longest query the keyboard takes. */
    static final int MAX_QUERY = 40;

    static final class Entry {
        final File file;
        /** Channel number, 0 when the preset has none (a user's own preset). */
        final int number;
        /** The name as shown (masked). */
        final String name;
        final String style;
        /** The stored name, normalised for matching, with a leading space so " word" finds a word start. */
        final String text;

        Entry(File file, int number, String name, String style) {
            this.file = file;
            this.number = number;
            this.name = name;
            this.style = style;
            String raw = file.getName();
            if (raw.endsWith(".milk")) raw = raw.substring(0, raw.length() - ".milk".length());
            this.text = " " + normalize(raw.replace('_', ' '));
        }
    }

    private final List<Entry> entries;

    SearchIndex(List<Entry> entries) {
        this.entries = new ArrayList<>(entries);
    }

    int size() {
        return entries.size();
    }

    /**
     * Lower case, apostrophes dropped ("can't" is "cant"), anything else that is not a letter or digit becomes a
     * space, spaces collapsed. Used for names, typed queries and spoken ones alike.
     */
    static String normalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = true;  // drops leading spaces
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '’') continue;
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        int n = out.length();
        if (n > 0 && out.charAt(n - 1) == ' ') out.setLength(n - 1);
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** The query after a key: a letter or digit is added, a space only after a word, never past MAX_QUERY. */
    static String typed(String query, char c) {
        if (query.length() >= MAX_QUERY) return query;
        if (c == ' ') return query.isEmpty() || query.endsWith(" ") ? query : query + ' ';
        if (!Character.isLetterOrDigit(c)) return query;
        return query + Character.toLowerCase(c);
    }

    /** The query after the delete key. */
    static String deleted(String query) {
        return query.isEmpty() ? query : query.substring(0, query.length() - 1);
    }

    /**
     * Presets whose name holds every typed word (in any order, inside words too: "green" finds "evergreen").
     * Names where every word starts a word come first, then by channel number (unnumbered last). An empty query
     * finds nothing.
     */
    List<Entry> search(String query) {
        String q = normalize(query);
        if (q.isEmpty()) return Collections.emptyList();
        String[] words = q.split(" ");
        List<Entry> starts = new ArrayList<>(), inside = new ArrayList<>();
        for (Entry e : entries) {
            boolean all = true, atStart = true;
            for (String w : words) {
                int at = e.text.indexOf(w);
                if (at < 0) {
                    all = false;
                    break;
                }
                if (!e.text.contains(" " + w)) atStart = false;
            }
            if (all) (atStart ? starts : inside).add(e);
        }
        starts.sort(SearchIndex::byNumberOrder);
        inside.sort(SearchIndex::byNumberOrder);
        starts.addAll(inside);
        return starts;
    }

    /** Channel n first, then the numbers that start with its digits (43: then 430-439, 4300-4399), in order. */
    List<Entry> byNumber(int n) {
        List<Entry> out = new ArrayList<>();
        if (n <= 0) return out;
        String prefix = Integer.toString(n);
        Entry exact = null;
        for (Entry e : entries) {
            if (e.number == n) exact = e;
            else if (e.number > 0 && Integer.toString(e.number).startsWith(prefix)) out.add(e);
        }
        out.sort(SearchIndex::byNumberOrder);
        if (exact != null) out.add(0, exact);
        return out;
    }

    private static int byNumberOrder(Entry a, Entry b) {
        if (a.number != b.number) {
            if (a.number == 0) return 1;
            if (b.number == 0) return -1;
            return Integer.compare(a.number, b.number);
        }
        return a.name.compareToIgnoreCase(b.name);
    }
}
