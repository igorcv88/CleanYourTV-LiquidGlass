package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Labels and icons ink dark over a bright patch of scene, white over a dark one, without flicker. */
public class GlassContentTest {
    @Test public void brightPatchesTurnTheInkDarkWithHysteresis() {
        assertTrue(GlassContent.nextLight(false, 0.9f));
        assertFalse(GlassContent.nextLight(false, 0.6f));
        assertTrue("stays dark ink in the band", GlassContent.nextLight(true, 0.6f));
        assertFalse(GlassContent.nextLight(true, 0.4f));
    }

    @Test public void onlyWhiteInkSwapsAndKeepsItsOpacity() {
        assertTrue(GlassContent.isLight(0xFFF5F6F8));
        assertTrue(GlassContent.isLight(0xB3FFFFFF));
        assertFalse("the blue Wi-Fi icon keeps its colour", GlassContent.isLight(0xFF169BFF));
        assertFalse(GlassContent.isLight(0xFF202020));
        assertEquals(0xB312161C, GlassContent.darkened(0xB3FFFFFF));
    }
}
