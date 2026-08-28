/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.api.hooks.DrawCallbacks;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * VeilRenderer wraps whatever draw callbacks are installed -- in practice
 * GpuPlugin -- and forwards every call. A method it fails to override is a
 * method the GPU plugin never receives, and most of them are default methods,
 * so the compiler will not say a word: rendering just breaks for everyone.
 *
 * The interface gains methods as RuneLite evolves, so this compares the two
 * directly rather than pinning a number.
 */
public class VeilRendererTest
{
	private static Set<String> signatures(Class<?> c, boolean declaredOnly)
	{
		return Arrays.stream(declaredOnly ? c.getDeclaredMethods() : c.getMethods())
			.filter(m -> !m.isSynthetic())
			.filter(m -> !m.getName().startsWith("lambda$"))
			.map(m -> m.getName() + "(" + Arrays.stream(m.getParameterTypes())
				.map(Class::getSimpleName).collect(Collectors.joining(",")) + ")")
			.collect(Collectors.toCollection(TreeSet::new));
	}

	@Test
	public void everyDrawCallbackIsForwarded()
	{
		Set<String> iface = new TreeSet<>();
		for (Method m : DrawCallbacks.class.getMethods())
		{
			if (m.isSynthetic() || java.lang.reflect.Modifier.isStatic(m.getModifiers()))
			{
				continue;
			}
			iface.add(m.getName() + "(" + Arrays.stream(m.getParameterTypes())
				.map(Class::getSimpleName).collect(Collectors.joining(",")) + ")");
		}
		Set<String> ours = signatures(VeilRenderer.class, true);

		Set<String> missing = new TreeSet<>(iface);
		missing.removeAll(ours);
		assertTrue("VeilRenderer must override every DrawCallbacks method or the "
			+ "GPU plugin stops receiving it. Missing: " + missing, missing.isEmpty());
		assertTrue("expected a real interface, got " + iface.size() + " methods",
			iface.size() >= 20);
	}
	/**
	 * Every hook that puts a model on the map has to ask whether it should.
	 *
	 * There are four of them -- the software draw, two drawDynamic overloads
	 * and drawTemp -- and for a long time only two were filtered. The client
	 * picks between the overloads, so "Hide the ground" came out half hidden:
	 * trees and walls went through the filtered path and vanished, while NPCs
	 * and some objects went through the unfiltered one and carried on walking
	 * about on ground that was not being drawn.
	 *
	 * The forwarding test above cannot catch that. A hook that forwards
	 * unconditionally is a correctly implemented method; it is just the wrong
	 * one. So this reads the bodies and insists each of them consults
	 * renderableVisible, which is also why that check lives in one method
	 * rather than being written out four times.
	 */
	@Test
	public void everyModelHookChecksVisibility() throws Exception
	{
		String src = new String(Files.readAllBytes(Paths.get("src", "main", "java",
			"com", "gielinordeeds", "VeilRenderer.java")), StandardCharsets.UTF_8);

		int checked = 0;
		int at = 0;
		while (true)
		{
			at = src.indexOf("	public void draw", at + 1);
			if (at < 0)
			{
				break;
			}
			int open = src.indexOf('{', at);
			String signature = src.substring(at, open);
			// The hooks handed a model and a position in LOCAL coordinates: the
			// two draws, both drawDynamics and drawTemp. Deliberately not
			// drawScenePaint or drawSceneTileModel, which are handed tile
			// coordinates outright and so ask tileVisible directly, nor
			// draw(int), which paints an overlay colour and has no position.
			if (!signature.contains("Renderable") && !signature.contains("GameObject"))
			{
				continue;
			}
			assertTrue(signature.trim() + " forwards without asking whether the "
					+ "ground under it is hidden -- route it through "
					+ "renderableVisible",
				bodyFrom(src, open).contains("renderableVisible("));
			checked++;
		}
		assertTrue("expected the four model hooks, found " + checked, checked >= 4);
	}

	/** Crude brace matching from an opening brace. */
	private static String bodyFrom(String src, int open)
	{
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
		return "";
	}
	/**
	 * The tile cull is the one that has to be wired up.
	 *
	 * Filtering the individual model hooks only ever hides the models that come
	 * through the hooks you thought of. tileInFrustum is asked before the
	 * client walks a tile at all, so a false there takes the ground, the
	 * objects, the items and the actors together -- and it defaults to true, so
	 * forwarding it blind silently disables the whole technique while every
	 * other test still passes.
	 */
	@Test
	public void hiddenTilesAreCulledBeforeTheirContentsAreDrawn() throws Exception
	{
		String src = new String(Files.readAllBytes(Paths.get("src", "main", "java",
			"com", "gielinordeeds", "VeilRenderer.java")), StandardCharsets.UTF_8);
		int at = src.indexOf("public boolean tileInFrustum(");
		assertTrue("tileInFrustum must be overridden", at >= 0);
		String body = bodyFrom(src, src.indexOf('{', src.indexOf(')', at)));

		assertTrue("tileInFrustum must consult the survey", body.contains("Visible("));
		assertTrue("and must be able to answer no", body.contains("return false;"));
	}
}
