package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The frost's rate follows what the TV sustains; Home's progress line follows the backdrop's hues. */
public class ScenicAdaptiveTest {
    @Test public void aFastTvRefreshesTheFrostOftenAndASlowOneStaysSafe() {
        assertEquals(25L, AerialBackdrop.frameMsFor(8f));
        assertEquals(33L, AerialBackdrop.frameMsFor(15f));
        assertEquals(50L, AerialBackdrop.frameMsFor(24f));
        assertEquals(66L, AerialBackdrop.frameMsFor(45f));
        for (float took = 1f; took < 200f; took += 1f) {
            long frame = AerialBackdrop.frameMsFor(took);
            assertTrue("a sample never takes more than about half its interval at " + took,
                    took <= frame / 2f + 1f || frame == 66L);
            assertTrue(AerialBackdrop.frameMsFor(took + 1f) >= frame);
        }
    }

    @Test public void aBlueSceneGivesABluePaletteAndAGreyOneNone() {
        int[] sea = new int[400];
        for (int i = 0; i < sea.length; i++) sea[i] = 0xFF1E5A96;
        int[] p = Blur.scenePalette(sea);
        float[] hsv = new float[3];
        ArtPalette.toHsv(p[0], hsv);
        assertTrue("hue " + hsv[0], hsv[0] > 190f && hsv[0] < 230f);
        int[] grey = new int[400];
        for (int i = 0; i < grey.length; i++) grey[i] = 0xFF808080;
        assertNull(Blur.scenePalette(grey));
    }

    @Test public void aShortDockIsCentredAndAFullOneIsNot() {
        // 1824 px dock, 36 px padding, 288 px cards, 32 px gaps: five cards leave 1752-1568 = 184 px, 92 a side.
        assertEquals(92, ScenicHome.centringInset(1824, 36, 5, 288, 32));
        assertEquals(0, ScenicHome.centringInset(1824, 36, 12, 288, 32));
        assertEquals((1752 - 288) / 2, ScenicHome.centringInset(1824, 36, 1, 288, 32));
    }

    @Test public void settingsDropsSearchDiscoverAndMySpaceAndAppsOpensTheTvAppList() throws Exception {
        String home = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/gathin/tvhome/ScenicHome.java")), java.nio.charset.StandardCharsets.UTF_8);
        int from = home.indexOf("private void fillPanel()"), to = home.indexOf("section(\"Look & feel\");", from);
        String panel = home.substring(from, to);
        assertTrue(!panel.contains("scenic_search") && !panel.contains("scenic_discover") && !panel.contains("scenic_my_space"));
        assertTrue(panel.contains("a.openAppSettings();"));
    }

    private static String read(String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/gathin/tvhome/" + name)), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void homeComesBackOnTheFrameItLeftNotBlack() throws Exception {
        String aerial = read("AerialBackdrop.java");
        assertTrue("the still is copied from the screen, not decoded while an app holds the decoder",
                aerial.contains("PixelCopy.request(video, target, result -> {"));
        assertTrue("the player outlives the copy, never Home's absence", aerial.contains("main.postDelayed(releaseLate, POSTER_COPY_TIMEOUT_MS);"));
        assertTrue("decoding stays as the fallback", aerial.contains("if (playing != null) capturePoster();"));
        assertTrue("the keyframe the player resumes from", aerial.contains("MediaMetadataRetriever.OPTION_CLOSEST_SYNC"));
        assertTrue(aerial.contains("p.seekTo(at, MediaPlayer.SEEK_CLOSEST_SYNC)"));
        assertTrue("a new flight never shows the old one's frame", aerial.contains("clearPoster();\n        cover.animate().setStartDelay(0).alpha(1f)"));
        assertTrue("the cover waits for the TV's video plane, not Android's first-frame report",
                aerial.contains("cover.animate().alpha(0f).setStartDelay(VIDEO_PLANE_MS)"));
    }

    @Test public void overTheCoverBackdropHomeWearsTheCoversColours() throws Exception {
        assertTrue(read("ScenicHome.java").contains("ribbon.setCoverBackdrop(wantWash);"));
        assertTrue(read("GlassCurrentView.java").contains("mix[i] = ArtPalette.lerp(scene[i], palette[i], cover);"));
    }

    @Test public void controlCenterClosesAlongItsOpeningPathMirrored() throws Exception {
        String cc = read("ControlCenter.java");
        assertTrue(cc.contains(".setDuration(OPEN_MS)\n                    .setInterpolator(EASE_BACK)"));
        assertTrue(cc.contains("EASE_BACK = t -> 1f - EASE.getInterpolation(1f - t);"));
    }

    @Test public void bigPanelsGetAWiderFrostThanControls() {
        float controls = Blur.screenSigma(Blur.MOTION_SIDE, Blur.MOTION_RADIUS, 3, 1920);
        float extra = Blur.screenSigma(Blur.MOTION_SIDE / 2, Blur.WIDE_RADIUS, 3, 1920);
        float panels = (float) Math.sqrt(controls * controls + extra * extra);
        assertTrue("controls " + controls, controls >= 60f && controls <= 72f);
        assertTrue("panels " + panels, panels >= 95f && panels <= 125f);
    }

    @Test public void halvingAveragesEachBlock() {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF};
        int[] dst = new int[1];
        Blur.halve(src, 2, 2, dst, 1, 1);
        assertEquals(0xFF808080, dst[0]);
    }

    @Test public void aFlightNearItsEndResumesFromTheStartAndSoDoesItsStill() {
        assertEquals(5000, AerialBackdrop.resumeAtMs(5000, 60000));
        assertEquals(0, AerialBackdrop.resumeAtMs(59000, 60000));
        assertEquals(0, AerialBackdrop.resumeAtMs(0, 60000));
    }
}
