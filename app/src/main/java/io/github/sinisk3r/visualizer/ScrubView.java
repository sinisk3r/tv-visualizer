package io.github.sinisk3r.visualizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

/**
 * The music card's scrubber (mockup A2, screen 1s-E), drawn in one view so a new position only invalidates it:
 * the track with its played part, and under its two ends a play/pause mark with the elapsed time (left) and the
 * song's length (right), as in Spotify's player.
 *
 * Unfocused: a thin track, played part white 70 %, no thumb. Focused: a thicker track, played part full white,
 * a round white thumb at the playhead with a soft halo. While Left/Right seek, a small pill above the thumb shows
 * where the song will land (drawn above the view's top: its parents do not clip children). Paused: the mark shows
 * pause, the time reads "Paused · 2:21", and everything dims a little.
 */
final class ScrubView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect bounds = new Rect();
    private final Drawable playMark, pauseMark;
    private final float d;  // px per dp

    /** 0..1000; -1 while the length is unknown (no fill, no thumb). */
    private int progress = -1;
    private String elapsed = "", total = "", pill;
    private boolean paused;

    ScrubView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, c.getResources().getDisplayMetrics());
        text.setTextSize(12 * sp);
        text.setFontFeatureSettings("'tnum'");
        pillText.setTextSize(11 * sp);
        pillText.setTypeface(Typeface.DEFAULT_BOLD);
        pillText.setFontFeatureSettings("'tnum'");
        pillText.setColor(0xFF111113);
        playMark = c.getDrawable(R.drawable.ic_play).mutate();
        pauseMark = c.getDrawable(R.drawable.ic_pause).mutate();
        setFocusable(true);
        setClickable(true);
        setWillNotDraw(false);
    }

    /** The values to show; redraws only when one changed. */
    void set(int progress, String elapsed, String total, boolean paused) {
        if (progress == this.progress && elapsed.equals(this.elapsed) && total.equals(this.total)
                && paused == this.paused) {
            return;
        }
        this.progress = progress;
        this.elapsed = elapsed;
        this.total = total;
        this.paused = paused;
        invalidate();
    }

    /** The pill over the thumb while seeking ("2:51"), or null. */
    void setPill(String s) {
        if (s == null ? pill == null : s.equals(pill)) return;
        pill = s;
        invalidate();
    }

    String elapsedText() {
        return elapsed;
    }

    String totalText() {
        return total;
    }

    String pillText() {
        return pill;
    }

    int progress() {
        return progress;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean focus = isFocused();
        int dim = paused ? 191 : 255;  // paused: 75 %
        float thumbR = 7 * d, halo = 6 * d;
        float left = thumbR, right = getWidth() - thumbR;
        float cy = thumbR + halo;
        float h = (focus ? 5 : 3) * d;

        // The track: rest, then the played part.
        track.setColor(0xFFFFFFFF);
        track.setAlpha(scale(0x33, dim));
        r.set(left, cy - h / 2, right, cy + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, track);
        float x = progress < 0 ? left : NowPlaying.thumbX(progress, left, right);
        if (progress > 0) {
            track.setAlpha(scale(focus ? 0xFF : 0xB3, dim));
            r.set(left, cy - h / 2, Math.max(x, left + h), cy + h / 2);
            c.drawRoundRect(r, h / 2, h / 2, track);
        }
        if (focus && progress >= 0) {
            track.setAlpha(scale(0x2E, dim));  // the halo, white 18 %
            c.drawCircle(x, cy, thumbR + halo, track);
            track.setAlpha(scale(0xFF, dim));
            c.drawCircle(x, cy, thumbR, track);
        }

        // Under the ends: mark and elapsed time on the left, the length on the right.
        float base = cy + thumbR + 4 * d - text.ascent();
        int textAlpha = scale(focus ? 0xC8 : 0x99, dim);
        float mark = 9 * d;
        Drawable m = paused ? pauseMark : playMark;
        m.setTint(0xFFFFFFFF);
        m.setAlpha(textAlpha);
        float capTop = base - text.getTextSize() * 0.72f;
        int mt = Math.round(capTop + (base - capTop - mark) / 2);
        m.setBounds(Math.round(left), mt, Math.round(left + mark), Math.round(mt + mark));
        m.draw(c);
        text.setColor(0xFFFFFFFF);
        text.setAlpha(textAlpha);
        c.drawText(elapsed, left + mark + 4 * d, base, text);
        c.drawText(total, right - text.measureText(total), base, text);

        if (pill != null && progress >= 0) {
            float pw = pillText.measureText(pill) + 12 * d, ph = 18 * d;
            float pl = NowPlaying.pillLeft(x, pw, 0, getWidth());
            float pb = cy - thumbR - halo - 2 * d;
            r.set(pl, pb - ph, pl + pw, pb);
            track.setAlpha(0xFF);
            c.drawRoundRect(r, ph / 2, ph / 2, track);
            pillText.getTextBounds("0", 0, 1, bounds);
            c.drawText(pill, pl + 6 * d, pb - (ph - bounds.height()) / 2, pillText);
        }
    }

    /** The height this view wants: halo, track, gap, one line of small text. */
    int wantedHeight() {
        return Math.round(13 * d + 7 * d + 4 * d + text.descent() - text.ascent());
    }

    private static int scale(int a, int dim) {
        return a * dim / 255;
    }
}
