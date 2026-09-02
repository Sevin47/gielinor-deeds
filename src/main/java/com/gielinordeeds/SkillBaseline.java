/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import lombok.Value;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * Turns RuneLite's StatChanged events into XP and level *deltas*.
 *
 * StatChanged reports a skill's total XP, not the amount just gained, and the
 * client fires one for every skill shortly after login. Treating those first
 * events as gains would hand a maxed account 200 million XP worth of charges
 * the moment it logged in -- enough to survey the entire map several times over
 * from a standing start.
 *
 * So the first sighting of each skill only records a baseline and reports
 * nothing. Every sighting after that reports the difference. The baselines are
 * not persisted: they are rebuilt each session from the client's own totals,
 * which is simpler and impossible to get wrong across a save.
 *
 * Levels are derived from the XP rather than read from the event. See
 * {@link #levelFor}.
 */
class SkillBaseline
{
	@Value
	static class Delta
	{
		long xpGained;
		/** Levels newly reached, in order, so each pays its own reward. */
		int fromLevel;
		int toLevel;

		boolean hasXp()
		{
			return xpGained > 0;
		}

		boolean leveled()
		{
			return toLevel > fromLevel;
		}
	}

	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);

	/** Forget everything. Called on login and logout, never mid-session. */
	void reset()
	{
		xp.clear();
	}

	/**
	 * Record a skill's total without reporting a gain.
	 *
	 * Lets the plugin fill the baseline from the client's own totals the moment
	 * it has a logged-in player, rather than waiting for a StatChanged per
	 * skill. Without that, enabling the plugin mid-session left every skill
	 * unseeded, and the first gain in each was swallowed as a baseline -- so a
	 * quest completing soon after paid nothing at all for the skills it
	 * touched.
	 */
	void seed(Skill skill, int totalXp)
	{
		xp.putIfAbsent(skill, totalXp);
	}

	/**
	 * Fill the whole baseline from a snapshot of the client's totals, or refuse.
	 *
	 * The snapshot is only real once the server has sent the stats, and after a
	 * login or a hop it has not. Until then every skill reads zero, and seeding
	 * from that is worse than not seeding at all: the baseline says the account
	 * has never trained, so the stat packet landing a moment later reads as the
	 * whole of it being earned at once. The XP half of that is held by the
	 * hourly cap, but level-ups bypass the cap by design, so it pays a charge
	 * for every level from 1 upward in every skill -- a couple of hundred
	 * charges for simply logging in.
	 *
	 * Hitpoints is the tell. Every account is created at level 10 with 1,154
	 * Hitpoints XP and no account can go below it, so a zero there is the
	 * client not knowing yet rather than a skill that has never been trained.
	 *
	 * All or nothing: a partial baseline reports itself as seeded, which would
	 * leave the missing skills to be filled by the stat packet -- correctly, as
	 * it happens, but only by luck of ordering. Refusing outright leaves the
	 * StatChanged events to do the seeding, which is the safe path anyway
	 * because those carry real totals by definition.
	 *
	 * @return true once the baseline holds something, seeded now or before
	 */
	boolean seedAll(ToIntFunction<Skill> totals)
	{
		if (isSeeded())
		{
			return true;
		}
		if (totals.applyAsInt(Skill.HITPOINTS) <= 0)
		{
			return false;                    // the stats have not arrived yet
		}
		for (Skill skill : Skill.values())
		{
			seed(skill, totals.applyAsInt(skill));
		}
		return true;
	}

	/**
	 * @return what changed, or null on the first sighting of this skill
	 */
	Delta observe(Skill skill, int totalXp)
	{
		Integer prevXp = xp.put(skill, totalXp);
		if (prevXp == null)
		{
			return null;                     // baseline only
		}
		long gained = Math.max(0, (long) totalXp - prevXp);
		// XP going backwards should never happen, but a dead-and-restored
		// session or a client quirk must not produce a negative or a windfall.
		if (gained == 0)
		{
			return null;
		}
		return new Delta(gained, levelFor(prevXp), levelFor(totalXp));
	}

	/**
	 * The real level for a total, derived rather than reported.
	 *
	 * StatChanged carries a level alongside the XP, and trusting it cost
	 * level-up charges: a reward that lands as one large lump can report the
	 * old level with the new total, and the difference is where the charges
	 * come from. Experience.getLevelForXp is the same function the client uses,
	 * and it cannot disagree with the XP it was given.
	 *
	 * Clamped to 99, so virtual levels past it pay nothing.
	 */
	private static int levelFor(int totalXp)
	{
		return Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp(totalXp));
	}

	boolean isSeeded()
	{
		return !xp.isEmpty();
	}
}
