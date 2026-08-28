/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How unsurveyed ground is marked in a Deed Locked run.
 *
 * The three are different bargains, not settings of one thing.
 *
 * <p>{@link #TINT} does not conceal, which is why it cannot fail: land you do
 * not own is washed with colour and reads as out of bounds.
 *
 * <p>{@link #BLACKOUT} is TINT with the colour black and the opacity full.
 *
 * <p>{@link #HIDDEN} is the only one that truly conceals, since the terrain is
 * never drawn. It needs a GPU plugin and falls back to tinting without one.
 */
@Getter
@RequiredArgsConstructor
public enum VeilStyle
{
	/** A wash of colour over ground you do not own. Cannot be seen around. */
	TINT("Tint", "Wash unsurveyed land with colour. It stays visible, but reads "
		+ "as out of bounds. Nothing to see around, at any zoom."),

	/** Paint it out. Visible from any angle, but it is a picture of a hole. */
	BLACKOUT("Black out", "Paint over unsurveyed land. Hides what is there, but "
		+ "it is paint on top of the world rather than the world being absent."),

	/** Do not draw it at all. Needs a GPU plugin; falls back to TINT without one. */
	HIDDEN("Hide the ground", "Stop unsurveyed terrain being drawn at all -- "
		+ "sharper and cheaper, and it takes the trees and buildings with it. "
		+ "Needs a GPU plugin, and a pulled-back camera can see past its edge.");

	private final String displayName;
	private final String description;

	@Override
	public String toString()
	{
		return displayName;
	}
}
