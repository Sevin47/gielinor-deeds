/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * What a player may change.
 *
 * Deed Locked comes first and is on, because it is what the plugin is. Playing
 * with it off is the secondary shape: owning land for its own sake, with the
 * map already visible.
 *
 * Deliberately none of the balance. The economy's tuning lives in
 * {@link Balance}, where changing one is a decision about the game rather than
 * a slider in a menu.
 */
@ConfigGroup(GielinorDeedsConfig.GROUP)
public interface GielinorDeedsConfig extends Config
{
	String GROUP = "gielinordeeds";

	@ConfigSection(
		name = "Surveying",
		description = "How the Survey deed option behaves",
		position = 2
	)
	String surveySection = "surveying";

	/*
	 * There are two ways to survey and they are both on by default: the deed
	 * you are standing on, from the panel, and the deed under your cursor, from
	 * the right-click menu. This turns the second one off.
	 *
	 * Survey deed is never the left-click action, since a charge is too easy
	 * to spend by accident on ground you meant to walk across. The only
	 * question is whether the option appears at all.
	 *
	 * Not a Deed Locked setting: the option is offered in ordinary play too.
	 */
	@ConfigItem(
		keyName = "surveyMenuOption",
		name = "Survey from the right-click menu",
		description = "Add Survey deed to the right-click menu on ground you "
			+ "can survey. Turn off to survey only from the side panel, on the "
			+ "deed you are standing on.",
		section = surveySection,
		position = 41
	)
	default boolean surveyMenuOption()
	{
		return true;
	}

	@ConfigSection(
		name = "Deed Locked",
		description = "The run: unsurveyed Gielinor is blacked out and you spend "
			+ "what you earn opening more of it.",
		position = 1
	)
	String lockSection = "deedlock";

	@ConfigItem(
		keyName = "deedLocked",
		name = "Deed Locked run",
		description = "Black out unsurveyed ground, grant the deed you are "
			+ "standing on, and only allow surveying deeds that touch land you "
			+ "own. Nothing here blocks movement -- the run is on your honour, "
			+ "the same way Region Locker works.<br><br>"
			+ "This is what the plugin is for. Off means owning land without "
			+ "the challenge: the whole map stays visible, you survey wherever "
			+ "you stand rather than outward from your estate, and survey range "
			+ "upgrades become worth buying.",
		section = lockSection,
		position = 1
	)
	default boolean deedLocked()
	{
		return false;
	}

	@ConfigItem(
		keyName = "veilUnsurveyed",
		name = "Black out unsurveyed ground",
		description = "Draw a veil over deeds you have not surveyed.",
		section = lockSection,
		position = 2
	)
	default boolean veilUnsurveyed()
	{
		return true;
	}

	@ConfigItem(
		keyName = "veilStyle",
		name = "How to mark it",
		description = "Tint washes unsurveyed land with colour and cannot be "
			+ "seen around at any zoom. Black out paints over it. Hide the "
			+ "ground stops it being drawn at all, which needs a GPU plugin.",
		section = lockSection,
		position = 3
	)
	default VeilStyle veilStyle()
	{
		return VeilStyle.TINT;
	}

	@Alpha
	@ConfigItem(
		keyName = "veilColor",
		name = "Colour",
		description = "The wash over unsurveyed land. Its alpha sets the "
			+ "strength, so a low alpha leaves the ground readable underneath.",
		section = lockSection,
		position = 4
	)
	default Color veilColor()
	{
		return new Color(24, 26, 32, 150);
	}

	@ConfigItem(
		keyName = "hideOffEstateMenus",
		name = "Lock me to my land",
		description = "Refuse clicks aimed off your estate -- walking there, "
			+ "objects on it, NPCs standing on it -- and say so in chat. The "
			+ "menu is left exactly as the game wrote it; the action simply "
			+ "does not happen. Other players are never affected, and "
			+ "inventory, interfaces and the bank are never touched. This does "
			+ "stop you walking out, which is further than Region Locker goes. "
			+ "Off by default.",
		section = lockSection,
		position = 7
	)
	default boolean hideOffEstateMenus()
	{
		return false;
	}

	@ConfigItem(
		keyName = "warnTrespass",
		name = "Shout when off your land",
		description = "Put GET BACK ON YOUR PROPERTY!! over your head while you "
			+ "are standing on ground you have not surveyed. Client-side only -- "
			+ "nobody else sees it and nothing is sent to the server.",
		section = lockSection,
		position = 9
	)
	default boolean warnTrespass()
	{
		return true;
	}

	@ConfigSection(
		name = "Display",
		description = "What to draw",
		position = 3
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showParcelBorder",
		name = "Outline current parcel",
		description = "Draw the border of the parcel you are standing in",
		section = displaySection,
		position = 11
	)
	default boolean showParcelBorder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOwnedTint",
		name = "Tint owned parcels",
		description = "Shade parcels you own in their district colour",
		section = displaySection,
		position = 12
	)
	default boolean showOwnedTint()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSurveyedOnMap",
		name = "Show surveyed land on the world map",
		description = "Faintly mark every parcel you have surveyed, so the map "
			+ "fills in as you explore. Turn off to show only what you own.",
		section = displaySection,
		position = 14
	)
	default boolean showSurveyedOnMap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWorldMap",
		name = "Show deeds on the world map",
		description = "Draw your claimed parcels on the world map",
		section = displaySection,
		position = 13
	)
	default boolean showWorldMap()
	{
		return true;
	}
}
