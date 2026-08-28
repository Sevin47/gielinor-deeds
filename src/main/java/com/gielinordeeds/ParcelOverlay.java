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
import java.awt.geom.Area;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws the parcel the player is standing in as one outlined 8x8 footprint.
 *
 * The whole parcel is unioned into a single Area before stroking rather than
 * drawing 64 individual tile polygons -- otherwise every interior tile edge
 * gets stroked too and the result reads as a grid of squares instead of one
 * plot of land.
 */
public class ParcelOverlay extends Overlay
{
	private static final int ALPHA_FILL = 40;
	private static final Color UNCLAIMABLE = new Color(0x80, 0x80, 0x80);
	/** Neutral, and not any tier colour. See renderLabel. */
	private static final Color UNKNOWN = new Color(0xB0, 0xB0, 0xC0);

	private final Client client;
	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;

	@Inject
	private ParcelOverlay(Client client, GielinorDeedsPlugin plugin, GielinorDeedsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Parcel p = plugin.getCurrentParcel();
		if (p == null || !config.showParcelBorder())
		{
			return null;
		}

		boolean owned = plugin.getEstate().owns(p.getPid());
		boolean known = plugin.isKnown(p);
		// An unknown parcel must not leak its tier through its colour, so it is
		// drawn in neutral grey until surveyed.
		Color base = !known ? UNKNOWN
			: owned ? p.getTier().color()
			: (p.isClaimable() ? Color.WHITE : UNCLAIMABLE);

		Area area = footprintOf(p);
		if (area == null)
		{
			return null;
		}

		if (owned && known && config.showOwnedTint())
		{
			g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), ALPHA_FILL));
			g.fill(area);
		}
		g.setColor(base);
		g.draw(area);

		renderLabel(g, p, owned);
		return null;
	}

	/**
	 * Union of the parcel's tiles that are currently in the loaded scene.
	 * Returns null when none are -- a player can stand in a parcel whose far
	 * corners are outside the scene, and getCanvasTilePoly returns null there.
	 */
	private Area footprintOf(Parcel p)
	{
		Area area = new Area();
		WorldPoint sw = p.getSouthWest();
		int cell = plugin.getGrid().getCell();
		boolean any = false;

		for (int dx = 0; dx < cell; dx++)
		{
			for (int dy = 0; dy < cell; dy++)
			{
				LocalPoint lp = LocalPoint.fromWorld(client,
					new WorldPoint(sw.getX() + dx, sw.getY() + dy, 0));
				if (lp == null)
				{
					continue;
				}
				Polygon poly = Perspective.getCanvasTilePoly(client, lp);
				if (poly != null)
				{
					area.add(new Area(poly));
					any = true;
				}
			}
		}
		return any ? area : null;
	}

	private void renderLabel(Graphics2D g, Parcel p, boolean owned)
	{
		boolean known = plugin.isKnown(p);
		String tier = p.getTier().getDisplayName();
		String text;

		if (!known)
		{
			// Nothing but the fact that it is unknown. Price, tier and even
			// whether it can be claimed are what the survey buys.
			text = plugin.canSurvey() ? "Unsurveyed -- 1 charge" : "Unsurveyed";
		}
		else if (owned)
		{
			text = (p.isLandmark() ? p.getLandmark().getDisplayName() : tier) + " -- yours";
		}
		else if (!p.isClaimable())
		{
			text = tier + " -- cannot be claimed";
		}
		else if (p.isLandmark())
		{
			text = p.getLandmark().getDisplayName() + " -- " + plugin.priceOf(p) + " to buy";
		}
		else
		{
			text = tier + " -- " + plugin.priceOf(p) + " to buy";
		}

		LocalPoint centre = LocalPoint.fromWorld(client, p.getSouthWest().dx(4).dy(4));
		if (centre == null)
		{
			return;
		}
		Point loc = Perspective.getCanvasTextLocation(client, g, centre, text, 0);
		if (loc != null)
		{
			OverlayUtil.renderTextLocation(g, loc, text, Color.WHITE);
		}
	}
}
