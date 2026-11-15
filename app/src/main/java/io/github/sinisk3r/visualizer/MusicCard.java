package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The music card (mockup A2, screens 1m and 1p): above the bar on the left while the bar is open with no other card.
 * Controls whatever app is playing: previous, a larger play/pause, next. These need no permission (media keys
 * through AudioManager).
 *
 * With notification access (granted once in TV Settings, asked for only from the card's "See song and album art"
 * row, after an explainer) it also shows the playing app's album art, title, artist and play state, from its media
 * session, and, when the session can seek and knows the song's length, a scrubber under the controls: elapsed
 * time, a bar, total time. Left/Right on it seek 10 s (held: 30 s). Nothing is stored or sent; notifications
 * themselves are never read (MusicListener is empty).
 *
 * Our own demo songs (DemoPlayer) have no media session, so the card ignores them.
 *
 * Remote: Up from any bar button lands on play/pause; Left/Right along the controls; Down to the scrubber when
 * shown, then "See song and album art" when shown, then back to the bar button Up came from. Built once; updates
 * only rewrite values.
 */
final class MusicCard {
    private static final String TAG = "Visualizer";
    private static final long FADE_MS = 140;

    private final Activity act;
    private final ViewGroup root;
    private final View bar, scrim;
    private final BooleanSupplier demoOn;
    private final AudioManager audio;
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final LinearLayout card;
    private final ImageView art, playIcon;
    private final TextView song, who, src, askRow;
    private final FrameLayout prev, play, next;
    /** The scrubber row: elapsed, bar, total. GONE without access or without seek. */
    private final LinearLayout seekRow;
    private final TextView seekAt, seekTotal;
    private final ProgressBar seekBar;
    /** The last seek's target and when (uptime), so quick steps build on it; -1: none. */
    private long seekTarget = -1, lastSeekAt;
    private final View explainer;
    private final TextView openSettings, notNow;

    /** The bar button Up came from; Down from the card returns there. */
    private View returnTo;
    private boolean shown, access;
    private MediaController controller;
    private Bitmap shownArt;
    private boolean listening;

    MusicCard(Activity act, ViewGroup root, View bar, View scrim, BooleanSupplier demoOn) {
        this.act = act;
        this.root = root;
        this.bar = bar;
        this.scrim = scrim;
        this.demoOn = demoOn;
        audio = act.getSystemService(AudioManager.class);
        sessions = act.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(act, MusicListener.class);
        prefs = act.getSharedPreferences("music", Context.MODE_PRIVATE);

        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setVisibility(View.GONE);
        // 288 dp, not the mockup's 320: the bar's Previous label (above the button) would run under a wider card.
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(288), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        cl.leftMargin = dp(48);
        cl.bottomMargin = dp(78);
        root.addView(card, cl);

        art = new ImageView(act);
        art.setBackgroundResource(R.drawable.art_bg);
        art.setClipToOutline(true);
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setImageResource(R.drawable.ic_note);
        art.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF74747A));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(56), dp(56));
        al.rightMargin = dp(8);
        al.gravity = Gravity.TOP;
        card.addView(art, al);

        LinearLayout meta = new LinearLayout(act);
        meta.setOrientation(LinearLayout.VERTICAL);
        card.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        song = text(13, 0xFFECECEE, true);
        meta.addView(song);
        who = text(12, 0xFFA4A4AA, false);
        meta.addView(who);

        LinearLayout ctl = new LinearLayout(act);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.setClipChildren(false);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ctlp.topMargin = dp(2);
        meta.addView(ctl, ctlp);
        prev = button(ctl, R.id.m_prev, R.drawable.ic_prev, "Previous song", 28, R.drawable.bar_button,
                v -> previous());
        play = button(ctl, R.id.m_play, R.drawable.ic_pause, "Play or pause", 36, R.drawable.music_play_bg,
                v -> playPause());
        playIcon = (ImageView) play.getChildAt(0);
        next = button(ctl, R.id.m_next, R.drawable.ic_next, "Next song", 28, R.drawable.bar_button, v -> next());
        src = text(12, 0xFF74747A, false);
        src.setGravity(Gravity.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(6);
        ctl.addView(src, sl);

        seekRow = new LinearLayout(act);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.setId(R.id.m_seek);
        seekRow.setBackgroundResource(R.drawable.card_row);
        seekRow.setPadding(dp(8), 0, dp(8), 0);
        seekRow.setFocusable(true);
        seekRow.setContentDescription("Song position: left and right seek");
        seekRow.setVisibility(View.GONE);
        seekAt = text(12, 0, false);
        seekAt.setTextColor(act.getColorStateList(R.color.on_focus));
        seekAt.setDuplicateParentStateEnabled(true);
        seekRow.addView(seekAt);
        seekBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        seekBar.setMax(1000);
        seekBar.setProgressDrawable(act.getDrawable(R.drawable.seek_bar));
        seekBar.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(0, dp(3), 1f);
        sbl.leftMargin = sbl.rightMargin = dp(7);
        seekRow.addView(seekBar, sbl);
        seekTotal = text(12, 0, false);
        seekTotal.setTextColor(act.getColorStateList(R.color.on_focus_dim));
        seekTotal.setDuplicateParentStateEnabled(true);
        seekRow.addView(seekTotal);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        srl.topMargin = dp(6);
        meta.addView(seekRow, srl);

        askRow = text(12, 0xFFECECEE, false);
        askRow.setTextColor(act.getColorStateList(R.color.on_focus));
        askRow.setText("See song and album art");
        askRow.setId(R.id.m_ask);
        askRow.setBackgroundResource(R.drawable.card_row);
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        askRow.setPadding(dp(8), 0, dp(8), 0);
        askRow.setFocusable(true);
        askRow.setClickable(true);
        askRow.setOnClickListener(v -> askForAccess());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        rl.topMargin = dp(6);
        meta.addView(askRow, rl);

        // The explainer (screen 1p): over everything, the bar hidden under it.
        FrameLayout ex = new FrameLayout(act);
        ex.setBackgroundColor(0x8C000000);
        ex.setVisibility(View.GONE);
        root.addView(ex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        ex.addView(box, new FrameLayout.LayoutParams(dp(450), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView h = text(16, 0xFFECECEE, true);
        h.setSingleLine(false);
        h.setText("See the song and album art?");
        box.addView(h);
        TextView body = paragraph("To show what's playing in Spotify, YouTube Music and other apps, Visualizer needs "
                + "notification access. Android asks for it on one Settings screen: turn on Visualizer there, then "
                + "press Back.", 0xFFECECEE);
        box.addView(body, paraParams());
        TextView small = paragraph("The song details stay on this TV. Visualizer does not read or keep your "
                + "notifications. Play, pause and skip work without this.", 0xFFA4A4AA);
        box.addView(small, paraParams());
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        box.addView(btns, bp);
        openSettings = chip("Open Settings", R.id.m_open_settings, v -> openSettings());
        notNow = chip("Not now", R.id.m_not_now, v -> closeExplainer());
        btns.addView(openSettings, chipParams(0));
        btns.addView(notNow, chipParams(dp(6)));
        explainer = ex;
    }

    // --- shown with the bar ---

    /** The bar is open with no other card: fade the card in (its values first). */
    void show() {
        shown = true;
        update();
        if (card.getVisibility() == View.VISIBLE) return;
        card.animate().cancel();
        card.setAlpha(0f);
        card.setVisibility(View.VISIBLE);
        card.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
    }

    /** Another card opened, or the menu closed. Hidden views are GONE (a visible one costs a 4K compose). */
    void hide() {
        shown = false;
        card.animate().cancel();
        card.setVisibility(View.GONE);
        if (explainer.getVisibility() == View.VISIBLE) closeExplainer();
        stopListening();
        returnTo = null;
    }

    boolean isShown() {
        return shown;
    }

    /** The access explainer is up: the menu counts it as a card (the longer idle wait). */
    boolean explainerShown() {
        return explainerOpen();
    }

    /** Once a second while the bar is open: access may have changed in Settings, the app may have changed. */
    void tick() {
        if (shown) update();
    }

    /** Back: closes the explainer. False when the menu should handle Back. */
    boolean back() {
        if (explainer.getVisibility() != View.VISIBLE) return false;
        closeExplainer();
        return true;
    }

    /** Keys while the bar is open with no other card. True when used. */
    boolean handleKey(KeyEvent e, View focused) {
        if (!shown || e.getAction() != KeyEvent.ACTION_DOWN) return explainerOpen() && isArrow(e.getKeyCode());
        int k = e.getKeyCode();
        if (explainerOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) openSettings.requestFocus();
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) notNow.requestFocus();
            return isArrow(k);
        }
        if (focused == null) return false;
        if (isInside(focused, bar)) {
            if (k != KeyEvent.KEYCODE_DPAD_UP) return false;
            returnTo = focused;
            play.requestFocus();
            return true;
        }
        if (!isInside(focused, card)) return false;
        boolean inControls = focused == prev || focused == play || focused == next;
        boolean seekShown = seekRow.getVisibility() == View.VISIBLE;
        boolean askShown = askRow.getVisibility() == View.VISIBLE;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focused == askRow && seekShown) seekRow.requestFocus();
                else if (focused == askRow || focused == seekRow) play.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inControls && seekShown) seekRow.requestFocus();
                else if ((inControls || focused == seekRow) && askShown) askRow.requestFocus();
                else toBar();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focused == seekRow) seek(-1, e);
                else if (focused == play) prev.requestFocus();
                else if (focused == next) play.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focused == seekRow) seek(1, e);
                else if (focused == prev) play.requestFocus();
                else if (focused == play) next.requestFocus();
                return true;
            default:
                return false;
        }
    }

    private void toBar() {
        View back = returnTo != null && returnTo.isShown() ? returnTo : bar.findViewById(R.id.b_fav);
        if (back != null) back.requestFocus();
    }

    private boolean explainerOpen() {
        return explainer.getVisibility() == View.VISIBLE;
    }

    private static boolean isArrow(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    // --- controls ---

    private void previous() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().skipToPrevious();
        else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void next() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().skipToNext();
        else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private void playPause() {
        MediaController c = controller;
        if (c != null) {
            if (NowPlaying.state(stateOf(c)) == NowPlaying.PLAYING) c.getTransportControls().pause();
            else c.getTransportControls().play();
        } else {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
        // Without a session the button follows "is music playing", which settles a moment later.
        ui.postDelayed(this::update, 400);
        ui.postDelayed(this::update, 1200);
    }

    /** To whichever app holds the active media session. Needs no permission. */
    private void mediaKey(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** Left (-1) or Right (+1) on the scrubber: 10 s, or 30 s once the key has been held ~1.5 s. */
    private void seek(int dir, KeyEvent e) {
        MediaController c = controller;
        long dur = durationOf(c);
        if (c == null || dur <= 0) return;
        long now = SystemClock.uptimeMillis();
        long from = NowPlaying.seekBase(seekTarget, lastSeekAt, now, positionOf(c, dur));
        long step = NowPlaying.seekStep(e.getRepeatCount() > 0 ? e.getEventTime() - e.getDownTime() : 0);
        seekTarget = NowPlaying.seekTarget(from, dir * step, dur);
        lastSeekAt = now;
        c.getTransportControls().seekTo(seekTarget);
        showPosition(seekTarget, dur);  // at once; the app's own report follows
    }

    private static long durationOf(MediaController c) {
        MediaMetadata m = c == null ? null : c.getMetadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private static long positionOf(MediaController c, long dur) {
        PlaybackState s = c.getPlaybackState();
        if (s == null) return 0;
        return NowPlaying.position(s.getPosition(), s.getLastPositionUpdateTime(), s.getPlaybackSpeed(),
                s.getState() == PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime(), dur);
    }

    /** Rewrites the scrubber's values only. */
    private void showPosition(long pos, long dur) {
        setIfChanged(seekAt, NowPlaying.time(pos));
        setIfChanged(seekTotal, NowPlaying.time(dur));
        int p = NowPlaying.progress(pos, dur);
        if (seekBar.getProgress() != p) seekBar.setProgress(p);
    }

    // --- notification access ---

    private void askForAccess() {
        if (prefs.getBoolean("explained", false)) {
            openSettings();
            return;
        }
        bar.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        card.setVisibility(View.GONE);
        explainer.animate().cancel();
        explainer.setAlpha(0f);
        explainer.setVisibility(View.VISIBLE);  // VISIBLE before focus
        explainer.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
        openSettings.requestFocus();
    }

    private void closeExplainer() {
        explainer.animate().cancel();
        explainer.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        if (shown) {
            card.setVisibility(View.VISIBLE);
            askRow.requestFocus();
        }
    }

    private void openSettings() {
        prefs.edit().putBoolean("explained", true).apply();
        if (explainerOpen()) closeExplainer();
        try {
            act.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Open TV Settings, Apps, Special app access, Notification access, and turn on Visualizer");
        }
    }

    private boolean checkAccess() {
        String enabled = android.provider.Settings.Secure.getString(act.getContentResolver(),
                "enabled_notification_listeners");
        return NowPlaying.hasAccess(enabled, act.getPackageName());
    }

    // --- media sessions (with access) ---

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> choose(list);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
            update();
        }
    };

    private void startListening() {
        if (listening) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, ui);
            listening = true;
        } catch (SecurityException e) {
            Log.w(TAG, "music: no session access", e);
        }
    }

    private void stopListening() {
        if (listening) {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            listening = false;
        }
        setController(null);
    }

    /** Re-picks from the active sessions: which one plays can change without the list changing. */
    private void rescan() {
        try {
            choose(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {  // access just turned off
            setController(null);
        }
    }

    private void choose(List<MediaController> list) {
        if (list == null) list = java.util.Collections.emptyList();
        String[] pkgs = new String[list.size()];
        int[] states = new int[list.size()];
        for (int i = 0; i < pkgs.length; i++) {
            pkgs[i] = list.get(i).getPackageName();
            states[i] = stateOf(list.get(i));
        }
        int i = NowPlaying.pick(pkgs, states, act.getPackageName());
        MediaController c = i < 0 ? null : list.get(i);
        if (c == null || controller == null || !c.getSessionToken().equals(controller.getSessionToken())) {
            setController(c);
        }
        if (shown) render();
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        if (c != null) c.registerCallback(callback, ui);
    }

    private static int stateOf(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    // --- values ---

    private void update() {
        if (!shown) return;
        boolean now = checkAccess();
        if (now != access) {
            access = now;
            if (access) startListening();
            else stopListening();
        } else if (access && !listening) {
            startListening();
        }
        if (access) rescan();
        else render();
    }

    /** Rewrites the card's values in place; never rebuilds it. */
    private void render() {
        if (access && askRow.isFocused()) play.requestFocus();  // before it goes: a GONE view drops focus
        askRow.setVisibility(access ? View.GONE : View.VISIBLE);
        MediaController c = controller;
        Bitmap bmp = null;
        int playing;
        long dur = 0;
        boolean seekable = false;
        if (c != null) {
            MediaMetadata m = c.getMetadata();
            String app = appName(c.getPackageName());
            if (m != null) {
                setIfChanged(song, NowPlaying.title(m.getString(MediaMetadata.METADATA_KEY_TITLE),
                        m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), app));
                String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (TextUtils.isEmpty(artist)) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                setIfChanged(who, NowPlaying.who(artist, m.getString(MediaMetadata.METADATA_KEY_ALBUM)));
                bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            } else {
                setIfChanged(song, NowPlaying.title(null, null, app));
                setIfChanged(who, "");
            }
            setIfChanged(src, app);
            playing = NowPlaying.state(stateOf(c));
            PlaybackState st = c.getPlaybackState();
            dur = durationOf(c);
            seekable = NowPlaying.canSeek(access, st == null ? 0 : st.getActions(), dur);
        } else if (access) {
            setIfChanged(song, "Nothing playing");
            setIfChanged(who, "Start music in any app");
            setIfChanged(src, "");
            playing = NowPlaying.STOPPED;
        } else {
            // Without access Android does not say which app plays, only whether something does (our demo songs
            // excluded: they have no session, so the keys would not reach them).
            setIfChanged(song, "Music controls");
            setIfChanged(who, "For the app playing music");
            setIfChanged(src, "");
            playing = audio.isMusicActive() && !demoOn.getAsBoolean() ? NowPlaying.PLAYING : NowPlaying.STOPPED;
        }
        who.setVisibility(who.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (seekable) {
            // While steps follow quickly, show where they lead, not the app's lagging report.
            long now = SystemClock.uptimeMillis();
            showPosition(NowPlaying.seekBase(seekTarget, lastSeekAt, now, positionOf(c, dur)), dur);
            seekRow.setVisibility(View.VISIBLE);
        } else {
            if (seekRow.isFocused()) play.requestFocus();  // before it goes: a GONE view drops focus
            seekRow.setVisibility(View.GONE);
            seekTarget = -1;
        }
        if (bmp != shownArt) {
            shownArt = bmp;
            if (bmp != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageTintList(null);
                art.setImageBitmap(bmp);
            } else {
                art.setScaleType(ImageView.ScaleType.CENTER);
                art.setImageResource(R.drawable.ic_note);
                art.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF74747A));
            }
        }
        int icon = playing == NowPlaying.PLAYING ? R.drawable.ic_pause : R.drawable.ic_play;
        if (!Integer.valueOf(icon).equals(playIcon.getTag())) {
            playIcon.setTag(icon);
            playIcon.setImageResource(icon);
            play.setContentDescription(playing == NowPlaying.PLAYING ? "Pause" : "Play");
        }
    }

    private String appName(String pkg) {
        PackageManager pm = act.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return NowPlaying.appFromPackage(pkg);
        }
    }

    // --- views ---

    private FrameLayout button(ViewGroup parent, int id, int icon, String label, int size, int bg,
            View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(act);
        cell.setId(id);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setBackgroundResource(bg);
        cell.setContentDescription(label);
        cell.setOnClickListener(click);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(act.getColorStateList(R.color.on_focus));
        iv.setDuplicateParentStateEnabled(true);
        int ic = size >= 36 ? 18 : 14;
        cell.addView(iv, new FrameLayout.LayoutParams(dp(ic), dp(ic), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.rightMargin = dp(4);
        parent.addView(cell, lp);
        return cell;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView paragraph(String s, int color) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        t.setText(s);
        return t;
    }

    private LinearLayout.LayoutParams paraParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        return p;
    }

    private TextView chip(String label, int id, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setId(id);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), 0, dp(13), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams chipParams(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        p.leftMargin = left;
        return p;
    }

    private static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(s, t.getText())) t.setText(s);
    }

    private static boolean isInside(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return v == ancestor;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
