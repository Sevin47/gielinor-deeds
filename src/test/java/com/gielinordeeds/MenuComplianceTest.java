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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The shape "Lock me to my land" has to keep, to stay allowed.
 *
 * Three rules, each from a source, and none of them obvious from reading the
 * code that implements them -- which is exactly why they are pinned here rather
 * than left as a comment somebody tidies away.
 *
 * <h2>Menu entries are never removed</h2>
 *
 * RuneLite's Rejected or Rolled Back Features lists "conditional menu entry
 * removing" -- hiding options based on some condition -- as rejected, reason
 * "can be overpowered in some cases". Hold Your Ground, which is on the plugin
 * hub doing this same job, never removes anything: every option renders, and
 * the click is refused afterwards.
 *
 * <h2>Other players are never touched</h2>
 *
 * Jagex's Third Party Client Guidelines name "reorders or removes player-based
 * options, such as 'Trade with'" as unacceptable. Hold Your Ground resolves
 * NPCs only -- npcs.byIndex(identifier) -- and never looks up a player. Being
 * on your own land is a rule about ground, so ground and what stands on it is
 * as far as this needs to reach.
 *
 * <h2>A refused click says so</h2>
 *
 * Not a rule, a consequence: a click that silently does nothing is
 * indistinguishable from a dropped one, so the player clicks again, harder.
 * Hold Your Ground prints a line every time it swallows a step.
 *
 * Source-level, like the rest of the plugin's structural tests. It cannot prove
 * the feature is allowed -- nobody can, the review is explicitly best-effort --
 * but it can prove the plugin has not quietly drifted back to the shape that
 * has no precedent behind it.
 */
public class MenuComplianceTest
{
	private static String plugin() throws Exception
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

	@Test
	public void theMenuIsNeverEditedOnACondition() throws Exception
	{
		String tick = bodyOf(plugin(), "public void onClientTick(");
		assertNotNull(tick);
		assertFalse("onClientTick must not drop entries on a condition -- "
			+ "RuneLite lists conditional menu entry removing as rejected, and "
			+ "the approved plugin doing this job refuses the click instead",
			tick.contains("aimsOffEstate("));
	}

	@Test
	public void otherPlayersAreNeverResolved() throws Exception
	{
		String src = plugin();
		assertFalse("PLAYER_ACTIONS is back -- Jagex's guidelines name removing "
			+ "player-based options as unacceptable", src.contains("PLAYER_ACTIONS"));
		assertFalse("this must never look up another player to decide anything",
			src.contains("players().byIndex("));
	}

	@Test
	public void aRefusedClickIsExplained() throws Exception
	{
		String click = bodyOf(plugin(), "public void onMenuOptionClicked(");
		assertNotNull(click);
		assertTrue("the click still has to be refused", click.contains("event.consume()"));
		assertTrue("and the player has to be told why, or they will just click "
			+ "again", click.contains("sayRefused("));
	}

	/**
	 * The refusal is the whole feature, and it is opt-in.
	 *
	 * Region Locker -- the thing this borrows its shape from -- blacks out and
	 * warns but never blocks, so a player who wants that should get it by
	 * default and have to ask for more.
	 */
	@Test
	public void blockingIsOffUntilAskedFor() throws Exception
	{
		Path p = Paths.get("src", "main", "java", "com", "gielinordeeds",
			"GielinorDeedsConfig.java");
		String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
		int at = src.indexOf("hideOffEstateMenus");
		assertTrue("expected the setting to exist", at >= 0);
		String after = src.substring(at, Math.min(src.length(), at + 1400));
		assertTrue("Lock me to my land must default to off",
			after.contains("return false;"));
	}
}
