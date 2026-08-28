/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a survey range upgrade actually buys.
 *
 * The arithmetic was never wrong. What was wrong is that the panel sold the
 * upgrade during a Deed Locked run, where it does nothing at all: a locked run
 * opens exactly the deed it paid for whatever instrument you hold, so the
 * radius is forced to 0 and a 400,000 Theodolite changes precisely nothing the
 * player can see. Buying it looked like it worked -- the name changed, the
 * money went -- and then no survey behaved any differently.
 *
 * So there are two things to pin: that coverage means what the panel says it
 * means, and that the run which ignores it does not sell it.
 */
public class SurveyRangeTest
{
	@Test
	public void coverageIsTheBlockOneChargeOpens()
	{
		assertEquals("Pacing is the deed underfoot and nothing else",
			1, SurveyRange.PACE.coverage());
		assertEquals("the chain is 3x3", 9, SurveyRange.CHAIN.coverage());
		assertEquals("the theodolite is 5x5", 25, SurveyRange.THEODOLITE.coverage());

		for (SurveyRange r : SurveyRange.values())
		{
			int side = r.getRadius() * 2 + 1;
			assertEquals(r + " should cover its own square", side * side, r.coverage());
		}
	}

	@Test
	public void theUpgradePathRunsOutAtTheTop()
	{
		assertEquals(SurveyRange.CHAIN, SurveyRange.next(0));
		assertEquals(SurveyRange.THEODOLITE, SurveyRange.next(1));
		assertNull("there is deliberately no third ring", SurveyRange.next(2));
		assertTrue(SurveyRange.isMaxed(2));
		assertTrue("a save from a future version must not unlock more",
			SurveyRange.isMaxed(99));
	}

	/** A stored level out of range must clamp, not throw. */
	@Test
	public void aBadStoredLevelClampsRatherThanThrowing()
	{
		assertEquals(SurveyRange.PACE, SurveyRange.forLevel(-5));
		assertEquals(SurveyRange.PACE, SurveyRange.forLevel(0));
		assertEquals(SurveyRange.THEODOLITE, SurveyRange.forLevel(2));
		assertEquals(SurveyRange.THEODOLITE, SurveyRange.forLevel(99));
	}

	@Test
	public void eachUpgradeCostsMoreAndCoversMore()
	{
		SurveyRange[] all = SurveyRange.values();
		assertEquals("the level everyone starts at is free", 0, all[0].getCost());
		for (int i = 1; i < all.length; i++)
		{
			assertTrue(all[i] + " should cost more than " + all[i - 1],
				all[i].getCost() > all[i - 1].getCost());
			assertTrue(all[i] + " should cover more than " + all[i - 1],
				all[i].coverage() > all[i - 1].coverage());
		}
	}

	/**
	 * The bug: a locked run forces the radius to 0, so the panel must not sell
	 * an upgrade to it.
	 *
	 * Source-level, because both halves are inside the plugin and reaching them
	 * needs the whole injected graph. It checks the two sides still agree --
	 * that the survey ignores range in a locked run, and that the purchase
	 * knows it does.
	 */
	@Test
	public void aLockedRunNeitherUsesTheRangeNorSellsIt() throws Exception
	{
		String src = source();

		String survey = bodyOf(src, "private List<Parcel> newDeedsAround(");
		assertNotNull(survey);
		assertTrue("a locked run is supposed to force the radius to zero",
			survey.contains("config.deedLocked()") && survey.contains("? 0"));

		String buy = bodyOf(src, "public void buyRangeUpgrade(");
		assertNotNull(buy);
		assertTrue("if a locked run ignores the range, buying one has to be "
			+ "refused -- otherwise the panel takes the money for nothing",
			buy.contains("config.deedLocked()"));
		assertTrue("and it must refuse before spending anything",
			buy.indexOf("config.deedLocked()") < buy.indexOf("setBalance"));
	}

	private static String source() throws Exception
	{
		Path p = Paths.get("src", "main", "java", "com", "gielinordeeds",
			"GielinorDeedsPlugin.java");
		assertTrue("expected the plugin source at " + p.toAbsolutePath(),
			Files.exists(p));
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	/** Crude brace matching. See SurveyDelayTest for why that is good enough. */
	private static String bodyOf(String src, String declaration)
	{
		int sig = src.indexOf(declaration);
		assertTrue("no method matching '" + declaration + "'", sig >= 0);
		int open = src.indexOf('{', sig);
		int depth = 0;
		for (int i = open; i < src.length(); i++)
		{
			char c = src.charAt(i);
			if (c == '{')
			{
				depth++;
			}
			else if (c == '}' && --depth == 0)
			{
				return src.substring(open, i + 1);
			}
		}
		return null;
	}
}
