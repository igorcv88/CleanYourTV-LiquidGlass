package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;

/**
 * Scenic Glass's single Liquid Glass material, shared by the dock, transport buttons, header buttons, panels,
 * Control Center, menus and the All Apps veil.
 *
 * <p>Composition, back to front:
 * <ol>
 *   <li>The scene behind, frosted ({@link Blur#GLASS_SIDE}: about a 50 px blur), aligned to the
 *       screen and drawn almost opaque: the scene's colour and light come through, its detail does not.
 *       Over an aerial the sample is refreshed about 15 times a second ({@link AerialBackdrop}).</li>
 *   <li>One adaptive tint from {@link GlassTone}: a smoked veil over bright scenes, a faint lift over dark
 *       ones.</li>
 *   <li>The lens: along the rim, a band of the same scene magnified about the surface's centre and slightly
 *       brightened, so the scenery bends where it meets the edge, as thick glass does.</li>
 *   <li>A sheen across the upper part, then a specular edge lit from above.</li>
 * </ol>
 *
 * <p>Cost per redraw: five or six round-rect fills, all with shaders built in {@link #onBoundsChange} or once
 * per scene bitmap. No capture, blur, decode or allocation in {@link #draw}. The elevation shadow stays faint
 * through {@link #getOutline}.
 */
final class SceneGlassDrawable extends Drawable {
    private static boolean frosted;

    /**
     * One clear glass, body and lens band alike: the same light and colour, so the rim differs from the middle
     * only by its refraction (a frosted-looking middle inside a clear rim read as two materials). Focus lifts
     * the glass itself, brighter and richer, never a veil.
     */
    // Saturation well above 1, as Apple's materials: a wide blur averages unlike hues (blue sky, brown cliff)
    // into grey, which read as a grey veil in the middle of the glass; the boost gives their colour back.
    private static final ColorFilter BODY = filter(1.45f, 1.1f), BODY_FOCUSED = filter(1.65f, 1.26f);
    private static final ColorFilter LENS = BODY, LENS_FOCUSED = BODY_FOCUSED;

    private static ColorFilter filter(float saturation, float brightness) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturation);
        ColorMatrix lift = new ColorMatrix();
        lift.setScale(brightness, brightness, brightness, 1f);
        m.postConcat(lift);
        return new ColorMatrixColorFilter(m);
    }

    private final View host;
    private final float radius;
    private final GlassTone.Kind kind;
    private final float density;
    private final Paint scene = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** A big panel's previous wide frost, under the latest one while it fades in. */
    private final Paint previous = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix previousMatrix = new Matrix();
    private Bitmap previousBitmap;
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Matrix lensMatrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private Bitmap shaderBitmap;
    private int alpha = 255;
    private boolean focused, selected;
    /** A row's focus surface: glass only while focused; a chosen row shows its check mark, not glass. */
    private boolean focusOnly;
    private float toneLum = Float.NaN;
    private boolean toneFocused, toneSampled;
    private int toneColor;

    /** Applies the material to a view: background, outline from the drawable, faint shadow. */
    static SceneGlassDrawable apply(View v, float radiusDp, GlassTone.Kind kind) {
        SceneGlassDrawable d = new SceneGlassDrawable(v, radiusDp, kind);
        v.setBackground(d);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return d;
    }

    /**
     * A list row's focus surface (Settings, screens, menus): clear lens glass while the row is focused, nothing
     * otherwise. A chosen row is marked by its check alone: glass on both made focus and choice look alike.
     */
    static SceneGlassDrawable focusSurface(View v, float radiusDp) {
        SceneGlassDrawable d = new SceneGlassDrawable(v, radiusDp, GlassTone.Kind.INSET);
        d.focusOnly = true;
        v.setBackground(d);
        return d;
    }

    /** The user's Frosted choice (Appearance): more material for very busy scenery. Set before building views. */
    static void setFrosted(boolean value) {
        frosted = value;
    }

    SceneGlassDrawable(View host, float radiusDp, GlassTone.Kind kind) {
        this.host = host;
        this.kind = kind;
        density = host.getResources().getDisplayMetrics().density;
        radius = radiusDp < 0 ? -1f : radiusDp * density;
        lens.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density * 1.2f));
        if (kind.sampleShare > 0f) Scene.register(host);
    }

    /**
     * Big panels (Settings, its screens, the rows inside them) draw the wide frost and glide between samples;
     * controls, the dock and the full-screen backdrop draw the clearer frost as it comes.
     */
    private boolean wide() {
        return kind == GlassTone.Kind.PANEL || kind == GlassTone.Kind.INSET;
    }

    GlassTone.Kind kind() {
        return kind;
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean next = false, chosen = false;
        for (int value : state) {
            if (value == android.R.attr.state_focused) next = true;
            else if (value == android.R.attr.state_selected) chosen = true;
        }
        if (focused == next && selected == chosen) return false;
        focused = next;
        selected = chosen;
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(@NonNull android.graphics.Rect b) {
        if (b.isEmpty()) return;
        // Lit from above: bright along the top, faint down the sides, catching light again at the bottom.
        edge.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom,
                new int[]{0xD9FFFFFF, 0x40FFFFFF, 0x1AFFFFFF, 0x59FFFFFF},
                new float[]{0f, 0.32f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        // A thin gloss under the top rim, not a white wash over the upper half (that read as frost, doubly so
        // on glass over glass such as the dock's All Apps card).
        float sheenEnd = b.top + Math.min(b.height() * 0.22f, 40f * density);
        sheen.setShader(new LinearGradient(b.left, b.top, b.left, sheenEnd,
                0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        float shortDp = Math.min(b.width(), b.height()) / density;
        lens.setStrokeWidth(GlassTone.lensBandDp(shortDp) * density);
    }

    private float cornerRadius() {
        return radius < 0 ? Math.min(rect.width(), rect.height()) / 2f
                : Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        rect.set(getBounds());
        if (rect.isEmpty() || (focusOnly && !focused)) return;
        float r = cornerRadius();
        float share = GlassTone.sampleShare(kind, frosted);
        boolean sampled = share > 0f && drawSample(c, r, share);
        int color = tone(sampled);
        tint.setColor(color);
        tint.setAlpha(Math.round((color >>> 24) * alpha / 255f));
        c.drawRoundRect(rect, r, r, tint);
        if (sampled && kind.lens) drawLens(c, r);
        if (kind == GlassTone.Kind.BACKDROP) return;
        sheen.setAlpha(Math.round(170 * alpha / 255f));
        c.drawRoundRect(rect, r, r, sheen);
        float inset = edge.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        edge.setAlpha(Math.round((focused ? 255 : kind == GlassTone.Kind.INSET ? 120 : 200) * alpha / 255f));
        c.drawRoundRect(rect, Math.max(0f, r - inset), Math.max(0f, r - inset), edge);
    }

    /** The scene-aligned frost. False when there is nothing sampled yet. */
    private boolean drawSample(Canvas c, float r, float share) {
        boolean wide = wide();
        Bitmap bmp = wide ? Scene.currentWide() : Scene.current();
        // Absolute screen alignment also works for PopupWindow panels, whose root view is not fullscreen.
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        if (bmp == null || bmp.isRecycled() || screenW <= 0 || screenH <= 0) return false;
        if (bmp != shaderBitmap) {
            scene.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            lens.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            shaderBitmap = bmp;
        }
        host.getLocationOnScreen(loc);
        place(bmp, matrix, screenW, screenH);
        scene.getShader().setLocalMatrix(matrix);
        scene.setColorFilter(focused ? BODY_FOCUSED : BODY);
        int body = Math.round(255 * share * alpha / 255f);
        long now = SystemClock.uptimeMillis();
        Bitmap before = wide ? Scene.previousWide(now) : null;
        if (before != null && !before.isRecycled()) {
            // Between two samples: the previous frost underneath, the latest fading in over it, so the panel
            // glides with the video instead of stepping; redrawn each frame until the latest is fully in.
            if (before != previousBitmap) {
                previous.setShader(new BitmapShader(before, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                previousBitmap = before;
            }
            place(before, previousMatrix, screenW, screenH);
            previous.getShader().setLocalMatrix(previousMatrix);
            previous.setColorFilter(scene.getColorFilter());
            previous.setAlpha(body);
            c.drawRoundRect(rect, r, r, previous);
            scene.setAlpha(Math.round(body * Scene.wideFade(now)));
            c.drawRoundRect(rect, r, r, scene);
            invalidateSelf();
        } else {
            scene.setAlpha(body);
            c.drawRoundRect(rect, r, r, scene);
        }
        return true;
    }

    /** The screen-aligned placement of a scene bitmap behind this host (loc already read). */
    private void place(Bitmap bmp, Matrix m, int screenW, int screenH) {
        float[] cover = SceneCrop.cover(bmp.getWidth(), bmp.getHeight(), screenW, screenH);
        m.setScale(cover[0], cover[0]);
        m.postTranslate(cover[1] - loc[0], cover[2] - loc[1]);
        // The host may be scaled (focus); undo it so the image stays still behind the glass.
        if (host.getScaleX() != 1f || host.getScaleY() != 1f) {
            m.postScale(1f / host.getScaleX(), 1f / host.getScaleY());
        }
    }

    /** The rim band: the same frost magnified about the centre, so the scenery bends at the edge. */
    private void drawLens(Canvas c, float r) {
        lensMatrix.set(matrix);
        lensMatrix.postScale(GlassTone.LENS_MAGNIFY, GlassTone.LENS_MAGNIFY, rect.centerX(), rect.centerY());
        lens.getShader().setLocalMatrix(lensMatrix);
        lens.setColorFilter(focused ? LENS_FOCUSED : LENS);
        lens.setAlpha(Math.round((focused ? 235 : 180) * alpha / 255f));
        float half = lens.getStrokeWidth() / 2f;
        rect.inset(half, half);
        float rr = Math.max(0f, r - half);
        c.drawRoundRect(rect, rr, rr, lens);
        rect.inset(-half, -half);
    }

    /** The adaptive tint, recomputed only when the scene's luminance, the focus or the sample's presence change. */
    private int tone(boolean sampled) {
        float lum = Scene.luminance();
        if (lum != toneLum || focused != toneFocused || sampled != toneSampled) {
            toneLum = lum;
            toneFocused = focused;
            toneSampled = sampled;
            toneColor = sampled ? GlassTone.tint(kind, lum, focused, frosted)
                    : GlassTone.fallbackTint(kind, lum, focused, frosted);
        }
        return toneColor;
    }

    @Override public void getOutline(@NonNull Outline outline) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        float r = radius < 0 ? Math.min(b.width(), b.height()) / 2f
                : Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        outline.setRoundRect(b, r);
        outline.setAlpha(kind.shadowAlpha * alpha / 255f);
    }

    @Override public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override public int getAlpha() {
        return alpha;
    }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
