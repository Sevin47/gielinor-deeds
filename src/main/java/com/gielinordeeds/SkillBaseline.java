/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.EnumMap;
import java.util.Map;
import lombok.Value;
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
 * not persisted: they are rebuilt from the login burst each
 * session, which is both simpler and impossible to get wrong across a save.
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
	private final Map<Skill, Integer> level = new EnumMap<>(Skill.class);

	/** Forget everything. Called on login and logout, never mid-session. */
	void reset()
	{
		xp.clear();
		level.clear();
	}

	/**
	 * @return what changed, or null on the first sighting of this skill
	 */
	Delta observe(Skill skill, int totalXp, int currentLevel)
	{
		Integer prevXp = xp.put(skill, totalXp);
		Integer prevLevel = level.put(skill, currentLevel);
		if (prevXp == null || prevLevel == null)
		{
			return null;                     // baseline only
		}
		long gained = Math.max(0, (long) totalXp - prevXp);
		// XP going backwards should never happen, but a dead-and-restored
		// session or a client quirk must not produce a negative or a windfall.
		if (gained == 0 && currentLevel <= prevLevel)
		{
			return null;
		}
		return new Delta(gained, prevLevel, Math.max(prevLevel, currentLevel));
	}

	boolean isSeeded()
	{
		return !xp.isEmpty();
	}
}
