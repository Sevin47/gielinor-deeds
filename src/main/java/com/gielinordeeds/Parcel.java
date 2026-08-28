/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One surveyed parcel: 8x8 game tiles of plane-0 Gielinor. */
@Value
public class Parcel
{
	String pid;
	int px;
	int py;
	Tier tier;
	/** Tier base price adjusted by how strongly the parcel commits to its tier. */
	int price;
	WorldPoint southWest;
	/** Set when a famous place stands here. Null for ordinary ground. */
	@Nullable Landmark landmark;

	public double rps()
	{
		return tier.rpsFor(price);
	}

	/**
	 * A landmark is always claimable, whatever the terrain classifier made of
	 * the ground under it. Otherwise regenerating the survey could put
	 * a famous place on a water parcel and delete it from the game.
	 */
	public boolean isClaimable()
	{
		return landmark != null || tier.isBuyable();
	}

	public boolean isLandmark()
	{
		return landmark != null;
	}

	/** What this parcel is called: the landmark, or just its grid id. */
	public String displayName()
	{
		return landmark != null ? landmark.getDisplayName() : "Parcel " + pid;
	}
}
