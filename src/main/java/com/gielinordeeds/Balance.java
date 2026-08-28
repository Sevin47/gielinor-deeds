/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

/**
 * Fixed tuning values for the economy.
 *
 * These are not settings. Each is a difficulty dial, and exposing one would let
 * a player remove the resource the plugin is built around with no way back to
 * the run they meant to play.
 *
 * {@link GielinorDeedsConfig} holds what a player may change: display, and the
 * shape of a Deed Locked run.
 */
public final class Balance
{
	private Balance()
	{
	}

	/**
	 * XP that earns one survey charge at level 99.
	 *
	 * Charged against the level of the skill that earned the XP, so a new
	 * account can reach the same hourly cap as a maxed one. See
	 * {@link SurveyCharges#xpCostFor}.
	 */
	public static final int XP_PER_CHARGE = 5000;

	/**
	 * Charges an hour from XP, and the most that can bank while away.
	 *
	 * The cap stops efficient training earning charges an order of magnitude
	 * faster than ordinary play. Level-ups pay through it regardless.
	 */
	public static final int CHARGES_PER_HOUR = 8;

	/** Most hours of rent that can build up while logged out. */
	public static final int OFFLINE_RENT_HOURS = 8;

	/**
	 * What a logged-out hour pays against a played one.
	 *
	 * At parity, closing the game would be the best way to earn. Charges are
	 * unaffected: they come only from XP, so they never accrue while away.
	 */
	public static final double OFFLINE_RENT_RATE = 0.25;

	/**
	 * Gap between payouts that still counts as playing.
	 *
	 * Rent settles on a 600ms timer, so a much longer gap means the client was
	 * not running.
	 */
	public static final long ONLINE_GRACE_MILLIS = 60_000L;

	/** Parcels owned before prices start climbing noticeably. */
	public static final int PRICE_SCALE = 60;

	/**
	 * How fast land gets dearer as an estate grows.
	 *
	 * Must exceed 1.0. Rent grows linearly in parcels owned, so a linear
	 * multiplier would leave affordability unchanged forever.
	 */
	public static final double PRICE_EXPONENT = 1.60;
}
