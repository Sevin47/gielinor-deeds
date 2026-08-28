/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rent accrual.
 *
 * These exist because of a real bug: accrue() is called on a 600ms timer, and
 * the first version floored the payout and advanced its clock to now regardless.
 * At a realistic 0.118/s that floors to zero every single tick, so the remainder
 * was discarded forever and rent never accrued at all. Everything here is
 * phrased against that failure.
 */
public class EstateTest
{
	private static final long CAP = 8L * 3600 * 1000;
	private static final long GRACE = Balance.ONLINE_GRACE_MILLIS;
	private static final double RATE = Balance.OFFLINE_RENT_RATE;
	/** A real two-parcel estate: Outskirts 0.069 + Farmland 0.049. */
	private static final double RPS = 0.069 + 0.049;
	private static final long T0 = 1_000_000L;

	@Test
	public void accruesAcrossManySmallTicks()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);                       // first call only sets the clock

		long t = T0;
		for (int i = 0; i < 500; i++)
		{
			t += 600;
			e.accrue(RPS, t, CAP, GRACE, RATE);
		}
		// 500 ticks x 600ms = 300s
		assertEquals((long) Math.floor(RPS * 300), e.getBalance());
	}

	/**
	 * Settling often must pay the same as settling once.
	 *
	 * Kept inside the grace on purpose. A 300-second gap is now read as time
	 * away and pays a quarter, so the old version of this -- 500 ticks against
	 * one 300s jump -- was comparing playing against not playing and would fail
	 * for an entirely correct reason.
	 */
	@Test
	public void manySmallTicksMatchOneBigTickWhilePlaying()
	{
		Estate ticked = new Estate();
		ticked.setBalance(0);
		ticked.accrue(RPS, T0, CAP, GRACE, RATE);
		long t = T0;
		for (int i = 0; i < 50; i++)
		{
			t += 600;
			ticked.accrue(RPS, t, CAP, GRACE, RATE);
		}

		Estate once = new Estate();
		once.setBalance(0);
		once.accrue(RPS, T0, CAP, GRACE, RATE);
		once.accrue(RPS, T0 + 30_000, CAP, GRACE, RATE);

		assertEquals(once.getBalance(), ticked.getBalance());
	}

	/**
	 * Time away pays, but pays less.
	 *
	 * There is no way to observe being logged out from inside the client, so a
	 * gap longer than the grace is what stands in for it: rent settles every
	 * 600ms, so nothing settling it for two hours means nobody was there.
	 */
	@Test
	public void timeAwayPaysTheReducedRate()
	{
		Estate away = new Estate();
		away.setBalance(0);
		away.accrue(RPS, T0, CAP, GRACE, RATE);
		away.accrue(RPS, T0 + 2L * 3600 * 1000, CAP, GRACE, RATE);

		assertEquals((long) Math.floor(RPS * RATE * 7200), away.getBalance());
	}

	/** A hitch, a loading screen or a world hop is not time away. */
	@Test
	public void aShortGapStillPaysFullRate()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);
		e.accrue(RPS, T0 + GRACE - 1, CAP, GRACE, RATE);

		assertEquals((long) Math.floor(RPS * (GRACE - 1) / 1000.0), e.getBalance());
	}

	/**
	 * Playing an hour must beat being away for one.
	 *
	 * The whole point of the reduced rate: at parity the best way to earn is to
	 * close the game, which is a strange thing for a plugin about walking
	 * around Gielinor to reward.
	 */
	@Test
	public void anHourPlayedBeatsAnHourAway()
	{
		Estate played = new Estate();
		played.setBalance(0);
		played.accrue(RPS, T0, CAP, GRACE, RATE);
		long t = T0;
		for (int i = 0; i < 6000; i++)
		{
			t += 600;
			played.accrue(RPS, t, CAP, GRACE, RATE);
		}

		Estate away = new Estate();
		away.setBalance(0);
		away.accrue(RPS, T0, CAP, GRACE, RATE);
		away.accrue(RPS, T0 + 3_600_000, CAP, GRACE, RATE);

		assertTrue("played " + played.getBalance() + " vs away " + away.getBalance(),
			played.getBalance() > away.getBalance());
	}

	@Test
	public void owningNothingBanksNoTime()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(0, T0, CAP, GRACE, RATE);
		e.accrue(0, T0 + 3_600_000, CAP, GRACE, RATE);             // an hour owning nothing
		e.accrue(RPS, T0 + 3_600_000 + 1000, CAP, GRACE, RATE);    // then buy something
		assertEquals(0, e.getBalance());
	}

	@Test
	public void offlineCapLimitsPayout()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);
		e.accrue(RPS, T0 + 30L * 24 * 3600 * 1000, CAP, GRACE, RATE);   // away for a month
		// Both limits apply: eight hours' worth at most, and at the away rate.
		assertEquals((long) Math.floor(RPS * RATE * (CAP / 1000.0)), e.getBalance());
	}

	@Test
	public void clockGoingBackwardsPaysNothing()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);
		e.accrue(RPS, T0 - 60_000, CAP, GRACE, RATE);
		assertEquals(0, e.getBalance());
	}

	@Test
	public void remainderCarriesRatherThanBeingDiscarded()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);
		// One second earns 0.118, which floors to nothing -- but must not reset
		// the clock, or the next second starts from zero again.
		e.accrue(RPS, T0 + 1000, CAP, GRACE, RATE);
		assertEquals(0, e.getBalance());
		// By ~8.5s the carried remainder crosses 1.
		e.accrue(RPS, T0 + 9000, CAP, GRACE, RATE);
		assertTrue("expected at least 1 by 9s, got " + e.getBalance(), e.getBalance() >= 1);
	}
	/**
	 * One absence is reported once.
	 *
	 * The clock used to advance by what was PAID FOR, which at a floor falls
	 * short of what elapsed by up to a coin's worth of seconds -- and on an
	 * estate of one field, a coin is minutes. So the tick after a long absence
	 * still found a gap bigger than the grace, paid that at the away rate too,
	 * and told the player they had been away a second time: "87 gp over 41m
	 * away" and then, immediately, "21 gp over 1m away".
	 */
	@Test
	public void timeAwayIsSettledInOneGo()
	{
		Estate e = new Estate();
		e.setBalance(0);
		e.accrue(RPS, T0, CAP, GRACE, RATE);

		long back = T0 + 41L * 60 * 1000;
		long first = e.accrue(RPS, back, CAP, GRACE, RATE);
		assertTrue("the absence should pay something", first > 0);

		// The very next tick, 600ms later, must not find another absence.
		long second = e.accrue(RPS, back + 600, CAP, GRACE, RATE);
		assertEquals("the same absence must not pay twice", 0, second);
	}

	/**
	 * The figure quoted to the player is the figure that was paid.
	 *
	 * The chat line says "earned N gp over M away", and both halves come from
	 * here: N is what accrue returns, M is the span it was asked to settle. If
	 * they disagree the message is a lie, however well the balance adds up.
	 */
	@Test
	public void whatIsPaidMatchesTheSpanItIsPaidFor()
	{
		for (long minutes : new long[]{2, 30, 90, 480})
		{
			Estate e = new Estate();
			e.setBalance(0);
			e.accrue(RPS, T0, CAP, GRACE, RATE);

			long span = Math.min(minutes * 60 * 1000, CAP);
			long paid = e.accrue(RPS, T0 + minutes * 60 * 1000, CAP, GRACE, RATE);

			assertEquals(minutes + " minutes away should pay for exactly that span",
				(long) Math.floor(RPS * RATE * (span / 1000.0)), paid);
			assertEquals("and the balance should hold exactly that", paid, e.getBalance());
		}
	}
}
