/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * One character's holdings and everything they have surveyed.
 *
 * Saved as JSON in RuneLite's own config store, so there is no server, no
 * account and no network -- your estate lives on your PC alongside the rest of
 * your RuneLite settings. Keyed per RuneScape name so alts keep separate
 * estates, which matters because a parcel is a record that a particular
 * character walked somewhere.
 */
@Slf4j
@Data
public class Estate
{
	/**
	 * Seed money, enough for one deed of most districts.
	 *
	 * Which deed to spend it on is the first decision of a run, so it must not
	 * cover every neighbour the opening charges reveal. It does have to cover
	 * at least one of them: developed land classifies as Capital, and a Capital
	 * deed runs past 1,200, so a run starting in a town could otherwise afford
	 * nothing at all.
	 */
	static final long STARTING_BALANCE = 2000;

	private long balance = STARTING_BALANCE;

	/** pid -> what was paid. Kept so the panel can value a portfolio even if
	 *  the survey is later regenerated with different prices. */
	private Map<String, Integer> owned = new HashMap<>();

	/** Epoch millis of the last rent payout. */
	private long lastAccrued = 0;

	/**
	 * The right to survey, earned by playing. Rent buys land; playing OSRS buys
	 * knowledge, and the two cannot substitute for each other.
	 */
	private SurveyCharges charges = new SurveyCharges();

	/**
	 * Survey range upgrade level. 0 surveys the parcel underfoot; each level
	 * widens the block one ring, so a charge goes further without ever handing
	 * out charges -- money buys reach, playing buys the right to use it.
	 */
	private int surveyRange = 0;

	/**
	 * Which parcels this character has surveyed, as a deflated base64 bitset
	 * indexed by ParcelGrid.indexOf.
	 *
	 * A set of "px_py" strings reaches ~88 KB of JSON at 10,000 surveyed
	 * parcels and grows without bound. A bitset over the whole 115,200-parcel
	 * grid is 14 KB flat, and deflates to very little while mostly zeroes.
	 */
	private String surveyedBits = "";

	/** Live view of surveyedBits. Transient so Gson never sees it. */
	private transient BitSet surveyed;

	public boolean owns(String pid)
	{
		return pid != null && owned.containsKey(pid);
	}

	// ── surveyed set ─────────────────────────────────────────────────────

	private BitSet bits()
	{
		if (surveyed == null)
		{
			surveyed = decode(surveyedBits);
		}
		return surveyed;
	}

	public boolean hasSurveyed(int index)
	{
		return index >= 0 && bits().get(index);
	}

	public void markSurveyed(int index)
	{
		if (index >= 0)
		{
			bits().set(index);
		}
	}

	/** Read-only view for iterating what has been surveyed. Do not mutate. */
	public BitSet surveyedSet()
	{
		return bits();
	}

	public int surveyedCount()
	{
		return bits().cardinality();
	}

	/**
	 * Mark everything owned as surveyed, and report how many that added.
	 *
	 * This is an invariant, not a migration: buying a parcel requires surveying
	 * it first, so owning land you have never surveyed is a state the game
	 * cannot legitimately reach. Enforcing it on load rather than fixing it once
	 * means it also repairs saves written before the surveyed set existed, where
	 * the owned map is the only surviving record that the player was ever there.
	 */
	public int reconcileOwnedAsSurveyed(ParcelGrid grid)
	{
		if (grid == null)
		{
			return 0;
		}
		int added = 0;
		for (String pid : owned.keySet())
		{
			Parcel p = grid.byPid(pid);
			int idx = grid.indexOf(p);
			if (idx >= 0 && !bits().get(idx))
			{
				bits().set(idx);
				added++;
			}
		}
		return added;
	}

	/** Fold the live bitset back into the serialisable field. Call before save. */
	public void packSurveyed()
	{
		if (surveyed != null)
		{
			surveyedBits = encode(surveyed);
		}
	}

	private static String encode(BitSet set)
	{
		byte[] raw = set.toByteArray();
		Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
		try
		{
			def.setInput(raw);
			def.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 32);
			byte[] buf = new byte[8192];
			while (!def.finished())
			{
				out.write(buf, 0, def.deflate(buf));
			}
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		finally
		{
			def.end();
		}
	}

	private static BitSet decode(String s)
	{
		if (s == null || s.isEmpty())
		{
			return new BitSet();
		}
		Inflater inf = new Inflater();
		try
		{
			inf.setInput(Base64.getDecoder().decode(s));
			ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
			byte[] buf = new byte[8192];
			while (!inf.finished())
			{
				int n = inf.inflate(buf);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary()))
				{
					break;                       // truncated; keep what we have
				}
				out.write(buf, 0, n);
			}
			return BitSet.valueOf(out.toByteArray());
		}
		catch (IllegalArgumentException | DataFormatException e)
		{
			// A corrupt survey record costs the player their exploration, which
			// is bad -- but refusing to load the estate at all would also cost
			// them their land, which is worse.
			log.warn("could not read the surveyed set, starting it empty", e);
			return new BitSet();
		}
		finally
		{
			inf.end();
		}
	}

	// ── economy ──────────────────────────────────────────────────────────

	/**
	 * Rent per second across everything owned, priced from the shipped survey.
	 * Derived rather than stored so regenerating the survey re-prices an existing
	 * estate instead of leaving it on stale numbers.
	 */
	public double rps(ParcelGrid grid)
	{
		if (grid == null)
		{
			return 0;
		}
		double total = 0;
		for (String pid : owned.keySet())
		{
			Parcel p = grid.byPid(pid);
			if (p != null)
			{
				total += p.rps();
			}
		}
		return total;
	}

	/**
	 * Pay out rent earned since the last payout, and return how much was paid.
	 *
	 * Called on a 600ms timer, so most calls earn less than one coin. The clock
	 * advances only by the time actually paid for, leaving the remainder to
	 * carry; flooring and advancing to now regardless would discard it every
	 * tick and rent would never accrue at all.
	 *
	 * Being logged out cannot be observed from inside the client, so it is
	 * inferred from the gap: anything longer than the grace means nothing was
	 * settling rent, and that whole span pays at the reduced rate.
	 *
	 * @param rps         rent per second across everything owned
	 * @param now         current epoch millis
	 * @param capMillis   most elapsed time that may be paid out at once
	 * @param graceMillis gap up to which the player counts as playing
	 * @param offlineRate what a second beyond the grace pays against a played one
	 */
	public long accrue(double rps, long now, long capMillis, long graceMillis,
		double offlineRate)
	{
		if (lastAccrued == 0)
		{
			lastAccrued = now;
			return 0;
		}
		// Anything beyond the cap is forfeited by moving the clock forward,
		// rather than paid out -- that is what the offline cap means.
		if (now - lastAccrued > capMillis)
		{
			lastAccrued = now - capMillis;
		}
		if (rps <= 0)
		{
			// Owning nothing must not bank time. Otherwise a player who idles for
			// an hour and then buys a parcel gets an hour of backpay on it.
			lastAccrued = now;
			return 0;
		}

		long elapsed = Math.max(0, now - lastAccrued);
		double rate = elapsed > graceMillis ? rps * offlineRate : rps;
		long earned = (long) Math.floor(rate * (elapsed / 1000.0));
		if (earned <= 0)
		{
			return 0;                     // leave the clock; the fraction carries
		}
		balance += earned;
		if (elapsed > graceMillis)
		{
			// Time away is settled outright, remainder and all.
			//
			// Carrying it looked tidy and reported the same absence twice. The
			// clock advances by what was PAID FOR, which at a floor is short of
			// what elapsed by up to one coin's worth of seconds -- and one
			// coin's worth, on an estate of one field, is minutes. So the next
			// tick found a gap still bigger than the grace, paid it at the away
			// rate as well, and announced a second absence: "87 gp over 41m
			// away" and then, a heartbeat later, "21 gp over 1m away".
			//
			// A fraction of a coin is not worth carrying across a session. It
			// is certainly not worth telling the player they were away twice.
			lastAccrued = now;
			return earned;
		}
		// Consumed at the rate actually paid, so the carried remainder stays
		// honest while playing: at a realistic 0.12/s a 600ms tick earns
		// nothing, and discarding that would mean rent never accrued at all.
		long consumed = (long) Math.round(earned / rate * 1000.0);
		lastAccrued += Math.min(consumed, elapsed);
		return earned;
	}

	/**
	 * How much dearer land has become, given how much is already held.
	 *
	 * This has to grow FASTER than linearly. Rent is linear in parcels owned,
	 * while (1 + owned/scale) is nearly constant for a small estate -- so under
	 * a linear multiplier affordability runs away rather than holding steady:
	 * measured, about 3 parcels an hour at ten owned rising past 20 at a
	 * thousand, which would leave money permanently irrelevant next to the
	 * ~12/hour the charge economy supplies.
	 *
	 * The exponent is not there to make land unaffordable. It is there to keep
	 * affordability in the same league as charges, so both resources matter.
	 */
	public double priceMultiplier(double scale, double exponent)
	{
		if (scale <= 0)
		{
			return 1.0;
		}
		return Math.pow(1.0 + owned.size() / scale, exponent);
	}

	/** What a parcel actually costs this estate right now. */
	public long effectivePrice(Parcel p, double scale, double exponent)
	{
		return p == null ? 0
			: Math.round(p.getPrice() * priceMultiplier(scale, exponent));
	}

	public long portfolioValue()
	{
		long v = 0;
		for (int paid : owned.values())
		{
			v += paid;
		}
		return v;
	}
}
