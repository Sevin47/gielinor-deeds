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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Panel buttons run on the Swing thread. Most of the game client may only be
 * touched on the client thread, and it enforces that with an assertion rather
 * than a return value -- so an offending call does not misbehave quietly, it
 * throws and the button does nothing at all.
 *
 * That is exactly what happened to flagging: every click on a land cover
 * button threw "must be called on client thread" from getWorldLocation, the
 * exception was swallowed into the RuneLite log, and the panel simply kept
 * saying "No flags yet" with no hint that anything had gone wrong.
 *
 * The plugin already had the right answer for this. State the panel needs is
 * settled once per tick on the client thread -- currentParcel, patchHere,
 * flagHere -- and the panel reads the cache. surveyCurrent and claimCurrent
 * always did this, which is why buying land worked while flagging did not.
 *
 * This is a source-level check rather than a runtime one because constructing
 * the plugin needs the whole injected graph, and the rule being protected is
 * about which code exists rather than what it computes. It is coarse on
 * purpose: it cannot prove thread safety, but it does catch reintroducing the
 * one call that broke it.
 */
public class PanelThreadSafetyTest
{
	/** Methods a panel button or the hotkey can invoke directly. */
	private static final List<String> PANEL_ENTRY_POINTS = Arrays.asList(
		"surveyCurrent", "claimCurrent", "buyRangeUpgrade");

	/** Client calls that assert they are on the client thread. */
	private static final List<String> CLIENT_THREAD_ONLY = Arrays.asList(
		"getWorldLocation(", "getLocalPlayer(", "getScene(", "getTopLevelWorldView(");

	private static String source() throws Exception
	{
		Path p = Paths.get("src", "main", "java", "com", "gielinordeeds",
			"GielinorDeedsPlugin.java");
		assertTrue("expected the plugin source at " + p.toAbsolutePath(),
			Files.exists(p));
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	/**
	 * Crude brace matching over the method body. Good enough for this file,
	 * which is ordinary formatted Java with no string literals containing
	 * braces inside these methods.
	 */
	private static String bodyOf(String src, String method)
	{
		int sig = src.indexOf("public void " + method + "()");
		if (sig < 0)
		{
			return null;
		}
		int open = src.indexOf('{', sig);
		int depth = 0;
		for (int i = open; i < src.length(); i++)
		{
			char c = src.charAt(i);
			if (c == '{')
			{
				depth++;
			}
			else if (c == '}')
			{
				depth--;
				if (depth == 0)
				{
					return src.substring(open, i + 1);
				}
			}
		}
		return null;
	}

	@Test
	public void noPanelActionTouchesTheClientDirectly() throws Exception
	{
		String src = source();
		List<String> offences = new ArrayList<>();

		for (String method : PANEL_ENTRY_POINTS)
		{
			String body = bodyOf(src, method);
			if (body == null)
			{
				continue;   // renamed or removed; the other tests cover behaviour
			}
			for (String call : CLIENT_THREAD_ONLY)
			{
				// Skip the comment lines that explain the rule itself.
				for (String line : body.split("\n"))
				{
					String t = line.trim();
					if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
					{
						continue;
					}
					if (t.contains(call))
					{
						offences.add(method + " calls " + call + " -- \"" + t + "\"");
					}
				}
			}
		}

		if (!offences.isEmpty())
		{
			fail("These run on the Swing thread and will throw \"must be called on "
				+ "client thread\", so the button will silently do nothing. Read the "
				+ "value from the per-tick cache instead:\n  "
				+ String.join("\n  ", offences));
		}
	}

	/**
	 * The cache these methods depend on has to actually be refreshed each tick,
	 * or they would read a value from whenever the player last happened to move.
	 */
	@Test
	public void theTickRefreshesTheStateThePanelReads() throws Exception
	{
		String src = source();
		String tick = bodyOf(src, "onGameTick");
		if (tick == null)
		{
			int at = src.indexOf("@Subscribe");
			assertTrue("expected a tick subscriber", at >= 0);
			tick = src.substring(at);
		}
		// patchHere and flagHere were here too, until land cover reporting was
		// removed. currentParcel is the one the surviving panel buttons read.
		for (String field : Arrays.asList("currentParcel ="))
		{
			assertTrue(field + " should be refreshed on the game tick",
				tick.contains(field));
		}
	}
}
