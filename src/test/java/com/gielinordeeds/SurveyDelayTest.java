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
 * A survey is work that takes time, not a click that resolves.
 *
 * Three rules hang off that, and all three are the kind that get quietly undone
 * by someone tidying up:
 *
 *   the charge is spent at the END       so a survey that is interrupted, or
 *                                        that finds the ground already open,
 *                                        costs nothing
 *   surveyAt only starts the work        it is called from a panel button on
 *                                        the Swing thread, so it must not
 *                                        touch the client -- the pose is
 *                                        started on the game tick instead
 *   finishSurvey is where it lands       markSurveyed and spend live there and
 *                                        nowhere else
 *
 * Source-level, like {@link PanelThreadSafetyTest}, and for the same reason:
 * constructing the plugin needs the whole injected graph, and what is being
 * protected here is which code exists rather than what it computes. It cannot
 * prove the delay works. It does catch collapsing it back to instant.
 */
public class SurveyDelayTest
{
	private static String plugin() throws Exception
	{
		return read(Paths.get("src", "main", "java", "com", "gielinordeeds",
			"GielinorDeedsPlugin.java"));
	}

	private static String panel() throws Exception
	{
		return read(Paths.get("src", "main", "java", "com", "gielinordeeds",
			"GielinorDeedsPanel.java"));
	}

	private static String read(Path p) throws Exception
	{
		assertTrue("expected source at " + p.toAbsolutePath(), Files.exists(p));
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	/**
	 * The body of a method, found by the start of its declaration.
	 *
	 * Crude brace matching, good enough for this file: ordinary formatted Java
	 * with no braces inside string literals in the methods concerned.
	 */
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
	public void startingASurveyDoesNotApplyIt() throws Exception
	{
		String body = bodyOf(plugin(), "public void surveyAt(");
		assertNotNull(body);
		assertFalse("surveyAt must not open ground -- that is finishSurvey's "
			+ "job, five seconds later", body.contains("markSurveyed("));
		assertFalse("surveyAt must not spend the charge: a survey that is "
			+ "interrupted has to cost nothing", body.contains(".spend()"));
		assertTrue("surveyAt has to actually start something",
			body.contains("surveyDoneAt") && body.contains("surveying = p"));
	}

	@Test
	public void theWorkLandsWhenItFinishes() throws Exception
	{
		String body = bodyOf(plugin(), "private void finishSurvey(");
		assertNotNull(body);
		assertTrue("finishSurvey is where the ground opens",
			body.contains("markSurveyed("));
		assertTrue("and where the charge goes", body.contains(".spend()"));
		assertTrue("it must clear the survey, or the tick fires it forever",
			body.contains("surveying = null"));
	}

	/**
	 * surveyAt is reachable from a panel button, and panel buttons run on the
	 * Swing thread. getLocalPlayer and friends assert the client thread and
	 * throw into the RuneLite log where nobody sees them.
	 */
	@Test
	public void startingASurveyStaysOffTheClientThread() throws Exception
	{
		String body = bodyOf(plugin(), "public void surveyAt(");
		for (String call : new String[]{"getLocalPlayer(", "getWorldLocation(",
			"getTopLevelWorldView(", "getScene(", "setAnimation("})
		{
			assertFalse("surveyAt reaches the client through " + call
				+ " -- move it to onGameTick", body.contains(call));
		}
	}

	/** Five seconds, and held by re-asserting the pose every tick. */
	@Test
	public void theDelayAndThePoseAreOnTheGameTick() throws Exception
	{
		String src = plugin();
		assertTrue("the delay is meant to be five seconds",
			src.contains("SURVEY_MILLIS = 5000"));

		String body = bodyOf(src, "public void onGameTick(");
		assertNotNull("the survey has to be driven by something", body);
		assertTrue("the pose has to be re-asserted, or it lasts one tick",
			body.contains("setAnimation(RESEARCH_ANIMATION)"));
		assertTrue("and the work has to finish", body.contains("finishSurvey("));
	}

	/**
	 * The compass is gone, and stays gone.
	 *
	 * It was a second way to do one thing -- and the one that could not say
	 * which deed it was about. The right-click option covers it on any tile.
	 */
	@Test
	public void theCompassSurveyButtonsAreGone() throws Exception
	{
		String src = plugin();
		assertFalse("surveyNeighbour was the compass's entry point",
			src.contains("surveyNeighbour"));
		assertFalse("canSurveyNeighbour lit its buttons",
			src.contains("canSurveyNeighbour"));
		assertFalse("the panel should no longer build a compass",
			panel().contains("buildCompass"));
	}
	/**
	 * The Survey deed option is not a Deed Locked feature.
	 *
	 * onClientTick used to return immediately unless the challenge mode was on,
	 * so in ordinary play the option was never offered -- which made "Survey on
	 * right-click only" look broken, since it shapes an option that was not
	 * there to shape. The frontier rule is the part that really does belong to
	 * the mode.
	 */
	@Test
	public void theSurveyOptionIsOfferedOutsideTheChallengeMode() throws Exception
	{
		String src = plugin();
		String tick = bodyOf(src, "public void onClientTick(");
		assertNotNull(tick);
		assertFalse("onClientTick must not bail out of ordinary play -- that is "
			+ "what stopped the survey option ever appearing there",
			tick.contains("!config.deedLocked() || grid == null"));

		String offer = bodyOf(src, "private boolean canSurveyFromHere(");
		assertNotNull("something has to decide when to offer the option", offer);
		assertTrue("the frontier rule is Deed Locked's, and only Deed Locked's",
			offer.contains("!config.deedLocked() || DeedLock.onFrontier("));
	}

	/**
	 * Writing the menu back cannot be decided by counting it.
	 *
	 * Hiding one entry and adding the survey option leaves the count exactly as
	 * it was, so a length comparison concluded nothing had happened and wrote
	 * nothing back -- and that is precisely the case of hovering a frontier
	 * deed in a locked run, the one time both halves of the method fire.
	 */
	@Test
	public void theMenuIsWrittenBackWhenOneEntrySwapsForAnother() throws Exception
	{
		String tick = bodyOf(plugin(), "public void onClientTick(");
		assertNotNull(tick);
		assertFalse("a size comparison misses a hide plus an add",
			tick.contains("if (keep.size() != entries.length)"));
		assertTrue("track whether the menu changed instead of inferring it",
			tick.contains("changed = true"));
	}
}
