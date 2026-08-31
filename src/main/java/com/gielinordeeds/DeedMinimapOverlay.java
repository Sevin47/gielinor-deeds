/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Marks the deeds you own on the minimap.
 *
 * The world map already shows holdings, but it has to be opened. The minimap is
 * on screen the whole time, so this answers "am I on my own land" without
 * interrupting anything.
 *
 * Drawn a deed at a time rather than a tile at a time. A deed is 8x8 tiles and
 * the minimap is about four pixels to the tile at default zoom, so one marker
 * per deed is both the cheaper drawing and the more legible one.
 */
public class DeedMinimapOverlay extends Overlay
{
	/** Scene tiles a deed spans. Matches ParcelGrid's cell. */
	private static final int CELL = 8;
	/** Alpha for the marker, low enough to leave the minimap readable. */
	private static final int ALPHA = 120;

	private final Client client;
	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;

	@Inject
	private DeedMinimapOverlay(Client client, GielinorDeedsPlugin plugin,
		GielinorDeedsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		// Above widgets, because the minimap is one and anything lower is
		// painted under it.
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		ParcelGrid grid = plugin.getGrid();
		if (!config.showMinimapDeeds() || grid == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		int baseX = wv.getBaseX(), baseY = wv.getBaseY();
		int plane = wv.getPlane();
		int span = Perspective.SCENE_SIZE;

		// Walk the scene a deed at a time -- about 169 steps rather than 10,816.
		for (int wx = baseX - Math.floorMod(baseX, CELL); wx < baseX + span; wx += CELL)
		{
			for (int wy = baseY - Math.floorMod(baseY, CELL); wy < baseY + span; wy += CELL)
			{
				Parcel p = grid.at(new WorldPoint(wx, wy, 0));
				if (p == null || !plugin.getEstate().owns(p.getPid()))
				{
					continue;
				}
				// The middle of the deed, so the marker sits on the deed rather
				// than on its south-west corner.
				LocalPoint lp = LocalPoint.fromWorld(client,
					new WorldPoint(wx + CELL / 2, wy + CELL / 2, plane));
				if (lp == null)
				{
					continue;
				}
				Point dot = Perspective.localToMinimap(client, lp);
				if (dot == null)
				{
					continue;
				}
				Color tint = p.getTier().color();
				g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), ALPHA));
				g.fillRect(dot.getX() - 2, dot.getY() - 2, 5, 5);
			}
		}
		return null;
	}
}
