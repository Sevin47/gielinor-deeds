/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Survey charges.
 *
 * The rules being defended here are design rules, not implementation details:
 * idling earns nothing, efficient XP does not earn disproportionately more than
 * ordinary play, and logging in does not pay out a lifetime of XP at once.
 */
public class SurveyChargesTest
{
	private static final long HOUR = 3_600_000L;
	private static final int PER_HOUR = 8;
	private static final int XP_PER = 5000;
	private static final long T0 = 1_000_000_000L;

	@Test
	public void xpEarnsChargesAtTheConfiguredRate()
	{
		SurveyCharges c = new SurveyCharges();
		assertEquals(3, c.addXp(3 * XP_PER, T0, XP_PER, PER_HOUR));
		assertEquals(3, c.getCharges());
	}

	@Test
	public void idlingEarnsNothing()
	{
		SurveyCharges c = new SurveyCharges();
		c.addXp(0, T0, XP_PER, PER_HOUR);
		// Time passing on its own must never produce a charge -- that is the
		// whole distinction between rent and charges.
		assertEquals(0, c.allowanceRemaining(T0 + 10 * HOUR, PER_HOUR) - PER_HOUR);
		assertEquals(0, c.getCharges());
	}

	@Test
	public void theHourlyCapFlattensEfficientTraining()
	{
		// 200k XP/hr against 40k XP/hr: 5x the XP, but the same charges, because
		// Deeds is a game about going places rather than about XP rate.
		SurveyCharges fast = new SurveyCharges();
		fast.addXp(200_000, T0, XP_PER, PER_HOUR);

		SurveyCharges steady = new SurveyCharges();
		steady.addXp(40_000, T0, XP_PER, PER_HOUR);

		assertEquals(PER_HOUR, fast.getCharges());
		assertEquals(PER_HOUR, steady.getCharges());
	}

	@Test
	public void aBacklogCannotBeBankedAgainstAnEmptyCap()
	{
		SurveyCharges c = new SurveyCharges();
		c.addXp(200_000, T0, XP_PER, PER_HOUR);          // drains the allowance
		assertEquals(PER_HOUR, c.getCharges());

		// Grinding on with no allowance must not stockpile XP for a later dump.
		c.addXp(5_000_000, T0 + 60_000, XP_PER, PER_HOUR);
		assertEquals(PER_HOUR, c.getCharges());
		assertTrue(c.getXpProgress() <= XP_PER);

		// An hour later the bucket has refilled, but only to its ceiling.
		int granted = c.addXp(200_000, T0 + HOUR + 60_000, XP_PER, PER_HOUR);
		assertTrue("granted " + granted, granted <= PER_HOUR + 1);
	}

	@Test
	public void allowanceRefillsSmoothlyRatherThanInSteps()
	{
		SurveyCharges c = new SurveyCharges();
		c.addXp(200_000, T0, XP_PER, PER_HOUR);
		assertEquals(0, c.allowanceRemaining(T0, PER_HOUR));
		// Half an hour back should return about half the bucket, not zero and
		// then everything at once on the hour.
		assertEquals(PER_HOUR / 2, c.allowanceRemaining(T0 + HOUR / 2, PER_HOUR));
		assertEquals(PER_HOUR, c.allowanceRemaining(T0 + 2 * HOUR, PER_HOUR));
	}

	@Test
	public void levelUpsPayThroughTheCap()
	{
		SurveyCharges c = new SurveyCharges();
		c.addXp(200_000, T0, XP_PER, PER_HOUR);          // cap reached
		int before = c.getCharges();
		c.addLevel(70);
		assertTrue("level-ups must not be capped", c.getCharges() > before);
	}

	@Test
	public void theLevelCurveRisesAndIsBounded()
	{
		assertEquals(SurveyCharges.LEVEL_FLOOR, SurveyCharges.levelReward(2));
		assertEquals(SurveyCharges.LEVEL_CAP, SurveyCharges.levelReward(99));
		int prev = 0;
		for (int lvl = 2; lvl <= 99; lvl++)
		{
			int r = SurveyCharges.levelReward(lvl);
			assertTrue("level " + lvl + " went backwards", r >= prev);
			assertTrue(r >= SurveyCharges.LEVEL_FLOOR && r <= SurveyCharges.LEVEL_CAP);
			prev = r;
		}
	}

	@Test
	public void spendingRequiresACharge()
	{
		SurveyCharges c = new SurveyCharges();
		assertFalse(c.spend());
		c.grant(1);
		assertTrue(c.spend());
		assertFalse(c.spend());
		assertEquals(0, c.getCharges());
	}

	// ── the baseline, which is where this goes badly wrong if unguarded ──

	@Test
	public void theFirstSightingOfASkillPaysNothing()
	{
		// StatChanged reports TOTAL xp and fires for every skill just after
		// login. Treating that as a gain would hand a maxed account 200m XP of
		// charges on login -- enough to survey the map several times over.
		SkillBaseline b = new SkillBaseline();
		assertNull(b.observe(Skill.ATTACK, 13_034_431, 99));
		assertNull(b.observe(Skill.MINING, 200_000_000, 99));
	}

	@Test
	public void laterSightingsReportOnlyTheDifference()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.MINING, 100_000, 50);
		SkillBaseline.Delta d = b.observe(Skill.MINING, 100_500, 50);
		assertEquals(500, d.getXpGained());
		assertFalse(d.leveled());
	}

	@Test
	public void everyLevelCrossedIsReported()
	{
		// A lamp or a big drop can cross several levels at once; each should pay.
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.SLAYER, 1000, 10);
		SkillBaseline.Delta d = b.observe(Skill.SLAYER, 50_000, 13);
		assertEquals(10, d.getFromLevel());
		assertEquals(13, d.getToLevel());
		assertTrue(d.leveled());
	}

	@Test
	public void aResetForgetsBaselinesSoTheNextLoginReseeds()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.MINING, 100_000, 50);
		assertTrue(b.isSeeded());
		b.reset();
		assertFalse(b.isSeeded());
		assertNull(b.observe(Skill.MINING, 100_000, 50));
	}

	/**
	 * The complaint this pricing exists for: at a flat 5,000 a new account
	 * earning 8k XP an hour saw 1.6 charges, while anything past level 50 sat
	 * at the hourly cap. The cap is supposed to be what limits everyone.
	 */
	@Test
	public void aLowLevelAccountCanReachTheHourlyCapToo()
	{
		final int base = 5000, cap = 8;
		// XP an hour, roughly, at each level. The shape is what matters.
		int[][] rates = {{10, 8_000}, {30, 25_000}, {50, 45_000}, {99, 200_000}};
		double lowFlat = 8_000 / (double) base;
		assertTrue("a flat price leaves a new account far short of the cap",
			lowFlat < cap / 3.0);

		for (int[] r : rates)
		{
			long cost = SurveyCharges.xpCostFor(r[0], base);
			double perHour = Math.min(cap, r[1] / (double) cost);
			assertTrue("level " + r[0] + " should get a workable rate, got " + perHour,
				perHour >= 3.5);
		}
		// and by the middle of the game the cap is the only thing binding
		assertEquals(cap, Math.min(cap, 45_000 / (double) SurveyCharges.xpCostFor(50, base)), 0.01);
	}

	/** Nobody pays more than the configured figure, and the price only rises. */
	@Test
	public void thePriceRisesWithLevelAndTopsOutAtTheConfiguredFigure()
	{
		final int base = 5000;
		long prev = 0;
		for (int lvl = 1; lvl <= 99; lvl++)
		{
			long cost = SurveyCharges.xpCostFor(lvl, base);
			assertTrue("price must never exceed the configured figure", cost <= base);
			assertTrue("price must not fall as level rises", cost >= prev);
			prev = cost;
		}
		assertEquals("level 99 pays exactly the configured figure",
			base, SurveyCharges.xpCostFor(99, base));
		assertEquals("levels above 99 are clamped, not extrapolated",
			base, SurveyCharges.xpCostFor(126, base));
	}

	/** A floor stops the earliest levels being effectively free. */
	@Test
	public void theEarliestLevelsStillCostSomething()
	{
		long cost = SurveyCharges.xpCostFor(1, 5000);
		assertTrue("level 1 should not be free", cost >= 5000 * SurveyCharges.MIN_PRICE_SHARE);
		assertEquals("a zero base disables the charge entirely",
			0, SurveyCharges.xpCostFor(50, 0));
	}
}
