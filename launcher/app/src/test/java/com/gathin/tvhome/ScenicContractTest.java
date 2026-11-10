package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards for what the JVM cannot run (Views need a TV or an emulator): read the Scenic sources and check the
 * contracts the redesign must keep. Media commands go to the existing session, overlays trap the D-pad, the
 * progress line stays straight, no Android 12+ API or per-frame allocation creeps into the drawing code.
 */
public class ScenicContractTest {
    private static String src(String name) {
        File dir = new File("src/main/java/com/gathin/tvhome");
        if (!dir.isDirectory()) dir = new File("app/src/main/java/com/gathin/tvhome");
        if (!dir.isDirectory()) dir = new File("launcher/app/src/main/java/com/gathin/tvhome");
        try {
            return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + name + " from " + dir.getAbsolutePath(), e);
        }
    }

    /** The body of the first method whose declaration matches {@code signature}, braces balanced. */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) fail("missing " + signature);
        int open = source.indexOf('{', at), depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    /** Whitespace-insensitive: indentation and line endings (CRLF checkouts) do not matter. */
    private static void assertContains(String what, String haystack, String needle) {
        assertTrue(what + ": expected `" + needle + "`", squash(haystack).contains(squash(needle)));
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ");
    }

    // ---- media commands stay on the existing session ----------------------------------------------

    @Test public void controlCenterTransportUsesTheSameSessionCommandsAsHome() {
        String home = src("ScenicHome.java");
        assertContains("Home prev", home, "() -> nowPlaying.previous()");
        assertContains("Home toggle", home, "() -> nowPlaying.togglePlay()");
        assertContains("Home next", home, "() -> nowPlaying.next()");
        assertContains("CC prev", home, "public void previous() { nowPlaying.previous(); }");
        assertContains("CC toggle", home, "public void togglePlay() { nowPlaying.togglePlay(); }");
        assertContains("CC next", home, "public void next() { nowPlaying.next(); }");
    }

    @Test public void noNewAudioPathInTheVisualLayer() {
        for (String f : new String[]{"ControlCenter.java", "GlassCurrentView.java", "SceneGlassDrawable.java",
                "RibbonSwells.java", "ArtPalette.java", "GlassTone.java", "ScreenStack.java"}) {
            String s = src(f);
            for (String banned : new String[]{"android.media.", "AudioRecord", "Visualizer", "MediaPlayer",
                    "AudioTrack", "MediaController"}) {
                assertFalse(f + " must not touch audio (" + banned + ")", s.contains(banned));
            }
        }
    }

    // ---- D-pad: overlays keep focus, dock and grid share the expanded Apps screen -------------------

    @Test public void everyOverlayTrapsTheDpad() {
        String keys = body(src("ScenicHome.java"), "boolean dispatchKey(KeyEvent ev)");
        assertContains("Control Center trap", keys, "if (next != null && controlCenter.contains(next)) return false;");
        assertContains("Up from the sheet's top row reaches its header button", keys,
                "if (code == KeyEvent.KEYCODE_DPAD_UP) quickButton.requestFocus();");
        assertContains("Down from the header returns into the sheet", keys,
                "else if (code == KeyEvent.KEYCODE_DPAD_DOWN) controlCenter.focusFirst();");
        assertContains("Control Center lost focus", keys, "controlCenter.focusFirst();");
        assertContains("panel/screen trap", keys, "if (next == null || !isInside(next, container)) return true;");
        assertContains("listen trap", keys, "if (listening) return listenKey(f, code);");
        assertContains("text field caret", keys, "f instanceof EditText");
    }

    @Test public void dockAndAllAppsNavigateAsTwoStackedRows() {
        String keys = body(src("ScenicHome.java"), "boolean dispatchKey(KeyEvent ev)");
        assertContains("Down from the dock opens All Apps", keys,
                "case ROW_DOCK:\n                    showApps(true, true);");
        assertContains("Up from the first row reaches the dock and so Home", keys,
                "a.focusedGridIsFirstRow()) {\n                showApps(false, false);\n                handler.post(() -> focusDock(lastDockPos));");
        assertContains("Down from the raised dock returns to the grid", keys,
                "if (row == ROW_DOCK && code == KeyEvent.KEYCODE_DPAD_DOWN) {\n                if (a.hasScenicGridItems()) a.focusGridAt(0);");
        assertContains("Up from the raised dock collapses the sheet", keys,
                "if (row == ROW_DOCK && code == KeyEvent.KEYCODE_DPAD_UP) {\n                showApps(false, false);");
        String back = body(src("ScenicHome.java"), "boolean onBack()");
        assertContains("Back from All Apps lands on All Apps", back, "focusDock(dockAdapter.getItemCount() - 1)");
    }

    @Test public void allAppsLiftsTheDockBelowTheHeaderAndReturnsItOnBack() {
        String home = body(src("ScenicHome.java"), "void showApps(boolean show, boolean focusGrid)");
        assertContains("header remains visible", home, "header.setVisibility(View.VISIBLE);");
        assertContains("dock stops below the header", home, "ScenicAppsMotion.dockTop(headerBottom, dp(12))");
        assertContains("grid reserves space for the dock", home, "ScenicAppsMotion.gridTop(dockTop, dockHeight, dp(14))");
        assertContains("grid rises from below", home, "ScenicAppsMotion.entryTravel(root.getHeight(), gridTop, dp(32))");
        assertContains("one animator coordinates the dock", home, "dockFrame.setTranslationY(appsDockShift * appsReveal);");
        assertContains("one animator coordinates the grid", home, "gridFrame.setTranslationY(appsGridTravel * (1f - appsReveal));");
        assertContains("reverse returns to Home", home, "float start = appsReveal, end = show ? 1f : 0f;");
    }

    @Test public void scenicHidesDockAppsOnlyInTheGrid() {
        String home = src("ScenicHome.java");
        assertContains("Scenic propagates live dock favourites", home, "a.setScenicDockPackages(visibleDock);");
        String adapter = src("AppAdapter.java");
        assertContains("grid filter uses the installed snapshot", adapter,
                "ScenicGridFilter.withoutDock(present, scenicDockPackages");
        assertContains("dock retains full installed entries", adapter, "for (Entry e : allItems) if (e.kind == Entry.APP)");
        assertContains("Scenic does not hide names in its first grid row", adapter,
                "!scenicHome && DockSlots.inDockRow(dockRow, singleRow, position, dockSize)");
        assertContains("Scenic labels remain visible", adapter,
                "cfg.scenicHome || cfg.alwaysShowNames ? 0.75f : 0f");
        assertContains("classic empty slots never distort Scenic focus", adapter,
                "emptySlots = !scenicHome && dockRow && !reordering");
        assertContains("App library still counts pinned dock apps as on Home", adapter,
                "for (Entry entry : allItems) if (entry.pkg.equals(pkg)) return true;");
        assertContains("empty grid leaves focus on the dock", home,
                "if (a.hasScenicGridItems()) a.focusGridAt(0);\n                        else focusDock(lastDockPos);");
        String activity = src("MainActivity.java");
        assertContains("Scenic grid skips the classic dock's top padding", activity,
                "if (cfg.scenicHome) {\n                // Scenic's separate dock");
        assertContains("Scenic does not draw a second dock band", activity,
                "if (dockRow && !cfg.scenicHome) grid.addItemDecoration(new DockDecoration(");
        assertContains("Scenic focus does not park the app row at the bottom", activity,
                "if (scenic != null) {\n            // The Scenic grid is below a separate floating dock");
    }

    // ---- Settings backstack --------------------------------------------------------------------

    @Test public void backInsideSettingsGoesOneLevelUp() {
        String home = src("ScenicHome.java");
        String back = body(home, "boolean onBack()");
        assertContains("Back pops a level", back, "backScreen();");
        String backScreen = body(home, "private void backScreen()");
        assertContains("parent rebuilt", backScreen, "parent.reopen.run();");
        String close = body(home, "private void closeScreen()");
        assertContains("first level returns to Settings", close, "if (screenFromPanel) returnToPanel();");
        // Every screen registers how to rebuild itself (or null for a transient one).
        Matcher m = Pattern.compile("openScreen\\(([^;]*)\\);").matcher(home);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue("openScreen without a reopen action: " + m.group(), m.group(1).contains(","));
        }
        // The retired photo-collection, Search, Discover and My Space screens were removed; the remaining
        // settings hierarchy must still register its reopen actions.
        assertTrue(n >= 8);
    }

    // ---- progress line -------------------------------------------------------------------------

    @Test public void progressLineIsAStraightHorizontalSegment() {
        String ribbon = src("GlassCurrentView.java");
        String build = body(ribbon, "private void buildPath(int w, int h)");
        assertContains("starts on the centre line", build, "path.moveTo(pathStart, cy);");
        assertContains("ends on the centre line", build, "path.lineTo(pathEnd, cy);");
        assertFalse("no curves on the seek path", Pattern.compile("\\bpath\\.(cubicTo|quadTo|arcTo|rLineTo)").matcher(ribbon).find());
        String focus = body(ribbon, "protected void onFocusChanged(");
        assertContains("the view is never stretched", focus, "setScaleY(1f);");
    }

    // ---- material, motion, API level ---------------------------------------------------------------

    @Test public void everyGlassSurfaceUsesTheSharedMaterialKinds() {
        for (String f : new String[]{"ScenicHome.java", "ControlCenter.java", "MainActivity.java"}) {
            Matcher m = Pattern.compile("new SceneGlassDrawable\\(([^;]*)\\)").matcher(src(f));
            while (m.find()) assertContains(f + " raw tint", m.group(1), "GlassTone.Kind.");
        }
        assertFalse("one material only", new File("src/main/java/com/gathin/tvhome/LiquidGlassDrawable.java").exists());
    }

    @Test public void drawingAllocatesNothingPerFrame() {
        String glass = body(src("SceneGlassDrawable.java"), "public void draw(@NonNull Canvas c)");
        assertFalse("SceneGlassDrawable.draw allocates", glass.contains("new "));
        String sample = body(src("SceneGlassDrawable.java"), "private boolean drawSample(");
        // The one allocation is guarded: a new shader only when the scene bitmap itself changes.
        assertContains("shader cached per bitmap", sample, "if (bmp != shaderBitmap) {");
        String swells = body(src("GlassCurrentView.java"), "private void drawSwells(");
        assertFalse("swells allocate", swells.contains("new "));
        String onDraw = body(src("GlassCurrentView.java"), "protected void onDraw(Canvas c)");
        assertFalse("ribbon onDraw allocates", onDraw.contains("new "));
    }

    @Test public void reducedMotionIsRespected() {
        String home = src("ScenicHome.java");
        assertContains("ribbon swells off", home, "ribbon.setMotionEnabled(!reducedMotion);");
        assertContains("Control Center told", home, "new ControlCenter(a, layer, regular, medium, reducedMotion,");
        String cc = src("ControlCenter.java");
        assertContains("no focus lift", body(cc, "private void focusable(View v, Runnable action)"), "if (reducedMotion) return;");
        assertContains("fade only", body(cc, "void open()"), "card.animate().alpha(1f).setDuration(120).start();");
        String ribbon = src("GlassCurrentView.java");
        assertContains("no swells without motion", body(ribbon, "private boolean updateLevel(long now)"),
                "playing && animateWave ? 1f : 0f");
    }

    @Test public void focusIsVisibleOnEveryMaterial() {
        String glass = src("SceneGlassDrawable.java");
        assertContains("stateful", glass, "@Override public boolean isStateful() { return true; }");
        assertContains("reads focus", glass, "android.R.attr.state_focused");
    }

    @Test public void noApiAbove30InTheRedesign() {
        for (String f : new String[]{"ControlCenter.java", "GlassCurrentView.java", "SceneGlassDrawable.java",
                "Scene.java", "ScreenStack.java", "GlassTone.java", "RibbonSwells.java", "ArtPalette.java",
                "NetworkSummary.java"}) {
            String s = src(f);
            for (String banned : new String[]{"RenderEffect", "BlendModeColorFilter", "setRenderEffect",
                    "VERSION_CODES.S", "PixelCopy", "WindowInsets.Type", "setBlurBehindRadius"}) {
                assertFalse(f + " uses " + banned, s.contains(banned));
            }
        }
    }

    // ---- the frost follows the aerial, cheaply --------------------------------------------------

    @Test public void aerialFrostIsAContinuousCheapStream() {
        String aerial = src("AerialBackdrop.java");
        String sample = body(aerial, "private void sampleGlass()");
        assertContains("reused copy target", sample, "if (snapshot == null || snapshot.getWidth() != w");
        assertFalse("no fresh blur bitmap per sample", sample.contains("Blur.blurred("));
        assertContains("two reused output bitmaps", sample, "glassStream.blur(target, side, radius)");
        assertContains("copied at twice the frost's size", sample, "2f * side");
        String done = body(aerial, "private void glassSampled(");
        assertContains("the next copy follows the last, at the same rate under music", done, "frameMs - took");
        assertContains("the rate adapts to what this TV can sustain", done, "frameMs = frameMsFor(tookAvg);");
        assertContains("a smaller sample under music", sample, "music ? Blur.MUSIC_SIDE : Blur.MOTION_SIDE");
        assertContains("only when the user opts in", sample, "lightFrostWithMusic && externalAudioPlaying");
        String audio = body(aerial, "void setExternalAudioPlaying(boolean playing)");
        assertContains("paused reports never restart the end grace", audio, "if (externalAudioPlaying && !musicEndPending) {");
        assertContains("opt-in, off by default", src("ScenicHome.java"), "getBoolean(MUSIC_FROST_KEY, false)");
        assertContains("luminance measured off the UI thread", done, "Scene.setStill(frost, lum, hues, wide);");
    }

    @Test public void focusIsGlassNeverAWhiteFill() {
        assertFalse("the #22 white focus fill is gone",
                new File("src/main/res/drawable/scenic_row_bg.xml").exists());
        assertContains("rows", src("ScenicHome.java"), "SceneGlassDrawable.focusSurface(r, 16f);");
        String glass = src("SceneGlassDrawable.java");
        assertContains("focus brightens the frost itself", glass, "scene.setColorFilter(focused ? BODY_FOCUSED : BODY);");
    }

    @Test public void allAppsFrostsTheAerialButNotTheAlbumWash() {
        String home = body(src("ScenicHome.java"), "void showApps(boolean show, boolean focusGrid)");
        assertContains("veil chosen per background", home, "dim.setBackground(washCovering() ? appsShade : appsFrost);");
    }

    @Test public void controlCenterNeverClipsAFocusedControl() {
        String cc = src("ControlCenter.java");
        assertFalse("no clip to the card's outline", cc.contains("card.setClipToOutline(true)"));
        assertContains("children may lift past their bounds", cc, "card.setClipChildren(false);");
        // The media platter clipped its focused title to its padding: the lift lost its rounded rim.
        assertContains("platter lets the title lift", cc, "platter.setClipToPadding(false);");
        assertTrue("sheet sits below the header's buttons", ControlCenter.TOP_DP >= 96);
        assertTrue("lift stays inside the 16dp padding", ControlCenter.FOCUS_SCALE <= 1.05f);
    }

    @Test public void controlCenterHasIndividualGlassWithoutAnOuterSheetOrRedundantTvFooter() {
        String cc = src("ControlCenter.java");
        assertFalse("no glass applied to the empty outer frame", cc.contains("SceneGlassDrawable.apply(card,"));
        assertFalse("no extra round glass layer over Wi-Fi and Sound icons", cc.contains("SceneGlassDrawable.apply(disc,"));
        assertFalse("no duplicate TV settings row", cc.contains("buildFooter();"));
        assertContains("Wi-Fi and Sound capsules have their own frost", cc,
                "SceneGlassDrawable.apply(c, 26f, GlassTone.Kind.CONTROL);");
        assertContains("whole media platter takes focus", cc, "focusable(platter, () -> {");
        assertContains("left from previous selects the platter", cc, "mediaPlatter.requestFocus();");
    }

    @Test public void scenicMediaCardOpensTheOwningMusicApp() {
        String home = src("ScenicHome.java");
        assertContains("CC media action", home, "public void listen() { quickAction(ScenicHome.this::openCurrentMediaApp); }");
        String open = body(home, "private void openCurrentMediaApp()");
        assertContains("current session package", open, "track.pkg");
        assertContains("TV activity first", open, "getLeanbackLaunchIntentForPackage");
        assertContains("regular activity fallback", open, "getLaunchIntentForPackage");
    }

    @Test public void downloadedAerialsCanBeDeletedButPhotoCollectionsCannotReappear() {
        String activity = src("MainActivity.java");
        String manager = body(activity, "void showDownloadedAerials()");
        assertContains("local files available without Apple catalog", manager, "AerialBackdrop.catalog(this)");
        assertContains("user confirms file deletion", manager, "R.string.aerials_confirm_remove");
        assertContains("deletion goes through constrained storage helper", manager, "AerialDownloads.removeFile(this, selected[0])");
        assertContains("selected deleted video is reset", manager, "AerialBackdrop.choose(this, AerialBackdrop.CYCLE)");
        assertContains("remember whether it was selected before unlinking", manager,
                "boolean removedChosen = selected[0].equals(AerialBackdrop.chosen(this, \"\"));");
        String deletion = body(src("AerialDownloads.java"), "static boolean removeFile(");
        assertContains("no path traversal", deletion, "fileName.matches(");
        assertContains("active downloads retained", deletion, "fileName.equals(downloading)");
        assertContains("actual stored video removed", deletion, "video.delete()");
        assertContains("silent remux cache cleared", deletion, "aerial-video-only");
        assertContains("JSON index updated", deletion, "writeIndex(d, keep)");
        String home = src("ScenicHome.java");
        assertContains("delete option in Wallpapers", home, "a.showDownloadedAerials();");
        assertContains("legacy photos can be removed only on user request", home, "a.legacyScenicPhotoBytes() > 0");
        assertContains("legacy photos cleanup has its own confirmation", activity, "R.string.scenic_legacy_photos_confirm");
        assertFalse("generic Scenic photo collections removed", home.contains("Scenic photo collections"));
        assertFalse("photo gallery player removed", home.contains("ScenicWallpaperPlayer"));
        assertFalse("photo gallery network feature removed", home.contains("CommonsWallpapers"));
        File dir = new File("src/main/java/com/gathin/tvhome");
        if (!dir.isDirectory()) dir = new File("launcher/app/src/main/java/com/gathin/tvhome");
        for (String removed : new String[]{"CommonsWallpapers.java", "ScenicWallpaperCatalog.java", "ScenicWallpaperPlayer.java"}) {
            assertFalse(removed + " should no longer ship", new File(dir, removed).exists());
        }
    }

    @Test public void replacementWorksFromSearchWithoutTrappingFocusBehindAScreen() {
        String home = src("ScenicHome.java");
        String toggle = body(home, "boolean toggleFavourite(AppAdapter.Entry e)");
        assertContains("close Search or Discover before focus moves", toggle, "closeScreenNow();");
        assertContains("raise grid and dock", toggle, "showApps(true, false);");
        String cancel = body(home, "private void cancelReplaceAndReturn()");
        assertContains("Back returns to selected app in grid", cancel, "a.focusScenicGridPackage(previous);");
    }

    @Test public void backupDoesNotRestoreDeviceLocalThemeSignature() {
        String backup = src("ScenicBackup.java");
        String encode = body(backup, "static String encode(Context context)");
        assertContains("local signature excluded from backup", encode,
                "LOCAL_THEME_SIGNATURE.equals(entry.getKey())) continue;");
        String restore = body(backup, "static void restore(Context context, String json)");
        assertContains("old backups skip another TV's signature", restore,
                "LOCAL_THEME_SIGNATURE.equals(key)) continue;");
        assertContains("destination signature preserved", restore,
                "editor.putString(LOCAL_THEME_SIGNATURE, localSignature);");
    }

    @Test public void scenicDockCapacityAndReplacementRemainDistinctFromAppGrid() {
        String scenic = src("ScenicHome.java");
        String adapter = src("AppAdapter.java");
        assertContains("dock cap tracks advanced column setting", scenic,
                "ScenicDockRules.capacity(cfg.columns)");
        assertContains("All Apps button can be hidden", scenic, "SHOW_ALL_APPS_DOCK_KEY");
        assertContains("full dock asks the owner which slot to replace", scenic, "replacingPkg = e.pkg;");
        assertContains("other apps dim during selection", adapter, "scenicReplacementCandidate.equals(e.pkg) ? 1f : 0.28f");
    }
}
