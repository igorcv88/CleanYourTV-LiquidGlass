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
}
