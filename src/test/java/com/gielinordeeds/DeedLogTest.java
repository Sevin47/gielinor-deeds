/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.api.coords.WorldPoint;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Deed Log's arithmetic.
 *
 * The log is a completion scoreboard, so its numbers have to be exactly right --
 * a percentage that cannot reach 100, or a kingdom that double-counts, quietly
 * ruins the only long-term goal the plugin has.
 */
public class DeedLogTest
{
	private static ParcelGrid grid;
	private static DeedLog log;

	@BeforeClass
	public static void loadSurvey() throws Exception
	{
		grid = ParcelGrid.load();
		log = new DeedLog(grid);
	}

	@Test
	public void kingdomsPartitionEveryClaimableParcel()
	{
		// Boxes overlap in principle -- first match wins -- so the only way to
		// know nothing is double-counted or dropped is that the parts sum to the
		// whole. FRONTIER is what makes this reachable.
		int sum = 0;
		for (Kingdom k : Kingdom.values())
		{
			sum += log.totalFor(k);
		}
		assertEquals(log.getBuyableTotal(), sum);
	}

	@Test
	public void tiersPartitionEveryClaimableParcel()
	{
		int sum = 0;
		for (Tier t : Tier.values())
		{
			sum += log.totalFor(t);
		}
		assertEquals(log.getBuyableTotal(), sum);
	}

	@Test
	public void unclaimableGroundIsExcludedFromTotals()
	{
		// A kingdom that counted its own coastline could never be finished.
		assertEquals(0, log.totalFor(Tier.WATER));
		assertEquals(0, log.totalFor(Tier.OFFMAP));
		assertTrue(log.getBuyableTotal() < grid.size());
	}

	@Test
	public void wellKnownTownsLandInTheKingdomAPlayerWouldName()
	{
		assertKingdom(3213, 3428, Kingdom.MISTHALIN);      // Varrock
		assertKingdom(2965, 3378, Kingdom.ASGARNIA);       // Falador
		assertKingdom(2757, 3477, Kingdom.KANDARIN);       // Camelot
		assertKingdom(3495, 3490, Kingdom.MORYTANIA);      // Canifis
		assertKingdom(3428, 2892, Kingdom.KHARIDIAN);      // Nardah
		assertKingdom(1740, 3550, Kingdom.KOUREND);        // Hosidius
		assertKingdom(2240, 3350, Kingdom.TIRANNWN);       // Prifddinas
		assertKingdom(2660, 3660, Kingdom.FREMENNIK);      // Rellekka
		assertKingdom(2852, 2952, Kingdom.KARAMJA);        // Shilo Village
	}

	private void assertKingdom(int x, int y, Kingdom expected)
	{
		assertEquals("(" + x + "," + y + ")", expected, Kingdom.of(x, y));
	}

	@Test
	public void anUntouchedEstateHasFoundNothing()
	{
		DeedLog.Snapshot s = log.snapshot(new Estate());
		assertEquals(0, s.getOverall().getSurveyed());
		assertEquals(0, s.kingdomsEntered());
		assertEquals(0, s.tierTypesOwned());
		assertEquals(log.getBuyableTotal(), s.getOverall().getTotal());
	}

	@Test
	public void surveyingOneParcelMovesExactlyOneOfEachCounter()
	{
		Parcel varrock = grid.at(new WorldPoint(3213, 3428, 0));
		Estate e = new Estate();
		e.markSurveyed(grid.indexOf(varrock));
		e.getOwned().put(varrock.getPid(), varrock.getPrice());

		DeedLog.Snapshot s = log.snapshot(e);
		assertEquals(1, s.getOverall().getSurveyed());
		assertEquals(1, s.getOverall().getOwned());
		assertEquals(1, s.kingdom(Kingdom.MISTHALIN).getSurveyed());
		assertEquals(1, s.tier(varrock.getTier()).getSurveyed());
		assertEquals(1, s.kingdomsEntered());
		assertEquals(1, s.tierTypesOwned());
		// and nowhere else
		assertEquals(0, s.kingdom(Kingdom.ASGARNIA).getSurveyed());
	}

	@Test
	public void surveyingUnclaimableGroundDoesNotCount()
	{
		// You can survey water -- finding out is the point -- but it must not
		// count toward a completion total it was excluded from.
		Parcel water = null;
		for (int i = 0; i < grid.size() && water == null; i++)
		{
			Parcel p = grid.atIndex(i);
			if (p != null && p.getTier() == Tier.WATER)
			{
				water = p;
			}
		}
		assertTrue("no water parcel in the survey", water != null);

		Estate e = new Estate();
		e.markSurveyed(grid.indexOf(water));
		assertEquals(0, log.snapshot(e).getOverall().getSurveyed());
	}
}
