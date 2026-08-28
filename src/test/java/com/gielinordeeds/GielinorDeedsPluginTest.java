/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev launcher: starts a real RuneLite with this plugin loaded from the
 * classpath, so the overlays and side panel can be exercised against a live
 * game session.
 *
 *     gradle run
 *
 * This lives under src/test so it never ships in the plugin jar -- the Plugin
 * Hub loads the plugin through its own manifest, and a main() that boots a
 * second client from inside a running client would be at best confusing.
 */
public class GielinorDeedsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GielinorDeedsPlugin.class);
		RuneLite.main(args);
	}
}
