/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every tab has to be on screen.
 *
 * The side panel grew from two tabs to four and the fourth one disappeared. Not
 * clipped, not truncated -- absent, with the three that fitted looking entirely
 * healthy beside it, which is why it read as "Report is not showing" rather
 * than as a layout problem. It is three tabs again now, which fit on one row --
 * but the layout that would hide the next one is still the shipped default, so
 * this keeps watching.
 *
 * RuneLite's MaterialTabGroup is a FlowLayout. A FlowLayout wraps its children
 * across as many rows as the width forces, but reports the preferred size of
 * ONE row however many it has, and the tab group sits in a BorderLayout's
 * NORTH, which hands a component exactly its preferred height. So the second
 * row was laid out into no height and clipped away.
 *
 * This lays the row out at the width it really gets and checks every tab ends
 * up inside it. That catches the same failure whatever causes it next time --
 * a fifth tab, a longer name, a bigger font.
 */
public class PanelTabsTest
{
	/** The tabs the panel builds, in order. */
	private static final String[] TABS = {"Estate", "Deeds", "Report"};

	/**
	 * The width the tab row actually gets: panel width, less the 8px border the
	 * panel puts on each side. Erring narrow is the safe direction -- it is the
	 * side that would report a failure rather than hide one.
	 */
	private static final int AVAILABLE = PluginPanel.PANEL_WIDTH - 16;

	private static MaterialTabGroup group(boolean wrapped)
	{
		MaterialTabGroup g = new MaterialTabGroup(new JPanel());
		if (wrapped)
		{
			g.setLayout(new WrapLayout(FlowLayout.CENTER, 8, 0));
		}
		for (String t : TABS)
		{
			g.addTab(new MaterialTab(t, g, new JPanel()));
		}
		return g;
	}

	/** Lay the group out at panel width and return it. */
	private static MaterialTabGroup laidOut(boolean wrapped)
	{
		MaterialTabGroup g = group(wrapped);
		Dimension pref = g.getPreferredSize();
		g.setBounds(0, 0, AVAILABLE, pref.height);
		g.doLayout();
		return g;
	}

	@Test
	public void everyTabFitsInsideTheRow()
	{
		MaterialTabGroup g = laidOut(true);
		Rectangle box = new Rectangle(0, 0, g.getWidth(), g.getHeight());

		for (int i = 0; i < g.getComponentCount(); i++)
		{
			Component c = g.getComponent(i);
			Rectangle at = c.getBounds();
			assertTrue("tab '" + TABS[i] + "' is laid out at " + at
					+ ", outside the " + box.width + "x" + box.height
					+ " the row was given -- it will not be drawn",
				box.contains(at));
			assertTrue("tab '" + TABS[i] + "' has no size", at.width > 0 && at.height > 0);
		}
	}

	/**
	 * The row is honest about needing more than one line.
	 *
	 * Not an assertion that it is exactly two: if the tabs are renamed short
	 * enough to fit on one, that is a fine outcome and this should not fail.
	 * What must hold is that the reported height covers whatever it lays out,
	 * which is the thing plain FlowLayout gets wrong.
	 */
	@Test
	public void theRowAsksForTheHeightItActuallyUses()
	{
		MaterialTabGroup g = laidOut(true);
		int lowest = 0;
		for (int i = 0; i < g.getComponentCount(); i++)
		{
			Rectangle at = g.getComponent(i).getBounds();
			lowest = Math.max(lowest, at.y + at.height);
		}
		assertTrue("the row reports " + g.getHeight() + "px but lays tabs out to "
			+ lowest + "px", g.getHeight() >= lowest);
	}

	/**
	 * The tabs the panel actually has fit on a single row.
	 *
	 * This is the property that was wanted all along -- a wrapped tab row is
	 * legible but ugly, and the fix for four tabs was to stop having four.
	 */
	@Test
	public void theTabsFitOnOneRow()
	{
		MaterialTabGroup g = laidOut(true);
		int y = g.getComponent(0).getBounds().y;
		for (int i = 1; i < g.getComponentCount(); i++)
		{
			assertEquals("tab '" + TABS[i] + "' wrapped onto another row -- "
					+ "the row is only " + AVAILABLE + "px wide",
				y, g.getComponent(i).getBounds().y);
		}
	}

	/**
	 * One more tab would not fit, and WrapLayout is what makes that visible.
	 *
	 * Without this, everything above would pass just as happily under the
	 * shipped FlowLayout, and the next person to add a tab would rediscover a
	 * silently missing one. This asserts the trap is still there and still
	 * covered: a fourth tab overflows, and the layout in use reports the height
	 * to show it rather than clipping it away.
	 */
	@Test
	public void afourthTabWouldOverflowButStillBeDrawn()
	{
		MaterialTabGroup plain = withExtra(false);
		Rectangle plainBox = new Rectangle(0, 0, plain.getWidth(), plain.getHeight());
		boolean lost = false;
		for (int i = 0; i < plain.getComponentCount(); i++)
		{
			lost |= !plainBox.contains(plain.getComponent(i).getBounds());
		}
		assertTrue("a fourth tab now fits at " + AVAILABLE + "px, so the panel "
			+ "could carry one again -- check that before deleting this", lost);

		MaterialTabGroup wrapped = withExtra(true);
		Rectangle box = new Rectangle(0, 0, wrapped.getWidth(), wrapped.getHeight());
		for (int i = 0; i < wrapped.getComponentCount(); i++)
		{
			assertTrue("WrapLayout must keep an overflowing tab on screen",
				box.contains(wrapped.getComponent(i).getBounds()));
		}
	}

	/** The real tabs plus one more, to prove the overflow case is still live. */
	private static MaterialTabGroup withExtra(boolean wrapped)
	{
		MaterialTabGroup g = new MaterialTabGroup(new JPanel());
		if (wrapped)
		{
			g.setLayout(new WrapLayout(FlowLayout.CENTER, 8, 0));
		}
		for (String t : TABS)
		{
			g.addTab(new MaterialTab(t, g, new JPanel()));
		}
		g.addTab(new MaterialTab("Settings", g, new JPanel()));
		Dimension pref = g.getPreferredSize();
		g.setBounds(0, 0, AVAILABLE, pref.height);
		g.doLayout();
		return g;
	}
}
