/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import net.runelite.client.ui.PluginPanel;

/**
 * A FlowLayout that reports the height it will actually use.
 *
 * FlowLayout wraps its children onto as many rows as the width forces, but
 * preferredLayoutSize reports a single row however many it holds. In a
 * BorderLayout's NORTH, which grants exactly the preferred height, every row
 * after the first is laid out into space that does not exist and is clipped
 * away silently.
 *
 * The wrap depends on the width the container is given, so that is what this
 * measures against. Before the first layout that width is 0; an unmeasured
 * container is assumed to be panel width, since guessing narrow costs a little
 * blank space while guessing wide costs a whole row.
 */
class WrapLayout extends FlowLayout
{
	WrapLayout(int align, int hgap, int vgap)
	{
		super(align, hgap, vgap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target)
	{
		return layoutSize(target, true);
	}

	@Override
	public Dimension minimumLayoutSize(Container target)
	{
		return layoutSize(target, false);
	}

	private Dimension layoutSize(Container target, boolean preferred)
	{
		synchronized (target.getTreeLock())
		{
			int available = target.getWidth();
			if (available <= 0)
			{
				available = PluginPanel.PANEL_WIDTH;
			}
			Insets in = target.getInsets();
			int usable = Math.max(1,
				available - in.left - in.right - getHgap() * 2);

			int width = 0, height = 0, rowWidth = 0, rowHeight = 0;
			for (int i = 0; i < target.getComponentCount(); i++)
			{
				Component c = target.getComponent(i);
				if (!c.isVisible())
				{
					continue;
				}
				Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
				if (rowWidth > 0 && rowWidth + getHgap() + d.width > usable)
				{
					width = Math.max(width, rowWidth);
					height += rowHeight + getVgap();
					rowWidth = 0;
					rowHeight = 0;
				}
				rowWidth += (rowWidth == 0 ? 0 : getHgap()) + d.width;
				rowHeight = Math.max(rowHeight, d.height);
			}
			width = Math.max(width, rowWidth);
			height += rowHeight;

			return new Dimension(width + in.left + in.right + getHgap() * 2,
				height + in.top + in.bottom + getVgap() * 2);
		}
	}
}
