package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

/**
 * Glass Current: the progress line above the dock. A straight 4dp line, lit in the cover's colours over the
 * elapsed part, a round knob at the playhead, the elapsed time at its left end and the remaining time at its
 * right end.
 *
 * <p>While music plays, waves rise from the elapsed part of the line, after Samsung Music's player: filled,
 * overlapping translucent layers ({@link RibbonSwells}) washed in the backdrop's hues on Home and in the cover's
 * ({@link ArtPalette}) in Listen, each a range of swells that pulse at their own tempo and drift along the line.
 * They are procedural decoration, not audio analysis. The line itself is always a straight horizontal segment:
 * the waves sit on it and never move it, the time labels or the knob. Reduced motion turns them off.
 *
 * <p>Focusable only when the session supports seeking: then Left/Right seek 15 s per press and are consumed;
 * Up/Down pass through. Unfocused, it never touches D-pad keys.
 */
final class GlassCurrentView extends View {
    interface SeekListener {
        void onSeekBy(long deltaMs);
    }

    /**
     * The waves step at a steady 30 Hz on every other vsync: the full-screen glass costs this TV's GPU about
     * 19 ms a frame, so 60 Hz drifted between 40 and 60 and stuttered, and a fixed delay after each draw beat
     * against the display. Motion times come from the vsync, not from when the draw happened to run.
     */
    private static final long FRAME_MS = 33L;
    private long nextFrameAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            if (now + 4L >= nextFrameAt) {
                nextFrameAt = now - nextFrameAt > FRAME_MS ? now + FRAME_MS : nextFrameAt + FRAME_MS;
                invalidate();
            } else {
                postOnAnimation(this);
            }
        }
    };
    /** Points per layer outline: one every few dp, capped. */
    private static final int MAX_POINTS = 72;
    private static final long PALETTE_FADE_MS = 900L, LEVEL_FADE_MS = 700L;
    private static final int GRADIENT_STEPS = 6;

    private final float density;
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path seg = new Path();
    private final Path swellPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] pos = new float[2];
    private final RibbonSwells.Frame waves = new RibbonSwells.Frame();
    private final int[] samples = new int[64];
    private float fraction = -1f;
    /** While the track advances: its live position, read every frame so the knob glides instead of stepping. */
    private java.util.function.LongSupplier livePosition;
    private long liveDuration;
    private String elapsed = "", remaining = "";
    private boolean seekable;
    private SeekListener seekListener;
    private int pathW = -1;
    private float pathStart, pathEnd;
    private boolean playing;
    private boolean animateWave = true;

    /** Palette cross-fade: from the previous track's colours to the new ones. */
    private int[] fromPalette = ArtPalette.DEFAULT.clone(), toPalette = ArtPalette.DEFAULT.clone();
    private final int[] palette = ArtPalette.DEFAULT.clone();
    private long paletteAt = -PALETTE_FADE_MS;
    private int gradientStep = -1;
    /** The backdrop's hues for Home, cross-faded the same way; all white when the scene has no colour. */
    private static final int[] WHITE3 = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
    private int[] fromScene = WHITE3.clone(), toScene = WHITE3.clone();
    private final int[] scene = WHITE3.clone();
    private long sceneAt = -PALETTE_FADE_MS;
    /** What is drawn: the scene's hues on Home, the cover's in Listen, mixed by {@link #colour}. */
    private final int[] mix = WHITE3.clone();

    /** Overall swell presence 0..1, eased toward 1 while playing and 0 while paused. */
    private float level;
    /** 0: the waves in shades of white (Home), 1: in the cover's colours (Listen). Follows the Listen motion. */
    private float colour;
    /**
     * 1 while the album cover, blurred, is Home's backdrop (Appearance's cover backdrop): Home then shows the
     * cover's colours as Listen does. Eased over PALETTE_FADE_MS with the wash; 0 over wallpaper or aerial.
     */
    private float coverBackdrop;
    private boolean coverBackdropOn;
    private long coverBackdropAt = -PALETTE_FADE_MS;
    private long levelAt;

    GlassCurrentView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        float stroke = 4f * density;
        for (Paint p : new Paint[]{lit, glow, rest}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(stroke);
        }
        lit.setColor(0xF2FFFFFF);
        glow.setStrokeWidth(stroke * 2.4f);
        glow.setColor(0x30FFFFFF);
        rest.setColor(0x52FFFFFF);
        knob.setColor(0xFFFFFFFF);
        knobRing.setColor(0xFFFFFFFF);
        swellPaint.setStyle(Paint.Style.FILL);
        label.setColor(0xD9F5F6F8);
        label.setTextSize(13f * ctx.getResources().getDisplayMetrics().scaledDensity);
        Typeface inter = ctx.getResources().getFont(R.font.inter_medium);
        label.setTypeface(inter);
        label.setFontFeatureSettings("tnum");
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSeekListener(SeekListener l) {
        seekListener = l;
    }

    void setMotionEnabled(boolean enabled) {
        animateWave = enabled;
        invalidate();
    }

    void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        levelAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** A restrained, multi-hue palette from the album's pixels; cross-fades from the previous track's. */
    void setArtwork(Bitmap artwork) {
        int[] next = ArtPalette.DEFAULT;
        if (artwork != null && !artwork.isRecycled() && artwork.getConfig() != Bitmap.Config.HARDWARE) {
            try {
                int w = artwork.getWidth(), h = artwork.getHeight();
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                    samples[y * 8 + x] = artwork.getPixel((x * 2 + 1) * (w - 1) / 16, (y * 2 + 1) * (h - 1) / 16);
                }
                next = ArtPalette.from(samples);
            } catch (RuntimeException ignored) { /* artwork may be revoked between session updates */ }
        }
        if (java.util.Arrays.equals(next, toPalette)) return;
        fromPalette = palette.clone();
        toPalette = next.clone();
        paletteAt = SystemClock.uptimeMillis();
        if (!animateWave) paletteAt -= PALETTE_FADE_MS;   // reduced motion: switch at once
        gradientStep = -1;
        invalidate();
    }

    /** The backdrop's palette (null: a scene without colour, so white glass); cross-fades from the last. */
    void setScenePalette(int[] hues) {
        int[] next = hues == null ? WHITE3 : hues;
        if (java.util.Arrays.equals(next, toScene)) return;
        fromScene = scene.clone();
        toScene = next.clone();
        sceneAt = SystemClock.uptimeMillis();
        if (!animateWave) sceneAt -= PALETTE_FADE_MS;
        gradientStep = -1;
        invalidate();
    }

    /** The playing track's position source (null when paused or unknown); the labels still come per second. */
    void setLivePosition(java.util.function.LongSupplier position, long duration) {
        livePosition = duration > 0 ? position : null;
        liveDuration = duration;
    }

    /** fraction -1 = unknown duration (subdued line, no remaining time). */
    void setProgress(float fraction, String elapsed, String remaining) {
        boolean changed = Math.abs(fraction - this.fraction) > 0.0005f || !elapsed.equals(this.elapsed)
                || !remaining.equals(this.remaining);
        this.fraction = fraction;
        this.elapsed = elapsed;
        this.remaining = remaining;
        if (changed) invalidate();
    }

    void setSeekable(boolean seekable) {
        if (this.seekable == seekable) return;
        this.seekable = seekable;
        setFocusable(seekable);
        if (!seekable && isFocused()) clearFocus();
    }

    boolean isSeekable() {
        return seekable;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        animate().cancel();
        // Never scale the entire view: doing so stretched the timestamp glyphs and bent the track.
        setScaleY(1f);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (seekable && isFocused() && seekListener != null
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            seekListener.onSeekBy(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? MediaTime.SEEK_STEP_MS : -MediaTime.SEEK_STEP_MS);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The seek path is always horizontal. Only the separate swells are animated. */
    private void buildPath(int w, int h) {
        float gap = 14f * density;
        float labelW = Math.max(label.measureText("0:00:00"), 40f * density);
        pathStart = labelW + gap;
        pathEnd = w - labelW - gap;
        float cy = h / 2f;
        path.reset();
        path.moveTo(pathStart, cy);
        path.lineTo(pathEnd, cy);
        measure.setPath(path, false);
        pathW = w;
        gradientStep = -1;
    }

    /** Advances the palette fade; rebuilds the line's shader only at a few steps of it, not every frame. */
    private boolean updatePalette(long now) {
        float k = Math.min(1f, (now - coverBackdropAt) / (float) PALETTE_FADE_MS);
        coverBackdrop = coverBackdropOn ? k : 1f - k;
        float cover = Math.max(colour, coverBackdrop);
        float t = Math.min(1f, (now - paletteAt) / (float) PALETTE_FADE_MS);
        float s = Math.min(1f, (now - sceneAt) / (float) PALETTE_FADE_MS);
        for (int i = 0; i < 3; i++) {
            palette[i] = ArtPalette.lerp(fromPalette[i], toPalette[i], t);
            scene[i] = ArtPalette.lerp(fromScene[i], toScene[i], s);
            mix[i] = ArtPalette.lerp(scene[i], palette[i], cover);
        }
        // The shader is rebuilt only at a few steps of each fade and of the Listen motion, not every frame.
        int step = Math.round(t * GRADIENT_STEPS)
                + (GRADIENT_STEPS + 1) * (Math.round(s * GRADIENT_STEPS)
                + (GRADIENT_STEPS + 1) * Math.round(cover * GRADIENT_STEPS));
        if (step != gradientStep) {
            gradientStep = step;
            // The played line in the scene's (Listen: the cover's) colours, light enough to read as lit.
            lit.setShader(new LinearGradient(pathStart, 0f, pathEnd, 0f,
                    new int[]{ArtPalette.toward(mix[1], 0xFFFFFFFF, 0.35f),
                            ArtPalette.toward(mix[0], 0xFFFFFFFF, 0.2f)},
                    null, Shader.TileMode.CLAMP));
            glow.setColor((mix[0] & 0x00FFFFFF) | 0x38000000);
            knobRing.setColor(ArtPalette.toward(mix[0], 0xFFFFFFFF, 0.15f));
            knobGlow.setColor((mix[0] & 0x00FFFFFF) | 0x47000000);
        }
        return t < 1f || s < 1f || k < 1f;
    }

    private boolean updateLevel(long now) {
        float target = playing && animateWave ? 1f : 0f;
        float stepAmount = Math.max(0f, (now - levelAt) / (float) LEVEL_FADE_MS);
        levelAt = now;
        if (level < target) level = Math.min(target, level + stepAmount);
        else if (level > target) level = Math.max(target, level - stepAmount);
        return level != target;
    }

    /** The waves, on the line, inside the elapsed span [from, to]: back layer first. */
    private void drawSwells(Canvas c, float from, float to, float cy, long now) {
        float span = to - from;
        float room = RibbonSwells.room(span / density);
        if (level <= 0f || room <= 0f) return;
        RibbonSwells.frame(now, waves);
        int points = Math.max(12, Math.min(MAX_POINTS, Math.round(span / (5f * density))));
        for (int layer = 0; layer < RibbonSwells.LAYERS; layer++) {
            swellPath.rewind();
            swellPath.moveTo(from, cy);
            // A smooth outline: each sample is a control point, the curve passes through the midpoints.
            float px = from, py = cy;
            for (int i = 1; i <= points; i++) {
                float u = i / (float) points;
                float x = from + span * u;
                float y = i == points ? cy : cy - RibbonSwells.height(waves, layer, u, level, room) * density;
                swellPath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
                px = x;
                py = y;
            }
            swellPath.lineTo(to, cy);
            swellPath.close();
            swellPaint.setColor(layerColour(layer));
            c.drawPath(swellPath, swellPaint);
        }
    }

    /**
     * How much of the cover's colour the waves carry: none on Home, where they sit quietly over the scenery as
     * shades of white glass, all of it in Listen. Set every frame of the Listen transition.
     */
    /** The blurred album cover became (or stopped being) Home's backdrop: see {@link #coverBackdrop}. */
    void setCoverBackdrop(boolean on) {
        if (on == coverBackdropOn) return;
        coverBackdropOn = on;
        long now = SystemClock.uptimeMillis();
        // Reverse from wherever a running fade is, so a quick toggle never jumps.
        float done = on ? coverBackdrop : 1f - coverBackdrop;
        coverBackdropAt = animateWave ? now - (long) (done * PALETTE_FADE_MS) : now - PALETTE_FADE_MS;
        gradientStep = -1;
        invalidate();
    }

    void setColour(float t) {
        float next = Math.max(0f, Math.min(1f, t));
        if (next == colour) return;
        colour = next;
        invalidate();
    }

    /** White glass on Home: four translucent whites, faint behind, brighter in front where they overlap. */
    private static final int[] WHITE_LAYERS = {0x2EFFFFFF, 0x47FFFFFF, 0x3DFFFFFF, 0x57FFFFFF};

    private int layerColour(int layer) {
        int home = sceneLayer(layer);
        float cover = Math.max(colour, coverBackdrop);
        if (cover <= 0f) return home;
        int tinted = tintedLayer(layer);
        return cover >= 1f ? tinted : ArtPalette.lerp(home, tinted, cover);
    }

    /** Home: the white glass layers, washed in the backdrop's hues (white again for a scene without colour). */
    private int sceneLayer(int layer) {
        int white = WHITE_LAYERS[layer];
        switch (layer) {
            case 0: return white;
            case 1: return (scene[0] & 0x00FFFFFF) | (white & 0xFF000000);
            case 2: return (ArtPalette.toward(scene[1], 0xFFFFFFFF, 0.15f) & 0x00FFFFFF) | (white & 0xFF000000);
            default: return (ArtPalette.toward(scene[2], 0xFFFFFFFF, 0.3f) & 0x00FFFFFF) | (white & 0xFF000000);
        }
    }

    /** Back to front: a pale haze, the cover's main colour, its second, then a light tint of the third. */
    private int tintedLayer(int layer) {
        switch (layer) {
            case 0: return 0x52FFFFFF;
            case 1: return (palette[0] & 0x00FFFFFF) | 0x9E000000;
            case 2: return (ArtPalette.toward(palette[1], 0xFFFFFFFF, 0.15f) & 0x00FFFFFF) | 0x85000000;
            default: return (ArtPalette.toward(palette[2], 0xFFFFFFFF, 0.4f) & 0x00FFFFFF) | 0x8C000000;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (pathW != w) buildPath(w, h);
        long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        boolean fading = updatePalette(now);
        boolean easing = updateLevel(now);
        boolean focused = isFocused();
        float cy = h / 2f;
        label.setAlpha(focused ? 255 : 210);
        float base = cy - (label.descent() + label.ascent()) / 2f;
        c.drawText(elapsed, pathStart - 14f * density - label.measureText(elapsed), base, label);
        if (!remaining.isEmpty()) c.drawText(remaining, pathEnd + 14f * density, base, label);
        float len = measure.getLength();
        java.util.function.LongSupplier live = livePosition;
        if (live != null && fraction >= 0f) {
            fraction = Math.max(0f, Math.min(1f, live.getAsLong() / (float) liveDuration));
        }
        if (fraction < 0f) {
            drawSwells(c, pathStart, pathEnd, cy, now);
            c.drawPath(path, rest);
        } else {
            float at = len * fraction;
            drawSwells(c, pathStart, pathStart + at, cy, now);
            seg.reset();
            measure.getSegment(at, len, seg, true);
            c.drawPath(seg, rest);
            seg.reset();
            measure.getSegment(0, at, seg, true);
            c.drawPath(seg, glow);
            c.drawPath(seg, lit);
            // The knob: always there, as Samsung's; larger with a halo while focused for seeking.
            measure.getPosTan(at, pos, null);
            float r = (focused ? 9f : 7f) * density;
            if (focused || level > 0f) c.drawCircle(pos[0], pos[1], r + 6f * density, knobGlow);
            c.drawCircle(pos[0], pos[1], r, knobRing);
            c.drawCircle(pos[0], pos[1], r - 2.5f * density, knob);
        }
        // Frames only while something moves and Home is visible; paused and reduced motion stay still.
        boolean moving = level > 0f || easing || fading;
        if (moving && isShown()) {
            removeCallbacks(tick);
            postOnAnimation(tick);
        }
    }
}
