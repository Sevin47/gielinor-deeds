/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Deed Log: how much of Gielinor you have seen, and how much you hold.
 *
 * Sorted by progress rather than alphabetically, so the kingdom you are working
 * through sits at the top and finished ones sink -- the list answers "what am I
 * doing" before it answers "what exists".
 */
class DeedLogPanel extends JPanel
{
	private static final NumberFormat FMT = NumberFormat.getIntegerInstance();
	/** Gold, so a held landmark reads as a trophy rather than another row. */
	private static final Color LANDMARK_OWNED = new Color(0xE8, 0xC0, 0x60);

	private final JLabel headline = new JLabel();
	private final JLabel kingdomsSeen = value();
	private final JLabel typesOwned = value();
	private final JProgressBar overall = new JProgressBar(0, 1000);
	private final JPanel kingdoms = new JPanel();
	private final JPanel tiers = new JPanel();
	private final JPanel landmarks = new JPanel();
	private final JLabel landmarksHeader = new JLabel();

	DeedLogPanel()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSummary());
		add(gap());
		add(buildSection("Kingdoms", kingdoms));
		add(gap());
		add(buildSection("District types", tiers));
		add(gap());
		add(buildLandmarks());
	}

	private JPanel buildSummary()
	{
		JPanel p = section("Gielinor");
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		headline.setFont(FontManager.getRunescapeBoldFont());
		headline.setForeground(Color.WHITE);

		overall.setStringPainted(true);
		overall.setPreferredSize(new Dimension(180, 16));
		overall.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		overall.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		overall.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel stats = new JPanel(new GridLayout(0, 2, 4, 2));
		stats.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		stats.add(key("Kingdoms"));
		stats.add(kingdomsSeen);
		stats.add(key("District types"));
		stats.add(typesOwned);

		body.add(headline);
		body.add(gap());
		body.add(overall);
		body.add(gap());
		body.add(stats);
		p.add(body, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildLandmarks()
	{
		JPanel p = section("Landmarks");
		landmarks.setLayout(new BoxLayout(landmarks, BoxLayout.Y_AXIS));
		landmarks.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		landmarksHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		landmarksHeader.setFont(FontManager.getRunescapeSmallFont());
		p.add(landmarksHeader, BorderLayout.NORTH);
		p.add(landmarks, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildSection(String title, JPanel host)
	{
		JPanel p = section(title);
		host.setLayout(new BoxLayout(host, BoxLayout.Y_AXIS));
		host.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(host, BorderLayout.CENTER);
		return p;
	}

	void update(DeedLog.Snapshot snap)
	{
		DeedLog.Progress all = snap.getOverall();
		headline.setText(FMT.format(all.getSurveyed()) + " / " + FMT.format(all.getTotal())
			+ " surveyed");
		overall.setValue((int) Math.round(all.surveyedFraction() * 1000));
		overall.setString(String.format("%.2f%% of Gielinor", all.surveyedFraction() * 100));

		long enteredTotal = Kingdom.values().length;
		kingdomsSeen.setText(snap.kingdomsEntered() + " / " + enteredTotal);
		long buyableTypes = 0;
		for (Tier t : Tier.values())
		{
			if (t.isLand())
			{
				buyableTypes++;
			}
		}
		typesOwned.setText(snap.tierTypesOwned() + " / " + buyableTypes);

		renderKingdoms(snap);
		renderTiers(snap);
		renderLandmarks(snap);
		revalidate();
		repaint();
	}

	private void renderKingdoms(DeedLog.Snapshot snap)
	{
		kingdoms.removeAll();
		List<Map.Entry<Kingdom, DeedLog.Progress>> rows =
			new ArrayList<>(snap.getByKingdom().entrySet());
		// Started-but-unfinished first, then untouched, then complete. A finished
		// kingdom is not a task any more, so it should stop taking up the top.
		rows.sort(Comparator
			.comparingInt((Map.Entry<Kingdom, DeedLog.Progress> e) ->
				e.getValue().isComplete() ? 2 : (e.getValue().getSurveyed() > 0 ? 0 : 1))
			.thenComparing(e -> -e.getValue().surveyedFraction()));

		for (Map.Entry<Kingdom, DeedLog.Progress> e : rows)
		{
			DeedLog.Progress pr = e.getValue();
			if (pr.getTotal() == 0)
			{
				continue;
			}
			kingdoms.add(progressRow(e.getKey().getDisplayName(),
				pr.getSurveyed(), pr.getOwned(), pr.getTotal(),
				ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

	private void renderTiers(DeedLog.Snapshot snap)
	{
		tiers.removeAll();
		for (Tier t : Tier.values())
		{
			if (!t.isLand())
			{
				continue;
			}
			DeedLog.Progress pr = snap.tier(t);
			tiers.add(progressRow(t.getDisplayName(), pr.getSurveyed(), pr.getOwned(),
				pr.getTotal(), t.color()));
		}
	}

	/**
	 * Every landmark, listed from the start and filled in as you find them --
	 * a collection log, not a discovery list. The location is never shown:
	 * you already know where Camelot is, and that is much better flavour than
	 * a coordinate.
	 */
	private void renderLandmarks(DeedLog.Snapshot snap)
	{
		landmarks.removeAll();
		int found = snap.getLandmarksFound().size();
		int owned = snap.getLandmarksOwned().size();
		int total = Landmark.values().length;
		landmarksHeader.setText(owned + " owned, " + found + " found, of " + total);

		for (Landmark lm : Landmark.values())
		{
			boolean isOwned = snap.getLandmarksOwned().contains(lm);
			boolean isFound = snap.getLandmarksFound().contains(lm);

			JLabel row = new JLabel(lm.getDisplayName());
			row.setFont(FontManager.getRunescapeSmallFont());
			row.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
			if (isOwned)
			{
				row.setForeground(LANDMARK_OWNED);
				row.setToolTipText("Yours");
			}
			else if (isFound)
			{
				row.setForeground(Color.WHITE);
				row.setToolTipText("Surveyed -- " + Landmark.PRICE + " to buy");
			}
			else
			{
				// Dim, but still named. The slot is the goal.
				row.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				row.setToolTipText("Not yet found");
			}
			landmarks.add(row);
		}
	}

	/**
	 * One row: the name, a bar, and surveyed against total.
	 *
	 * Every row asks the same question and the number agrees with the bar
	 * beside it. Whether you own anything here is shown by the name turning
	 * gold, which costs no width in a 225px panel, and the tooltip carries all
	 * three figures for anyone who wants them.
	 */
	private JPanel progressRow(String name, int surveyed, int owned, int total, Color tint)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

		JLabel label = new JLabel(name);
		label.setForeground(surveyed >= total && total > 0
			? ColorScheme.PROGRESS_COMPLETE_COLOR
			: owned > 0 ? LANDMARK_OWNED : tint);
		label.setFont(FontManager.getRunescapeSmallFont());

		// Always surveyed against total, matching the bar beside it. Whether
		// you own anything here shows as the row turning gold, and the tooltip
		// carries all three figures.
		JLabel count = new JLabel(FMT.format(surveyed) + " / " + FMT.format(total));
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setToolTipText(FMT.format(owned) + " owned, " + FMT.format(surveyed)
			+ " surveyed, of " + FMT.format(total) + " claimable");

		JProgressBar bar = new JProgressBar(0, Math.max(1, total));
		bar.setValue(surveyed);
		bar.setPreferredSize(new Dimension(60, 6));
		bar.setMaximumSize(new Dimension(60, 6));
		bar.setForeground(tint);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setToolTipText(count.getToolTipText());

		JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		right.add(bar, BorderLayout.WEST);
		right.add(count, BorderLayout.EAST);

		row.add(label, BorderLayout.WEST);
		row.add(right, BorderLayout.EAST);
		return row;
	}

	private static JPanel section(String title)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 8, 6)));
		JLabel header = new JLabel(title);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		p.add(header, BorderLayout.NORTH);
		return p;
	}

	private static JLabel value()
	{
		JLabel l = new JLabel("--");
		l.setForeground(Color.WHITE);
		l.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD));
		return l;
	}

	private static JLabel key(String s)
	{
		JLabel l = new JLabel(s);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(FontManager.getRunescapeSmallFont());
		return l;
	}

	private static JPanel gap()
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setPreferredSize(new Dimension(1, 6));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		return p;
	}
}
