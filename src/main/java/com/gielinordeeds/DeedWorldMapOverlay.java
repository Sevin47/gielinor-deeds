/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.BitSet;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

/**
 * Draws owned parcels on the world map.
 *
 * Parcel rectangles are found by mapping the south-west and north-east corners
 * through {@link WorldMapOverlay#mapWorldPointToGraphicsPoint} and drawing
 * between them, rather than by mapping one corner and deriving a size from
 * {@code getWorldMapZoom()}. Two-corner mapping stays correct at every zoom
 * level for free and has no scale factor to keep in sync with the client's.
 */
public class DeedWorldMapOverlay extends Overlay
{
	private static final int ALPHA_FILL = 70;
	/** Deliberately much fainter than owned land -- this is a record of having
	 *  been somewhere, not a claim on it. */
	private static final int ALPHA_SURVEYED = 34;
	/*
	 * Below this many pixels a parcel is too small to read as a shape, so it is
	 * drawn as a filled dot instead of an outlined rectangle. Without this the
	 * whole-world view degenerates into unreadable one-pixel outlines.
	 */
	private static final int MIN_READABLE_PX = 4;

	private final Client client;
	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;
	private final WorldMapOverlay worldMapOverlay;

	// Package-private rather than private so the draw-hook test can construct
	// one without a live client. Guice injects package-private constructors.
	@Inject
	DeedWorldMapOverlay(Client client, GielinorDeedsPlugin plugin,
		GielinorDeedsConfig config, WorldMapOverlay worldMapOverlay)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.worldMapOverlay = worldMapOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		// MANUAL keeps this out of the normal overlay stack so it is painted
		// only in the world map's own pass, inside the clip set below. A MANUAL
		// overlay is painted only when it registers a draw hook, so without the
		// drawAfterLayer call it is added, never throws, and never appears. A
		// test pins the hook.
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
		setPriority(1.0f);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showWorldMap() || plugin.getGrid() == null)
		{
			return null;
		}

		// InterfaceID.Worldmap.MAP_CONTAINER, not MAP_DISPLAY: the container is
		// the widget the deprecated ComponentID.WORLD_MAP_MAPVIEW actually pointed
		// at (both are 38993927). MAP_DISPLAY is a different, inner widget.
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			return null;
		}

		// mapWorldPointToGraphicsPoint happily returns coordinates outside the
		// map widget for points scrolled off-screen; without this clip they
		// would be painted over the rest of the interface.
		Rectangle bounds = map.getBounds();
		Shape prevClip = g.getClip();
		g.setClip(bounds);
		try
		{
			ParcelGrid grid = plugin.getGrid();
			if (config.showSurveyedOnMap())
			{
				drawSurveyed(g, grid, bounds);
			}
			// Owned parcels paint over the surveyed wash, so land you actually
			// hold still reads clearly against everything you have merely walked.
			for (String pid : plugin.getEstate().getOwned().keySet())
			{
				drawParcel(g, grid, pid, bounds);
			}
			drawCurrent(g, bounds);
		}
		finally
		{
			g.setClip(prevClip);
		}
		return null;
	}

	/**
	 * A faint wash over everything surveyed, so the map fills in as the player
	 * explores rather than arriving complete.
	 *
	 * Iterates the surveyed bitset rather than the whole 115,200-parcel grid:
	 * the work is then bounded by how much has actually been explored, which is
	 * the number that matters, and an unexplored map costs nothing to draw.
	 */
	private void drawSurveyed(Graphics2D g, ParcelGrid grid, Rectangle bounds)
	{
		Estate estate = plugin.getEstate();
		BitSet set = estate.surveyedSet();
		for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1))
		{
			Parcel p = grid.atIndex(i);
			if (p == null || estate.owns(p.getPid()))
			{
				continue;                       // owned parcels are drawn solid below
			}
			Rectangle r = rectFor(grid, p, bounds);
			if (r == null)
			{
				continue;
			}
			Color c = p.getTier().color();
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), ALPHA_SURVEYED));
			g.fillRect(r.x, r.y, Math.max(1, r.width), Math.max(1, r.height));
		}
	}

	private void drawParcel(Graphics2D g, ParcelGrid grid, String pid, Rectangle bounds)
	{
		Parcel p = grid.byPid(pid);
		if (p == null)
		{
			return;
		}
		Rectangle r = rectFor(grid, p, bounds);
		if (r == null)
		{
			return;
		}
		Color c = p.getTier().color();
		if (r.width < MIN_READABLE_PX || r.height < MIN_READABLE_PX)
		{
			g.setColor(c);
			g.fillRect(r.x, r.y, Math.max(2, r.width), Math.max(2, r.height));
			return;
		}
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), ALPHA_FILL));
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(c);
		g.drawRect(r.x, r.y, r.width, r.height);
	}

	/** The parcel being surveyed right now, outlined in white so it stands out. */
	private void drawCurrent(Graphics2D g, Rectangle bounds)
	{
		Parcel p = plugin.getCurrentParcel();
		if (p == null || plugin.getEstate().owns(p.getPid()))
		{
			return;
		}
		Rectangle r = rectFor(plugin.getGrid(), p, bounds);
		if (r == null)
		{
			return;
		}
		g.setColor(Color.WHITE);
		g.drawRect(r.x, r.y, Math.max(2, r.width), Math.max(2, r.height));
	}

	/**
	 * Screen rectangle for a parcel, or null if it maps off the visible map.
	 * The north-east corner is the parcel's far edge, so it takes +cell on both
	 * axes -- using the parcel's own last tile would draw one tile short.
	 */
	private Rectangle rectFor(ParcelGrid grid, Parcel p, Rectangle bounds)
	{
		int cell = grid.getCell();
		WorldPoint sw = p.getSouthWest();
		WorldPoint ne = new WorldPoint(sw.getX() + cell, sw.getY() + cell, 0);

		Point a = worldMapOverlay.mapWorldPointToGraphicsPoint(sw);
		Point b = worldMapOverlay.mapWorldPointToGraphicsPoint(ne);
		if (a == null || b == null)
		{
			return null;
		}

		int x = Math.min(a.getX(), b.getX());
		int y = Math.min(a.getY(), b.getY());
		int w = Math.abs(b.getX() - a.getX());
		int h = Math.abs(b.getY() - a.getY());
		Rectangle r = new Rectangle(x, y, w, h);
		// Cheap reject for the common case: most owned parcels are nowhere near
		// the current view, and skipping them here avoids a fill per parcel.
		return r.intersects(bounds) ? r : null;
	}
}
