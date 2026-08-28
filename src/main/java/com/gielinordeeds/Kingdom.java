/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Named regions of Gielinor, for the Deed Log.
 *
 * The survey knows every parcel's RS region id, but "region 12853" means
 * nothing to a player. What people actually think in is kingdoms -- "I've
 * explored all of Asgarnia" -- so the log is grouped that way instead.
 *
 * These are axis-aligned boxes, which real kingdoms are not, so **order
 * matters**: the first box containing a point wins. Tight inner areas are
 * listed before the broad ones that surround them (Fremennik before Kandarin,
 * Fossil Island before nothing, and so on).
 *
 * Every one of 22 well-known settlements resolves to the kingdom a player would
 * name, which is the bar these boxes have to clear -- not cartographic
 * precision. Roughly a fifth of buyable land is scattered coastline and small
 * islands that belong to no kingdom; that is FRONTIER, and it is a real place to
 * finish rather than a bug to hide.
 */
@Getter
@RequiredArgsConstructor
public enum Kingdom
{
	// Order matters: tighter areas first. Karamja before Kandarin and Asgarnia,
	// or Brimhaven and Musa Point fall into the mainland boxes that overlap the
	// island; Asgarnia before Misthalin, or Port Sarim and the Falador party
	// room end up in Misthalin. Both were caught by validating landmarks.
	WILDERNESS("Wilderness", 2940, 3520, 3392, 3970),
	KARAMJA("Karamja", 2700, 2860, 3060, 3185),
	ASGARNIA("Asgarnia", 2860, 3185, 3072, 3560),
	MISTHALIN("Misthalin", 3010, 3130, 3390, 3520),
	FREMENNIK("Fremennik", 2500, 3600, 2940, 3900),
	KANDARIN("Kandarin", 2350, 3040, 2860, 3700),
	MORYTANIA("Morytania", 3390, 3130, 3790, 3610),
	FOSSIL_ISLAND("Fossil Island", 3600, 3660, 3900, 3900),
	KHARIDIAN("Kharidian Desert", 3130, 2700, 3600, 3130),
	FELDIP("Feldip Hills", 2300, 2760, 2700, 3060),
	TIRANNWN("Tirannwn", 2110, 3040, 2350, 3400),
	ISLE_OF_SOULS("Isle of Souls", 2050, 2750, 2350, 3060),
	APE_ATOLL("Ape Atoll", 2600, 2680, 2850, 2860),
	KEBOS("Kebos Lowlands", 1150, 3550, 1470, 3750),
	KOUREND("Kourend", 1470, 3380, 1900, 3800),
	LOVAKENGJ("Lovakengj", 1400, 3750, 1900, 4100),
	VARLAMORE("Varlamore", 1400, 2900, 1950, 3380),
	/** Everything else: outlying coast and small islands. Deliberately last. */
	FRONTIER("Frontier", 0, 0, 0, 0);

	private final String displayName;
	private final int minX;
	private final int minY;
	private final int maxX;
	private final int maxY;

	private static final Kingdom[] BOXED;

	static
	{
		Kingdom[] all = values();
		BOXED = new Kingdom[all.length - 1];        // every entry but FRONTIER
		System.arraycopy(all, 0, BOXED, 0, all.length - 1);
	}

	public boolean contains(int x, int y)
	{
		return x >= minX && x < maxX && y >= minY && y < maxY;
	}

	/** First box containing the point, or FRONTIER. Never null. */
	public static Kingdom of(int x, int y)
	{
		for (Kingdom k : BOXED)
		{
			if (k.contains(x, y))
			{
				return k;
			}
		}
		return FRONTIER;
	}

	public static Kingdom of(Parcel p)
	{
		return p == null ? FRONTIER
			: of(p.getSouthWest().getX(), p.getSouthWest().getY());
	}
}
