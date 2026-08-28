/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * The rules of Deed Locked: a challenge run where unsurveyed Gielinor is
 * blacked out and you spend what you earn opening more of it.
 *
 * <h2>Why a deed and not a region</h2>
 *
 * A deed is 8x8 tiles, smaller than most banks. The objection to locking at
 * that scale is that a run confined to 64 tiles cannot train, so cannot earn
 * charges, so never unlocks anything.
 *
 * The veil is not a wall. Nothing here blocks movement and the menu filter is
 * off by default, so a fresh account on one deed still has all of Lumbridge to
 * train in. A deed bounds what you can see and what you own; the XP earned out
 * in the dark is what buys the next one.
 *
 * Two rules make the small unit workable:
 *
 *   the opening grant   you own the deed you stand on and hold GRANT_CHARGES
 *                       survey charges. Nothing else is surveyed, and which
 *                       direction to spend them in is yours.
 *   frontier surveying  you may survey any deed touching one you own, so the
 *                       map opens outward from your estate rather than
 *                       requiring you to walk onto ground you cannot see.
 *
 * <h2>Why the frontier is owned land, not surveyed land</h2>
 *
 * Charges come from XP and money comes from rent. If a survey alone moved the
 * frontier, expansion would need only charges and money would buy nothing you
 * could not already reach. Anchoring it to ownership makes a charge open the
 * ring around what you hold and a purchase move that ring, so neither currency
 * can advance alone.
 *
 * <h2>Enforcement is honour-system</h2>
 *
 * Nothing here blocks movement. The veil hides ground and the menu filter can
 * refuse clicks, but a player who wants to walk out still can.
 */
public final class DeedLock
{
	/**
	 * Survey charges a run begins with.
	 *
	 * Three, and the ground they open is the player's choice. An earlier
	 * version surveyed three neighbours FOR them, which is the same amount of
	 * map and none of the decision: the run opened with a fact rather than a
	 * question. Handing over the charges instead means the first thing that
	 * happens in a locked run is somebody deciding which way to look.
	 *
	 * Before that it was a 5x5 block of 25 deeds -- a small town given away
	 * before the player had done anything, so the opening was survived rather
	 * than played and the first real choice arrived an hour in.
	 */
	public static final int GRANT_CHARGES = 3;

	/** How far the opening looks for ground it can give you. */
	private static final int GRANT_SEARCH = 1;

	private DeedLock()
	{
	}

	/**
	 * True when this deed may be surveyed from where the estate currently
	 * stands: it is unsurveyed, and it touches a deed you OWN.
	 *
	 * Touching one you have merely surveyed is not enough -- see the class
	 * note. Surveying is what lets you see a deed and learn its price; buying
	 * it is what lets you survey past it.
	 *
	 * Diagonals count. A frontier that only opened along the compass would
	 * make diagonal expansion cost two deeds instead of one, which is a tax on
	 * nothing in particular.
	 */
	public static boolean onFrontier(ParcelGrid grid, Estate estate, @Nullable Parcel p)
	{
		if (grid == null || estate == null || p == null)
		{
			return false;
		}
		int idx = grid.indexOf(p);
		if (idx < 0 || estate.hasSurveyed(idx))
		{
			return false;
		}
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				Parcel n = grid.at(p.getPx() + dx, p.getPy() + dy);
				if (n != null && estate.owns(n.getPid()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Start the run, once, wherever it begins.
	 *
	 * You own the deed under your feet, recorded as paid 0, and hold
	 * {@link #GRANT_CHARGES} survey charges. Nothing else is surveyed.
	 *
	 * At minute zero the frontier is the ring around you, since every
	 * neighbour is unsurveyed and touches land you own. Charges reveal those
	 * and then stop: nothing further out touches anything of yours, so the map
	 * goes no further until you buy.
	 *
	 * The deed given is the one you are standing on, or the nearest claimable
	 * one if you are on water or a landmark that cannot be owned. If nothing in
	 * reach is claimable nothing is granted at all, so the run begins properly
	 * once you stand somewhere it can.
	 *
	 * Returns 1 when a run begins here, or 0 if the estate already holds
	 * ground, which is what stops it re-firing every time the plugin loads.
	 */
	public static int grantStart(ParcelGrid grid, Estate estate, @Nullable Parcel here)
	{
		if (grid == null || estate == null || here == null || estate.surveyedCount() > 0)
		{
			return 0;
		}

		// Found before anything is written, so a start with nowhere to stand
		// leaves the estate untouched rather than half-begun.
		Parcel deed = nearestClaimable(grid, here, null);
		if (deed == null)
		{
			return 0;
		}

		estate.markSurveyed(grid.indexOf(deed));
		estate.getOwned().put(deed.getPid(), 0);
		estate.getCharges().grant(GRANT_CHARGES);
		return 1;
	}

	/**
	 * The closest claimable deed to {@code from} that the estate has not seen.
	 *
	 * Pass a null estate to ignore what has been surveyed, which is how the
	 * very first deed is chosen. Ties break on scan order, which spreads the
	 * opening's neighbours across compass directions rather than stacking them
	 * along one axis -- the four orthogonal neighbours all sit at distance 1
	 * and are visited before any diagonal.
	 */
	@Nullable
	private static Parcel nearestClaimable(ParcelGrid grid, Parcel from,
		@Nullable Estate estate)
	{
		Parcel best = null;
		int nearest = Integer.MAX_VALUE;
		for (int dx = -GRANT_SEARCH; dx <= GRANT_SEARCH; dx++)
		{
			for (int dy = -GRANT_SEARCH; dy <= GRANT_SEARCH; dy++)
			{
				Parcel q = grid.at(from.getPx() + dx, from.getPy() + dy);
				int idx = grid.indexOf(q);
				if (q == null || idx < 0 || !q.isClaimable())
				{
					continue;
				}
				if (estate != null && estate.hasSurveyed(idx))
				{
					continue;
				}
				int distance = dx * dx + dy * dy;
				if (distance < nearest)
				{
					nearest = distance;
					best = q;
				}
			}
		}
		return best;
	}

	/**
	 * Whether the veil covers this ground.
	 *
	 * The single answer for the whole mode: the veil, the menu filter and the
	 * trespass warning all ask it, so all three agree about the same ground.
	 *
	 * Upper floors belong to the deed underneath them, so the plane is dropped
	 * before the lookup and a building straddling the boundary is half veiled
	 * on every storey.
	 *
	 * Ground the survey never covered is never veiled. Caves, dungeons and
	 * instances lie outside the grid's bounds, and there is no deed there to be
	 * off.
	 */
	public static boolean isVeiled(@Nullable ParcelGrid grid, @Nullable Estate estate,
		@Nullable WorldPoint wp)
	{
		WorldPoint ground = ParcelGrid.groundOf(wp);
		if (grid == null || estate == null || ground == null
			|| !grid.isSurveyable(ground))
		{
			return false;
		}
		int idx = grid.indexOf(grid.at(ground));
		return idx >= 0 && !estate.hasSurveyed(idx);
	}
}
