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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Deed Locked lives or dies on two rules, so both are pinned here.
 *
 * The parked design for this mode said an 8x8 deed was too small to be the
 * unit of access: you cannot train on 64 tiles, so you cannot earn charges, so
 * you never unlock anything and the run is dead before it begins. The opening
 * grant and the frontier rule are the answer to that, and if either regresses
 * the mode goes back to being unplayable in exactly that way.
 */
public class DeedLockTest
{
	private static ParcelGrid grid;

	@BeforeClass
	public static void load() throws Exception
	{
		grid = ParcelGrid.load();
		assertNotNull("parcels.bin should be packaged with the plugin", grid);
	}

	/**
	 * Buy a deed outright, the way a run does before it can survey past it.
	 *
	 * The price does not matter here -- these tests are about the frontier
	 * rule, not the economy -- so it goes in the ledger at 1.
	 */
	private static void buy(Estate e, Parcel p)
	{
		assertNotNull(p);
		e.markSurveyed(grid.indexOf(p));
		e.getOwned().put(p.getPid(), 1);
	}

	private static Parcel somewhereClaimable()
	{
		// Lumbridge -- the safe opening pick, and claimable ground either side.
		Parcel p = grid.at(new net.runelite.api.coords.WorldPoint(3222, 3218, 0));
		assertNotNull("expected a parcel at Lumbridge", p);
		return p;
	}

	/**
	 * The opening is deliberately tiny: one deed owned, a few in view.
	 *
	 * This test used to insist on a town's worth -- "under 1000 tiles is not a
	 * viable start" -- on the assumption that the veil is a wall you have to
	 * live inside. It is not: nothing blocks movement and the menu filter is
	 * off by default, so a fresh account still has the whole of Lumbridge to
	 * train in. What the opening bounds is what you can SEE and OWN, and
	 * keeping that small is the point rather than a problem to be solved.
	 */
	@Test
	public void theOpeningIsOneDeedAndSomeChargesToSpend()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		int opened = DeedLock.grantStart(grid, e, start);

		assertEquals("one deed, and only the one", 1, opened);
		assertEquals("nothing else is surveyed", 1, e.surveyedCount());
		assertEquals("and only that one is owned", 1, e.getOwned().size());
		assertTrue(e.owns(start.getPid()));
		assertEquals("what you get instead of ground is charges to spend",
			DeedLock.GRANT_CHARGES, e.getCharges().getCharges());
	}

	/** A run that has already started never gets a second grant. */
	@Test
	public void theGrantFiresOnlyOnce()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		assertTrue(DeedLock.grantStart(grid, e, start) > 0);
		int after = e.surveyedCount();
		assertEquals("a second call must open nothing", 0,
			DeedLock.grantStart(grid, e, start));
		assertEquals(after, e.surveyedCount());
		// and not even somewhere else on the map
		assertEquals(0, DeedLock.grantStart(grid, e,
			grid.at(new net.runelite.api.coords.WorldPoint(3213, 3428, 0))));
	}

	/**
	 * Frontier surveying is what stops the mode deadlocking. Blacked-out ground
	 * is ground you are not meant to walk onto, so if opening it required
	 * standing on it the run could never expand.
	 */
	@Test
	public void onlyLandTouchingTheEstateCanBeSurveyed()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		DeedLock.grantStart(grid, e, start);

		int side = 1;
		// Neighbours are shown but not given, so nothing is on the
		// frontier until one of them is bought.
		Parcel edge = grid.at(start.getPx() + side, start.getPy());
		buy(e, edge);

		Parcel justOutside = grid.at(start.getPx() + side + 1, start.getPy());
		Parcel farAway = grid.at(start.getPx() + 40, start.getPy() + 40);

		assertTrue("the deed just past a deed you own is on the frontier",
			DeedLock.onFrontier(grid, e, justOutside));
		assertFalse("ground across the map does not",
			DeedLock.onFrontier(grid, e, farAway));
		assertFalse("nor does land already held",
			DeedLock.onFrontier(grid, e, start));
		assertFalse(DeedLock.onFrontier(grid, e, null));
	}

	/** Diagonals count, or diagonal expansion would cost two deeds instead of one. */
	@Test
	public void theFrontierIncludesDiagonals()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		DeedLock.grantStart(grid, e, start);
		int r = 1;
		buy(e, grid.at(start.getPx() + r, start.getPy() + r));
		Parcel corner = grid.at(start.getPx() + r + 1, start.getPy() + r + 1);
		assertTrue("the diagonal corner touches the deed just bought",
			DeedLock.onFrontier(grid, e, corner));
	}

	/**
	 * Expanding one deed at a time keeps working, step after step -- but each
	 * step has to be BOUGHT, not merely surveyed.
	 *
	 * This test used to survey its way across the map without spending a coin,
	 * which is exactly the hole the ownership frontier closes: charges alone
	 * could open ground forever and money bought nothing you could not already
	 * reach.
	 */
	@Test
	public void theFrontierAdvancesAsTheEstateIsBought()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		DeedLock.grantStart(grid, e, start);

		int px = start.getPx() + 1;
		for (int step = 1; step <= 5; step++)
		{
			Parcel next = grid.at(px + step, start.getPy());
			assertNotNull("ran off the grid at step " + step, next);
			if (step == 1)
			{
				// The opening shows neighbours but gives none, so there is
				// no frontier at all until one is bought.
				for (int i = 1; i <= 1; i++)
				{
					buy(e, grid.at(start.getPx() + i, start.getPy()));
				}
			}
			assertTrue("step " + step + " should be on the frontier",
				DeedLock.onFrontier(grid, e, next));

			e.markSurveyed(grid.indexOf(next));
			assertFalse("and not still on it once surveyed",
				DeedLock.onFrontier(grid, e, next));

			// Surveying it does not open the deed beyond it. Buying it does.
			Parcel beyond = grid.at(px + step + 1, start.getPy());
			assertNotNull(beyond);
			assertFalse("surveying must not move the ring at step " + step,
				DeedLock.onFrontier(grid, e, beyond));
			e.getOwned().put(next.getPid(), 1);
			assertTrue("buying must move the ring at step " + step,
				DeedLock.onFrontier(grid, e, beyond));
		}
	}

	/**
	 * You start owning one deed and looking at twenty-four you do not.
	 *
	 * The block is surveyed so you can see it and price it; only the ground
	 * under your feet is yours. That makes the frontier empty at minute zero --
	 * every neighbour of your one deed is already surveyed, and surveyed ground
	 * is not frontier -- so the first thing a run can do is buy, not survey.
	 * The two currencies interlock from the first move.
	 */
	@Test
	public void youStartOwningOnlyTheDeedYouStandOn()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		int opened = DeedLock.grantStart(grid, e, start);

		assertEquals("only the deed you stand on is surveyed", 1, e.surveyedCount());
		assertEquals("and only it is owned", 1, e.getOwned().size());
		assertTrue("and it is the one you are standing on", e.owns(start.getPid()));
		assertTrue("given, not sold", e.getOwned().get(start.getPid()) == 0);

		// Every neighbour is unsurveyed and touches land you own, so the whole
		// ring is open and which way you look is entirely your decision.
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				Parcel q = grid.at(start.getPx() + dx, start.getPy() + dy);
				if ((dx != 0 || dy != 0) && q != null && q.isClaimable())
				{
					assertTrue("every direction should be open to survey",
						DeedLock.onFrontier(grid, e, q));
				}
			}
		}

		Parcel shown = grid.at(start.getPx() + 1, start.getPy());
		assertNotNull(shown);

		// Two deeds out touches nothing you own, in any direction. The ring
		// around you is frontier; nothing past it is, until you buy.
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				if (Math.abs(dx) < 2 && Math.abs(dy) < 2)
				{
					continue;
				}
				assertFalse("nothing two deeds out touches owned land yet",
					DeedLock.onFrontier(grid, e,
						grid.at(start.getPx() + dx, start.getPy() + dy)));
			}
		}

		// Buying a neighbour is what moves the ring outward.
		buy(e, shown);
		assertTrue("owning a neighbour opens the ground past it",
			DeedLock.onFrontier(grid, e, grid.at(
				start.getPx() + 2 * (shown.getPx() - start.getPx()),
				start.getPy() + 2 * (shown.getPy() - start.getPy()))));
	}

	/**
	 * A run does not begin somewhere it could never move.
	 *
	 * If nothing in the opening block can be owned -- open water, say -- then
	 * granting the survey anyway would start a run with no owned deed, so no
	 * frontier, so no way to ever survey again. Nothing is written instead, and
	 * the run begins properly wherever the player next stands.
	 */
	@Test
	public void aRunDoesNotBeginWhereNothingCanBeOwned()
	{
		Estate e = new Estate();
		Parcel unclaimable = null;
		for (int i = 0; i < grid.size() && unclaimable == null; i++)
		{
			Parcel p = grid.atIndex(i);
			if (p != null && !p.isClaimable())
			{
				// Deep enough inside unclaimable ground that the whole opening
				// block is unclaimable too.
				boolean all = true;
				for (int dx = -1; dx <= 1; dx++)
				{
					for (int dy = -1; dy <= 1; dy++)
					{
						Parcel q = grid.at(p.getPx() + dx, p.getPy() + dy);
						all &= q == null || !q.isClaimable();
					}
				}
				if (all)
				{
					unclaimable = p;
				}
			}
		}
		assertNotNull("expected some open water on the map", unclaimable);

		assertEquals("nothing is granted out there", 0,
			DeedLock.grantStart(grid, e, unclaimable));
		assertEquals("and nothing is surveyed either", 0, e.surveyedCount());
		assertTrue("so the run can still begin on land",
			DeedLock.grantStart(grid, e, somewhereClaimable()) > 0);
	}

	/**
	 * Trespass is the same question as the veil, so it is the same call.
	 *
	 * It used to have its own spelling that took an already-resolved parcel --
	 * which is precisely what cannot be resolved on an upper floor, so the
	 * warning fell silent up there while the veil went on covering the ground.
	 * One rule, asked of a point, cannot drift like that.
	 */
	@Test
	public void standingOffYourOwnLandIsReportedAsTrespass()
	{
		Estate e = new Estate();
		Parcel start = somewhereClaimable();
		DeedLock.grantStart(grid, e, start);

		assertFalse("inside the grant is not trespassing",
			DeedLock.isVeiled(grid, e, start.getSouthWest()));
		assertTrue("outside it is",
			DeedLock.isVeiled(grid, e, somewhereUnsurveyed(e)));
		assertTrue("and still is one floor up",
			DeedLock.isVeiled(grid, e, atPlane(somewhereUnsurveyed(e), 1)));
		assertFalse("as is standing over your own land, one floor up",
			DeedLock.isVeiled(grid, e, atPlane(start.getSouthWest(), 1)));
		assertFalse(DeedLock.isVeiled(grid, e, null));
	}
	// ---------------------------------------------------------------------
	// What the veil covers.
	//
	// Both of these were reported from play rather than caught here, which is
	// the point of pinning them: the veil looked completely correct on the
	// ground floor of the surface, and that is most of the game.
	// ---------------------------------------------------------------------

	/** A deed the opening grant does not reach, for the "not yours" side. */
	private static WorldPoint somewhereUnsurveyed(Estate e)
	{
		Parcel start = somewhereClaimable();
		Parcel far = grid.at(start.getPx() + 40, start.getPy());
		assertNotNull("expected grid to extend 40 deeds east of Lumbridge", far);
		assertFalse("this deed is supposed to be outside the grant",
			e.hasSurveyed(grid.indexOf(far)));
		return far.getSouthWest();
	}

	/**
	 * The reported bug: climb a ladder in a building on the estate boundary and
	 * the whole floor was veiled, the half over your own land included.
	 *
	 * A deed is a piece of ground, so the storey above it is judged by the
	 * ground. Asking about plane 1 directly answered "not surveyable, therefore
	 * not surveyed" for every deed up there.
	 */
	@Test
	public void anUpperFloorIsVeiledExactlyLikeTheGroundBeneathIt()
	{
		Estate e = new Estate();
		WorldPoint owned = somewhereClaimable().getSouthWest();
		DeedLock.grantStart(grid, e, somewhereClaimable());
		WorldPoint theirs = somewhereUnsurveyed(e);

		for (int plane = 0; plane <= 3; plane++)
		{
			assertFalse("floor " + plane + " over your own deed must be clear",
				DeedLock.isVeiled(grid, e, atPlane(owned, plane)));
			assertTrue("floor " + plane + " over an unsurveyed deed stays veiled",
				DeedLock.isVeiled(grid, e, atPlane(theirs, plane)));
		}
	}

	/**
	 * The same building, half on each side of the boundary: upstairs has to
	 * come out half veiled, not all of it and not none of it.
	 */
	@Test
	public void aBuildingStraddlingTheBoundaryIsHalfVeiledUpstairs()
	{
		Estate e = new Estate();
		Parcel mine = somewhereClaimable();
		int idx = grid.indexOf(mine);
		e.markSurveyed(idx);

		Parcel next = grid.at(mine.getPx() + 1, mine.getPy());
		assertNotNull(next);
		assertFalse("the deed next door is deliberately not surveyed",
			e.hasSurveyed(grid.indexOf(next)));

		WorldPoint upstairsMine = atPlane(mine.getSouthWest(), 1);
		WorldPoint upstairsTheirs = atPlane(next.getSouthWest(), 1);
		assertFalse("your half of the first floor must stay clear",
			DeedLock.isVeiled(grid, e, upstairsMine));
		assertTrue("their half of the same floor must stay veiled",
			DeedLock.isVeiled(grid, e, upstairsTheirs));
	}

	/**
	 * Underground is not part of the survey, so nothing down there is veiled.
	 *
	 * Dungeons sit in their own band of the world map, well north of the
	 * surface the grid covers, so there is no deed to be off -- and treating
	 * "no deed here" as "not your deed" painted whole caves out.
	 */
	@Test
	public void undergroundIsNeverVeiled()
	{
		Estate e = new Estate();
		DeedLock.grantStart(grid, e, somewhereClaimable());

		// Varrock sewers, roughly. The assert is the test: if the grid ever
		// grows to cover this, the reasoning above stops holding.
		WorldPoint sewer = new WorldPoint(3200, 9600, 0);
		assertFalse("this coordinate is supposed to be off the surveyed grid",
			grid.isSurveyable(sewer));

		for (int plane = 0; plane <= 3; plane++)
		{
			assertFalse("nothing underground is veiled, plane " + plane,
				DeedLock.isVeiled(grid, e, atPlane(sewer, plane)));
		}
	}

	/** Ground level still behaves, which is the half that already worked. */
	@Test
	public void theSurfaceIsUnaffected()
	{
		Estate e = new Estate();
		DeedLock.grantStart(grid, e, somewhereClaimable());

		assertFalse(DeedLock.isVeiled(grid, e, somewhereClaimable().getSouthWest()));
		assertTrue(DeedLock.isVeiled(grid, e, somewhereUnsurveyed(e)));
		assertFalse("no grid, nothing to veil", DeedLock.isVeiled(null, e,
			somewhereUnsurveyed(e)));
		assertFalse(DeedLock.isVeiled(grid, e, null));
	}

	private static WorldPoint atPlane(WorldPoint wp, int plane)
	{
		return new WorldPoint(wp.getX(), wp.getY(), plane);
	}
	// ---------------------------------------------------------------------
	// Open water: buyable so a run can cross to an island, but not land.
	// ---------------------------------------------------------------------

	@Test
	public void openWaterCanBeBoughtButPaysNothing()
	{
		assertTrue("water has to be buyable, or islands are unreachable",
			Tier.WATER.isBuyable());
		assertFalse("and it must not be land", Tier.WATER.isLand());
		assertEquals("a sea crossing is a cost, not an investment",
			0.0, Tier.WATER.rpsFor(50), 1e-9);
	}

	/**
	 * Water is excluded from the map there is to finish.
	 *
	 * There are 75,072 water parcels against 29,766 of land. Counting them
	 * would make the Deed Log a percentage of the ocean.
	 */
	@Test
	public void openWaterDoesNotCountTowardFinishingTheMap()
	{
		int land = 0, water = 0;
		for (int i = 0; i < grid.size(); i++)
		{
			Parcel p = grid.atIndex(i);
			if (p == null)
			{
				continue;
			}
			if (p.getTier() == Tier.WATER)
			{
				water++;
				assertTrue("water should be claimable", p.isClaimable());
				assertFalse("but never land", p.isLand());
			}
			else if (p.isLand())
			{
				land++;
			}
		}
		assertTrue("expected a lot of water", water > land);
		assertEquals("the Deed Log counts land only", land,
			new DeedLog(grid).getBuyableTotal());
	}

	/**
	 * A run never begins on water.
	 *
	 * The opening deed is the only thing paying rent at minute zero. Granted a
	 * water deed, an account would have no income at all and could never afford
	 * a second one.
	 */
	@Test
	public void aRunNeverBeginsOnWater()
	{
		Parcel sea = null;
		for (int i = 0; i < grid.size() && sea == null; i++)
		{
			Parcel p = grid.atIndex(i);
			if (p == null || p.getTier() != Tier.WATER)
			{
				continue;
			}
			boolean allSea = true;
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					Parcel q = grid.at(p.getPx() + dx, p.getPy() + dy);
					allSea &= q == null || !q.isLand();
				}
			}
			if (allSea)
			{
				sea = p;
			}
		}
		assertNotNull("expected some open sea on the map", sea);

		Estate e = new Estate();
		assertEquals("no land in reach, so no run begins", 0,
			DeedLock.grantStart(grid, e, sea));
		assertEquals(0, e.surveyedCount());
		assertTrue("but it still begins on land", DeedLock.grantStart(grid, e,
			somewhereClaimable()) > 0);
	}

	/** Water can be surveyed and bought like anything else on the frontier. */
	@Test
	public void aRunCanBridgeAcrossWater()
	{
		Parcel start = somewhereClaimable();
		Parcel sea = null;
		for (int i = 0; i < grid.size() && sea == null; i++)
		{
			Parcel p = grid.atIndex(i);
			if (p != null && p.getTier() == Tier.WATER)
			{
				sea = p;
			}
		}
		assertNotNull(sea);

		Estate e = new Estate();
		DeedLock.grantStart(grid, e, start);
		// Put the estate next door to the water, then check it can be crossed.
		e.markSurveyed(grid.indexOf(grid.at(sea.getPx() - 1, sea.getPy())));
		e.getOwned().put(grid.at(sea.getPx() - 1, sea.getPy()).getPid(), 1);

		assertTrue("water on the frontier must be surveyable",
			DeedLock.onFrontier(grid, e, sea));
		assertTrue("and buyable once surveyed", sea.isClaimable());
		assertEquals("without ever paying rent", 0.0, sea.rps(), 1e-9);
	}
}
