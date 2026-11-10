package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Labels and icons on a glass control follow the light behind that control, as on iOS: white over a dark
 * scene, dark over a bright one (a sky behind the Control Center's Wi-Fi and Sound capsules washed white
 * labels out, more so with the brighter focused glass). Each control measures its own patch of the scene
 * ({@link SceneGlassDrawable}), not the whole screen's mean, so one dark cliff elsewhere changes nothing.
 * Only light colours swap: a coloured icon (the blue Wi-Fi) and pictures (album art) stay as they are.
 * One decision per row ({@link Group}): Wi-Fi and Sound, the header's two buttons, a row of transport
 * buttons, the four shortcuts. Buttons side by side in two inks read as a fault.
 */
final class GlassContent {
    private GlassContent() { }

    /** Hysteresis on the glass's own luminance, 0..1, so a control never flickers between the two. */
    static final float LIGHT_ON = 0.64f, LIGHT_OFF = 0.54f;
    /**
     * The dark ink: a deep slate blue, not black, at 88% of the original colour's opacity, so it sits in
     * the glass's own colour rather than on top of it as a flat black.
     */
    static final int DARK_RGB = 0x1C2A3C;
    static final float DARK_OPACITY = 0.88f;

    static boolean nextLight(boolean light, float luminance) {
        return light ? luminance > LIGHT_OFF : luminance > LIGHT_ON;
    }

    /** White-ish (a label or icon colour that a bright glass would wash out). */
    static boolean isLight(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 190f && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40;
    }

    static int darkened(int argb) {
        return Math.round((argb >>> 24) * DARK_OPACITY) << 24 | DARK_RGB;
    }

    /** One row's ink: the mean light behind its controls decides for all of them, with hysteresis. */
    static final class Group {
        private final List<SceneGlassDrawable> members = new ArrayList<>();
        private boolean light;

        void report(SceneGlassDrawable d, float luminance) {
            d.inkLuminance = luminance;
            if (!members.contains(d)) {
                members.add(d);
                if (light) post(d, true);
            }
            float sum = 0f;
            int n = 0;
            for (SceneGlassDrawable m : members) {
                if (m.inkLuminance >= 0f) {
                    sum += m.inkLuminance;
                    n++;
                }
            }
            if (n == 0) return;
            boolean next = nextLight(light, sum / n);
            if (next == light) return;
            light = next;
            for (SceneGlassDrawable m : members) post(m, next);
        }

        private static void post(SceneGlassDrawable d, boolean light) {
            View host = d.host();
            host.post(() -> apply(host, light));   // never change views from inside a draw
        }
    }

    /**
     * The row a control belongs to: its parent, or, when the parent holds no other glass control (a
     * shortcut's column of button and label), the parent's parent. A control inside another (the media
     * platter's transport buttons) shares the outer one's decision: one platter, one ink.
     */
    static Group groupFor(View host) {
        for (android.view.ViewParent p = host.getParent(); p instanceof View; p = p.getParent()) {
            if (((View) p).getBackground() instanceof SceneGlassDrawable
                    && ((SceneGlassDrawable) ((View) p).getBackground()).kind() == GlassTone.Kind.CONTROL) {
                return groupFor((View) p);
            }
        }
        if (!(host.getParent() instanceof ViewGroup)) return new Group();
        ViewGroup row = (ViewGroup) host.getParent();
        if (glassControls(row) < 2 && row.getParent() instanceof ViewGroup) row = (ViewGroup) row.getParent();
        Object g = row.getTag(R.id.glass_ink_group);
        if (g instanceof Group) return (Group) g;
        Group made = new Group();
        row.setTag(R.id.glass_ink_group, made);
        return made;
    }

    private static int glassControls(ViewGroup g) {
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i).getBackground() instanceof SceneGlassDrawable) n++;
        }
        return n;
    }

    /** Applies the ink for a light (true) or dark glass to v and everything inside it. */
    static void apply(View v, boolean light) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Object kept = t.getTag(R.id.glass_ink_orig);
            ColorStateList orig = kept instanceof ColorStateList ? (ColorStateList) kept : t.getTextColors();
            if (kept == null) t.setTag(R.id.glass_ink_orig, orig);
            if (orig != null && isLight(orig.getDefaultColor())) {
                t.setTextColor(light ? ColorStateList.valueOf(darkened(orig.getDefaultColor())) : orig);
            }
        } else if (v instanceof ImageView && !(((ImageView) v).getDrawable() instanceof BitmapDrawable)) {
            ImageView i = (ImageView) v;
            Object kept = i.getTag(R.id.glass_ink_orig);
            ColorStateList orig = kept instanceof ColorStateList ? (ColorStateList) kept : i.getImageTintList();
            if (kept == null && orig != null) i.setTag(R.id.glass_ink_orig, orig);
            if (orig != null && isLight(orig.getDefaultColor())) {
                i.setImageTintList(light ? ColorStateList.valueOf(darkened(orig.getDefaultColor())) : orig);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) apply(g.getChildAt(k), light);
        }
    }
}
