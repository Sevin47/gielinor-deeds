/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.OverlayLayer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The world map overlay's draw hook.
 *
 * This exists because of a real bug that produced no error of any kind. An
 * overlay on OverlayLayer.MANUAL is painted only if it registers a draw hook;
 * the normal renderer skips that layer entirely. The overlay was constructed,
 * added to the OverlayManager, and silently never drawn -- the map simply stayed
 * empty, with nothing in the logs to suggest why.
 *
 * Constructing with nulls is deliberate and safe: the constructor only calls
 * setPosition/setLayer/drawAfterLayer/setPriority and never touches the injected
 * collaborators, so this pins the registration without needing a live client.
 */
public class DeedWorldMapOverlayTest
{
	@Test
	public void registersAWorldMapDrawHook()
	{
		DeedWorldMapOverlay overlay = new DeedWorldMapOverlay(null, null, null, null);

		assertEquals("a world map overlay must sit on the MANUAL layer",
			OverlayLayer.MANUAL, overlay.getLayer());

		assertTrue("MANUAL overlays are never painted without a draw hook -- "
				+ "without this the map silently stays empty",
			overlay.getDrawHooks().contains(InterfaceID.Worldmap.MAP_CONTAINER));
	}
}
