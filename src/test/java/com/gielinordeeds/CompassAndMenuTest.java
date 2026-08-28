/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Two bugs that both came from assuming a convention instead of checking it.
 *
 * The compass surveyed the opposite deed from the one named, because the parcel
 * grid counts py downward and the buttons were laid out as if it counted up.
 *
 * The menu filter removed every right-click option in the game, because
 * GAME_OBJECT_FIRST_OPTION and GAME_OBJECT_FIFTH_OPTION are 3 and 1001 rather
 * than consecutive, so a range test between them covered everything. The
 * filter no longer reads coordinates out of a menu entry at all -- it asks the
 * client which tile the cursor is on -- but it still has to know which actions
 * are scene-anchored, so that list stays pinned.
 *
 * Neither was visible from reading the code. Both are cheap to pin.
 */
public class CompassAndMenuTest
{
	private static ParcelGrid grid;

	@BeforeClass
	public static void load() throws Exception
	{
		grid = ParcelGrid.load();
		assertNotNull("parcels.bin should be packaged", grid);
	}

	/**
	 * The convention the compass has to respect: py counts down from the top of
	 * the map, so a larger py is further south.
	 */
	@Test
	public void parcelYCountsSouthward()
	{
		WorldPoint north = new WorldPoint(3222, 3300, 0);
		WorldPoint south = new WorldPoint(3222, 3200, 0);
		assertTrue("further north must have the smaller py",
			grid.pyOf(north) < grid.pyOf(south));

		// and px counts the ordinary way, east-positive
		assertTrue(grid.pxOf(new WorldPoint(3100, 3220, 0))
			< grid.pxOf(new WorldPoint(3300, 3220, 0)));
	}

	/**
	 * Walking the grid by the compass's own deltas must land where the label
	 * says. This is the test that would have caught the reversal.
	 */
	@Test
	public void compassDeltasMoveTheWayTheyAreLabelled()
	{
		Parcel start = grid.at(new WorldPoint(3222, 3218, 0));
		assertNotNull(start);
		int y0 = start.getSouthWest().getY();
		int x0 = start.getSouthWest().getX();

		// north is dy -1 in parcel space
		Parcel n = grid.at(start.getPx(), start.getPy() - 1);
		assertNotNull(n);
		assertTrue("N must increase world y", n.getSouthWest().getY() > y0);

		Parcel s = grid.at(start.getPx(), start.getPy() + 1);
		assertNotNull(s);
		assertTrue("S must decrease world y", s.getSouthWest().getY() < y0);

		Parcel e = grid.at(start.getPx() + 1, start.getPy());
		assertNotNull(e);
		assertTrue("E must increase world x", e.getSouthWest().getX() > x0);

		Parcel w = grid.at(start.getPx() - 1, start.getPy());
		assertNotNull(w);
		assertTrue("W must decrease world x", w.getSouthWest().getX() < x0);
	}

	/**
	 * The assumption that broke the menu. These five are not consecutive, so
	 * nothing may range-check between them.
	 */
	@Test
	public void gameObjectOptionsAreNotConsecutiveIds()
	{
		int first = MenuAction.GAME_OBJECT_FIRST_OPTION.getId();
		int fifth = MenuAction.GAME_OBJECT_FIFTH_OPTION.getId();
		assertTrue("if these ever became consecutive this test can go",
			fifth - first > 5);

		// everything a range between them would wrongly capture
		for (MenuAction m : new MenuAction[]{MenuAction.NPC_FIRST_OPTION,
			MenuAction.ITEM_FIRST_OPTION, MenuAction.WIDGET_FIRST_OPTION,
			MenuAction.CC_OP, MenuAction.PLAYER_FIRST_OPTION})
		{
			assertTrue(m + " sits inside the bad range", m.getId() > first && m.getId() < fifth);
		}
	}

	/**
	 * Only scene-anchored actions may be judged by position. Inventory and
	 * interface entries carry widget and item ids in the same fields, and
	 * reading those as coordinates is what emptied the menu.
	 */
	@Test
	public void onlySceneActionsAreTreatedAsPositions()
	{
		for (MenuAction m : new MenuAction[]{MenuAction.WALK,
			MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION,
			MenuAction.GROUND_ITEM_THIRD_OPTION})
		{
			assertTrue(m + " is scene-anchored", GielinorDeedsPlugin.isSceneActionForTest(m));
		}
		for (MenuAction m : new MenuAction[]{MenuAction.NPC_FIRST_OPTION,
			MenuAction.ITEM_FIRST_OPTION, MenuAction.WIDGET_FIRST_OPTION,
			MenuAction.CC_OP, MenuAction.PLAYER_FIRST_OPTION,
			MenuAction.RUNELITE, MenuAction.WIDGET_CONTINUE})
		{
			assertFalse(m + " must never be judged by position",
				GielinorDeedsPlugin.isSceneActionForTest(m));
		}
	}

}
