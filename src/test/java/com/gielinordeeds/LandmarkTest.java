/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Landmarks are hand-authored coordinates, which makes them the most likely
 * thing in the plugin to be quietly wrong. These check every entry against the
 * shipped survey rather than trusting the table.
 */
public class LandmarkTest
{
	private static ParcelGrid grid;

	@BeforeClass
	public static void loadSurvey() throws Exception
	{
		grid = ParcelGrid.load();
	}

	private Parcel parcelOf(Landmark lm)
	{
		return grid.at(new WorldPoint(lm.getX(), lm.getY(), 0));
	}

	@Test
	public void everyLandmarkResolvesToAParcel()
	{
		assertEquals("some landmark fell outside the surveyed grid",
			Landmark.values().length, grid.landmarkCount());
		for (Landmark lm : Landmark.values())
		{
			assertNotNull(lm.getDisplayName(), parcelOf(lm));
		}
	}

	@Test
	public void everyLandmarkIsClaimable()
	{
		for (Landmark lm : Landmark.values())
		{
			Parcel p = parcelOf(lm);
			assertTrue(lm.getDisplayName() + " is not claimable", p.isClaimable());
			assertTrue(lm.getDisplayName() + " is not flagged as a landmark", p.isLandmark());
			assertEquals(lm, p.getLandmark());
		}
	}

	@Test
	public void landmarksAllCostTheSameAndMoreThanAnyOrdinaryParcel()
	{
		for (Landmark lm : Landmark.values())
		{
			assertEquals(lm.getDisplayName(), Landmark.PRICE, parcelOf(lm).getPrice());
		}
		// The dearest ordinary parcel must still be cheaper, or landmarks stop
		// reading as trophies.
		int dearest = 0;
		for (int i = 0; i < grid.size(); i++)
		{
			Parcel p = grid.atIndex(i);
			if (p != null && !p.isLandmark())
			{
				dearest = Math.max(dearest, p.getPrice());
			}
		}
		assertTrue("landmark price " + Landmark.PRICE + " <= dearest parcel " + dearest,
			Landmark.PRICE > dearest);
	}

	@Test
	public void noLandmarkSitsOnAWaterParcel()
	{
		// Landmarks are always claimable, so one on a water parcel does not break
		// anything outright -- it just quietly shows as "Open water" in the Deed
		// Log, which reads as a bug. This caught the Fishing Guild when the water
		// data improved and Lake Hemenster grew into its parcel.
		for (Landmark lm : Landmark.values())
		{
			Tier t = parcelOf(lm).getTier();
			assertTrue(lm.getDisplayName() + " is on " + t.getDisplayName(),
				t != Tier.WATER && t != Tier.OFFMAP);
		}
	}

	@Test
	public void noTwoLandmarksShareAParcel()
	{
		// Two famous places in one 8x8 parcel would make one of them unobtainable.
		Map<String, Landmark> seen = new HashMap<>();
		for (Landmark lm : Landmark.values())
		{
			Landmark prev = seen.put(parcelOf(lm).getPid(), lm);
			assertTrue(lm.getDisplayName() + " shares a parcel with "
				+ (prev == null ? "" : prev.getDisplayName()), prev == null);
		}
	}

	@Test
	public void namesAreUnique()
	{
		Set<String> names = new HashSet<>();
		for (Landmark lm : Landmark.values())
		{
			assertTrue("duplicate name " + lm.getDisplayName(), names.add(lm.getDisplayName()));
		}
	}

	@Test
	public void landmarksLandInTheKingdomAPlayerWouldName()
	{
		assertKingdom(Landmark.BRIMHAVEN, Kingdom.KARAMJA);
		assertKingdom(Landmark.MUSA_POINT, Kingdom.KARAMJA);
		assertKingdom(Landmark.TAI_BWO_WANNAI, Kingdom.KARAMJA);
		assertKingdom(Landmark.PORT_SARIM, Kingdom.ASGARNIA);
		assertKingdom(Landmark.FALADOR_PARTY_ROOM, Kingdom.ASGARNIA);
		assertKingdom(Landmark.VARROCK_SQUARE, Kingdom.MISTHALIN);
		assertKingdom(Landmark.CAMELOT_CASTLE, Kingdom.KANDARIN);
		assertKingdom(Landmark.WILDERNESS_DITCH, Kingdom.WILDERNESS);
		assertKingdom(Landmark.MARIM, Kingdom.APE_ATOLL);
		assertKingdom(Landmark.HOSIDIUS, Kingdom.KOUREND);
		assertKingdom(Landmark.PRIFDDINAS, Kingdom.TIRANNWN);
	}

	private void assertKingdom(Landmark lm, Kingdom expected)
	{
		assertEquals(lm.getDisplayName(), expected, Kingdom.of(parcelOf(lm)));
	}

	@Test
	public void findingALandmarkFillsItsSlotInTheLog()
	{
		DeedLog log = new DeedLog(grid);
		Estate e = new Estate();
		assertEquals(0, log.snapshot(e).getLandmarksFound().size());

		Parcel camelot = parcelOf(Landmark.CAMELOT_CASTLE);
		e.markSurveyed(grid.indexOf(camelot));
		DeedLog.Snapshot found = log.snapshot(e);
		assertTrue(found.getLandmarksFound().contains(Landmark.CAMELOT_CASTLE));
		assertTrue(found.getLandmarksOwned().isEmpty());

		e.getOwned().put(camelot.getPid(), camelot.getPrice());
		assertTrue(log.snapshot(e).getLandmarksOwned().contains(Landmark.CAMELOT_CASTLE));
	}
}
