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
}
