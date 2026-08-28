/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Experience;
import net.runelite.api.coords.WorldPoint;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What a Deed Locked account actually holds after a full free-to-play grind.
 *
 * <h2>Why this exists</h2>
 *
 * Old School is a long game, and a plugin bolted onto it has to be a long game
 * too. If a run to 50 in every free skill hands out enough charges to survey a
 * tenth of Gielinor and enough rent to buy whatever it surveys, the whole
 * economy is decoration -- the player never chooses what to open next, because
 * they can open everything.
 *
 * Nothing here is a guess about the numbers: it drives the real
 * {@link SurveyCharges}, {@link Estate}, {@link Balance} and {@link ParcelGrid},
 * so what it prints is what the plugin does. What it does guess is the player --
 * how long they take, and that they always spend well.
 *
 * <h2>The model, and what it assumes</h2>
 *
 * <ul>
 * <li>Fourteen free skills from 1 to 50. Hitpoints is left out because combat
 *     XP carries it past 50 on its own long before the other three are done.
 * <li>All fourteen trained in parallel, so charge prices track a rising average
 *     level rather than one skill running ahead. Training them one at a time
 *     buys slightly more, since more XP is earned at low levels where a charge
 *     is cheap.
 * <li>An optimal buyer: every charge opens the richest deed on the frontier,
 *     and every spare coin goes on the best rent-per-coin deed available. A
 *     real player does worse, so these are ceilings.
 * <li>Deed Locked, so one charge opens exactly one deed.
 * </ul>
 *
 * This is a report, not a rule. It asserts only that the model ran and that the
 * plugin's own limits held; the numbers it prints are for a person to judge.
 */
public class ProgressionReportTest
{
	/** Free skills that have to be trained. See the class note on Hitpoints. */
	private static final int SKILLS = 14;
	private static final int TARGET_LEVEL = 50;
	private static final long MINUTE = 60_000L;

	private static ParcelGrid grid;
	private static DeedLog log;

	@BeforeClass
	public static void load() throws Exception
	{
		grid = ParcelGrid.load();
		assertNotNull(grid);
		log = new DeedLog(grid);
	}

	@Test
	public void reportTutorialIslandToFiftyInEverything()
	{
		long xpEach = Experience.getXpForLevel(TARGET_LEVEL);
		System.out.println();
		System.out.println("=== Deed Locked: tutorial island to 50 in every F2P skill ===");
		System.out.printf("  %d skills x %,d xp = %,d xp total%n",
			SKILLS, xpEach, xpEach * SKILLS);
		System.out.printf("  buyable deeds on the map: %,d%n", log.getBuyableTotal());
		System.out.printf("  charge price: %,d xp at 99, %,d at 50, %,d at 10%n",
			SurveyCharges.xpCostFor(99, Balance.XP_PER_CHARGE),
			SurveyCharges.xpCostFor(50, Balance.XP_PER_CHARGE),
			SurveyCharges.xpCostFor(10, Balance.XP_PER_CHARGE));
		System.out.println();
		int perSkill = 0;
		for (int lvl = 2; lvl <= TARGET_LEVEL; lvl++)
		{
			perSkill += SurveyCharges.levelReward(lvl);
		}
		System.out.printf("  levels 2-50 pay %,d charges per skill, %,d across %d%n",
			perSkill, perSkill * SKILLS, SKILLS);
		System.out.println();
		System.out.printf("  %-7s %9s %9s %9s %8s %9s %7s %11s %9s%n",
			"hours", "chg:lvl", "chg:xp", "surveyed", "%map", "bought", "%owned",
			"gp", "rent/hr");

		for (int hours : new int[]{40, 80, 150, 300})
		{
			Run r = simulate(hours);
			System.out.printf("  %-7d %,9d %,9d %,9d %7.2f%% %,9d %6.0f%% %,11d %,9d%n",
				hours, r.levelCharges, r.xpCharges, r.surveyed,
				100.0 * r.surveyed / log.getBuyableTotal(), r.bought,
				100.0 * r.bought / Math.max(1, r.surveyed), r.balance,
				Math.round(r.rps * 3600));

			assertTrue("the model should open something", r.surveyed > 0);
			assertTrue("a locked run opens one deed per charge, so surveys can "
					+ "never exceed charges earned plus the opening grant",
				r.surveyed <= r.levelCharges + r.xpCharges + 64);
		}

		System.out.println();
		System.out.println("  chg:lvl are level-up charges, which bypass the hourly");
		System.out.println("  cap by design. chg:xp are the capped ones.");
		reportOpening();
		reportValuePerCoin();
	}

	/**
	 * The first evening, which is where a run is won or lost.
	 *
	 * Everything else here measures a finished account. Nobody abandons a
	 * plugin at hour three hundred -- they abandon it in the first hour, when
	 * either nothing is happening or everything already has. The opening grant
	 * and the starting balance are the only two dials that touch this, and
	 * until now neither had ever been looked at.
	 *
	 * Sampled from the same simulated run as the table above, at a rate that
	 * reaches 50 in everything in 80 hours.
	 */
	private void reportOpening()
	{
		System.out.println();
		System.out.println("  --- the opening, at an 80 hour pace ---");
		System.out.printf("  opening: 1 deed owned, %d charges to spend, %,d gp%n",
			DeedLock.GRANT_CHARGES, Estate.STARTING_BALANCE);
		System.out.printf("  %-8s %9s %9s %9s %9s %11s%n",
			"at", "charges", "surveyed", "owned", "rent/hr", "gp");
		Run r = simulate(80, new int[]{15, 60, 300, 1200});
		for (int i = 0; i < r.marks.size(); i++)
		{
			Mark m = r.marks.get(i);
			System.out.printf("  %-8s %,9d %,9d %,9d %,9d %,11d%n",
				m.label, m.charges, m.surveyed, m.owned,
				Math.round(m.rps * 3600), m.balance);
		}
	}

	/**
	 * What a coin buys, by district.
	 *
	 * rpsFor scales a parcel's rent by its price against the tier's base, so
	 * rent per coin is a property of the TIER and nothing else -- every
	 * Wasteland deed is exactly as good a buy as every other. Which makes the
	 * ordering here the whole of the investment decision in the game, and worth
	 * looking at directly rather than inferring from what a simulated buyer
	 * happened to pick.
	 */
	private void reportValuePerCoin()
	{
		System.out.println();
		System.out.println("  --- rent per coin, by district ---");
		List<Tier> tiers = new ArrayList<>();
		for (Tier t : Tier.values())
		{
			if (t.isBuyable() && t.getBasePrice() > 0)
			{
				tiers.add(t);
			}
		}
		tiers.sort(Comparator.comparingDouble(
			(Tier t) -> -t.getBaseRps() / t.getBasePrice()));
		for (Tier t : tiers)
		{
			double perCoin = t.getBaseRps() / t.getBasePrice();
			System.out.printf("  %-12s base %,5d  rps %.4f  payback %,6.0fs  "
					+ "rent/coin %.6f%n",
				t.getDisplayName(), t.getBasePrice(), t.getBaseRps(),
				t.getBasePrice() / t.getBaseRps(), perCoin);
		}
		System.out.println();
	}

	/** A snapshot part-way through a run. */
	private static final class Mark
	{
		String label;
		int charges;
		int surveyed;
		int owned;
		long balance;
		double rps;
	}

	/** One simulated account. */
	private static final class Run
	{
		final List<Mark> marks = new ArrayList<>();
		int levelCharges;
		int xpCharges;
		int surveyed;
		int bought;
		long balance;
		double rps;
	}

	private Run simulate(int hours)
	{
		return simulate(hours, new int[0]);
	}

	private Run simulate(int hours, int[] markMinutes)
	{
		Estate estate = new Estate();
		SurveyCharges purse = estate.getCharges();
		Run out = new Run();

		Parcel start = grid.at(new WorldPoint(3222, 3218, 0));   // Lumbridge
		assertNotNull(start);
		out.surveyed += DeedLock.grantStart(grid, estate, start);

		// At minute zero the frontier is the whole ring around the one deed you
		// own: every neighbour is unsurveyed and every neighbour touches your
		// land. Past that ring, nothing, until something is bought.
		Set<Integer> frontier = new HashSet<>();
		pushNeighbours(estate, frontier, start.getPx(), start.getPy(), 0);

		// Nothing is surveyed but the deed you stand on, so nothing is yet for
		// sale. The opening's charges are what put the first deed on the market.
		List<Parcel> forSale = new ArrayList<>();
		for (int i = 0; i < grid.size(); i++)
		{
			if (!estate.hasSurveyed(i))
			{
				continue;
			}
			Parcel q = grid.atIndex(i);
			if (q != null && q.isClaimable() && !estate.owns(q.getPid()))
			{
				forSale.add(q);
			}
		}

		long totalMinutes = hours * 60L;
		// Paid out against a running target rather than a per-minute rate:
		// dividing first truncates, and the shortfall left every skill one level
		// short of 50 -- which quietly dropped the largest level reward of the
		// run from every column.
		long goal = Experience.getXpForLevel(TARGET_LEVEL);
		long[] skillXp = new long[SKILLS];
		int[] skillLevel = new int[SKILLS];
		for (int i = 0; i < SKILLS; i++)
		{
			skillLevel[i] = 1;
		}

		long now = 1_000_000L;
		estate.accrue(estate.rps(grid), now, cap(), Balance.ONLINE_GRACE_MILLIS,
			Balance.OFFLINE_RENT_RATE);

		for (long minute = 0; minute < totalMinutes; minute++)
		{
			now += MINUTE;

			for (int i = 0; i < SKILLS; i++)
			{
				if (skillLevel[i] >= TARGET_LEVEL)
				{
					continue;
				}
				long want = goal * (minute + 1) / totalMinutes;
				long gained = want - skillXp[i];
				if (gained <= 0)
				{
					continue;
				}
				skillXp[i] = want;
				out.xpCharges += purse.addXp(gained, now,
					SurveyCharges.xpCostFor(skillLevel[i], Balance.XP_PER_CHARGE),
					Balance.CHARGES_PER_HOUR);

				int reached = Experience.getLevelForXp((int) Math.min(Integer.MAX_VALUE,
					skillXp[i]));
				while (skillLevel[i] < Math.min(reached, TARGET_LEVEL))
				{
					skillLevel[i]++;
					out.levelCharges += purse.addLevel(skillLevel[i]);
				}
			}

			// Playing, so a full-rate minute.
			estate.accrue(estate.rps(grid), now, cap(), Balance.ONLINE_GRACE_MILLIS,
				Balance.OFFLINE_RENT_RATE);

			// Spend every charge the moment it lands, on the richest deed the
			// frontier offers -- the most a player could get out of them.
			while (purse.getCharges() > 0 && !frontier.isEmpty())
			{
				Parcel best = richest(frontier);
				if (best == null)
				{
					break;
				}
				purse.spend();
				estate.markSurveyed(grid.indexOf(best));
				frontier.remove(grid.indexOf(best));
				out.surveyed++;
				if (best.isClaimable())
				{
					forSale.add(best);
				}
				// No neighbours opened here any more: the frontier follows OWNED
				// land, so surveying a deed does not let you survey past it.
			}

			for (int mm : markMinutes)
			{
				if (minute + 1 == mm)
				{
					Mark m = new Mark();
					m.label = mm < 60 ? mm + "m" : (mm / 60) + "h";
					m.charges = purse.getCharges();
					m.surveyed = out.surveyed;
					m.owned = out.bought;
					m.balance = estate.getBalance();
					m.rps = estate.rps(grid);
					out.marks.add(m);
				}
			}

			// Buy the best rent per coin the purse can actually reach.
			//
			// Deliberately not "the best one, if affordable". Rent per coin is
			// a property of the district, but the price is per parcel, so the
			// top of the list is regularly a dear parcel of a cheap district
			// while a cheaper one sits three rows down. Stopping at the first
			// unaffordable row modelled a player who stares at one deed for
			// forty-five minutes, and made the early game look rent-starved
			// when it was nothing of the kind.
			while (true)
			{
				Parcel pick = null;
				long pickPrice = 0;
				double bestValue = -1;
				for (Parcel p : forSale)
				{
					long price = estate.effectivePrice(p, Balance.PRICE_SCALE,
						Balance.PRICE_EXPONENT);
					if (price > estate.getBalance())
					{
						continue;
					}
					double value = p.rps() / Math.max(1.0, price);
					if (value > bestValue)
					{
						bestValue = value;
						pick = p;
						pickPrice = price;
					}
				}
				if (pick == null)
				{
					break;
				}
				estate.setBalance(estate.getBalance() - pickPrice);
				estate.getOwned().put(pick.getPid(),
					(int) Math.min(Integer.MAX_VALUE, pickPrice));
				forSale.remove(pick);
				out.bought++;
				// Buying is what moves the ring outward.
				pushNeighbours(estate, frontier, pick.getPx(), pick.getPy(), 1);
			}
		}

		out.balance = estate.getBalance();
		out.rps = estate.rps(grid);
		return out;
	}

	private static long cap()
	{
		return Balance.OFFLINE_RENT_HOURS * 3600_000L;
	}

	/** The best-paying deed on the frontier, which is where a player would go. */
	private Parcel richest(Set<Integer> frontier)
	{
		Parcel best = null;
		double bestRps = -1;
		for (int idx : frontier)
		{
			Parcel p = grid.atIndex(idx);
			if (p == null)
			{
				continue;
			}
			if (p.rps() > bestRps)
			{
				bestRps = p.rps();
				best = p;
			}
		}
		return best;
	}

	private void pushNeighbours(Estate estate, Set<Integer> frontier, int px, int py,
		int radius)
	{
		for (int dx = -radius - 1; dx <= radius + 1; dx++)
		{
			for (int dy = -radius - 1; dy <= radius + 1; dy++)
			{
				Parcel q = grid.at(px + dx, py + dy);
				int idx = grid.indexOf(q);
				if (q != null && idx >= 0 && !estate.hasSurveyed(idx))
				{
					frontier.add(idx);
				}
			}
		}
	}


}
