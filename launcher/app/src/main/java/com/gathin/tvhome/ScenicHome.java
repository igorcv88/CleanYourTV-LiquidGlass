package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Scenic Glass Home, layered over MainActivity's grid (which becomes the Apps screen):
 *
 * - open scenery (aerial video, a wallpaper, or the built-in {@link Scenery}) with only faint regional shade;
 * - a quiet header: weather top left (only when set up), clock and one round Settings button top right;
 * - a floating glass dock low on the screen with the favourites ({@link Favorites}) in a horizontal shelf and
 *   All Apps as its last item;
 * - Glass Current while a real media session plays ({@link NowPlaying}): artwork, title, artist, the actions
 *   the session supports and Listen, grouped above the {@link GlassCurrentView} ribbon, the art's blur washing
 *   the screen and the glass;
 * - Listen: the artwork grows into a full-screen listening view; Back reverses it;
 * - a glass Settings side panel and compact secondary screens (Search, Discover, My Space, Wallpapers,
 *   Appearance, Weather, Music access, Backup).
 *
 * Every overlay records what had focus ({@link FocusMemory}) and Back gives it back.
 */
final class ScenicHome implements NowPlaying.Listener {
    static final String PREFS = "scenic_home";
    static final String SHOW_ALL_APPS_DOCK_KEY = "show_all_apps_dock";
    static final String WALLPAPER_KEY = "wallpaper", GLASS_KEY = "glass";
    /** Opt-in: while music plays over an aerial, a smaller (cheaper, more diffuse) live frost. */
    static final String MUSIC_FROST_KEY = "light_frost_with_music";
    /** Keep silent aerials by default; album art is an opt-in playback backdrop. */
    static final String MUSIC_BACKDROP_KEY = "music_backdrop";

    private static final Interpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.3f, 1f);
    private static final long PANEL_MS = 360, LISTEN_MS = 420;
    private static final int TEXT = 0xFFF5F6F8;

    private final MainActivity a;
    private final FrameLayout root;
    private final Config cfg;
    private final float density;
    private final boolean reducedMotion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Typeface regular, medium, display, displayLight;
    private final FocusMemory<View> focusMemory = new FocusMemory<>();

    private final FrameLayout layer;
    private final View header;
    private final TextView weatherView;
    private final View settingsButton;
    /** The header's Control Center button: Up from the sheet's top row lands here, as on iOS. */
    private final View quickButton;
    private final FrameLayout dockFrame;
    private final RecyclerView dock;
    private final DockAdapter dockAdapter;
    private final GlassCurrentView ribbon;
    private final LinearLayout nowGroup;
    private final ImageView nowArt;
    private final TextView nowTitle, nowArtist;
    private final ImageView btnPrev, btnToggle, btnNext;
    private final TextView btnListen;
    private final ImageView wash;
    private final View dim;
    private final SceneGlassDrawable appsFrost;
    private final GradientDrawable appsShade;
    private ImageView still;

    private FrameLayout listenLayer;
    private ImageView bigArt;
    private TextView bigTitle, bigArtist, bigAlbum;
    private ImageView bigPrev, bigToggle, bigNext;
    private boolean listening;
    private ValueAnimator listenAnim;

    private FrameLayout panel;
    private LinearLayout panelRows;
    private TextView panelTitle;
    private ScrollView panelScroll;
    private FrameLayout screen;
    private LinearLayout screenContent;
    private ScrollView screenScroll;
    private TextView screenTitle;
    private ControlCenter controlCenter;
    private boolean appsShown;
    /** One reversible animation moves both surfaces; it never changes the dock's permanent Home layout. */
    private ValueAnimator appsAnimator;
    private float appsReveal;
    private int appsDockShift, appsGridTravel;

    private final NowPlaying nowPlaying;
    private NowPlaying.Track track;
    private boolean resumed;
    private final Weather weather;
    /** Scene decoding and painting, one thread for this Home instance; shut down in release(). */
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean released;
    private int screenToken;
    /** The inner levels of Settings; see {@link ScreenStack}. */
    private final ScreenStack<Runnable> screens = new ScreenStack<>();
    private boolean screenFromPanel, navigatingBack;
    private int panelFocusIndex = -1;
    /** {scrollY, focusIndex} to restore on the screen being built, or null for a fresh one. */
    private int[] pendingRestore;

    private List<String> favourites;
    private final Map<String, AppAdapter.Entry> byPkg = new HashMap<>();
    private String movingPkg;
    private String replacingPkg;
    private int lastDockPos;
    private PopupWindow menu;

    ScenicHome(MainActivity activity, FrameLayout root, Config cfg, boolean useStill, int washIndex) {
        this.a = activity;
        this.root = root;
        this.cfg = cfg;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.reducedMotion = cfg.reducedMotion || !cfg.animations;
        regular = activity.getResources().getFont(R.font.inter_regular);
        medium = activity.getResources().getFont(R.font.inter_medium);
        display = activity.getResources().getFont(R.font.inter_display_medium);
        displayLight = activity.getResources().getFont(R.font.inter_display_light);
        Scene.reset();
        SceneGlassDrawable.setFrosted("frosted".equals(activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(GLASS_KEY, "clear")));
        AerialBackdrop.setLightFrostWithMusic(activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(MUSIC_FROST_KEY, false));
        weather = new Weather(activity);
        nowPlaying = new NowPlaying(activity, this);

        if (useStill) addStill();

        // Album wash: the blurred cover over the scenery while music plays, under every control.
        wash = new ImageView(activity);
        wash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wash.setAlpha(0f);
        wash.setVisibility(View.GONE);
        wash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(wash, Math.max(0, Math.min(washIndex, root.getChildCount())), full());
        // Under All Apps: over a moving aerial, a full-screen veil of the same frosted glass, so the names
        // read over the flight; over the album wash (already blurred) only a gentle shade.
        dim = new View(activity);
        appsFrost = new SceneGlassDrawable(dim, 0f, GlassTone.Kind.BACKDROP);
        appsShade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x14080F18, 0x38080F18, 0x47080F18});
        dim.setBackground(appsShade);
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dim, root.indexOfChild(wash) + 1, full());

        layer = new FrameLayout(activity);
        layer.setClipChildren(false);
        layer.setClipToPadding(false);
        root.addView(layer, full());

        // ---- header ----
        FrameLayout head = new FrameLayout(activity);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        header = head;
        FrameLayout.LayoutParams headLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        headLp.setMargins(dp(56), dp(32), dp(48), 0);
        layer.addView(head, headLp);
        weatherView = text(17, medium, TEXT);
        weatherView.setShadowLayer(dp(6), 0, dp(1), 0x40000000);
        weatherView.setFontFeatureSettings("tnum");
        head.addView(weatherView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout right = new LinearLayout(activity);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        head.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTypeface(display);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        clock.setTextColor(TEXT);
        clock.setFontFeatureSettings("tnum");
        clock.setShadowLayer(dp(6), 0, dp(1), 0x40000000);
        clock.setIncludeFontPadding(false);
        right.addView(clock);
        ImageView quick = roundButton(R.drawable.ic_quick_controls, 48, "Control Center");
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        quickLp.setMarginStart(dp(18));
        right.addView(quick, quickLp);
        quick.setOnClickListener(v -> toggleControlCenter());
        quickButton = quick;
        ImageView gear = roundButton(R.drawable.ic_settings, 48, a.getString(R.string.scenic_settings));
        settingsButton = gear;
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        gearLp.setMarginStart(dp(12));
        right.addView(gear, gearLp);
        gear.setOnClickListener(v -> {
            if (controlCenter != null && controlCenter.isOpen()) closeControlCenterNow();
            openPanel();
        });

        // ---- dock ----
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        float scale = Math.min(1f, (screenW / density) / 960f);
        int tileW = Math.round(dp(144) * scale), tileH = Math.round(dp(84) * scale);
        int pad = Math.round(dp(18) * scale), gap = Math.round(dp(16) * scale);
        dockFrame = new FrameLayout(activity);
        dockFrame.setClipChildren(true);
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                tileH + 2 * pad, Gravity.BOTTOM);
        dockLp.setMargins(dp(48), 0, dp(48), dp(36));
        layer.addView(dockFrame, dockLp);
        SceneGlassDrawable.apply(dockFrame, 30f, GlassTone.Kind.CHROME);
        dockFrame.setElevation(dp(8));
        dockFrame.setClipToOutline(true);
        dock = new RecyclerView(activity);
        dock.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                // Keep the focused card a full card (plus gap) clear of the dock's ends while there is more to see.
                int l = child.getLeft() - gap - tileW / 2, r = child.getRight() + gap + tileW / 2;
                int dx = 0;
                if (l < 0) dx = l;
                else if (r > parent.getWidth()) dx = r - parent.getWidth();
                if (dx == 0) return false;
                if (immediate || reducedMotion) parent.scrollBy(dx, 0);
                else parent.smoothScrollBy(dx, 0, EASE, 260);
                return true;
            }
        });
        dock.setPadding(pad, pad, pad, pad);
        dock.setClipToPadding(false);
        dock.setClipChildren(false);
        dock.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dock.setItemAnimator(null);
        dock.setHasFixedSize(true);
        dock.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView p, @NonNull RecyclerView.State s) {
                if (p.getChildAdapterPosition(v) > 0) out.left = gap;
            }
        });
        dockAdapter = new DockAdapter(tileW, tileH);
        dock.setAdapter(dockAdapter);
        dockFrame.addView(dock, full());
        dockFrame.setTranslationZ(0f);

        // ---- Glass Current ribbon: its line 25dp above the dock's top ----
        int dockTop = dp(36) + tileH + 2 * pad;
        ribbon = new GlassCurrentView(activity);
        // Tall enough for the waves (RibbonSwells.MAX_HEIGHT_DP) above the line, which stays where it was.
        FrameLayout.LayoutParams ribbonLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        ribbonLp.setMargins(dp(64), 0, dp(64), dockTop + dp(25) - dp(32));
        layer.addView(ribbon, ribbonLp);
        ribbon.setVisibility(View.GONE);
        ribbon.setSeekListener(this::seekBy);
        ribbon.setMotionEnabled(!reducedMotion);
        // On Home the line takes the backdrop's colours (the aerial or wallpaper behind it); Listen, the cover's.
        Scene.setPaletteListener(() -> ribbon.setScenePalette(Scene.palette()));
        ribbon.setScenePalette(Scene.palette());

        // ---- now playing group, above the ribbon ----
        nowGroup = new LinearLayout(activity);
        nowGroup.setOrientation(LinearLayout.HORIZONTAL);
        nowGroup.setGravity(Gravity.CENTER_VERTICAL);
        nowGroup.setClipChildren(false);
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        nowLp.setMargins(dp(64), 0, dp(64), dockTop + dp(25) + dp(18) + dp(16));
        layer.addView(nowGroup, nowLp);
        nowGroup.setVisibility(View.GONE);
        nowArt = new ImageView(activity);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setOutlineProvider(rounded(14));
        nowArt.setClipToOutline(true);
        nowArt.setElevation(dp(8));
        nowGroup.addView(nowArt, new LinearLayout.LayoutParams(dp(104), dp(104)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.setMarginStart(dp(22));
        nowGroup.addView(col, colLp);
        nowTitle = text(24, display, TEXT);
        nowTitle.setMaxWidth(dp(520));
        nowArtist = text(16, regular, 0xC7F5F6F8);
        nowArtist.setMaxWidth(dp(520));
        for (TextView t : new TextView[]{nowTitle, nowArtist}) t.setShadowLayer(dp(8), 0, dp(1), 0x40000000);
        col.addView(nowTitle);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(2);
        col.addView(nowArtist, artistLp);
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctlLp.topMargin = dp(12);
        col.addView(controls, ctlLp);
        btnPrev = transport(controls, R.drawable.ic_skip_prev, "Previous", () -> nowPlaying.previous());
        btnToggle = transport(controls, R.drawable.ic_pause, "Play or pause", () -> nowPlaying.togglePlay());
        btnNext = transport(controls, R.drawable.ic_skip_next, "Next", () -> nowPlaying.next());
        btnListen = new TextView(activity);
        btnListen.setText(R.string.scenic_listen);
        btnListen.setTypeface(medium);
        btnListen.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnListen.setTextColor(a.getColorStateList(R.color.scenic_row_text));
        btnListen.setGravity(Gravity.CENTER);
        btnListen.setPadding(dp(22), 0, dp(22), 0);
        SceneGlassDrawable.apply(btnListen, -1f, GlassTone.Kind.CONTROL);
        btnListen.setFocusable(true);
        btnListen.setClickable(true);
        LinearLayout.LayoutParams listenLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        listenLp.setMarginStart(dp(14));
        controls.addView(btnListen, listenLp);
        btnListen.setOnClickListener(v -> openListen());
        focusMotion(btnListen);

        buildPanel();
        buildScreen();
        buildControlCenter();
        // The original Apps RecyclerView belongs to a weighted LinearLayout. Its classic
        // header is gone in Scenic, so reserve a real layout inset (not a translation)
        // before the first row: scrolling and D-pad focus must never slide under the clock.
        View gridFrame = a.gridFrame();
        ViewGroup.MarginLayoutParams gridLp = (ViewGroup.MarginLayoutParams) gridFrame.getLayoutParams();
        gridLp.topMargin = dp(124);
        gridLp.bottomMargin = dp(22);
        gridFrame.setLayoutParams(gridLp);
        String w = weather.cached();
        weatherView.setText(w);
        weatherView.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    void resume() {
        resumed = true;
        nowPlaying.start();
        refreshWeather();
        startTick();
    }

    void pause() {
        resumed = false;
        nowPlaying.stop();
        handler.removeCallbacks(tick);
        if (menu != null) menu.dismiss();
        endMove();
    }

    void release() {
        released = true;
        nowPlaying.release();
        worker.shutdownNow();
        Scene.reset();
    }

    void onMinute() {
        refreshWeather();
    }

    private void refreshWeather() {
        if (!weather.configured()) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        weather.refresh(line -> {
            weatherView.setText(line);
            weatherView.setVisibility(View.VISIBLE);
        });
    }

    /** Home pressed while Home is in front: close everything and focus the first dock item. */
    void goHome() {
        closeAllOverlays();
        if (appsShown) showApps(false, false);
        lastDockPos = 0;
        dock.scrollToPosition(0);
        handler.post(() -> focusDock(0));
    }

    View inputsAnchor() { return settingsButton; }

    boolean appsShown() {
        return appsShown;
    }

    /** Focus for Home itself (after a reload or coming back from an app). */
    void focusHome() {
        if (listening || panel.getVisibility() == View.VISIBLE || screen.getVisibility() == View.VISIBLE) return;
        if (appsShown && a.hasScenicGridItems()) { a.focusGridAt(-1); return; }
        focusDock(lastDockPos);
    }

    // ---- apps & dock ---------------------------------------------------------------------------

    /** The grid reloaded: rebuild the shelf from the current entries. */
    void onAppsChanged(List<AppAdapter.Entry> entries) {
        byPkg.clear();
        List<String> installed = new ArrayList<>();
        for (AppAdapter.Entry e : entries) {
            if (!byPkg.containsKey(e.pkg)) {
                byPkg.put(e.pkg, e);
                installed.add(e.pkg);
            }
        }
        favourites = Favorites.effective(Favorites.load(a), cfg.dockApps, installed,
                ScenicDockRules.capacity(cfg.columns));
        List<String> visibleDock = ScenicDockRules.takeVisible(Favorites.visible(favourites, byPkg.keySet()), dockCapacity());
        List<AppAdapter.Entry> shown = new ArrayList<>();
        for (String p : visibleDock) shown.add(byPkg.get(p));
        boolean hadFocus = dock.hasFocus();
        dockAdapter.set(shown);
        a.setScenicDockPackages(visibleDock);
        if (hadFocus) handler.post(() -> focusDock(lastDockPos));
    }

    boolean toggleFavourite(AppAdapter.Entry e) {
        if (favourites == null) return false;
        boolean adding = !favourites.contains(e.pkg);
        if (adding && ScenicDockRules.needsReplacement(
                Favorites.visible(favourites, byPkg.keySet()), e.pkg, dockCapacity())) {
            // Search and Discover also expose long-press Add to Dock. Their screen layer traps
            // D-pad focus, so move into the expanded All Apps layout before picking a slot.
            // Back then returns to the original app, still highlighted in the grid.
            if (screen.getVisibility() == View.VISIBLE && screens.depth() > 0) {
                closeScreenNow();
                showApps(true, false);
            }
            replacingPkg = e.pkg;
            a.setScenicReplacementCandidate(e.pkg);
            Toast.makeText(a, a.getString(R.string.scenic_replace_choose, e.label), Toast.LENGTH_LONG).show();
            handler.post(() -> focusDock(Math.min(lastDockPos, dockAdapter.favouriteCount() - 1)));
            return true;
        }
        favourites = Favorites.toggle(favourites, e.pkg);
        Favorites.save(a, favourites);
        rebuildShelf();
        Toast.makeText(a, a.getString(adding ? R.string.scenic_added : R.string.scenic_removed, e.label), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void rebuildShelf() {
        List<String> visibleDock = ScenicDockRules.takeVisible(Favorites.visible(favourites, byPkg.keySet()), dockCapacity());
        List<AppAdapter.Entry> shown = new ArrayList<>();
        for (String p : visibleDock) shown.add(byPkg.get(p));
        dockAdapter.set(shown);
        a.setScenicDockPackages(visibleDock);
    }

    private int dockCapacity() { return ScenicDockRules.capacity(cfg.columns); }

    private boolean showAllAppsDock() { return prefs().getBoolean(SHOW_ALL_APPS_DOCK_KEY, true); }

    private void finishReplace(String oldPkg) {
        if (replacingPkg == null) return;
        String newPkg = replacingPkg;
        favourites = ScenicDockRules.replace(favourites, oldPkg, newPkg);
        Favorites.save(a, favourites);
        cancelReplace();
        rebuildShelf();
        Toast.makeText(a, a.getString(R.string.scenic_replaced, byPkg.get(newPkg).label), Toast.LENGTH_SHORT).show();
        handler.post(() -> focusDock(dockAdapter.indexOf(newPkg)));
    }

    private void cancelReplace() {
        replacingPkg = null;
        a.setScenicReplacementCandidate(null);
    }

    private void cancelReplaceAndReturn() {
        String previous = replacingPkg;
        cancelReplace();
        if (appsShown) a.focusScenicGridPackage(previous);
    }

    private void focusDock(int pos) {
        if (dockFrame.getVisibility() != View.VISIBLE) return;
        int p = DockNav.restore(pos, dockAdapter.getItemCount());
        RecyclerView.ViewHolder vh = dock.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            vh.itemView.findViewById(R.id.dockCard).requestFocus();
            return;
        }
        dock.scrollToPosition(p);
        dock.post(() -> {
            RecyclerView.ViewHolder h = dock.findViewHolderForAdapterPosition(p);
            if (h != null) h.itemView.findViewById(R.id.dockCard).requestFocus();
        });
    }

    /** The Apps grid rises from below and lifts the dock to a shelf just beneath the header. */
    void showApps(boolean show, boolean focusGrid) {
        if (appsShown == show) return;
        appsShown = show;
        if (show && controlCenter != null && controlCenter.isOpen()) closeControlCenterNow();
        // The header stays visible and clear of the raised dock. The real grid has a layout inset,
        // so its cards cannot scroll behind either the clock or the dock.
        header.setVisibility(View.VISIBLE);
        View gridFrame = a.gridFrame();
        if (appsAnimator != null) appsAnimator.cancel();
        dim.animate().cancel();
        if (show) {
            int headerBottom = header.getBottom() > 0 ? header.getBottom() : dp(96);
            int dockHeight = dockFrame.getHeight() > 0 ? dockFrame.getHeight() : dockFrame.getLayoutParams().height;
            int dockTop = ScenicAppsMotion.dockTop(headerBottom, dp(12));
            int gridTop = ScenicAppsMotion.gridTop(dockTop, dockHeight, dp(14));
            appsDockShift = dockTop - dockFrame.getTop();
            appsGridTravel = ScenicAppsMotion.entryTravel(root.getHeight(), gridTop, dp(32));
            ViewGroup.MarginLayoutParams gridLp = (ViewGroup.MarginLayoutParams) gridFrame.getLayoutParams();
            if (gridLp.topMargin != gridTop) {
                gridLp.topMargin = gridTop;
                gridFrame.setLayoutParams(gridLp);
            }
            dim.setBackground(washCovering() ? appsShade : appsFrost);
            dim.setVisibility(View.VISIBLE);
            gridFrame.setVisibility(View.VISIBLE);
            fade(nowGroup, false, 0);
            fade(ribbon, false, 0);
        }
        if (!show) {
            if (track != null) {
                fade(nowGroup, true, 0);
                fade(ribbon, true, 0);
            }
        }
        float start = appsReveal, end = show ? 1f : 0f;
        appsAnimator = ValueAnimator.ofFloat(start, end);
        appsAnimator.setDuration(reducedMotion ? 120 : Math.max(120L, Math.round(440 * Math.abs(end - start))));
        appsAnimator.setInterpolator(reducedMotion ? new DecelerateInterpolator() : EASE);
        appsAnimator.addUpdateListener(animation -> {
            appsReveal = (float) animation.getAnimatedValue();
            dockFrame.setTranslationY(appsDockShift * appsReveal);
            gridFrame.setTranslationY(appsGridTravel * (1f - appsReveal));
            gridFrame.setAlpha(ScenicAppsMotion.gridAlpha(appsReveal));
            dim.setAlpha(appsReveal);
        });
        appsAnimator.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                if (appsShown) {
                    if (focusGrid) {
                        if (a.hasScenicGridItems()) a.focusGridAt(0);
                        else focusDock(lastDockPos);
                    }
                } else {
                    gridFrame.setVisibility(View.GONE);
                    dim.setVisibility(View.GONE);
                    // Restore the old grid geometry for the next fresh entrance.
                    ViewGroup.MarginLayoutParams gridLp = (ViewGroup.MarginLayoutParams) gridFrame.getLayoutParams();
                    gridLp.topMargin = dp(124);
                    gridFrame.setLayoutParams(gridLp);
                }
            }
        });
        appsAnimator.start();
    }

    private void launch(AppAdapter.Entry e) {
        a.launchEntry(e);
    }

    final class DockAdapter extends RecyclerView.Adapter<DockAdapter.Holder> {
        private final int tileW, tileH;
        private final List<AppAdapter.Entry> items = new ArrayList<>();

        private final ViewOutlineProvider cardOutline = rounded(16);

        DockAdapter(int tileW, int tileH) {
            this.tileW = tileW;
            this.tileH = tileH;
            setHasStableIds(true);
        }

        void set(List<AppAdapter.Entry> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        int favouriteCount() {
            return items.size();
        }

        AppAdapter.Entry at(int pos) {
            return pos >= 0 && pos < items.size() ? items.get(pos) : null;
        }

        int indexOf(String pkg) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) return i;
            return -1;
        }

        @Override public long getItemId(int pos) {
            return showAllAppsDock() && DockNav.isAllApps(pos, items.size()) ? Long.MAX_VALUE : items.get(pos).pkg.hashCode();
        }

        @Override public int getItemCount() {
            return items.size() + (showAllAppsDock() ? 1 : 0);
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(a).inflate(R.layout.item_dock, parent, false);
            View card = v.findViewById(R.id.dockCard);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            card.setLayoutParams(lp);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            boolean allApps = showAllAppsDock() && DockNav.isAllApps(pos, items.size());
            h.card.setContentDescription(allApps ? a.getString(R.string.scenic_all_apps) : items.get(pos).label);
            if (allApps) {
                // A control, not an app: frosted glass, the grid icon and its caption.
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                h.icon.setImageResource(R.drawable.ic_apps);
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.icon.getLayoutParams();
                lp.width = dp(26);
                lp.height = dp(26);
                lp.bottomMargin = dp(14);
                h.icon.setLayoutParams(lp);
                h.icon.setVisibility(View.VISIBLE);
                h.label.setText(R.string.scenic_all_apps);
                h.label.setVisibility(View.VISIBLE);
                // A glass control in the dock, with the material's own faint shadow: a full-alpha outline shadow
                // under translucent glass showed through as a grey slab when focus lifted the card.
                if (!(h.card.getBackground() instanceof SceneGlassDrawable)) {
                    h.card.setBackground(new SceneGlassDrawable(h.card, 16f, GlassTone.Kind.CONTROL));
                }
                h.card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            } else {
                AppAdapter.Entry e = items.get(pos);
                h.card.setOutlineProvider(cardOutline);
                if (h.card.getTag(R.id.tile_bg_orig) == null) h.card.setTag(R.id.tile_bg_orig, a.getDrawable(R.drawable.tile_bg));
                BannerFit.bind(h.card, h.banner, h.icon, h.label, e, tileW, tileH, dp(16), false);
            }
            // The reflective rim is already part of the glass material. The separate
            // ShineView gave All Apps a visibly stacked third sheet during focus.
            h.glare.setVisibility(allApps ? View.GONE : View.VISIBLE);
            h.card.setForeground(movingPkg != null && !allApps && items.get(pos).pkg.equals(movingPkg)
                    ? a.getDrawable(R.drawable.scenic_moving_ring) : null);
            boolean focused = h.card.isFocused();
            if (!reducedMotion) TvFocusMotion.set(h.card, h.banner, h.glare, focused, density);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View card, glare;
            final ImageView banner, icon;
            final TextView label;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.dockCard);
                banner = v.findViewById(R.id.banner);
                icon = v.findViewById(R.id.icon);
                label = v.findViewById(R.id.label);
                glare = v.findViewById(R.id.glare);
                card.setOutlineProvider(cardOutline);
                card.setClipToOutline(true);
                card.setOnClickListener(x -> {
                    int pos = getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    if (movingPkg != null) { endMove(); return; }
                    if (replacingPkg != null) {
                        if (pos < items.size()) finishReplace(items.get(pos).pkg);
                        return;
                    }
                    if (showAllAppsDock() && DockNav.isAllApps(pos, items.size())) {
                        a.playOpenSound();
                        showApps(true, true);
                    } else {
                        launch(items.get(pos));
                    }
                });
                card.setOnLongClickListener(x -> {
                    int pos = getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION || replacingPkg != null
                            || (showAllAppsDock() && DockNav.isAllApps(pos, items.size()))) return false;
                    showDockMenu(card, items.get(pos));
                    return true;
                });
                card.setOnFocusChangeListener((x, focused) -> {
                    if (reducedMotion) {
                        x.animate().scaleX(focused ? TvFocusMotion.SCALE : 1f).scaleY(focused ? TvFocusMotion.SCALE : 1f)
                                .translationZ(focused ? dp(6) : 0).setDuration(120).start();
                    } else {
                        TvFocusMotion.apply(x, banner, glare, focused, density);
                    }
                    int pos = getBindingAdapterPosition();
                    if (focused && pos != RecyclerView.NO_POSITION) lastDockPos = pos;
                });
                card.setOnKeyListener((x, code, ev) -> {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int pos = getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return false;
                    boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, rightKey = code == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (replacingPkg != null) {
                        if (code == KeyEvent.KEYCODE_BACK) { cancelReplaceAndReturn(); return true; }
                        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
                    }
                    if (movingPkg != null) {
                        if (left || rightKey) {
                            moveFavourite(left ? -1 : 1);
                            return true;
                        }
                        if (code == KeyEvent.KEYCODE_BACK) { endMove(); return true; }
                        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
                    }
                    if (left || rightKey) {
                        int to = DockNav.step(pos, left ? -1 : 1, getItemCount());
                        if (to == pos) {
                            if (!reducedMotion) TvFocusMotion.bump(card, code, density);
                            return true;
                        }
                        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && ev.getRepeatCount() > 0) {
                            // Held key: let RecyclerView's focus search move one per repeat; retargets, never stacks.
                            return false;
                        }
                    }
                    if (code == KeyEvent.KEYCODE_MENU && !(showAllAppsDock() && DockNav.isAllApps(pos, items.size()))) {
                        showDockMenu(card, items.get(pos));
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private void showDockMenu(View anchor, AppAdapter.Entry e) {
        if (menu != null) menu.dismiss();
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(10), dp(10), dp(10), dp(10));
        SceneGlassDrawable.apply(rows, 22f, GlassTone.Kind.PANEL);
        PopupWindow p = new PopupWindow(rows, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu = p;
        p.setOnDismissListener(() -> { if (menu == p) menu = null; });
        rows.addView(row(a.getString(R.string.scenic_move), null, () -> { p.dismiss(); startMove(e.pkg); }));
        rows.addView(row(a.getString(R.string.scenic_remove), null, () -> { p.dismiss(); toggleFavourite(e); }));
        rows.addView(row(a.getString(R.string.scenic_app_info), null, () -> {
            p.dismiss();
            a.openIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)));
        }));
        p.setElevation(dp(16));
        p.showAsDropDown(anchor, 0, -anchor.getHeight() - dp(170));
        rows.getChildAt(0).requestFocus();
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        dockAdapter.notifyDataSetChanged();
        Toast.makeText(a, R.string.scenic_moving, Toast.LENGTH_SHORT).show();
        int pos = dockAdapter.indexOf(pkg);
        handler.post(() -> focusDock(pos));
    }

    private void moveFavourite(int step) {
        if (movingPkg == null) return;
        List<String> next = Favorites.moveVisible(favourites, byPkg.keySet(), movingPkg, step);
        if (next.equals(favourites)) return;
        favourites = next;
        Favorites.save(a, favourites);
        rebuildShelf();
        int pos = dockAdapter.indexOf(movingPkg);
        lastDockPos = pos;
        dock.post(() -> focusDock(pos));
    }

    private void endMove() {
        if (movingPkg == null) return;
        movingPkg = null;
        dockAdapter.notifyDataSetChanged();
    }

    // ---- keys ------------------------------------------------------------------------------------

    private static final int ROW_NONE = 0, ROW_HEADER = 1, ROW_CONTROLS = 2, ROW_RIBBON = 3, ROW_DOCK = 4;

    private int rowOf(View f) {
        if (f == null) return ROW_NONE;
        if (f == ribbon) return ROW_RIBBON;
        if (isInside(f, dock)) return ROW_DOCK;
        if (isInside(f, nowGroup)) return ROW_CONTROLS;
        if (isInside(f, header)) return ROW_HEADER;
        return ROW_NONE;
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    private View lastControl;

    /** Home's vertical rows: Header, Controls, Ribbon, Dock. Called before the activity's own handling. */
    boolean dispatchKey(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = ev.getKeyCode();
        View f = a.getCurrentFocus();
        if (replacingPkg != null && isInside(f, dock)
                && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) return true;
        if (listening) return listenKey(f, code);
        if (controlCenter != null && controlCenter.isOpen()) {
            if (!isDpad(code)) return false;
            if (f == quickButton || f == settingsButton) {
                // The header's two buttons stay reachable above the open sheet: Left/Right between them,
                // Down back into the sheet, Up stays.
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) quickButton.requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) settingsButton.requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) controlCenter.focusFirst();
                return true;
            }
            if (f == null || !controlCenter.contains(f)) {   // focus was lost mid-animation: bring it back
                controlCenter.focusFirst();
                return true;
            }
            View next = f.focusSearch(direction(code));
            if (next != null && controlCenter.contains(next)) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) quickButton.requestFocus();   // top row: up to the button
            return true;
        }
        boolean screenOpen = screen.getVisibility() == View.VISIBLE && screens.depth() > 0;
        if (panel.getVisibility() == View.VISIBLE || screenOpen) {
            // Keep focus inside the open overlay (not a screen that is only fading out after Back).
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (panel.getVisibility() == View.VISIBLE
                        && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)
                        && movePanelFocus(f, code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) return true;
                if (screenOpen
                        && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)
                        && moveScreenFocus(f, code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) return true;
                // A text field moves its own caret with Left/Right.
                if (f instanceof EditText && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return false;
                View container = panel.getVisibility() == View.VISIBLE ? panel : screen;
                View next = f == null ? null : f.focusSearch(direction(code));
                if (next == null || !isInside(next, container)) return true;
            }
            return false;
        }
        int row = rowOf(f);
        // Moving a dock card: Left/Right move it (the card's key listener), Up/Down stay put.
        if (movingPkg != null && row == ROW_DOCK && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) return true;
        if (appsShown) {
            // Up out of the grid's first row reaches the dock, and the dock means Home: close All Apps
            // on the way rather than stopping on the raised shelf for one more press.
            if (code == KeyEvent.KEYCODE_DPAD_UP && row == ROW_NONE && a.focusedGridIsFirstRow()) {
                showApps(false, false);
                handler.post(() -> focusDock(lastDockPos));
                return true;
            }
            if (row == ROW_DOCK && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (a.hasScenicGridItems()) a.focusGridAt(0);
                return true;
            }
            if (row == ROW_DOCK && code == KeyEvent.KEYCODE_DPAD_UP) {
                showApps(false, false);
                handler.post(() -> focusDock(lastDockPos));
                return true;
            }
            return false;
        }
        if (row == ROW_NONE) {
            if (isDpad(code)) {
                focusDock(lastDockPos);
                return true;
            }
            return false;
        }
        boolean music = nowGroup.getVisibility() == View.VISIBLE;
        boolean seek = music && ribbon.getVisibility() == View.VISIBLE && ribbon.isSeekable();
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            switch (row) {
                case ROW_DOCK:
                    if (seek) ribbon.requestFocus();
                    else if (music) focusControls();
                    else settingsButton.requestFocus();
                    return true;
                case ROW_RIBBON:
                    focusControls();
                    return true;
                case ROW_CONTROLS:
                    settingsButton.requestFocus();
                    return true;
                case ROW_HEADER:
                    return true;
                default:
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            switch (row) {
                case ROW_HEADER:
                    if (music) focusControls(); else focusDock(lastDockPos);
                    return true;
                case ROW_CONTROLS:
                    lastControl = f;
                    if (seek) ribbon.requestFocus(); else focusDock(lastDockPos);
                    return true;
                case ROW_RIBBON:
                    focusDock(lastDockPos);
                    return true;
                case ROW_DOCK:
                    showApps(true, true);
                    return true;
                default:
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (row == ROW_HEADER) {
                View next = f.focusSearch(direction(code));
                return next == null || !isInside(next, header);
            }
            if (row == ROW_CONTROLS) {
                View next = f.focusSearch(direction(code));
                if (next == null || !isInside(next, nowGroup)) return true;
                lastControl = next;
            }
        }
        return false;
    }

    /** Deterministic D-pad traversal across scroll boundaries and non-focusable section headings. */
    private boolean movePanelFocus(View focused, int step) {
        int current = -1;
        for (int i = 0; i < panelRows.getChildCount(); i++) {
            if (isInside(focused, panelRows.getChildAt(i))) { current = i; break; }
        }
        if (current < 0) return false;
        for (int i = current + step; i >= 0 && i < panelRows.getChildCount(); i += step) {
            View candidate = panelRows.getChildAt(i);
            if (candidate.isFocusable() && candidate.getVisibility() == View.VISIBLE) {
                candidate.requestFocus();
                // ScrollView brings focused children into view itself; no scroll delta guessing.
                return true;
            }
        }
        if (step < 0) panelScroll.smoothScrollTo(0, 0);
        return true;
    }

    /** Explicit vertical D-pad order also works when an EditText consumes Up/Down. */
    private boolean moveScreenFocus(View focused, int step) {
        int current = -1;
        for (int i = 0; i < screenContent.getChildCount(); i++) {
            if (isInside(focused, screenContent.getChildAt(i))) { current = i; break; }
        }
        if (current < 0) return false;
        for (int i = current + step; i >= 0 && i < screenContent.getChildCount(); i += step) {
            View candidate = screenContent.getChildAt(i);
            if (candidate.getVisibility() != View.VISIBLE) continue;
            if (candidate.isFocusable() && candidate.requestFocus()) return true;
            if (candidate instanceof ViewGroup && focusFirst((ViewGroup) candidate)) return true;
        }
        if (step < 0) screenScroll.smoothScrollTo(0, 0);
        return true;
    }

    private void focusControls() {
        if (lastControl != null && lastControl.getVisibility() == View.VISIBLE && isInside(lastControl, nowGroup)) {
            lastControl.requestFocus();
        } else {
            btnListen.requestFocus();
        }
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            default: return View.FOCUS_DOWN;
        }
    }

    /** Back: the top overlay closes and gives focus back. False when Home has nothing open. */
    boolean onBack() {
        if (menu != null) { menu.dismiss(); return true; }
        if (replacingPkg != null) { cancelReplaceAndReturn(); return true; }
        if (controlCenter != null && controlCenter.isOpen()) { closeControlCenter(); return true; }
        if (movingPkg != null) { endMove(); return true; }
        if (listening) { closeListen(); return true; }
        // depth > 0: a screen still fading out after its last Back is not a level to go back from again.
        if (screen.getVisibility() == View.VISIBLE && screens.depth() > 0) { backScreen(); return true; }
        if (panel.getVisibility() == View.VISIBLE) { closePanel(); return true; }
        if (appsShown) {
            showApps(false, false);
            handler.post(() -> focusDock(dockAdapter.getItemCount() - 1));   // back onto All Apps
            return true;
        }
        return false;
    }

    private void closeAllOverlays() {
        if (menu != null) menu.dismiss();
        if (controlCenter != null) controlCenter.close(false);
        endMove();
        cancelReplace();
        if (listening) {
            if (listenAnim != null) listenAnim.cancel();
            finishListenClose();
        }
        screenToken++;   // late gallery/network callbacks must not fill the hidden screen
        screen.setVisibility(View.GONE);
        screens.clear();
        screenFromPanel = false;
        pendingRestore = null;
        panel.setVisibility(View.GONE);
        focusMemory.clear();
    }

    // ---- Glass Current -----------------------------------------------------------------------------

    @Override
    public void onNowPlaying(NowPlaying.Track t) {
        // Spotify's advanced mixes run inside Spotify. Never mix a muted aerial AudioTrack into the
        // same output: if a video-only copy is unavailable, the wallpaper yields to playback.
        a.setAerialAudioPlaying(t != null && (MediaTime.advancing(t.state)
                || t.state == MediaTime.STATE_BUFFERING));
        NowPlaying.Track old = track;
        track = t;
        syncControlCenterMedia();
        if (t == null) {
            ribbon.setPlaying(false);
            ribbon.setLivePosition(null, 0);
            ribbon.setArtwork(null);
            if (listening) closeListen();
            if (!appsShown) {
                fade(nowGroup, false, 0);
                fade(ribbon, false, 0);
            } else {
                nowGroup.setVisibility(View.GONE);
                ribbon.setVisibility(View.GONE);
            }
            if (nowGroup.hasFocus() || ribbon.hasFocus()) focusDock(lastDockPos);
            showWash(null);
            return;
        }
        nowTitle.setText(t.title);
        nowArtist.setText(t.artist != null ? t.artist : "");
        nowArtist.setVisibility(t.artist != null ? View.VISIBLE : View.GONE);
        setArt(nowArt, t.art, 14f);
        boolean playing = MediaTime.advancing(t.state) || t.state == MediaTime.STATE_BUFFERING;
        ribbon.setArtwork(t.art);
        ribbon.setPlaying(playing);
        int toggleIcon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnToggle.setImageResource(toggleIcon);
        setVisible(btnPrev, MediaTime.can(t.actions, MediaTime.ACTION_SKIP_TO_PREVIOUS));
        setVisible(btnNext, MediaTime.can(t.actions, MediaTime.ACTION_SKIP_TO_NEXT));
        setVisible(btnToggle, MediaTime.canToggle(t.actions, t.state));
        ribbon.setSeekable(MediaTime.canSeek(t.actions, t.duration));
        if (bigArt != null) {
            bigTitle.setText(t.title);
            bigArtist.setText(t.artist != null ? t.artist : "");
            bigAlbum.setText(t.album != null ? t.album : "");
            setArt(bigArt, t.art, 24f);
            bigToggle.setImageResource(toggleIcon);
            setVisible(bigPrev, btnPrev.getVisibility() == View.VISIBLE);
            setVisible(bigNext, btnNext.getVisibility() == View.VISIBLE);
            setVisible(bigToggle, btnToggle.getVisibility() == View.VISIBLE);
        }
        updateProgress();
        // Not while Listen is open: the compact group is hidden under it on purpose.
        if (!appsShown && !listening && (old == null || nowGroup.getVisibility() != View.VISIBLE)) {
            fade(nowGroup, true, 0);
            fade(ribbon, true, 0);
        }
        // While the new cover decodes, the previous track's wash stays (no flash of the wallpaper between tracks);
        // it changes once the art is ready, or fades out if the track turns out to have none.
        syncMusicBackdrop();
        startTick();
    }

    private void syncMusicBackdrop() {
        NowPlaying.Track current = track;
        if (current == null) { showWash(null); return; }
        boolean playing = MediaTime.advancing(current.state) || current.state == MediaTime.STATE_BUFFERING;
        boolean preferArt = "art".equals(prefs().getString(MUSIC_BACKDROP_KEY, "aerial"));
        // If a silent video cannot be made, cover the suspended aerial with available artwork
        // rather than leaving a black frame while preserving Spotify's mixing engine.
        if (!playing || (!preferArt && !a.aerialNeedsArtworkFallback())) {
            showWash(null);
        } else if (!current.artPending) {
            showWash(current.blurredArt, current.glassArt);
        }
    }

    /** The track's cover, or (no art yet, or none at all) the music glyph on glass: never the previous cover. */
    private void setArt(ImageView view, Bitmap art, float radiusDp) {
        if (art != null) {
            view.setImageBitmap(art);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setBackground(null);
        } else {
            view.setImageResource(R.drawable.ic_music);
            view.setScaleType(ImageView.ScaleType.CENTER);
            if (!(view.getBackground() instanceof SceneGlassDrawable))
                view.setBackground(new SceneGlassDrawable(view, radiusDp, GlassTone.Kind.CONTROL));
        }
    }

    private void setVisible(View v, boolean on) {
        if (!on && v.isFocused()) btnListen.requestFocus();
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** The album wash is fully over the scenery (the aerial need not play behind it). */
    boolean washCovering() {
        return wash.getVisibility() == View.VISIBLE && wash.getAlpha() >= 1f;
    }

    private Bitmap washSource;
    private boolean washDesired;

    private void showWash(Bitmap blurred) {
        showWash(blurred, null);
    }

    private void showWash(Bitmap blurred, Bitmap glassArt) {
        Scene.setMusic(blurred == null ? null : glassArt);
        boolean wantWash = blurred != null;
        if (blurred == washSource && wantWash == washDesired) return;
        washSource = blurred;
        washDesired = wantWash;
        wash.animate().cancel();
        if (blurred == null) {
            if (wash.getVisibility() != View.VISIBLE) return;
            a.resumeAerial();
            wash.animate().alpha(0f).setDuration(reducedMotion ? 150 : 450).withEndAction(() -> {
                wash.setVisibility(View.GONE);
                wash.setImageDrawable(null);
            }).start();
            return;
        }
        BitmapDrawable d = new BitmapDrawable(a.getResources(), blurred);
        d.setFilterBitmap(true);
        wash.setImageDrawable(d);
        wash.setVisibility(View.VISIBLE);
        // Fully covered by the wash: the aerial's decoder has nothing to show, so it is released until the
        // music stops (AerialBackdrop keeps the position).
        wash.animate().alpha(1f).setDuration(reducedMotion ? 150 : 450).withEndAction(() -> {
            if (washSource == blurred && wash.getVisibility() == View.VISIBLE) a.pauseAerial();
        }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            syncMusicBackdrop();
            if (resumed && track != null && MediaTime.advancing(track.state)) handler.postDelayed(this, 1000);
        }
    };

    private void startTick() {
        handler.removeCallbacks(tick);
        if (resumed && track != null && MediaTime.advancing(track.state)) handler.postDelayed(tick, 1000);
    }

    private void updateProgress() {
        NowPlaying.Track t = track;
        if (t == null) return;
        long pos = t.positionNow();
        float frac = MediaTime.fraction(pos, t.duration);
        ribbon.setProgress(frac, MediaTime.format(pos), MediaTime.formatRemaining(pos, t.duration));
        ribbon.setLivePosition(MediaTime.advancing(t.state) ? t::positionNow : null, t.duration);
    }

    private void seekBy(long delta) {
        NowPlaying.Track t = track;
        if (t == null || !MediaTime.canSeek(t.actions, t.duration)) return;
        long target = MediaTime.seekTarget(t.positionNow(), delta, t.duration);
        nowPlaying.seekTo(target);
        // Show the new place at once; the session's own state update follows and replaces it.
        t.position = target;
        t.updateTime = SystemClock.elapsedRealtime();
        updateProgress();
        a.playTick();
    }

    // ---- Listen ----------------------------------------------------------------------------------

    private void buildListen() {
        listenLayer = new FrameLayout(a);
        listenLayer.setVisibility(View.GONE);
        listenLayer.setClipChildren(false);
        layer.addView(listenLayer, layer.indexOfChild(ribbon), full());
        bigArt = new ImageView(a);
        bigArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bigArt.setElevation(dp(18));
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp(320), dp(320), Gravity.START | Gravity.CENTER_VERTICAL);
        artLp.setMarginStart(dp(96));
        artLp.bottomMargin = dp(40);
        listenLayer.addView(bigArt, artLp);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        colLp.setMarginStart(dp(96 + 320 + 48));
        colLp.bottomMargin = dp(40);
        listenLayer.addView(col, colLp);
        bigTitle = text(36, display, TEXT);
        bigTitle.setMaxLines(2);
        bigTitle.setSingleLine(false);
        bigArtist = text(20, regular, 0xD9F5F6F8);
        bigAlbum = text(15, regular, 0x99F5F6F8);
        for (TextView t : new TextView[]{bigTitle, bigArtist, bigAlbum}) {
            t.setShadowLayer(dp(10), 0, dp(1), 0x40000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            col.addView(t, lp);
        }
        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctl.topMargin = dp(24);
        col.addView(controls, ctl);
        bigPrev = transport(controls, R.drawable.ic_skip_prev, "Previous", () -> nowPlaying.previous());
        bigToggle = transport(controls, R.drawable.ic_pause, "Play or pause", () -> nowPlaying.togglePlay());
        bigNext = transport(controls, R.drawable.ic_skip_next, "Next", () -> nowPlaying.next());
        TextView openApp = new TextView(a);
        openApp.setText("Open app");
        openApp.setTypeface(medium);
        openApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        openApp.setTextColor(a.getColorStateList(R.color.scenic_row_text));
        openApp.setGravity(Gravity.CENTER);
        openApp.setPadding(dp(22), 0, dp(22), 0);
        SceneGlassDrawable.apply(openApp, -1f, GlassTone.Kind.CONTROL);
        openApp.setFocusable(true);
        openApp.setClickable(true);
        LinearLayout.LayoutParams oa = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        oa.setMarginStart(dp(14));
        controls.addView(openApp, oa);
        openApp.setOnClickListener(v -> {
            if (!nowPlaying.sendSessionActivity()) {
                Intent i = nowPlaying.appIntent();
                if (i != null) a.openIntent(i);
            }
        });
        focusMotion(openApp);
    }

    private void openListen() {
        if (track == null || listening) return;
        if (listenLayer == null) buildListen();
        onNowPlaying(track);   // fills the big view
        setArt(bigArt, track.art, 24f);
        focusMemory.push("listen", a.getCurrentFocus());
        listening = true;
        // The same frost as All Apps over the aerial (the album wash needs none), fading in with the motion.
        dim.animate().cancel();
        dim.setBackground(washCovering() ? appsShade : appsFrost);
        dim.setAlpha(0f);
        dim.setVisibility(View.VISIBLE);
        listenLayer.setAlpha(0f);   // until the first frame of the animation places the art over the small one
        listenLayer.setVisibility(View.VISIBLE);
        listenLayer.post(() -> animateListen(true));
    }

    private void closeListen() {
        if (!listening) return;
        animateListen(false);
    }

    /**
     * Enter: the small cover grows into the big one (same centre-crop square, so the image does not jump),
     * its corner radius easing from 14dp to 28dp on screen; the group, header and dock step away; the ribbon
     * slides down into the dock's place. Exit runs the same animation backwards.
     */
    private void animateListen(boolean enter) {
        if (listenAnim != null) listenAnim.cancel();
        int[] from = new int[2], to = new int[2];
        nowArt.getLocationInWindow(from);
        float bx = bigArt.getLeft() + listenLayer.getLeft(), by = bigArt.getTop() + listenLayer.getTop();
        int[] layerLoc = new int[2];
        layer.getLocationInWindow(layerLoc);
        to[0] = (int) bx + layerLoc[0];
        to[1] = (int) by + layerLoc[1];
        float s0 = nowArt.getWidth() / (float) Math.max(1, bigArt.getWidth());
        float dx0 = from[0] - to[0], dy0 = from[1] - to[1];
        float ribbonDrop = dockFrame.getHeight() + dp(4);
        bigArt.setPivotX(0f);
        bigArt.setPivotY(0f);
        final float startT = enter ? 0f : 1f, endT = enter ? 1f : 0f;
        ValueAnimator v = ValueAnimator.ofFloat(startT, endT);
        v.setDuration(reducedMotion ? 160 : LISTEN_MS);
        v.setInterpolator(EASE);
        nowArt.setAlpha(0f);
        listenLayer.setAlpha(1f);
        v.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float s = s0 + (1f - s0) * t;
            bigArt.setScaleX(s);
            bigArt.setScaleY(s);
            bigArt.setTranslationX(dx0 * (1f - t));
            bigArt.setTranslationY(dy0 * (1f - t));
            float radiusOnScreen = dp(14) + (dp(28) - dp(14)) * t;
            bigArt.setTag(R.id.motion_z, radiusOnScreen / s);
            bigArt.invalidateOutline();
            float rest = Math.max(0f, (t - 0.35f) / 0.65f);
            for (int i = 0; i < listenLayer.getChildCount(); i++) {
                View c = listenLayer.getChildAt(i);
                if (c != bigArt) {
                    c.setAlpha(rest);
                    c.setTranslationY(dp(16) * (1f - rest));
                }
            }
            float away = Math.min(1f, t * 1.6f);
            nowGroup.setAlpha(1f - away);
            header.setAlpha(1f - away * 0.6f);
            dockFrame.setAlpha(1f - away);
            dockFrame.setTranslationY(dp(32) * away);
            ribbon.setTranslationY(ribbonDrop * t);
            ribbon.setColour(t);
            if (!appsShown) dim.setAlpha(t);
        });
        bigArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                Object r = view.getTag(R.id.motion_z);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r instanceof Float ? (Float) r : dp(28));
            }
        });
        bigArt.setClipToOutline(true);
        v.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                if (enter) {
                    dockFrame.setVisibility(View.INVISIBLE);
                    nowGroup.setVisibility(View.INVISIBLE);
                    (bigToggle.getVisibility() == View.VISIBLE ? bigToggle : listenLayer).requestFocus();
                    if (!bigToggle.isFocused()) focusFirst(listenLayer);
                } else {
                    finishListenClose();
                }
            }
        });
        if (!enter) {
            dockFrame.setVisibility(View.VISIBLE);
            nowGroup.setVisibility(View.VISIBLE);
        }
        listenAnim = v;
        v.start();
    }

    private void finishListenClose() {
        listening = false;
        if (listenLayer != null) listenLayer.setVisibility(View.GONE);
        nowArt.setAlpha(1f);
        nowGroup.setAlpha(1f);
        nowGroup.setVisibility(track != null && !appsShown ? View.VISIBLE : View.GONE);
        header.setAlpha(1f);
        dockFrame.setAlpha(1f);
        dockFrame.setTranslationY(0f);
        dockFrame.setVisibility(appsShown ? View.GONE : View.VISIBLE);
        ribbon.setTranslationY(0f);
        ribbon.setColour(0f);
        if (!appsShown) {
            dim.setAlpha(0f);
            dim.setVisibility(View.GONE);
        }
        View back = focusMemory.top() != null && "listen".equals(focusMemory.top()) ? focusMemory.pop() : null;
        if (back != null && back.isAttachedToWindow() && back.getVisibility() == View.VISIBLE && back.isFocusable()) back.requestFocus();
        else if (track != null) btnListen.requestFocus();
        else focusDock(lastDockPos);
    }

    private boolean listenKey(View f, int code) {
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && f != ribbon && ribbon.isSeekable() && ribbon.getVisibility() == View.VISIBLE) {
            ribbon.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && f == ribbon) {
            focusFirst(listenLayer);
            return true;
        }
        if (isDpad(code) && f != ribbon) {
            View next = f == null ? null : f.focusSearch(direction(code));
            return next == null || !isInside(next, listenLayer);
        }
        return false;
    }

    // ---- Settings panel --------------------------------------------------------------------------

    private void buildPanel() {
        panel = new FrameLayout(a);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(0, dp(24), dp(24), dp(24));
        layer.addView(panel, lp);
        SceneGlassDrawable.apply(panel, 28f, GlassTone.Kind.PANEL);
        panel.setClipToOutline(true);
        panel.setElevation(dp(24));
        LinearLayout shell = new LinearLayout(a);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(24), dp(16), dp(16));
        panel.addView(shell, full());
        panelTitle = text(26, display, TEXT);
        panelTitle.setText(R.string.scenic_settings);
        panelTitle.setPadding(dp(16), 0, 0, dp(12));
        shell.addView(panelTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        // The heading is outside the scroll area. Requesting focus on row one can no
        // longer scroll the heading off the top or remove the breathing room above it.
        ScrollView scroll = new ScrollView(a);
        panelScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        panelRows = new LinearLayout(a);
        panelRows.setOrientation(LinearLayout.VERTICAL);
        panelRows.setPadding(0, dp(10), 0, dp(30));
        scroll.addView(panelRows);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void fillPanel() {
        panelRows.removeAllViews();
        // System first: the TV's own settings (Chromecast/Google TV), network and inputs.
        panelRows.addView(row(a.getString(R.string.scenic_tv_settings), null, () -> { closePanelNow(); a.openTvSettings(); }));
        panelRows.addView(row(a.getString(R.string.scenic_network), null, () -> { closePanelNow(); a.openNetworkSettings(); }));
        if (a.hasInputs()) panelRows.addView(row(a.getString(R.string.scenic_inputs), null, () -> { closePanelNow(); a.showInputsMenu(); }));
        section("Home");
        panelRows.addView(row(a.getString(R.string.scenic_home), null, () -> { closePanelNow(); goHome(); }));
        panelRows.addView(row(a.getString(R.string.scenic_apps), null, () -> { closePanelNow(); showApps(true, true); }));
        panelRows.addView(row(a.getString(R.string.scenic_search), null, this::openSearch));
        panelRows.addView(row(a.getString(R.string.scenic_discover), null, this::openDiscover));
        panelRows.addView(row(a.getString(R.string.scenic_my_space), null, this::openMySpace));
        section("Look & feel");
        panelRows.addView(row(a.getString(R.string.scenic_wallpapers), null, this::openWallpapers));
        panelRows.addView(row(a.getString(R.string.scenic_appearance), null, this::openAppearance));
        panelRows.addView(row(a.getString(R.string.scenic_favorites), null, () -> {
            closePanelNow();
            showApps(true, true);
            Toast.makeText(a, R.string.scenic_fav_hint, Toast.LENGTH_LONG).show();
        }));
        panelRows.addView(row(a.getString(R.string.scenic_sounds), null, () -> { closePanelNow(); a.showSounds(); }));
        section("Home features");
        panelRows.addView(row(a.getString(R.string.scenic_weather), weather.configured()
                ? weather.place() + (Weather.CELSIUS.equals(weather.unit()) ? "  °C" : "  °F") : "Off", this::openWeather));
        panelRows.addView(row(a.getString(R.string.scenic_music_access), NowPlaying.hasAccess(a) ? "On" : "Needs access", this::openMusicAccess));
        section("More");
        panelRows.addView(row(a.getString(R.string.scenic_reorder), null, () -> {
            closePanelNow();
            showApps(true, true);
            a.startReorder();
        }));
        panelRows.addView(row(a.getString(R.string.scenic_sleep), null, () -> {
            closePanelNow();
            if (!SleepService.sleep()) Toast.makeText(a, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
        }));
        panelRows.addView(row(a.getString(R.string.scenic_backup), null, this::openBackup));
        panelRows.addView(row(a.getString(R.string.scenic_advanced), null, () -> { closePanelNow(); a.openLauncherSettings(); }));
    }

    private void section(String name) {
        TextView t = text(13, medium, 0x8CF5F6F8);
        t.setText(name.toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(16), dp(18), 0, dp(6));
        panelRows.addView(t);
    }

    void openPanel() {
        if (panel.getVisibility() == View.VISIBLE) return;
        fillPanel();
        focusMemory.push("panel", a.getCurrentFocus());
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationX(dp(48));
        panel.animate().alpha(1f).translationX(0f).setDuration(reducedMotion ? 120 : PANEL_MS).setInterpolator(EASE)
                .withLayer().start();
        a.playOpenSound();
        panelScroll.scrollTo(0, 0);
        panelRows.getChildAt(0).requestFocus();
    }

    private void closePanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.animate().cancel();
        panel.animate().alpha(0f).translationX(dp(48)).setDuration(reducedMotion ? 100 : 260).setInterpolator(EASE)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        restoreFocus("panel");
    }

    /** Closes the panel at once (an action leaves Home or opens another layer). */
    private void closePanelNow() {
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
        restoreFocus("panel");
    }

    // ---- Control Center: actual TV shortcuts, never simulated Wi-Fi/audio toggles -----------------

    private void buildControlCenter() {
        controlCenter = new ControlCenter(a, layer, regular, medium, reducedMotion, new ControlCenter.Actions() {
            @Override public void network() { quickAction(ScenicHome.this::openQuickNetwork); }
            @Override public void sound() { quickAction(() -> a.openIntent(new Intent(Settings.ACTION_SOUND_SETTINGS))); }
            @Override public boolean hasInputs() { return a.hasInputs(); }
            @Override public void inputs() { quickAction(a::showInputsMenu); }
            @Override public void scenery() { quickAction(ScenicHome.this::openWallpapers); }
            @Override public void weather() { quickAction(ScenicHome.this::openWeather); }
            @Override public void sleep() {
                quickAction(() -> {
                    if (!SleepService.sleep()) Toast.makeText(a, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
                });
            }
            @Override public void tvSettings() { quickAction(a::openTvSettings); }
            @Override public void listen() { quickAction(ScenicHome.this::openCurrentMediaApp); }
            @Override public void musicAccess() { quickAction(ScenicHome.this::openMusicAccess); }
            // Transport keeps the sheet open: the same session commands as the Home buttons.
            @Override public void previous() { nowPlaying.previous(); }
            @Override public void togglePlay() { nowPlaying.togglePlay(); }
            @Override public void next() { nowPlaying.next(); }
        });
    }

    /** The Control Center's full media card opens the owning app; Home's Listen action is separate. */
    private void openCurrentMediaApp() {
        if (track == null || track.pkg == null) { openMusicAccess(); return; }
        AppAdapter.Entry entry = byPkg.get(track.pkg);
        if (entry != null) { launch(entry); return; }
        Intent i = a.getPackageManager().getLeanbackLaunchIntentForPackage(track.pkg);
        if (i == null) i = a.getPackageManager().getLaunchIntentForPackage(track.pkg);
        if (i != null) a.openIntent(i);
        else openListen();
    }

    /** Read-only connectivity page, with a real system Wi-Fi management action on unprivileged TVs. */
    private void openQuickNetwork() {
        openScreen(a.getString(R.string.scenic_network), this::openQuickNetwork);
        NetworkSummary state = networkSummary();
        heading("Connection");
        note(state.title + " · " + state.detail);
        note("Wi-Fi power and joining networks require TV system permissions. "
                + "Use the device's network controls to connect or disconnect.");
        screenContent.addView(row("Manage Wi-Fi networks", null,
                () -> { closeScreenNow(); a.openNetworkSettings(); }));
        focusFirstRow();
    }

    /** Only while the sheet is open; opening it syncs first. */
    private void syncControlCenterMedia() {
        if (controlCenter == null || !controlCenter.isOpen()) return;
        NowPlaying.Track t = track;
        if (t == null) {
            controlCenter.setTrack(null, null, null, false, false, false, false);
            return;
        }
        boolean playing = MediaTime.advancing(t.state) || t.state == MediaTime.STATE_BUFFERING;
        controlCenter.setTrack(t.title, t.artist, t.art, playing,
                MediaTime.can(t.actions, MediaTime.ACTION_SKIP_TO_PREVIOUS),
                MediaTime.canToggle(t.actions, t.state),
                MediaTime.can(t.actions, MediaTime.ACTION_SKIP_TO_NEXT));
    }

    private void toggleControlCenter() {
        if (controlCenter.isOpen()) { closeControlCenter(); return; }
        focusMemory.push("quick", a.getCurrentFocus());
        controlCenter.setNetwork(networkSummary());
        controlCenter.setSoundOutput(soundOutputLabel());
        controlCenter.setFooter(controlCenterFooter());
        controlCenter.open();
        syncControlCenterMedia();
        a.playOpenSound();
    }

    /** Best available connected output label; routing decisions remain the TV's responsibility. */
    private String soundOutputLabel() {
        android.media.AudioManager audio = a.getSystemService(android.media.AudioManager.class);
        if (audio == null) return "TV audio";
        android.media.AudioDeviceInfo[] devices = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
        String output = "TV speakers";
        for (android.media.AudioDeviceInfo device : devices) {
            switch (device.getType()) {
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    return device.getProductName() == null ? "Bluetooth audio" : device.getProductName().toString();
                case android.media.AudioDeviceInfo.TYPE_HDMI_ARC:
                case android.media.AudioDeviceInfo.TYPE_HDMI_EARC:
                    output = "HDMI audio";
                    break;
                case android.media.AudioDeviceInfo.TYPE_HDMI:
                    output = "HDMI output";
                    break;
                default:
                    break;
            }
        }
        return output;
    }

    private void closeControlCenter() {
        controlCenter.close(true);
        restoreFocus("quick");
    }

    private void closeControlCenterNow() {
        controlCenter.close(false);
        restoreFocus("quick");
    }

    private void quickAction(Runnable action) {
        closeControlCenterNow();
        action.run();
    }

    /** Date and, when set up, the weather line: the sheet's quiet information row. */
    private String controlCenterFooter() {
        java.util.Locale locale = a.getResources().getConfiguration().getLocales().get(0);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM");
        String date = new java.text.SimpleDateFormat(pattern, locale).format(new java.util.Date());
        CharSequence w = weatherView.getVisibility() == View.VISIBLE ? weatherView.getText() : "";
        return w.length() == 0 ? date : date + "  ·  " + w;
    }

    /** The same reading as the classic Home's network pill: state, and the network name when Android shares it. */
    @SuppressWarnings("deprecation")
    private NetworkSummary networkSummary() {
        boolean online = false, validated = false, ethernet = false, wifi = false;
        String ssid = null;
        try {
            android.net.ConnectivityManager cm = a.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network net : cm != null ? cm.getAllNetworks() : new android.net.Network[0]) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (wifi && !ethernet) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                ssid = info != null ? info.getSSID() : null;
            }
        } catch (RuntimeException ignored) {
            // A TV without the service, or a SecurityException: the capsule falls back to "Network settings".
        }
        return NetworkSummary.of(online, validated, ethernet, wifi, ssid);
    }

    private void restoreFocus(String layerName) {
        if (!layerName.equals(focusMemory.top())) return;
        View back = focusMemory.pop();
        if (back != null && back.isAttachedToWindow() && back.isShown() && back.isFocusable()) back.requestFocus();
        else if (appsShown) a.focusGridAt(-1);
        else focusDock(lastDockPos);
    }

    // ---- secondary screens -------------------------------------------------------------------------

    private void buildScreen() {
        screen = new FrameLayout(a);
        screen.setVisibility(View.GONE);
        screen.setBackgroundColor(0x33000000);
        // This entire layer must be above the dock's elevated RecyclerView. Raising
        // only the child box does not beat the dock's parent Z-order on Android TV 11.
        screen.setElevation(dp(32));
        layer.addView(screen, full());
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(36), dp(28), dp(36), dp(20));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(40), 0, dp(40));
        screen.addView(box, lp);
        SceneGlassDrawable.apply(box, 30f, GlassTone.Kind.PANEL);
        box.setClipToOutline(true);
        box.setElevation(dp(24));
        screenTitle = text(30, display, TEXT);
        box.addView(screenTitle);
        ScrollView scroll = new ScrollView(a);
        screenScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        screenContent = new LinearLayout(a);
        screenContent.setOrientation(LinearLayout.VERTICAL);
        screenContent.setPadding(0, dp(16), 0, dp(8));
        scroll.addView(screenContent);
        // Give the scrollable list all space beneath the fixed heading.
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(scroll, sl);
    }

    /**
     * Shows a secondary screen. {@code reopen} rebuilds it when Back returns to it; null marks a transient
     * screen (a download in progress) that Back skips. See {@link ScreenStack} for refresh/unwind rules.
     */
    private void openScreen(String title, Runnable reopen) {
        screenToken++;
        boolean fromPanel = panel.getVisibility() == View.VISIBLE;
        if (fromPanel) {
            panelFocusIndex = indexOfFocused(panelRows);
            panel.animate().cancel();
            panel.setVisibility(View.GONE);
        }
        boolean wasOpen = screen.getVisibility() == View.VISIBLE && screens.depth() > 0;
        if (wasOpen && !navigatingBack) saveScreenState(screens.top());
        if (!wasOpen) {
            screens.clear();
            screenFromPanel = fromPanel;
            // Opened from Settings, Back returns to Settings: its own focus record stays on top of the memory.
            if (!fromPanel) focusMemory.push("screen", a.getCurrentFocus());
            screen.animate().cancel();
            screen.setVisibility(View.VISIBLE);
            screen.setAlpha(0f);
            screen.setScaleX(0.985f);
            screen.setScaleY(0.985f);
            screen.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(reducedMotion ? 120 : PANEL_MS)
                    .setInterpolator(EASE).withLayer().start();
        }
        ScreenStack.Move move = screens.enter(title, reopen);
        ScreenStack.Entry<Runnable> top = screens.top();
        boolean restoring = navigatingBack || move == ScreenStack.Move.REFRESH || move == ScreenStack.Move.POP_TO;
        pendingRestore = restoring ? new int[]{top.scrollY, top.focusIndex} : null;
        if (move == ScreenStack.Move.PUSH) slideContent(1);
        else if (navigatingBack || move == ScreenStack.Move.POP_TO) slideContent(-1);
        navigatingBack = false;
        screenTitle.setText(title);
        screenContent.removeAllViews();
        screenScroll.scrollTo(0, 0);
    }

    /** One level deeper slides in from the right; back slides in from the left. Reduced motion: a fade. */
    private void slideContent(int direction) {
        for (View v : new View[]{screenTitle, screenScroll}) {
            v.animate().cancel();
            v.setAlpha(0.2f);
            v.setTranslationX(reducedMotion ? 0f : dp(18) * direction);
            v.animate().alpha(1f).translationX(0f).setDuration(reducedMotion ? 100 : 240).setInterpolator(EASE).start();
        }
    }

    private void saveScreenState(ScreenStack.Entry<Runnable> e) {
        if (e == null) return;
        e.scrollY = screenScroll.getScrollY();
        e.focusIndex = indexOfFocused(screenContent);
    }

    /** Index of the direct child of {@code g} that holds focus, or -1. */
    private int indexOfFocused(ViewGroup g) {
        View f = a.getCurrentFocus();
        for (int i = 0; f != null && i < g.getChildCount(); i++) if (isInside(f, g.getChildAt(i))) return i;
        return -1;
    }

    /** Back inside the screen layer: the previous level, then Settings (if it opened the screen), then Home. */
    private void backScreen() {
        hideKeyboard();
        ScreenStack.Entry<Runnable> parent = screens.back();
        if (parent == null) {
            closeScreen();
            return;
        }
        navigatingBack = true;
        try {
            parent.reopen.run();
        } finally {
            navigatingBack = false;
        }
    }

    private void closeScreen() {
        screenToken++; // Ignore any late gallery/network callbacks from the closed screen.
        hideKeyboard();
        screens.clear();
        pendingRestore = null;
        screen.animate().cancel();
        screen.animate().alpha(0f).setDuration(reducedMotion ? 100 : 220)
                .withEndAction(() -> { screen.setVisibility(View.GONE); screenContent.removeAllViews(); }).start();
        if (screenFromPanel) returnToPanel();
        else restoreFocus("screen");
    }

    /** Leaves the screen layer at once, for an action that leaves Home or opens another layer. */
    private void closeScreenNow() {
        screenToken++;
        hideKeyboard();
        screens.clear();
        pendingRestore = null;
        screen.animate().cancel();
        screen.setVisibility(View.GONE);
        screenContent.removeAllViews();
        boolean fromPanel = screenFromPanel;
        screenFromPanel = false;
        restoreFocus(fromPanel ? "panel" : "screen");
    }

    /** Settings comes back as it was left: same scroll, the row that opened the screen focused. */
    private void returnToPanel() {
        screenFromPanel = false;
        fillPanel();   // statuses (weather, music access) may have changed; the rows and their order do not
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationX(reducedMotion ? 0f : -dp(12));
        panel.animate().alpha(1f).translationX(0f).setDuration(reducedMotion ? 100 : 240).setInterpolator(EASE)
                .withLayer().start();
        View row = panelFocusIndex >= 0 && panelFocusIndex < panelRows.getChildCount()
                ? panelRows.getChildAt(panelFocusIndex) : null;
        if (row == null || !row.isFocusable() || !row.requestFocus()) focusFirst(panelRows);
    }

    /** First row, or, when coming back to this screen, the row and scroll it was left at. */
    private void focusFirstRow() {
        final int[] restore = pendingRestore;
        pendingRestore = null;
        screenContent.post(() -> {
            if (restore == null || !restoreScreenFocus(restore[1])) {
                focusFirst(screenContent);
                return;
            }
            // After layout and before the first frame: the restored scroll never shows a jump.
            screenScroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    screenScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    screenScroll.scrollTo(0, restore[0]);
                    return true;
                }
            });
        });
    }

    private boolean restoreScreenFocus(int index) {
        if (index < 0) return false;
        for (int i = Math.min(index, screenContent.getChildCount() - 1); i >= 0; i--) {
            View c = screenContent.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (c.isFocusable() && c.requestFocus()) return true;
            if (c instanceof ViewGroup && focusFirst((ViewGroup) c)) return true;
        }
        return false;
    }

    private static boolean focusFirst(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (c.isFocusable()) { c.requestFocus(); return true; }
            if (c instanceof ViewGroup && focusFirst((ViewGroup) c)) return true;
        }
        return false;
    }

    private void note(String s) {
        TextView t = text(16, regular, 0xB3F5F6F8);
        t.setSingleLine(false);
        t.setText(s);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(8), dp(16), dp(10));
        screenContent.addView(t);
    }

    private void heading(String s) {
        TextView t = text(13, medium, 0x8CF5F6F8);
        t.setText(s.toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(16), dp(16), 0, dp(6));
        screenContent.addView(t);
    }

    // Search: installed apps and My Space titles, typed with the TV keyboard.
    private void openSearch() {
        openScreen(a.getString(R.string.scenic_search), this::openSearch);
        EditText field = field("Apps and saved titles");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        screenContent.addView(field, fieldLp());
        LinearLayout results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        screenContent.addView(results);
        List<MySpace.Title> titles = MySpace.load(a);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { fillSearch(results, s.toString(), titles); }
        });
        fillSearch(results, "", titles);
        field.requestFocus();
        showKeyboard(field);
    }

    private void fillSearch(LinearLayout results, String q, List<MySpace.Title> titles) {
        results.removeAllViews();
        String query = q.trim().toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty()) return;
        int n = 0;
        for (AppAdapter.Entry e : a.allEntries()) {
            if (!e.label.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            results.addView(appRow(e));
            if (++n >= 12) break;
        }
        for (MySpace.Title t : MySpace.search(titles, query)) results.addView(titleRow(t, false));
        if (results.getChildCount() == 0) {
            TextView none = text(16, regular, 0x99F5F6F8);
            none.setText("Nothing on this TV matches “" + q.trim() + "”.");
            none.setPadding(dp(16), dp(12), 0, 0);
            results.addView(none);
        }
    }

    // Discover: only what this TV knows: saved titles and recently opened apps. No catalogue is invented.
    private void openDiscover() {
        openScreen(a.getString(R.string.scenic_discover), this::openDiscover);
        List<MySpace.Title> titles = MySpace.load(a);
        List<AppAdapter.Entry> recent = new ArrayList<>();
        for (String p : Recents.load(a)) {
            AppAdapter.Entry e = byPkg.get(p);
            if (e != null) recent.add(e);
        }
        if (titles.isEmpty() && recent.isEmpty()) {
            note("Nothing to show yet. Discover lists what is on this TV: titles you save in My Space and the apps you "
                    + "open from Home. It does not download a catalogue or recommendations.");
            screenContent.addView(row(a.getString(R.string.scenic_apps), null, () -> { closeScreenNow(); showApps(true, true); }));
            screenContent.addView(row(a.getString(R.string.scenic_my_space), null, this::openMySpace));
            focusFirstRow();
            return;
        }
        if (!titles.isEmpty()) {
            heading("From My Space");
            for (int i = 0; i < Math.min(6, titles.size()); i++) screenContent.addView(titleRow(titles.get(i), false));
        }
        if (!recent.isEmpty()) {
            heading("Recently opened");
            for (AppAdapter.Entry e : recent) screenContent.addView(appRow(e));
        }
        focusFirstRow();
    }

    // My Space: local titles in collections.
    private void openMySpace() {
        openScreen(a.getString(R.string.scenic_my_space), this::openMySpace);
        List<MySpace.Title> titles = MySpace.load(a);
        screenContent.addView(row("Add a title", null, this::openAddTitle));
        if (titles.isEmpty()) {
            note("Save shows, films or albums here to find them again, grouped in collections. Titles stay on this "
                    + "TV. A title can open its app, or a link the app understands.");
        } else {
            for (Map.Entry<String, List<MySpace.Title>> c : MySpace.byCollection(titles).entrySet()) {
                heading(c.getKey());
                for (MySpace.Title t : c.getValue()) screenContent.addView(titleRow(t, true));
            }
            note("Hold OK on a title to remove it.");
        }
        focusFirstRow();
    }

    private void openAddTitle() {
        openScreen("Add a title", this::openAddTitle);
        EditText title = field("Title");
        EditText collection = field("Collection (" + MySpace.DEFAULT_COLLECTION + ")");
        EditText link = field("Link (optional)");
        screenContent.addView(title, fieldLp());
        screenContent.addView(collection, fieldLp());
        final List<AppAdapter.Entry> apps = new ArrayList<>(dockAdapter.items);
        for (AppAdapter.Entry e : a.allEntries()) if (!apps.contains(e)) apps.add(e);
        final int[] app = {-1};
        View[] holder = new View[1];
        holder[0] = row("Opens with", "None", () -> {
            app[0] = apps.isEmpty() ? -1 : (app[0] + 1) % (apps.size() + 1);
            if (app[0] == apps.size()) app[0] = -1;
            ((TextView) holder[0].findViewWithTag("status")).setText(app[0] < 0 ? "None" : apps.get(app[0]).label);
        });
        screenContent.addView(holder[0]);
        screenContent.addView(link, fieldLp());
        screenContent.addView(row("Save", null, () -> {
            String t = title.getText().toString().trim();
            if (t.isEmpty()) {
                Toast.makeText(a, "Type a title first", Toast.LENGTH_SHORT).show();
                title.requestFocus();
                return;
            }
            List<MySpace.Title> all = MySpace.load(a);
            all.add(0, new MySpace.Title(t, collection.getText().toString(), app[0] >= 0 ? apps.get(app[0]).pkg : "",
                    link.getText().toString()));
            MySpace.save(a, all);
            hideKeyboard();
            openMySpace();
        }));
        note("OK on “Opens with” picks the next app. A link (for example one copied from the app) opens in that app.");
        title.requestFocus();
        showKeyboard(title);
    }

    private View titleRow(MySpace.Title t, boolean removable) {
        AppAdapter.Entry e = t.pkg.isEmpty() ? null : byPkgOrAll(t.pkg);
        View r = row(t.title, e != null ? e.label : t.collection, () -> openTitle(t));
        if (removable) {
            r.setOnLongClickListener(v -> {
                List<MySpace.Title> all = MySpace.load(a);
                all.remove(t);
                MySpace.save(a, all);
                Toast.makeText(a, "Removed “" + t.title + "”", Toast.LENGTH_SHORT).show();
                openMySpace();
                return true;
            });
        }
        return r;
    }

    private AppAdapter.Entry byPkgOrAll(String pkg) {
        AppAdapter.Entry e = byPkg.get(pkg);
        return e;
    }

    private void openTitle(MySpace.Title t) {
        if (!t.uri.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!t.pkg.isEmpty()) i.setPackage(t.pkg);
            if (i.resolveActivity(a.getPackageManager()) != null) {
                closeScreenNow();
                a.openIntent(i);
                return;
            }
        }
        AppAdapter.Entry e = t.pkg.isEmpty() ? null : byPkg.get(t.pkg);
        if (e != null) {
            closeScreenNow();
            launch(e);
            return;
        }
        Toast.makeText(a, t.pkg.isEmpty() ? "No app is set for this title" : "Its app is not installed", Toast.LENGTH_SHORT).show();
    }

    private void openWallpapers() {
        openScreen(a.getString(R.string.scenic_wallpapers), this::openWallpapers);
        SharedPreferences p = prefs();
        String choice = p.getString(WALLPAPER_KEY, "auto");
        boolean aerials = !AerialBackdrop.catalog(a).isEmpty();
        heading("Motion scenery");
        screenContent.addView(choiceRow("Aerial video", aerials ? null : "None on this TV", "auto".equals(choice) && aerials, () -> {
            if (!aerials) { closeScreenNow(); a.showAerialDownloads(); return; }
            boolean wasStill = !"auto".equals(choice);
            p.edit().putString(WALLPAPER_KEY, "auto").apply();
            // From the built-in still there is no aerial attached: restart Home so the chosen one plays (the
            // picker only restarts when the file changes). Already on aerials: pick another one.
            if (wasStill) { a.recreate(); return; }
            closeScreenNow();
            a.showAerialPicker();
        }));
        screenContent.addView(choiceRow("Built-in scenery", null,
                "still".equals(choice) || ("auto".equals(choice) && !aerials), () -> {
            p.edit().putString(WALLPAPER_KEY, "still").apply();
            a.recreate();
        }));
        screenContent.addView(row("Download Apple aerials", null, () -> { closeScreenNow(); a.showAerialDownloads(); }));
        screenContent.addView(row("Manage downloaded aerial videos", null,
                () -> { closeScreenNow(); a.showDownloadedAerials(); }));
        if (a.legacyScenicPhotoBytes() > 0) screenContent.addView(row(
                a.getString(R.string.scenic_legacy_photos_cleanup), null, a::offerLegacyScenicPhotoCleanup));
        screenContent.addView(row("Brazil · drone videos", null, this::openBrazilVideos));
        heading("Background while music plays");
        String musicBackdrop = p.getString(MUSIC_BACKDROP_KEY, "aerial");
        screenContent.addView(choiceRow("Keep the aerial video", "Default",
                !"art".equals(musicBackdrop), () -> chooseMusicBackdrop("aerial")));
        screenContent.addView(choiceRow("Lightly blurred album artwork", null,
                "art".equals(musicBackdrop), () -> chooseMusicBackdrop("art")));
        note("Aerials are played without an audio track when the TV supports video-only playback. "
                + "If the TV cannot isolate the video, album artwork replaces it automatically during music "
                + "to protect Spotify's mixing effects. You can also choose artwork all the time to reduce "
                + "video decoding. Pausing music returns to the selected wallpaper.");
        focusFirstRow();
    }

    private void chooseMusicBackdrop(String mode) {
        prefs().edit().putString(MUSIC_BACKDROP_KEY, mode).apply();
        // Re-evaluate the active session immediately; no launcher restart or audio interruption.
        if (track != null) onNowPlaying(track);
        openWallpapers();
    }

    private void openBrazilVideos() {
        openScreen("Brazil · drone videos", this::openBrazilVideos);
        List<String[]> installed = AerialBackdrop.catalog(a);
        int brazilCount = 0;
        for (String[] video : installed) if (video[0].startsWith("br_")) brazilCount++;
        final int count = brazilCount;
        screenContent.addView(row("Play Brazil videos · Rotate", count + " downloaded", () -> {
            if (count == 0) { Toast.makeText(a, "Download a video first", Toast.LENGTH_SHORT).show(); return; }
            prefs().edit().putString(WALLPAPER_KEY, "auto").apply();
            AerialBackdrop.choose(a, AerialBackdrop.CYCLE_BRAZIL);
            a.recreate();
        }));
        heading("Download free-licensed drone footage");
        screenContent.addView(row("Explore videos across Brazil", null, () -> openBrazilVideoSources("all")));
        screenContent.addView(row("Explore videos from Minas Gerais", null, () -> openBrazilVideoSources("minas")));
        screenContent.addView(row("Video credits & licences", null, this::openBrazilVideoCredits));
        note("The Wikimedia Commons drone video collection currently has limited cities. Salvador has a 4K "
                + "clip, but footage from every Brazilian capital is not available. "
                + "WebM playback depends on the TV's video decoder. Remove downloaded clips under Wallpapers "
                + "→ Manage downloaded aerial videos.");
        heading("Downloaded on this TV");
        for (String[] video : installed) {
            if (!video[0].startsWith("br_")) continue;
            screenContent.addView(row(video[1], "Play this video", () -> {
                prefs().edit().putString(WALLPAPER_KEY, "auto").apply();
                AerialBackdrop.choose(a, video[0]);
                a.recreate();
            }));
        }
        focusFirstRow();
    }

    private void openBrazilVideoCredits() {
        openScreen("Brazil videos · credits", this::openBrazilVideoCredits);
        File dir = a.getExternalFilesDir("aerials");
        File index = dir == null ? null : new File(dir, "aerials.json");
        int n = 0;
        try {
            if (index != null && index.isFile() && index.length() < 128 * 1024) {
                String content = new String(java.nio.file.Files.readAllBytes(index.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                org.json.JSONArray entries = new org.json.JSONArray(content);
                for (int i = 0; i < entries.length(); i++) {
                    org.json.JSONObject video = entries.optJSONObject(i);
                    if (video == null || !video.optString("file").startsWith("br_")) continue;
                    heading(video.optString("title", "Brazil aerial"));
                    note(video.optString("credit", "") + "\n" + video.optString("source", ""));
                    n++;
                }
            }
        } catch (Exception ignored) { note("Could not read the current credit index."); }
        if (n == 0) note("No Brazil video credits saved yet.");
        screenContent.addView(row("Back to Brazil videos", null, this::openBrazilVideos));
        focusFirstRow();
    }

    private void openBrazilVideoSources(String region) {
        openScreen("Brazil · available drone clips", () -> openBrazilVideoSources(region));
        note("Loading the Wikimedia Commons free-licensed video catalog…");
        int generation = screenToken;
        worker.execute(() -> {
            List<BrazilAerials.Clip> clips = null;
            String error = null;
            try { clips = BrazilAerials.browse(region); }
            catch (Exception e) { error = e.getMessage(); }
            List<BrazilAerials.Clip> results = clips;
            String failure = error;
            handler.post(() -> {
                if (released || generation != screenToken) return;
                screenContent.removeAllViews();
                if (failure != null) note("Could not load the catalog: " + failure);
                else if (results == null || results.isEmpty()) note("No licensed clips matched. Try a different region.");
                else for (BrazilAerials.Clip clip : results) {
                    String quality = clip.height >= 2160 ? "4K" : clip.height >= 1080 ? "Full HD" : "HD";
                    View r = row(clip.title, quality, () -> downloadBrazilVideo(clip));
                    r.setContentDescription(clip.title + ". " + clip.credit + ". " + quality);
                    screenContent.addView(r);
                }
                note("Each video is downloaded only after you select it. Clips can be large (up to 180 MiB) "
                        + "and keep their source and attribution in aerials.json.");
                screenContent.addView(row("Back to Brazil", null, this::openBrazilVideos));
                focusFirstRow();
            });
        });
    }

    private void downloadBrazilVideo(BrazilAerials.Clip clip) {
        openScreen("Downloading Brazil aerial", null);
        note(clip.title + "\n" + clip.credit + "\n" + clip.source);
        TextView progress = text(18, medium, TEXT);
        progress.setText("Downloading… (up to 180 MiB)");
        progress.setPadding(dp(16), dp(20), 0, dp(20));
        screenContent.addView(progress);
        int generation = screenToken;
        worker.execute(() -> {
            String error = null;
            try {
                BrazilAerials.save(a, clip, (bytes, total) -> handler.post(() -> {
                    if (!released && generation == screenToken) {
                        String progressLabel = (bytes / (1024 * 1024)) + " MiB downloaded";
                        if (total > 0) progressLabel += " / " + (total / (1024 * 1024)) + " MiB";
                        progress.setText(progressLabel);
                    }
                }));
            } catch (Exception e) { error = e.getMessage(); }
            String failure = error;
            handler.post(() -> {
                if (released || generation != screenToken) return;
                if (failure == null) openBrazilVideos();
                else {
                    progress.setText("Download failed: " + failure);
                    screenContent.addView(row("Retry", null, () -> downloadBrazilVideo(clip)));
                    focusFirstRow();
                }
            });
        });
    }

    private void openAppearance() {
        openScreen(a.getString(R.string.scenic_appearance), this::openAppearance);
        SharedPreferences choices = a.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean reduced = cfg.reducedMotion;
        screenContent.addView(row("Choose one of 17 themes", null, () -> {
            closeScreenNow();
            a.openThemeGallery();
        }));
        screenContent.addView(row("Reduced motion", reduced ? "On" : "Off", () -> {
            choices.edit().putBoolean(Config.REDUCED_MOTION_KEY, !reduced).apply();
            a.recreate();
        }));
        String glass = prefs().getString(GLASS_KEY, "clear");
        screenContent.addView(row("Glass", "clear".equals(glass) ? "Clear" : "Frosted", () -> {
            prefs().edit().putString(GLASS_KEY, "clear".equals(glass) ? "frosted" : "clear").apply();
            a.recreate();
        }));
        screenContent.addView(row("All Apps button in Dock", showAllAppsDock() ? "On" : "Off", () -> {
            if (showAllAppsDock() && dockAdapter.favouriteCount() == 0) {
                Toast.makeText(a, R.string.scenic_hide_allapps_requires_favourite, Toast.LENGTH_LONG).show();
                return;
            }
            prefs().edit().putBoolean(SHOW_ALL_APPS_DOCK_KEY, !showAllAppsDock()).apply();
            dockAdapter.notifyDataSetChanged();
            openAppearance();
        }));
        boolean lightFrost = prefs().getBoolean(MUSIC_FROST_KEY, false);
        screenContent.addView(row("Lighter glass with music", lightFrost ? "On" : "Off", () -> {
            prefs().edit().putBoolean(MUSIC_FROST_KEY, !lightFrost).apply();
            a.recreate();
        }));
        screenContent.addView(row("Home layout", "Scenic Glass", () -> {
            choices.edit().putBoolean(Config.SCENIC_KEY, false).apply();
            a.recreate();
        }));
        note("Reduced motion shortens and flattens every animation. Home layout switches back to the classic grid "
                + "Home; its Settings menu has no way back yet, so use Advanced launcher settings or clear the app's "
                + "data to return to Scenic Glass.");
        focusFirstRow();
    }

    private void openWeather() {
        openScreen(a.getString(R.string.scenic_weather), this::openWeather);
        heading("Temperature units");
        screenContent.addView(choiceRow("Celsius (°C)", null, Weather.CELSIUS.equals(weather.unit()),
                () -> chooseWeatherUnit(Weather.CELSIUS)));
        screenContent.addView(choiceRow("Fahrenheit (°F)", null, Weather.FAHRENHEIT.equals(weather.unit()),
                () -> chooseWeatherUnit(Weather.FAHRENHEIT)));
        heading("City");
        note(weather.configured() ? "Showing the weather for " + weather.place() + ", from Open-Meteo, refreshed at most "
                + "every 30 minutes while Home is in front." : "Weather is off. Type a city to show its current "
                + "conditions in the top left. Uses Open-Meteo (free, no account); nothing is sent but the city name "
                + "and its coordinates.");
        EditText city = field("City");
        city.setText(weather.place());
        screenContent.addView(city, fieldLp());
        screenContent.addView(row("Save", null, () -> {
            String q = city.getText().toString().trim();
            if (q.isEmpty()) return;
            hideKeyboard();
            int generation = screenToken;
            weather.setPlace(q, name -> {
                if (name == null) {
                    Toast.makeText(a, "Could not find “" + q + "” (or no internet)", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(a, "Weather: " + name, Toast.LENGTH_SHORT).show();
                refreshWeather();
                // Only if Weather is still the screen in front: the lookup may finish after the user moved on.
                if (generation == screenToken && screen.getVisibility() == View.VISIBLE) backScreen();
            });
        }));
        if (weather.configured()) {
            screenContent.addView(row("Turn weather off", null, () -> {
                weather.clear();
                weatherView.setVisibility(View.GONE);
                backScreen();
            }));
        }
        // Open at the first unit choice; the city field remains reachable by Down.
        // Automatically focusing EditText can open the TV IME and trap arrow keys.
        focusFirstRow();
    }

    private void chooseWeatherUnit(String unit) {
        weather.setUnit(unit);
        weatherView.setText("");
        refreshWeather();
        openWeather();
        screenContent.post(() -> screenContent.getChildAt(Weather.CELSIUS.equals(unit) ? 1 : 2).requestFocus());
    }

    private void openMusicAccess() {
        openScreen(a.getString(R.string.scenic_music_access), this::openMusicAccess);
        if (NowPlaying.hasAccess(a)) {
            note("Access is on. When an app such as Spotify or YouTube Music plays, its track, artwork and controls "
                    + "show above the Dock. Only the controls the app offers appear.");
            focusFirstRow();
            return;
        }
        note("To show what is playing, Android needs this launcher to have notification access (it reads media "
                + "sessions only, never notification content).");
        Intent i = NowPlaying.accessSettingsIntent(a);
        if (i != null) {
            screenContent.addView(row("Open notification access", null, () -> { closeScreenNow(); a.openIntent(i); }));
        } else {
            note("This TV has no settings screen for it. From a computer on the same network, run:\n\n"
                    + NowPlaying.adbGrantCommand(a));
        }
        focusFirstRow();
    }

    private void openBackup() {
        openScreen(a.getString(R.string.scenic_backup), this::openBackup);
        note("Export Dock favourites, My Space, weather and Home settings as a JSON document. "
                + "Select Google Drive in your TV's file picker if available; that provider uses its own signed-in "
                + "Google account. Some TV firmware has no document picker or Drive provider. "
                + "No account details are read by Scenic.");
        screenContent.addView(row(a.getString(R.string.scenic_backup_save), null,
                () -> a.scenicBackupDocument(false)));
        screenContent.addView(row(a.getString(R.string.scenic_backup_restore), null,
                () -> a.scenicBackupDocument(true)));
        note("Restore replaces current Scenic settings after confirmation. Keep a copy somewhere outside the TV. "
                + "If Home fails, the project's tools/restore.sh and tools/recovery.py can restore the stock launcher "
                + "using ADB; this settings backup cannot repair a broken Home installation.");
        focusFirstRow();
    }

    // ---- view helpers ----------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void addStill() {
        still = new ImageView(a);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(still, 0, full());
        int w = Math.max(320, a.getResources().getDisplayMetrics().widthPixels / 2);
        int h = Math.max(180, a.getResources().getDisplayMetrics().heightPixels / 2);
        worker.execute(() -> {
            Bitmap img = Scenery.paint(w, h);
            Bitmap blurred = Blur.glass(img);
            handler.post(() -> {
                if (released) return;
                BitmapDrawable d = new BitmapDrawable(a.getResources(), img);
                d.setFilterBitmap(true);
                still.setImageDrawable(d);
                still.setAlpha(0f);
                still.animate().alpha(1f).setDuration(400).start();
                Scene.setStill(blurred);
            });
        });
    }

    /** A theme wallpaper behind the glass: its blurred copy, decoded small off the UI thread. */
    void useWallpaperScene(File file) {
        if (file == null) return;
        worker.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b == null) return;
            Bitmap blurred = Blur.glass(b);
            handler.post(() -> { if (!released) Scene.setStill(blurred); });
        });
    }

    private FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(a);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

    private ImageView roundButton(int icon, int sizeDp, String description) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setImageTintList(a.getColorStateList(R.color.scenic_icon_tint));
        SceneGlassDrawable.apply(b, -1f, GlassTone.Kind.CONTROL);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(description);
        b.setElevation(dp(4));
        focusMotion(b);
        return b;
    }

    private ImageView transport(LinearLayout row, int icon, String description, Runnable action) {
        ImageView b = roundButton(icon, 44, description);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        if (row.getChildCount() > 0) lp.setMarginStart(dp(10));
        row.addView(b, lp);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void focusMotion(View v) {
        v.setOnFocusChangeListener((x, focused) -> {
            float s = focused ? 1.06f : 1f;
            x.animate().scaleX(s).scaleY(s).translationY(focused ? -dp(2) : 0).setDuration(reducedMotion ? 80 : 200)
                    .setInterpolator(EASE).start();
        });
    }

    /** A Settings/screen row: title, optional status on the right; OK runs the action. */
    private View row(String title, String status, Runnable action) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(16), 0);
        SceneGlassDrawable.focusSurface(r, 16f);
        r.setFocusable(true);
        r.setClickable(true);
        TextView t = text(17, medium, TEXT);
        t.setTextColor(a.getColorStateList(R.color.scenic_row_text));
        t.setDuplicateParentStateEnabled(true);
        t.setText(title);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = text(15, regular, TEXT);
        s.setTag("status");
        s.setTextColor(a.getColorStateList(R.color.scenic_row_status));
        s.setDuplicateParentStateEnabled(true);
        s.setText(status == null ? "" : status);
        s.setMaxWidth(dp(220));
        r.addView(s);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        r.setOnClickListener(v -> action.run());
        return r;
    }

    private View choiceRow(String title, String status, boolean chosen, Runnable action) {
        View r = row(title, chosen ? "✓" : status, action);
        r.setSelected(chosen);
        return r;
    }

    private View appRow(AppAdapter.Entry e) {
        View r = row(e.label, byPkg.containsKey(e.pkg) && favourites != null && favourites.contains(e.pkg) ? "In Dock" : "", () -> {
            closeScreenNow();
            launch(e);
        });
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(e.icon);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMarginEnd(dp(14));
        ((LinearLayout) r).addView(icon, 0, lp);
        r.setOnLongClickListener(v -> {
            toggleFavourite(e);
            return true;
        });
        return r;
    }

    private EditText field(String hint) {
        EditText f = new EditText(a);
        f.setHint(hint);
        f.setHintTextColor(0x80F5F6F8);
        f.setTextColor(TEXT);
        f.setTypeface(regular);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        f.setSingleLine(true);
        f.setBackgroundResource(R.drawable.scenic_field_bg);
        f.setPadding(dp(18), 0, dp(18), 0);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setTextColor(ColorStateList.valueOf(TEXT));
        return f;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
    }

    private void showKeyboard(View v) {
        v.post(() -> {
            InputMethodManager imm = a.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void fade(View v, boolean in, int dy) {
        v.animate().cancel();
        long ms = reducedMotion ? 120 : (in ? 320 : 220);
        if (in) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(dy);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).setDuration(ms).setInterpolator(EASE).start();
        } else {
            if (v.getVisibility() != View.VISIBLE) return;
            v.animate().alpha(0f).translationY(dy).setDuration(ms).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }
}
