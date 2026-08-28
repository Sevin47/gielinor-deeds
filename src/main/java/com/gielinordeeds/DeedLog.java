/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import lombok.Getter;

/**
 * What is left to find.
 *
 * The plugin's real long game is not money -- income compounds until the whole
 * 9.1M map is a few dozen hours of rent -- it is the map itself, which takes
 * 139 hours of surveying plus the walk. This class is the scoreboard for that:
 * how much of each kingdom and each district type you have seen and hold.
 *
 * Totals are counted once from the shipped survey at startup rather than
 * shipped as a second data file, so they cannot fall out of step with
 * parcels.bin. Counting all 115,200 parcels is a few milliseconds.
 */
public class DeedLog
{
	/** Seen / held / possible, for one grouping. */
	@Getter
	public static final class Progress
	{
		private int surveyed;
		private int owned;
		private int total;

		public double surveyedFraction()
		{
			return total == 0 ? 0 : (double) surveyed / total;
		}

		public double ownedFraction()
		{
			return total == 0 ? 0 : (double) owned / total;
		}

		public boolean isComplete()
		{
			return total > 0 && surveyed >= total;
		}
	}

	private final ParcelGrid grid;
	/** Buyable parcels per kingdom and per tier -- fixed for a given survey. */
	private final Map<Kingdom, Integer> kingdomTotals = new EnumMap<>(Kingdom.class);
	private final Map<Tier, Integer> tierTotals = new EnumMap<>(Tier.class);
	@Getter private int buyableTotal;

	public DeedLog(ParcelGrid grid)
	{
		this.grid = grid;
		for (Kingdom k : Kingdom.values())
		{
			kingdomTotals.put(k, 0);
		}
		for (Tier t : Tier.values())
		{
			tierTotals.put(t, 0);
		}
		for (int i = 0; i < grid.size(); i++)
		{
			Parcel p = grid.atIndex(i);
			// Unclaimable ground is excluded from every total. A kingdom that
			// counted its own coastline could never be finished, which makes the
			// percentage useless as a goal.
			if (p == null || !p.isClaimable())
			{
				continue;
			}
			buyableTotal++;
			kingdomTotals.merge(Kingdom.of(p), 1, Integer::sum);
			tierTotals.merge(p.getTier(), 1, Integer::sum);
		}
	}

	public int totalFor(Kingdom k)
	{
		return kingdomTotals.getOrDefault(k, 0);
	}

	public int totalFor(Tier t)
	{
		return tierTotals.getOrDefault(t, 0);
	}

	/** Current standing. Walks only what the player has actually touched. */
	public Snapshot snapshot(Estate estate)
	{
		Snapshot s = new Snapshot();
		for (Kingdom k : Kingdom.values())
		{
			s.byKingdom.computeIfAbsent(k, x -> new Progress()).total = totalFor(k);
		}
		for (Tier t : Tier.values())
		{
			s.byTier.computeIfAbsent(t, x -> new Progress()).total = totalFor(t);
		}
		s.overall.total = buyableTotal;

		// Iterating the surveyed bitset rather than the grid keeps this bounded
		// by how much has been explored, so an untouched save costs nothing.
		BitSet set = estate.surveyedSet();
		for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1))
		{
			Parcel p = grid.atIndex(i);
			if (p == null || !p.isClaimable())
			{
				continue;
			}
			boolean owned = estate.owns(p.getPid());
			bump(s.byKingdom.get(Kingdom.of(p)), owned);
			bump(s.byTier.get(p.getTier()), owned);
			bump(s.overall, owned);
			if (p.getLandmark() != null)
			{
				s.landmarksFound.add(p.getLandmark());
				if (owned)
				{
					s.landmarksOwned.add(p.getLandmark());
				}
			}
		}
		return s;
	}

	private static void bump(Progress p, boolean owned)
	{
		if (p == null)
		{
			return;
		}
		p.surveyed++;
		if (owned)
		{
			p.owned++;
		}
	}

	/** An immutable-enough view of one moment's progress. */
	public static final class Snapshot
	{
		@Getter private final Map<Kingdom, Progress> byKingdom = new EnumMap<>(Kingdom.class);
		@Getter private final Map<Tier, Progress> byTier = new EnumMap<>(Tier.class);
		@Getter private final Progress overall = new Progress();
		/** Landmarks stood on, and landmarks held. The Log's trophy shelf. */
		@Getter private final EnumSet<Landmark> landmarksFound = EnumSet.noneOf(Landmark.class);
		@Getter private final EnumSet<Landmark> landmarksOwned = EnumSet.noneOf(Landmark.class);

		public Progress kingdom(Kingdom k)
		{
			return byKingdom.getOrDefault(k, new Progress());
		}

		public Progress tier(Tier t)
		{
			return byTier.getOrDefault(t, new Progress());
		}

		/** District types with at least one parcel owned -- the "one of each" goal. */
		public int tierTypesOwned()
		{
			int n = 0;
			for (Map.Entry<Tier, Progress> e : byTier.entrySet())
			{
				if (e.getKey().isBuyable() && e.getValue().getOwned() > 0)
				{
					n++;
				}
			}
			return n;
		}

		public int kingdomsEntered()
		{
			int n = 0;
			for (Progress p : byKingdom.values())
			{
				if (p.getSurveyed() > 0)
				{
					n++;
				}
			}
			return n;
		}
	}
}
