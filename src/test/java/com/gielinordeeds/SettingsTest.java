/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the player may change, and what they may not.
 *
 * The settings panel used to carry three sections that were difficulty dials
 * wearing a preference's clothes:
 *
 *   Claiming              XP per charge, the hourly cap, offline rent -- the
 *                         entire cost of surveying
 *   Economy               how fast land gets dearer, which is the only thing
 *                         stopping an estate compounding away the game
 *   Inspecting the map    reveal everything, free surveys, free land, in a
 *                         section the source itself called "dev"
 *
 * All three are fixed in {@link Balance} now. This pins that they stay fixed,
 * because a setting is much easier to add back than to notice: nothing breaks
 * if one reappears, the game just quietly stops being the game.
 */
public class SettingsTest
{
	/** Settings that are now fixed numbers. None of these may come back. */
	private static final List<String> REMOVED = Arrays.asList(
		"xpPerCharge", "scaleXpByLevel", "chargesPerHour", "offlineRentHours",
		"priceScale", "priceExponent",
		"revealAll", "freeSurveys", "freeLand");

	/**
	 * Sections that are gone. Matched loosely, since names get reworded.
	 *
	 * "correction" is on the list because land cover reporting was removed
	 * outright rather than tidied: collecting corrections from everyone who
	 * installs the plugin means verifying all of them, forever, and a tile
	 * number sent to the author does the same job at none of the cost.
	 */
	private static final List<String> REMOVED_SECTIONS = Arrays.asList(
		"claiming", "economy", "inspecting", "dev", "debug", "developer", "cheat",
		"correction");

	@Test
	public void theBalanceDialsAreNoLongerSettings()
	{
		TreeSet<String> found = new TreeSet<>();
		for (Method m : GielinorDeedsConfig.class.getDeclaredMethods())
		{
			if (REMOVED.contains(m.getName()))
			{
				found.add(m.getName());
			}
		}
		assertTrue("these are fixed in Balance and must not be settings again: "
			+ found, found.isEmpty());
	}

	@Test
	public void noSectionOffersToMakeTheGameEasier()
	{
		for (Field f : GielinorDeedsConfig.class.getDeclaredFields())
		{
			ConfigSection s = f.getAnnotation(ConfigSection.class);
			if (s == null)
			{
				continue;
			}
			String name = s.name().toLowerCase();
			for (String gone : REMOVED_SECTIONS)
			{
				assertTrue("section '" + s.name() + "' is back",
					!name.contains(gone));
			}
		}
	}

	/**
	 * Everything still on the interface is a real, declared setting.
	 *
	 * The cheap way to sneak a dial past the test above is to leave the method
	 * on the config and just drop its annotation -- it disappears from the
	 * settings panel but stays a per-player value that something can read.
	 * A config interface should hold settings and section markers, nothing else.
	 */
	@Test
	public void everythingLeftOnTheConfigIsAnActualSetting()
	{
		for (Method m : GielinorDeedsConfig.class.getDeclaredMethods())
		{
			if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() > 0)
			{
				continue;
			}
			assertTrue(m.getName() + " is on the config but is not a @ConfigItem "
				+ "-- either annotate it or move the value to Balance",
				m.isAnnotationPresent(ConfigItem.class));
		}
	}

	/**
	 * The balance numbers are what somebody chose, and changing one is a
	 * decision rather than an edit.
	 *
	 * Most of these are still the old config defaults -- taking a slider away
	 * should not silently rebalance every existing save, which is a separate
	 * decision from removing the slider.
	 *
	 * PRICE_EXPONENT is the exception and is deliberately not 1.30 any more.
	 * Simulated over a long run at 1.30, rent compounded past prices around the
	 * two hundred hour mark and an account ended up owning 92% of everything it
	 * had surveyed -- at which point money has stopped being a decision. See
	 * ProgressionReportTest, which is where a change to any of these should be
	 * argued before it is made.
	 */
	@Test
	public void theFixedNumbersAreTheOnesTheGameIsBalancedOn()
	{
		assertEquals(5000, Balance.XP_PER_CHARGE);
		assertEquals(8, Balance.CHARGES_PER_HOUR);
		assertEquals(8, Balance.OFFLINE_RENT_HOURS);
		assertEquals(60, Balance.PRICE_SCALE);
		assertEquals(1.60, Balance.PRICE_EXPONENT, 1e-9);
		assertEquals(0.25, Balance.OFFLINE_RENT_RATE, 1e-9);
	}

	/** The sections a player should still see. */
	@Test
	public void theSettingsThatRemainAreDisplayAndTheRunItself()
	{
		TreeSet<String> sections = new TreeSet<>();
		for (Field f : GielinorDeedsConfig.class.getDeclaredFields())
		{
			ConfigSection s = f.getAnnotation(ConfigSection.class);
			if (s != null)
			{
				sections.add(s.name());
			}
		}
		assertEquals("expected exactly the sections that are about how the plugin "
			+ "looks, how surveying behaves and how a run is shaped, got " + sections,
			3, sections.size());
		assertTrue(sections + " should still offer Deed Locked",
			sections.contains("Deed Locked"));
		assertTrue(sections + " should still offer Display",
			sections.contains("Display"));
		assertTrue(sections + " should offer Surveying, which is not a Deed "
			+ "Locked feature and stopped working when it was filed as one",
			sections.contains("Surveying"));
	}
}
