package io.github.sinisk3r.visualizer;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;

/**
 * Saves a small picture of a preset for Find's preview: once a preset has played 10 s, the finished frame is
 * scaled down on the GPU (blit to a 320x180 framebuffer), read back (230 KB) and written as a JPEG in app storage by
 * a background thread. Once per preset: a preset that already has a picture is never read again. GL thread only;
 * called from the activity's frame counter after VizRenderer has drawn, before the frame is swapped.
 */
final class ThumbGrabber {
    static final int W = 320, H = 180;
    static final long AT_MS = 10_000;
    private static final String TAG = "Visualizer";

    private final File dir;
    private final Executor saver;
    private String preset;
    private long since;
    private boolean done;
    private boolean broken;
    private int surfaceW, surfaceH;
    private int fbo, tex;

    ThumbGrabber(File dir, Executor saver) {
        this.dir = dir;
        this.saver = saver;
    }

    /** Where a preset's picture is kept (it may not exist yet). */
    static File file(File dir, String presetName) {
        return new File(dir, presetName + ".jpg");
    }

    /** A new GL context: the old framebuffer is gone. */
    void contextLost() {
        fbo = 0;
        tex = 0;
    }

    void surface(int w, int h) {
        surfaceW = w;
        surfaceH = h;
    }

    /** After every frame. Cheap until a preset reaches 10 s without a picture. */
    void frame(File current, long nowMs) {
        if (current == null || broken) return;
        if (!current.getName().equals(preset)) {
            preset = current.getName();
            since = nowMs;
            done = file(dir, preset).exists();
            return;
        }
        if (done || nowMs - since < AT_MS || surfaceW == 0) return;
        done = true;
        try {
            ByteBuffer px = read();
            if (px != null) {
                String name = preset;
                saver.execute(() -> save(px, file(dir, name)));
            }
        } catch (RuntimeException e) {
            broken = true;  // never try again this run: a GL error here must not cost every preset a frame
            Log.w(TAG, "thumbnail read failed", e);
        }
    }

    private ByteBuffer read() {
        int[] prev = new int[2];
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, prev, 0);
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prev, 1);
        boolean scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST);
        if (fbo == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            tex = ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, W, H);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
            GLES30.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D,
                    tex, 0);
        }
        if (scissor) GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo);
        GLES30.glBlitFramebuffer(0, 0, surfaceW, surfaceH, 0, 0, W, H, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
        int err = GLES30.glGetError();
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, prev[0]);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prev[1]);
        if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        if (err != GLES30.GL_NO_ERROR) throw new IllegalStateException("GL error " + err);
        return px;
    }

    /** RGBA rows bottom-up (GL) to a JPEG, top-down. Background thread. */
    private static void save(ByteBuffer px, File out) {
        int[] argb = new int[W * H];
        px.rewind();
        for (int y = 0; y < H; y++) {
            int row = (H - 1 - y) * W;
            for (int x = 0; x < W; x++) {
                int r = px.get() & 0xFF, g = px.get() & 0xFF, b = px.get() & 0xFF;
                px.get();
                argb[row + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(argb, W, H, Bitmap.Config.ARGB_8888);
        File tmp = new File(out.getPath() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, os);
        } catch (java.io.IOException e) {
            Log.w(TAG, "thumbnail save failed: " + out.getName(), e);
            return;
        } finally {
            bmp.recycle();
        }
        if (!tmp.renameTo(out)) Log.w(TAG, "thumbnail rename failed: " + out.getName());
    }
}
