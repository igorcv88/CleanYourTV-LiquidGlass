package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Labels and icons on a glass control follow the light behind that control, as on iOS: white over a dark
 * scene, dark over a bright one (a sky behind the Control Center's Wi-Fi and Sound capsules washed white
 * labels out, more so with the brighter focused glass). Each control measures its own patch of the scene
 * ({@link SceneGlassDrawable}), not the whole screen's mean, so one dark cliff elsewhere changes nothing.
 * Only light colours swap: a coloured icon (the blue Wi-Fi) and pictures (album art) stay as they are.
 */
final class GlassContent {
    private GlassContent() { }

    /** Hysteresis on the glass's own luminance, 0..1, so a control never flickers between the two. */
    static final float LIGHT_ON = 0.64f, LIGHT_OFF = 0.54f;
    /** The dark ink: near black with a cool cast, at the original colour's own opacity. */
    static final int DARK_RGB = 0x12161C;

    static boolean nextLight(boolean light, float luminance) {
        return light ? luminance > LIGHT_OFF : luminance > LIGHT_ON;
    }

    /** White-ish (a label or icon colour that a bright glass would wash out). */
    static boolean isLight(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 190f && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40;
    }

    static int darkened(int argb) {
        return (argb & 0xFF000000) | DARK_RGB;
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
