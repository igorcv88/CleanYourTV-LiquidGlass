package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Control Center: separately frosted controls, grouped at the top right without an opaque outer sheet.
 *
 * <pre>
 *  ┌──────────────────────────────────────────┐
 *  │ (wifi) Wi-Fi        │ (spk) Sound        │   status capsules: shortcuts, never fake toggles
 *  │        Connected    │       TV settings  │
 *  ├──────────────────────────────────────────┤
 *  │ [art] Title                 (⏮)(⏯)(⏭) │   media platter, only real session actions
 *  │       Artist                             │
 *  ├──────────────────────────────────────────┤
 *  │  (in)     (scn)     (wx)     (zz)        │   round shortcuts with captions
 *  │ Inputs   Scenery   Weather   Sleep       │
 *  ├──────────────────────────────────────────┤
 *  │ Thu 5 Nov · 24° São Paulo     (⚙ TV)     │   quiet info line + TV settings
 *  └──────────────────────────────────────────┘
 * </pre>
 *
 * Every control runs an existing, real action. Wi-Fi and sound are honest shortcuts to the TV's settings,
 * because an app cannot toggle Wi-Fi or switch audio outputs without privileged access. The platters inside
 * use {@link GlassTone.Kind#INSET}, so the sheet is one layer of sampled glass, not glass stacked on glass.
 */
final class ControlCenter {
    interface Actions {
        void network();
        void sound();
        boolean hasInputs();
        void inputs();
        void scenery();
        void weather();
        void sleep();
        void tvSettings();
        void listen();
        void musicAccess();
        void previous();
        void togglePlay();
        void next();
    }

    static final int WIDTH_DP = 360;
    /** Focus lift of a control: small, so it stays inside the card's padding. */
    /** Top of the sheet, in dp: clear of the header's clock and buttons. */
    static final int TOP_DP = 100;
    static final float FOCUS_SCALE = 1.04f;
    private static final Interpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.3f, 1f);
    /**
     * The opening played backwards in time, for closing. Closing with EASE itself left the sheet's last tenth
     * of opacity lingering for a third of the fade: the frost at 5-15% read as a grey ghost over the video.
     */
    private static final Interpolator EASE_BACK = t -> 1f - EASE.getInterpolation(1f - t);
    private static final long OPEN_MS = 320L;
    private static final int TEXT = 0xFFF5F6F8, DIM = 0xB3F5F6F8, FAINT = 0x8CF5F6F8;

    private final Context ctx;
    private final Actions actions;
    private final float density;
    private final boolean reducedMotion;
    private final Typeface regular, medium;

    private final FrameLayout overlay;
    private final LinearLayout card;
    private ImageView netIcon;
    private TextView netTitle, netDetail;
    private TextView soundDetail;
    private ImageView art;
    private TextView mediaTitle, mediaArtist;
    private ImageView prev, toggle, next;
    private View inputsColumn;
    private LinearLayout mediaPlatter;
    private boolean open;

    ControlCenter(Context ctx, FrameLayout parent, Typeface regular, Typeface medium, boolean reducedMotion,
                  Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
        this.regular = regular;
        this.medium = medium;
        this.reducedMotion = reducedMotion;
        density = ctx.getResources().getDisplayMetrics().density;

        overlay = new FrameLayout(ctx);
        overlay.setVisibility(View.GONE);
        // Above the dock's elevated cards; no scrim, Home stays as it was around the sheet.
        overlay.setElevation(dp(40));
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        parent.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        // All glass belongs to the individual controls. The outer layout is only a spacing/focus container.
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        // Below the header's buttons (they end near 88dp, and lift when focused), never over them.
        lp.setMargins(0, dp(TOP_DP), dp(42), 0);
        overlay.addView(card, lp);

        buildStatusRow();
        buildMedia();
        buildShortcuts();
        setTrack(null, null, null, false, false, false, false);
    }

    // ---- structure -----------------------------------------------------------------------------

    private void buildStatusRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        View network = capsule(row, R.drawable.ic_wifi, "Wi-Fi", "Network settings", false, actions::network);
        netIcon = network.findViewWithTag("icon");
        netTitle = network.findViewWithTag("title");
        netDetail = network.findViewWithTag("detail");
        View sound = capsule(row, R.drawable.ic_cc_sound, "Sound", "TV speakers", true, actions::sound);
        soundDetail = sound.findViewWithTag("detail");
    }

    /** Each capsule samples its own scenery; the icon is a simple glyph, not a second glass bubble. */
    private View capsule(LinearLayout row, int icon, String title, String detail, boolean second, Runnable action) {
        LinearLayout c = new LinearLayout(ctx);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(12), 0, dp(10), 0);
        SceneGlassDrawable.apply(c, 26f, GlassTone.Kind.CONTROL);
        focusable(c, action);
        ImageView disc = new ImageView(ctx);
        disc.setTag("icon");
        disc.setImageResource(icon);
        disc.setImageTintList(ctx.getColorStateList(R.color.scenic_icon_tint));
        disc.setScaleType(ImageView.ScaleType.CENTER);
        disc.setDuplicateParentStateEnabled(true);
        c.addView(disc, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(dp(10));
        c.addView(col, colLp);
        TextView t = text(14, medium, TEXT);
        t.setTag("title");
        t.setText(title);
        col.addView(t);
        TextView d = text(12, regular, DIM);
        d.setTag("detail");
        d.setText(detail);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(1);
        col.addView(d, dLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (second) lp.setMarginStart(dp(12));
        row.addView(c, lp);
        c.setContentDescription(title);
        return c;
    }

    private void buildMedia() {
        LinearLayout platter = new LinearLayout(ctx);
        mediaPlatter = platter;
        platter.setGravity(Gravity.CENTER_VERTICAL);
        platter.setPadding(dp(6), dp(6), dp(10), dp(6));
        platter.setClipChildren(false);
        platter.setClipToPadding(false);
        SceneGlassDrawable.apply(platter, 24f, GlassTone.Kind.CONTROL);
        focusable(platter, () -> {
            if (mediaPlaying != null) actions.listen(); else actions.musicAccess();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        lp.topMargin = dp(10);
        card.addView(platter, lp);

        // Artwork and titles together: one target that opens Listen (or Music access when nothing plays).
        LinearLayout info = new LinearLayout(ctx);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(6), dp(4), dp(8), dp(4));
        info.setFocusable(false);
        platter.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(rounded(12));
        art.setClipToOutline(true);
        info.addView(art, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(dp(10));
        info.addView(col, colLp);
        mediaTitle = text(14, medium, TEXT);
        col.addView(mediaTitle);
        mediaArtist = text(12, regular, DIM);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(2);
        col.addView(mediaArtist, aLp);

        prev = smallButton(platter, R.drawable.ic_skip_prev, "Previous", actions::previous);
        toggle = smallButton(platter, R.drawable.ic_pause, "Play or pause", actions::togglePlay);
        next = smallButton(platter, R.drawable.ic_skip_next, "Next", actions::next);
        // DPAD_LEFT from Previous returns to the whole media card, not an inset album/title capsule.
        prev.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                mediaPlatter.requestFocus();
                return true;
            }
            return false;
        });
        platter.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                View destination = prev.getVisibility() == View.VISIBLE ? prev
                        : toggle.getVisibility() == View.VISIBLE ? toggle : next;
                destination.requestFocus();
                return true;
            }
            return false;
        });
    }

    private ImageView smallButton(LinearLayout row, int icon, String description, Runnable action) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setImageTintList(ctx.getColorStateList(R.color.scenic_icon_tint));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(description);
        SceneGlassDrawable.apply(b, 22f, GlassTone.Kind.CONTROL);
        focusable(b, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMarginStart(dp(6));
        row.addView(b, lp);
        return b;
    }

    private void buildShortcuts() {
        LinearLayout row = new LinearLayout(ctx);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        card.addView(row, lp);
        inputsColumn = shortcut(row, R.drawable.ic_input, "Inputs", actions::inputs);
        shortcut(row, R.drawable.ic_picture, "Scenery", actions::scenery);
        shortcut(row, R.drawable.ic_cc_weather, "Weather", actions::weather);
        shortcut(row, R.drawable.ic_cc_sleep, "Sleep", actions::sleep);
    }

    /** A round glass button with its caption underneath; the column, not the caption, is the layout unit. */
    private View shortcut(LinearLayout row, int icon, String caption, Runnable action) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setImageTintList(ctx.getColorStateList(R.color.scenic_icon_tint));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(caption);
        SceneGlassDrawable.apply(b, 22f, GlassTone.Kind.CONTROL);
        focusable(b, action);
        col.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView t = text(12, medium, DIM);
        t.setText(caption);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(5);
        col.addView(t, tLp);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    // ---- state ---------------------------------------------------------------------------------

    private String mediaPlaying;

    /** The active session, or nulls when nothing plays. Only the actions the session offers are shown. */
    void setTrack(String title, String artist, Bitmap artwork, boolean playing,
                  boolean canPrevious, boolean canToggle, boolean canNext) {
        mediaPlaying = title;
        if (title == null) {
            mediaTitle.setText("Not playing");
            mediaArtist.setText("Music access");
            art.setImageResource(R.drawable.ic_music);
            art.setScaleType(ImageView.ScaleType.CENTER);
            if (!(art.getBackground() instanceof SceneGlassDrawable)) {
                art.setBackground(new SceneGlassDrawable(art, 12f, GlassTone.Kind.INSET));
            }
            canPrevious = canToggle = canNext = false;
        } else {
            mediaTitle.setText(title);
            mediaArtist.setText(artist == null ? "" : artist);
            if (artwork != null) {
                art.setImageBitmap(artwork);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setBackground(null);
            } else {
                art.setImageResource(R.drawable.ic_music);
                art.setScaleType(ImageView.ScaleType.CENTER);
                if (!(art.getBackground() instanceof SceneGlassDrawable)) {
                    art.setBackground(new SceneGlassDrawable(art, 12f, GlassTone.Kind.INSET));
                }
            }
        }
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        show(prev, canPrevious);
        show(toggle, canToggle);
        show(next, canNext);
    }

    void setNetwork(NetworkSummary s) {
        netIcon.setImageResource(s.kind == NetworkSummary.ETHERNET ? R.drawable.ic_ethernet : R.drawable.ic_wifi);
        netIcon.setImageTintList(ColorStateList.valueOf(s.kind == NetworkSummary.WIFI ? 0xFF169BFF
                : s.kind == NetworkSummary.ETHERNET ? 0xFF64DCC4 : DIM));
        netTitle.setText(s.title);
        netDetail.setText(s.detail);
        ((View) netIcon.getParent()).setContentDescription(s.title + ", " + s.detail);
    }

    void setSoundOutput(String label) {
        soundDetail.setText(label == null || label.isEmpty() ? "TV audio" : label);
    }

    void setFooter(String line) {
        // The status/footer row was removed: TV settings already has a persistent header button.
    }

    private void show(View v, boolean on) {
        if (!on && v.isFocused()) focusFirst();
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ---- open / close --------------------------------------------------------------------------

    boolean isOpen() {
        return open;
    }

    View root() {
        return overlay;
    }

    /** Grows from the trigger's corner (top right): the same spatial path in and out. */
    void open() {
        if (open) return;
        open = true;
        overlay.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        inputsColumn.setVisibility(actions.hasInputs() ? View.VISIBLE : View.GONE);
        overlay.setVisibility(View.VISIBLE);
        card.animate().cancel();
        card.setPivotX(dp(WIDTH_DP));
        card.setPivotY(0f);
        if (card.getAlpha() >= 1f) card.setAlpha(0f);
        if (reducedMotion) {
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationY(0f);
            card.animate().alpha(1f).setDuration(120).start();
        } else {
            if (card.getScaleX() >= 1f) {
                card.setScaleX(0.94f);
                card.setScaleY(0.94f);
                card.setTranslationY(-dp(8));
            }
            // Starts from whatever is on screen, so reopening mid-close reverses smoothly.
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(OPEN_MS)
                    .setInterpolator(EASE).withLayer().start();
        }
        // Only if still open: a Back before this runs has already handed focus back to Home.
        card.post(() -> { if (open) focusFirst(); });
    }

    void close(boolean animated) {
        if (!open) return;
        open = false;
        // While it fades out, Home's D-pad must not land on a control of the vanishing sheet.
        overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.animate().cancel();
        if (!animated) {
            overlay.setVisibility(View.GONE);
            card.setAlpha(1f);
            return;
        }
        if (reducedMotion) {
            card.animate().alpha(0f).setDuration(100)
                    .withEndAction(this::hideIfClosed).start();
        } else {
            // The same path and length as the opening, mirrored: back up into the button's corner.
            card.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(-dp(8)).setDuration(OPEN_MS)
                    .setInterpolator(EASE_BACK).withLayer().withEndAction(this::hideIfClosed).start();
        }
    }

    private void hideIfClosed() {
        if (open) return;
        overlay.setVisibility(View.GONE);
        card.setAlpha(1f);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setTranslationY(0f);
    }

    boolean contains(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == card) return true;
        }
        return false;
    }

    /** First control in reading order: the network capsule. */
    void focusFirst() {
        View first = (View) netIcon.getParent();
        if (first.getVisibility() == View.VISIBLE) first.requestFocus();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void focusable(View v, Runnable action) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
        v.setOnFocusChangeListener((x, focused) -> {
            // Reduced motion keeps the brighter glass and rim as feedback, without the lift.
            if (reducedMotion) return;
            float s = focused ? FOCUS_SCALE : 1f;
            x.animate().scaleX(s).scaleY(s).setDuration(180).setInterpolator(EASE).start();
        });
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private ViewOutlineProvider rounded(float radiusDp) {
        final float r = radiusDp * density;
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
