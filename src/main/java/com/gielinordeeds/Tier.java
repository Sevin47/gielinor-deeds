/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * District tiers. The ordinal IS the on-disk tier byte in parcels.bin and the
 * tier code the server stores, so this enum must stay in the same order as
 * TIERS in build_grid.py and export_parcels.py. Appending is safe; reordering
 * silently reclassifies every parcel already claimed in the database.
 */
@Getter
@RequiredArgsConstructor
public enum Tier
{
	/*
	 * basePrice anchors what parcels.bin charges; baseRps is what a parcel at
	 * that price pays. Their quotient is how long a deed takes to pay for
	 * itself, and because rpsFor scales both together that payback is a
	 * property of the district alone.
	 *
	 * Payback runs from about 45 minutes at the bottom to three hours at the
	 * top, so the two currencies pull against each other: a Capital deed pays
	 * five times what a Wasteland one does and is the better use of a charge,
	 * while a Wasteland deed pays for itself four times faster and is the
	 * better use of a coin.
	 *
	 * The spread is anchored on Wasteland keeping its existing rent, because
	 * Wasteland and its neighbours cover most of Gielinor; anchoring elsewhere
	 * would raise the map's total income as a side effect.
	 */
	CAPITAL("Capital", 800, 0.0740, 0xF0784E, true),
	HARBOUR("Harbour", 500, 0.0575, 0x3FB8AF, true),
	TOWNSHIP("Township", 400, 0.0510, 0x9B7BF5, true),
	OUTSKIRTS("Outskirts", 220, 0.0370, 0xC08FB0, true),
	COAST("Coast", 200, 0.0351, 0x4FA3C7, true),
	FARMLAND("Farmland", 150, 0.0301, 0x8FBF6B, true),
	WOODLAND("Woodland", 120, 0.0267, 0x3F7F3A, true),
	JUNGLE("Jungle", 130, 0.0279, 0x1C6B40, true),
	SAVANNAH("Savannah", 90, 0.0229, 0xCBBF5C, true),
	SWAMP("Swamp", 80, 0.0215, 0x6D8F7C, true),
	DESERT("Desert", 70, 0.0200, 0xE7D3A1, true),
	HIGHLAND("Highland", 60, 0.0184, 0xA9A396, true),
	TUNDRA("Tundra", 55, 0.0176, 0xEDF3F7, true),
	VOLCANIC("Volcanic", 45, 0.0158, 0xCF3D1A, true),
	/*
	 * Wasteland, not Wilderness. This tier comes from LULC class 99, "blighted
	 * or barren land" -- scorched dirt and dead ground, which occurs all over
	 * Gielinor and not only in the PvP area. Naming it Wilderness made the map
	 * look wrong to anyone who knows where the Wilderness actually is.
	 */
	WASTELAND("Wasteland", 40, 0.0148, 0x6A5046, true),
	/*
	 * Open water is buyable and pays nothing.
	 *
	 * It is bought to cross, not to hold: the Wizard Tower, the causeways and
	 * every island need a chain of water deeds before there is anything on the
	 * far side to survey. Without that, a run can be walled in by a river it
	 * can see across but never reach.
	 *
	 * Rent stays at zero so a sea crossing is a cost rather than an investment,
	 * and isLand() keeps all 75,072 of these out of the Deed Log -- counting
	 * them would make finishing the map a percentage of the ocean.
	 */
	WATER("Open water", 50, 0.0, 0x4A7FA5, true),
	OFFMAP("Unsurveyed", 0, 0.0, 0x2A2E38, false);

	private static final Tier[] BY_CODE = values();

	private final String displayName;
	private final int basePrice;
	private final double baseRps;
	private final int rgb;
	private final boolean buyable;

	public static Tier byCode(int code)
	{
		return (code >= 0 && code < BY_CODE.length) ? BY_CODE[code] : OFFMAP;
	}

	public Color color()
	{
		return new Color(rgb);
	}

	/**
	 * Per-parcel rent. build_grid.price_rps() scales price and rps by the same
	 * commitment multiplier, so the ratio is exact -- this is a derivation, not
	 * an approximation, and it is why parcels.bin stores no rps field.
	 */
	public double rpsFor(int parcelPrice)
	{
		return basePrice == 0 ? 0.0 : baseRps * ((double) parcelPrice / basePrice);
	}

	/**
	 * True when this district is dry land: rentable, and part of the map to
	 * finish.
	 *
	 * Open water is buyable but is neither. Everything that counts progress or
	 * looks for somewhere to live off asks this rather than isBuyable.
	 */
	public boolean isLand()
	{
		return buyable && baseRps > 0;
	}
}
