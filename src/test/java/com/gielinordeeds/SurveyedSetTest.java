/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import com.google.gson.Gson;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The surveyed set: what the player has explored.
 *
 * This is the one piece of state that cannot be recovered if it is lost -- land
 * can be re-bought, but re-walking thousands of parcels cannot be undone. So the
 * round trip through JSON is pinned here, including the shape it actually takes
 * in RuneLite's config store rather than just the in-memory behaviour.
 */
public class SurveyedSetTest
{
	private static final int GRID = 115_200;
	private final Gson gson = new Gson();

	private Estate roundTrip(Estate e)
	{
		e.packSurveyed();
		return gson.fromJson(gson.toJson(e), Estate.class);
	}

	@Test
	public void survivesAJsonRoundTrip()
	{
		Estate e = new Estate();
		int[] marked = {0, 1, 4_231, 55_555, GRID - 1};
		for (int i : marked)
		{
			e.markSurveyed(i);
		}

		Estate back = roundTrip(e);
		for (int i : marked)
		{
			assertTrue("lost parcel " + i, back.hasSurveyed(i));
		}
		assertEquals(marked.length, back.surveyedCount());
		assertFalse(back.hasSurveyed(2));
	}

	@Test
	public void aFreshEstateHasSurveyedNothing()
	{
		Estate back = roundTrip(new Estate());
		assertEquals(0, back.surveyedCount());
		assertFalse(back.hasSurveyed(0));
	}

	@Test
	public void outOfRangeIndexesAreIgnoredRatherThanThrowing()
	{
		Estate e = new Estate();
		e.markSurveyed(-1);
		assertFalse(e.hasSurveyed(-1));
		assertEquals(0, e.surveyedCount());
	}

	@Test
	public void aCorruptRecordCostsExplorationButNotTheEstate()
	{
		// Losing the survey record is bad; refusing to load the save -- and with
		// it the player's land and balance -- would be worse.
		Estate e = new Estate();
		e.setBalance(4321);
		e.setSurveyedBits("this is not valid base64 deflate data!!");
		assertEquals(0, e.surveyedCount());
		assertEquals(4321, e.getBalance());
	}

	@Test
	public void ownedLandCountsAsSurveyed() throws Exception
	{
		// Reproduces the save shape that existed before the surveyed set: land in
		// the owned map, nothing surveyed. Buying requires surveying first, so
		// this state is unreachable in play and must repair itself on load.
		ParcelGrid grid = ParcelGrid.load();
		Estate e = new Estate();
		e.getOwned().put("289_107", 1200);
		e.getOwned().put("290_133", 195);
		assertEquals(0, e.surveyedCount());

		assertEquals(2, e.reconcileOwnedAsSurveyed(grid));
		assertTrue(e.hasSurveyed(grid.indexOf(grid.byPid("289_107"))));
		assertTrue(e.hasSurveyed(grid.indexOf(grid.byPid("290_133"))));

		// Idempotent: a second load must not keep reporting work to do, or it
		// would rewrite the save file on every login forever.
		assertEquals(0, e.reconcileOwnedAsSurveyed(grid));
	}

	@Test
	public void reconcileIgnoresJunkPids() throws Exception
	{
		ParcelGrid grid = ParcelGrid.load();
		Estate e = new Estate();
		e.getOwned().put("not_a_pid", 1);
		e.getOwned().put("9999_9999", 1);
		assertEquals(0, e.reconcileOwnedAsSurveyed(grid));
		assertEquals(0, e.surveyedCount());
	}

	@Test
	public void staysSmallEnoughForTheConfigStore()
	{
		// A set of "px_py" strings would be ~88 KB at this size, growing forever.
		// The compressed bitset is what keeps a heavily-explored save reasonable
		// to sit in RuneLite's properties file.
		Estate e = new Estate();
		Random rnd = new Random(42);
		for (int i = 0; i < 20_000; i++)
		{
			e.markSurveyed(rnd.nextInt(GRID));
		}
		e.packSurveyed();
		int kb = e.getSurveyedBits().length() / 1024;
		assertTrue("20k scattered parcels serialised to " + kb + " KB", kb < 30);

		Estate back = gson.fromJson(gson.toJson(e), Estate.class);
		assertEquals(e.surveyedCount(), back.surveyedCount());
	}
}
