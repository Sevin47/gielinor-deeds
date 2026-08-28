/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Better instruments: how far a single survey charge reaches.
 *
 * This is the money sink that respects the charge economy. Rent buys reach, not
 * charges -- you still have to earn the right to survey by playing, but each
 * charge covers more ground once you have paid for the equipment.
 *
 * Deliberately only two upgrades. A third ring (7x7, 49 parcels a charge) would
 * put the whole 33,398-parcel map inside about 57 hours and flatten the one
 * goal that is supposed to outlast everything else.
 */
@Getter
@RequiredArgsConstructor
public enum SurveyRange
{
	/** The parcel underfoot, and nothing else. */
	PACE("Pacing", 0, 0),
	/** 3x3 -- 9 parcels a charge. */
	CHAIN("Surveyor's chain", 1, 25_000),
	/** 5x5 -- 25 parcels a charge. */
	THEODOLITE("Theodolite", 2, 400_000);

	private static final SurveyRange[] LEVELS = values();

	private final String displayName;
	private final int radius;
	/** Cost to move UP to this level. Zero for the level everyone starts at. */
	private final long cost;

	/** Parcels covered by one charge at this level. */
	public int coverage()
	{
		int side = radius * 2 + 1;
		return side * side;
	}

	public static SurveyRange forLevel(int level)
	{
		return LEVELS[Math.max(0, Math.min(LEVELS.length - 1, level))];
	}

	public static boolean isMaxed(int level)
	{
		return level >= LEVELS.length - 1;
	}

	/** The upgrade above this level, or null when there is nothing left to buy. */
	public static SurveyRange next(int level)
	{
		return isMaxed(level) ? null : LEVELS[level + 1];
	}
}
