package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * A cached, once-per-scene blur: the image is drawn down to a few dozen pixels, box-blurred three times
 * (close to a gaussian) on the CPU, and the small result is drawn back up with bilinear filtering, which
 * smooths it further for free. Runs off the UI thread when a scene changes, never per frame. The pixel pass
 * works on an int[] so it is tested on the JVM. Approach after GlassLauncher (MIT, tlfares/GlassLauncher,
 * gfx/Blur.kt): blur a small cached copy once and sample it, never the live screen.
 */
final class Blur {
    private Blur() { }

    /**
     * Scenic Glass's frost: 320 px across a 1920 px screen with three radius-10 box passes, a gaussian of
     * about 63 screen pixels: colour and light come through the glass, detail does not, and the 6x stretch
     * back to the screen never shows a pixel. Wide frost greys unlike hues together (sky and cliff); the
     * glass's saturation boost (SceneGlassDrawable.BODY) gives them back. Big panels use {@link #WIDE_RADIUS}. A box pass costs the same at any
     * radius. The old 96 px / radius 4 sample (~90 px) was a featureless smear that read as milk.
     */
    static final int GLASS_SIDE = 320, GLASS_RADIUS = 10;

    /**
     * The aerial's moving frost, sampled many times a second: 240 px with radius 8, about 68 screen pixels,
     * matching the still frost: at ~51 px a river's bank still showed sharp in a panel's middle. It is copied from the video at twice this size and halved with filtering:
     * a copy straight at this size point-samples the video, which showed as sharp, pixelated detail.
     */
    static final int MOTION_SIDE = 240, MOTION_RADIUS = 8;

    /**
     * The aerial's frost while music plays over it: a smaller sample (about 2.3x cheaper to blur, and a smaller
     * copy), blurred wider (~78 px) so its lower resolution never shows. Same rate: only quality is traded.
     */
    static final int MUSIC_SIDE = 160, MUSIC_RADIUS = 6;

    /**
     * Big panels (Settings and its screens) take the frost once more, halved and blurred again with this
     * radius: about 110 screen pixels in all, so a fast subject behind a wide panel reads as moving colour,
     * not a shape trailing the video. Small controls keep the clearer frost above.
     */
    static final int WIDE_RADIUS = 5;

    /** The scene sample every glass surface draws: see {@link #GLASS_SIDE}. */
    static Bitmap glass(Bitmap src) {
        return blurred(src, GLASS_SIDE, GLASS_RADIUS);
    }

    /**
     * Gaussian sigma, in screen pixels, of {@code passes} box passes of {@code radius} on a sample {@code side}
     * pixels wide, stretched over a screen {@code screenW} wide. For tests and documentation of the frost.
     */
    static float screenSigma(int side, int radius, int passes, int screenW) {
        float d = 2f * radius + 1f;
        return (float) Math.sqrt(passes * (d * d - 1f) / 12f) * screenW / (float) side;
    }

    /** Downsamples src to fit maxSide (aspect kept) and blurs it; a new bitmap, src untouched. */
    static Bitmap blurred(Bitmap src, int maxSide, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, maxSide / (float) Math.max(w, h));
        int sw = Math.max(2, Math.round(w * s)), sh = Math.max(2, Math.round(h * s));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(src, new Rect(0, 0, w, h), new Rect(0, 0, sw, sh), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        boxBlur(px, sw, sh, radius);
        small.setPixels(px, 0, sw, 0, 0, sw, sh);
        return small;
    }

    /** Three horizontal + vertical box passes of this radius, in place, opaque output. */
    static void boxBlur(int[] px, int w, int h, int radius) {
        boxBlur(px, new int[px.length], w, h, radius);
    }

    /** As {@link #boxBlur(int[], int, int, int)} with a caller's scratch buffer of the same length. */
    static void boxBlur(int[] px, int[] tmp, int w, int h, int radius) {
        if (radius < 1 || w < 1 || h < 1) return;
        for (int pass = 0; pass < 3; pass++) {
            boxLine(px, tmp, w, h, radius, true);
            boxLine(tmp, px, w, h, radius, false);
        }
    }

    private static void boxLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[index(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int sub = in[index(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    /** {@link ArtPalette} of a scene, or null when it has no real hue: the line then stays white glass. */
    static int[] scenePalette(int[] px) {
        int[] p = ArtPalette.from(px);
        return java.util.Arrays.equals(p, ArtPalette.DEFAULT) ? null : p;
    }

    /** Each output pixel the mean of a 2x2 block of src (opaque ARGB). */
    static void halve(int[] src, int sw, int sh, int[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            int y0 = Math.min(sh - 1, y * 2) * sw, y1 = Math.min(sh - 1, y * 2 + 1) * sw;
            for (int x = 0; x < dw; x++) {
                int x0 = Math.min(sw - 1, x * 2), x1 = Math.min(sw - 1, x * 2 + 1);
                int a = src[y0 + x0], b = src[y0 + x1], c = src[y1 + x0], d = src[y1 + x1];
                int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF) + 2) >> 2;
                int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF) + 2) >> 2;
                int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
                dst[y * dw + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }

    /** The wide frost of a still scene (wallpaper): once per scene, never per frame. */
    static Bitmap wide(Bitmap frost) {
        int w = frost.getWidth(), h = frost.getHeight();
        return blurred(frost, Math.max(2, Math.max(w, h) / 2), WIDE_RADIUS);
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
    /**
     * Blurs a stream of frames (the aerial's glass samples) without allocating per frame: two output bitmaps
     * used in turn, so the one on screen is never written while the next is made. One worker thread only.
     */
    static final class Stream {
        private final Bitmap[] out = new Bitmap[2];
        private final Canvas[] canvas = new Canvas[2];
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect from = new Rect(), to = new Rect();
        private int[] px, tmp;
        private int next;
        private float luminance = 0.5f;
        /** The scene's hues, re-read about once a second (every PALETTE_EVERY frames), from a sparse sample. */
        private static final int PALETTE_EVERY = 16, PALETTE_SAMPLES = 1024;
        private int frames;
        private int[] sparse;
        private volatile int[] palette;
        /** The wide frost: three bitmaps in turn, as a panel cross-fades from the previous one to the latest. */
        private final Bitmap[] wide = new Bitmap[3];
        private int[] widePx, wideTmp;
        private int wideNext;
        private Bitmap wideLast;

        /** The blurred frame (one of two bitmaps, alternating); src untouched. */
        Bitmap blur(Bitmap src, int maxSide, int radius) {
            int w = src.getWidth(), h = src.getHeight();
            float s = Math.min(1f, maxSide / (float) Math.max(w, h));
            int sw = Math.max(2, Math.round(w * s)), sh = Math.max(2, Math.round(h * s));
            Bitmap dst = out[next];
            if (dst == null || dst.getWidth() != sw || dst.getHeight() != sh) {
                dst = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                out[next] = dst;
                canvas[next] = new Canvas(dst);
                px = new int[sw * sh];
                tmp = new int[sw * sh];
            }
            if (px.length != sw * sh) {
                px = new int[sw * sh];
                tmp = new int[sw * sh];
            }
            if (sw == w && sh == h) {
                // Already the frost's size (the aerial copies at it): no scaling pass.
                src.getPixels(px, 0, sw, 0, 0, sw, sh);
            } else {
                from.set(0, 0, w, h);
                to.set(0, 0, sw, sh);
                canvas[next].drawBitmap(src, from, to, paint);
                dst.getPixels(px, 0, sw, 0, 0, sw, sh);
            }
            boxBlur(px, tmp, sw, sh, radius);
            dst.setPixels(px, 0, sw, 0, 0, sw, sh);
            luminance = GlassTone.meanLuminance(px);
            if (frames++ % PALETTE_EVERY == 0) palette = scenePalette(px);
            makeWide(px, sw, sh);
            next = 1 - next;
            return dst;
        }

        private void makeWide(int[] px, int sw, int sh) {
            int ww = Math.max(2, sw / 2), wh = Math.max(2, sh / 2);
            Bitmap out = wide[wideNext];
            if (out == null || out.getWidth() != ww || out.getHeight() != wh) {
                out = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888);
                wide[wideNext] = out;
            }
            if (widePx == null || widePx.length != ww * wh) {
                widePx = new int[ww * wh];
                wideTmp = new int[ww * wh];
            }
            halve(px, sw, sh, widePx, ww, wh);
            boxBlur(widePx, wideTmp, ww, wh, WIDE_RADIUS);
            out.setPixels(widePx, 0, ww, 0, 0, ww, wh);
            wideLast = out;
            wideNext = (wideNext + 1) % wide.length;
        }

        /** The last wide frost made: see {@link #WIDE_RADIUS}. */
        Bitmap wide() {
            return wideLast;
        }

        private int[] scenePalette(int[] all) {
            int n = Math.min(PALETTE_SAMPLES, all.length);
            if (sparse == null || sparse.length != n) sparse = new int[n];
            float stride = all.length / (float) n;
            for (int i = 0; i < n; i++) sparse[i] = all[(int) (i * stride)];
            return Blur.scenePalette(sparse);
        }

        /** The last frames' three hues for Home's progress line, or null for a scene without colour. */
        int[] palette() {
            return palette;
        }

        /** Mean luminance of the last frame blurred, for the glass tint. */
        float luminance() {
            return luminance;
        }
    }
}
