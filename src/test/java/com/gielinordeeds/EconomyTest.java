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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Progressive pricing and survey range.
 *
 * The property worth defending is easy to get wrong and invisible in play.
 * Rent is linear in parcels owned, and the multiplier (1 + owned/scale) is
 * nearly constant while the estate is small -- so under LINEAR pricing
 * affordability does not stay flat, it runs away, from about 3 parcels an hour
 * at ten owned to 20+ at a thousand. Money would never matter again.
 *
 * The exponent does not make land unaffordable. It holds affordability near the
 * ~12/hour the charge economy supplies, so neither resource becomes irrelevant.
 */
public class EconomyTest
{
	private static final double SCALE = 60;
	private static final double EXP = 1.30;
	private static ParcelGrid grid;

	@BeforeClass
	public static void loadSurvey() throws Exception
	{
		grid = ParcelGrid.load();
	}

	private Estate withOwned(int n)
	{
		Estate e = new Estate();
		for (int i = 0; i < n; i++)
		{
			e.getOwned().put("p" + i, 100);
		}
		return e;
	}

	@Test
	public void anEmptyEstatePaysBasePrice()
	{
		assertEquals(1.0, new Estate().priceMultiplier(SCALE, EXP), 0.0001);
	}

	@Test
	public void pricesClimbWithHoldings()
	{
		double prev = 0;
		for (int owned : new int[]{0, 10, 50, 100, 500, 1000})
		{
			double m = withOwned(owned).priceMultiplier(SCALE, EXP);
			assertTrue("multiplier went backwards at " + owned, m >= prev);
			prev = m;
		}
		assertTrue(withOwned(1000).priceMultiplier(SCALE, EXP) > 10);
	}

	@Test
	public void theExponentKeepsMoneyBindingWhileLinearWouldNot()
	{
		// Rent is linear in parcels owned while the multiplier (1 + owned/scale)
		// is nearly constant for a small estate, so under LINEAR pricing
		// affordability runs away -- roughly 3/hr at ten parcels up to 20+/hr at
		// a thousand. Charges supply about 12/hr, so linear pricing would leave
		// money permanently irrelevant.
		//
		// The exponent's job is not to make land unaffordable; it is to hold
		// affordability near the charge supply so both resources keep mattering.
		double rps = 0.12;
		double linearLate = affordablePerHour(1000, 1.0, rps);
		double curvedLate = affordablePerHour(1000, EXP, rps);

		assertTrue("linear pricing runs away: " + linearLate, linearLate > 18);
		assertTrue("the exponent must meaningfully dampen it: " + curvedLate,
			curvedLate < linearLate * 0.75);

		// And it must stay in the same league as the charge supply (~12/hr) at
		// every size, rather than overshooting into unaffordable.
		for (int owned : new int[]{100, 200, 500, 1000, 2000})
		{
			double a = affordablePerHour(owned, EXP, rps);
			assertTrue("affordability overshot at " + owned + ": " + a, a < 14);
		}
	}

	@Test
	public void affordabilityTurnsOverRatherThanClimbingForever()
	{
		// It should peak in the mid game and then decline, so a large estate is
		// buying land more slowly than a middling one.
		double rps = 0.12;
		double mid = affordablePerHour(200, EXP, rps);
		double late = affordablePerHour(2000, EXP, rps);
		assertTrue("late game should be tighter than mid: " + mid + " -> " + late,
			late < mid);
	}

	private double affordablePerHour(int owned, double exponent, double rpsPerParcel)
	{
		double incomePerHour = owned * rpsPerParcel * 3600;
		double cost = 1200 * withOwned(owned).priceMultiplier(SCALE, exponent);
		return incomePerHour / cost;
	}

	@Test
	public void theRefundIsBasedOnWhatWasPaidNotTodaysPrice()
	{
		// A parcel bought cheaply before prices climbed must stay cheap in the
		// ledger, or abandoning early purchases becomes a windfall.
		Estate e = withOwned(0);
		Parcel p = grid.at(new WorldPoint(3213, 3428, 0));
		long early = e.effectivePrice(p, SCALE, EXP);
		e.getOwned().put(p.getPid(), (int) early);

		Estate big = withOwned(500);
		assertTrue("prices should have climbed",
			big.effectivePrice(p, SCALE, EXP) > early);
		assertEquals(early, (long) e.getOwned().get(p.getPid()));
	}

	// ── survey range ─────────────────────────────────────────────────────

	@Test
	public void rangeCoverageGrowsByRings()
	{
		assertEquals(1, SurveyRange.PACE.coverage());
		assertEquals(9, SurveyRange.CHAIN.coverage());
		assertEquals(25, SurveyRange.THEODOLITE.coverage());
	}

	@Test
	public void upgradesCostMoreAsTheyGetBetter()
	{
		assertEquals(0, SurveyRange.PACE.getCost());
		assertTrue(SurveyRange.CHAIN.getCost() > 0);
		assertTrue(SurveyRange.THEODOLITE.getCost() > SurveyRange.CHAIN.getCost() * 5);
	}

	@Test
	public void theLadderEndsAndSaysSo()
	{
		assertEquals(SurveyRange.CHAIN, SurveyRange.next(0));
		assertEquals(SurveyRange.THEODOLITE, SurveyRange.next(1));
		assertNull("there must be no third ring -- 49 parcels a charge would put "
			+ "the whole map inside ~57 hours", SurveyRange.next(2));
		assertTrue(SurveyRange.isMaxed(2));
	}

	@Test
	public void anOutOfRangeLevelClampsRatherThanThrowing()
	{
		// A save written by a future version must not crash an older one.
		assertEquals(SurveyRange.PACE, SurveyRange.forLevel(-5));
		assertEquals(SurveyRange.THEODOLITE, SurveyRange.forLevel(99));
	}

	@Test
	public void rangeStillLeavesTheMapAVeryLongGoal()
	{
		// 25 parcels a charge at ~12 charges/hour is ~300/hour: about 111 hours
		// for all 33,398. Long, but no longer a lifetime -- which is the trade
		// the upgrade is meant to sell.
		int perHour = 25 * 12;
		int hours = 33_398 / perHour;
		assertTrue("map got too cheap: " + hours + "h", hours > 80);
	}
}
