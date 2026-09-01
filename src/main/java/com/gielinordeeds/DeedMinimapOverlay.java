/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.annotation.Nullable;
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
 * Each deed is drawn at its true size, as the quad its four corners project to.
 * The minimap rotates with the camera, so projecting the corners rather than
 * offsetting from a centre point is what keeps the marker square with the world
 * instead of with the screen.
 *
 * A deed at a time rather than a tile at a time: they tile exactly, so 64 tile
 * quads would draw the same shape for 64 times the work.
 */
public class DeedMinimapOverlay extends Overlay
{
	/** Scene tiles a deed spans. Matches ParcelGrid's cell. */
	private static final int CELL = 8;
	/** Alpha for the fill, low enough to leave the minimap readable. */
	private static final int FILL_ALPHA = 90;
	/** The outline, firmer than the fill so edges between deeds stay visible. */
	private static final int EDGE_ALPHA = 170;

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
				Polygon bounds = footprint(wx, wy, plane, baseX, baseY);
				if (bounds == null)
				{
					continue;
				}
				Color tint = p.getTier().color();
				g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(),
					FILL_ALPHA));
				g.fill(bounds);
				g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(),
					EDGE_ALPHA));
				g.draw(bounds);
			}
		}
		return null;
	}

	/**
	 * The deed's four corners, projected onto the minimap.
	 *
	 * Corners sit between tiles, so they are addressed in local units directly
	 * rather than through a tile centre: a tile is LOCAL_TILE_SIZE across and
	 * the corner at world (gx, gy) is at ((gx - baseX) * size, ...). That makes
	 * the quad meet its neighbours exactly, with no half-tile gap or overlap.
	 *
	 * Returns null unless all four corners project. localToMinimap answers only
	 * within about twenty tiles of the player, so a deed at the edge of that
	 * range drops out rather than being drawn as a torn shape.
	 */
	@Nullable
	private Polygon footprint(int wx, int wy, int plane, int baseX, int baseY)
	{
		int[][] corners = {{wx, wy}, {wx + CELL, wy},
			{wx + CELL, wy + CELL}, {wx, wy + CELL}};
		Polygon out = new Polygon();
		for (int[] c : corners)
		{
			LocalPoint lp = new LocalPoint(
				(c[0] - baseX) * Perspective.LOCAL_TILE_SIZE,
				(c[1] - baseY) * Perspective.LOCAL_TILE_SIZE);
			Point at = Perspective.localToMinimap(client, lp);
			if (at == null)
			{
				return null;
			}
			out.addPoint(at.getX(), at.getY());
		}
		return out;
	}
}
