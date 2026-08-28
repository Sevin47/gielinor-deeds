/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import lombok.Data;
import net.runelite.api.Experience;

/**
 * How playing turns into the right to survey land.
 *
 * Charges come from XP and from level-ups, and from nothing else. They do not
 * accrue while logged out; rent does. That separation is the point of the
 * resource.
 *
 * XP-derived charges pass through a leaky bucket capped at
 * {@link Balance#CHARGES_PER_HOUR}, so efficient training cannot outpace
 * ordinary play by an order of magnitude. Level-ups bypass the cap.
 */
@Data
public class SurveyCharges
{
	/** Charges in hand, spent one per parcel surveyed. */
	private int charges;

	/** XP banked toward the next charge. Clamped -- see {@link #addXp}. */
	private long xpProgress;

	/** Leaky-bucket allowance for XP-derived charges, in whole charges. */
	private double allowance = -1;      // -1 = uninitialised, filled on first use
	private long allowanceTouched;

	// ── level-up curve ───────────────────────────────────────────────────
	// Shape borrowed from OSRS TCG's LevelUpCreditMath: a power curve so that a
	// level 90 is worth far more than a level 20. The numbers are ours.
	//
	// The floor is 1 rather than 3. At 3, simulated over fourteen free skills
	// to 50, level-ups paid 2,324 charges against 465 from XP: 83% of a run
	// bypassed the hourly cap, and play time barely changed the total, since
	// curve rather than by the clock.
	//
	// A floor of 1 makes an early level a nod rather than a windfall, which is
	// what it should have been: reaching level 3 in Cooking is not an
	// achievement worth a tenth of an hour's cap. The top is untouched -- a 99
	// still pays 40 -- so the curve now actually curves.
	static final int LEVEL_FLOOR = 1;
	static final int LEVEL_CAP = 40;
	private static final double LEVEL_STEEPNESS = 2.5d;

	/** Charges awarded for reaching a level. Never capped. */
	public static int levelReward(int level)
	{
		if (level <= 2)
		{
			return LEVEL_FLOOR;
		}
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return LEVEL_CAP;
		}
		double progress = (level - 2.0d) / (Experience.MAX_REAL_LEVEL - 2.0d);
		double curve = Math.pow(progress, LEVEL_STEEPNESS);
		double mult = Math.pow((double) LEVEL_CAP / LEVEL_FLOOR, curve);
		return (int) Math.round(LEVEL_FLOOR * mult);
	}

	// ── what a charge costs ──────────────────────────────────────────────
	// A flat price is a much worse deal the lower your level, because XP rates
	// climb with level while the price does not. At a flat 5,000 a level 10
	// account earning 8k/hr sees 1.6 charges an hour, while anyone past level
	// 50 sits at the hourly cap and cannot earn more however hard they train.
	// The cap was meant to be the limit for everyone; instead it only bound
	// the top half of the game.
	//
	// So the price scales with the level of whichever skill earned the XP. The
	// square root is deliberate: XP rates rise faster than level does, so a
	// price rising as fast as level would flatten everyone onto the cap and
	// delete the progression entirely. At this shape a new account reaches
	// about half the cap and grows into the whole of it by roughly level 30.
	//
	// The configured figure is the price at 99. Nobody pays more than that.
	private static final double PRICE_CURVE = 0.5d;
	static final double MIN_PRICE_SHARE = 0.08d;

	/** What one charge costs in XP, for a skill at this level. */
	public static long xpCostFor(int level, long base)
	{
		if (base <= 0)
		{
			return 0;
		}
		int lvl = Math.max(1, Math.min(Experience.MAX_REAL_LEVEL, level));
		double share = Math.pow(lvl / (double) Experience.MAX_REAL_LEVEL, PRICE_CURVE);
		return Math.max(1L, Math.round(base * Math.max(MIN_PRICE_SHARE, share)));
	}

	// ── the bucket ───────────────────────────────────────────────────────

	private void regenerate(long now, int perHour)
	{
		if (allowance < 0)
		{
			allowance = perHour;            // a fresh estate starts full
			allowanceTouched = now;
			return;
		}
		if (allowanceTouched == 0 || now <= allowanceTouched)
		{
			allowanceTouched = now;
			return;
		}
		double hours = (now - allowanceTouched) / 3_600_000.0;
		allowance = Math.min(perHour, allowance + hours * perHour);
		allowanceTouched = now;
	}

	/** Whole charges of XP allowance available right now, for display. */
	public int allowanceRemaining(long now, int perHour)
	{
		regenerate(now, perHour);
		return (int) Math.floor(allowance);
	}

	/**
	 * Bank XP and convert it into charges, respecting the hourly cap.
	 *
	 * @return charges granted, for the caller to report
	 */
	public int addXp(long xp, long now, long xpPerCharge, int perHour)
	{
		if (xp <= 0 || xpPerCharge <= 0)
		{
			return 0;
		}
		regenerate(now, perHour);

		xpProgress += xp;
		int granted = 0;
		while (xpProgress >= xpPerCharge && allowance >= 1.0)
		{
			xpProgress -= xpPerCharge;
			allowance -= 1.0;
			charges++;
			granted++;
		}
		// Clamped at one charge's worth, or a player could grind against an
		// empty allowance and then dump the backlog at once.
		if (xpProgress > xpPerCharge)
		{
			xpProgress = xpPerCharge;
		}
		return granted;
	}

	/** Award a level-up. Bypasses the hourly cap by design. */
	public int addLevel(int level)
	{
		int reward = levelReward(level);
		charges += reward;
		return reward;
	}

	public boolean spend()
	{
		if (charges <= 0)
		{
			return false;
		}
		charges--;
		return true;
	}

	public void grant(int n)
	{
		charges = Math.max(0, charges + n);
	}
}
