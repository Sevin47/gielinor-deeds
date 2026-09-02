/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
		assertNull(b.observe(Skill.ATTACK, 13_034_431));
		assertNull(b.observe(Skill.MINING, 200_000_000));
	}

	@Test
	public void laterSightingsReportOnlyTheDifference()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.MINING, 100_000);
		SkillBaseline.Delta d = b.observe(Skill.MINING, 100_500);
		assertEquals(500, d.getXpGained());
		assertFalse(d.leveled());
	}

	@Test
	public void everyLevelCrossedIsReported()
	{
		// A lamp or a quest reward can cross several levels at once, and each
		// one pays its own charge.
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.SLAYER, Experience.getXpForLevel(10));
		SkillBaseline.Delta d = b.observe(Skill.SLAYER, Experience.getXpForLevel(13));
		assertEquals(10, d.getFromLevel());
		assertEquals(13, d.getToLevel());
		assertTrue(d.leveled());
	}

	/**
	 * A quest reward that crosses many levels at once pays for all of them.
	 *
	 * Waterfall Quest is the standard example: 13,750 Attack and Strength on an
	 * account that may have none, taking a skill from 1 to 30 in one event.
	 */
	@Test
	public void aQuestRewardPaysForEveryLevelItCrosses()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.ATTACK, 0);
		SkillBaseline.Delta d = b.observe(Skill.ATTACK, 13_750);

		assertEquals(13_750, d.getXpGained());
		assertEquals(1, d.getFromLevel());
		assertEquals(Experience.getLevelForXp(13_750), d.getToLevel());
		assertTrue("30 levels in one event should still be 30 levels",
			d.getToLevel() - d.getFromLevel() > 20);
	}

	/**
	 * The level is derived from the XP, never read from the event.
	 *
	 * StatChanged carries a level alongside the total, and a reward landing as
	 * one lump can report the old level with the new XP. Trusting it lost every
	 * level-up charge from that event, which is where quest rewards went.
	 */
	@Test
	public void theLevelComesFromTheXpNotFromTheEvent()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.COOKING, Experience.getXpForLevel(20));
		SkillBaseline.Delta d = b.observe(Skill.COOKING, Experience.getXpForLevel(25));

		assertEquals(20, d.getFromLevel());
		assertEquals("the XP says 25, so the delta must say 25", 25, d.getToLevel());
	}

	/** Virtual levels past 99 pay nothing. */
	@Test
	public void levelsPastNinetyNineDoNotPay()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.FISHING, 13_034_431);          // exactly 99
		SkillBaseline.Delta d = b.observe(Skill.FISHING, 30_000_000);

		assertEquals(99, d.getFromLevel());
		assertEquals(99, d.getToLevel());
		assertFalse("no more levels to cross", d.leveled());
	}

	/**
	 * Seeding fills the baseline without paying, so no real gain is lost.
	 *
	 * The baseline used to be filled only by StatChanged, so enabling the
	 * plugin mid-session left every skill unseeded and swallowed the first gain
	 * in each one. A quest completing soon after paid nothing for the skills it
	 * touched.
	 */
	@Test
	public void seedingMeansTheFirstRealGainIsNotSwallowed()
	{
		SkillBaseline b = new SkillBaseline();
		b.seed(Skill.WOODCUTTING, 5000);
		assertTrue(b.isSeeded());

		SkillBaseline.Delta d = b.observe(Skill.WOODCUTTING, 6000);
		assertNotNull("a seeded skill must report its first real gain", d);
		assertEquals(1000, d.getXpGained());
	}

	/** Seeding never overwrites a total already known. */
	@Test
	public void seedingDoesNotDisturbASkillAlreadyTracked()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.MINING, 100_000);
		b.seed(Skill.MINING, 0);
		SkillBaseline.Delta d = b.observe(Skill.MINING, 100_500);
		assertEquals("the seed must not have reset the total", 500, d.getXpGained());
	}

	@Test
	public void aResetForgetsBaselinesSoTheNextLoginReseeds()
	{
		SkillBaseline b = new SkillBaseline();
		b.observe(Skill.MINING, 100_000);
		assertTrue(b.isSeeded());
		b.reset();
		assertFalse(b.isSeeded());
		assertNull(b.observe(Skill.MINING, 100_000));
	}

	// ── logging in ───────────────────────────────────────────────────────
	//
	// The 600ms schedule reaches a fresh login before the stats do: the game
	// state is LOGGED_IN and the player is named while every skill still reads
	// zero. Seeding from that snapshot said the account had never trained, so
	// the stat packet a moment later read as the whole of it being earned at
	// once -- and level rewards bypass the hourly cap, so it paid a charge for
	// every level from 1 upward in every skill.
	//
	// Reported against a total level of about 183, which minted 168 charges in
	// the first second of a session that had ended the night before with none.

	/** All zeroes is the client not knowing yet, and must not be recorded. */
	@Test
	public void aLoginBeforeTheStatsArriveIsNotASnapshot()
	{
		SkillBaseline b = new SkillBaseline();
		assertFalse("an empty client must not seed a baseline",
			b.seedAll(skill -> 0));
		assertFalse("nothing may be recorded from it", b.isSeeded());
	}

	/** Once the stats land, the same call seeds every skill. */
	@Test
	public void theStatsArrivingSeedsTheWholeBaseline()
	{
		SkillBaseline b = new SkillBaseline();
		assertTrue(b.seedAll(skill -> skill == Skill.HITPOINTS ? 1154 : 13_363));
		assertTrue(b.isSeeded());
		assertNull("a seeded skill reports no gain until it actually gains",
			b.observe(Skill.MINING, 13_363));
		assertEquals(100, b.observe(Skill.MINING, 13_463).getXpGained());
	}

	/**
	 * The whole sequence, end to end: log in early, let the stats land, and
	 * come out the other side owing nothing.
	 */
	@Test
	public void loggingInPaysNothingHoweverEarlyTheTickLands()
	{
		SkillBaseline b = new SkillBaseline();
		SurveyCharges c = new SurveyCharges();
		long now = 1_000_000L;

		// The schedule beats the stat packet. Refused, so nothing is recorded.
		b.seedAll(skill -> 0);

		// The stat packet, one StatChanged per skill, every one a first
		// sighting. A level 60 in all of them is 566 charges of level rewards
		// if any of this is mistaken for a gain.
		int paid = 0;
		for (Skill skill : Skill.values())
		{
			SkillBaseline.Delta d = b.observe(skill, 273_742);
			if (d == null)
			{
				continue;
			}
			paid += c.addXp(d.getXpGained(), now, 5000, Balance.CHARGES_PER_HOUR);
			for (int lvl = d.getFromLevel() + 1; lvl <= d.getToLevel(); lvl++)
			{
				paid += c.addLevel(lvl);
			}
		}

		assertEquals("logging in must not pay", 0, paid);
		assertEquals(0, c.getCharges());
		assertEquals("and must not spend the hourly allowance either",
			Balance.CHARGES_PER_HOUR, c.allowanceRemaining(now, Balance.CHARGES_PER_HOUR));
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
