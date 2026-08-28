/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel, in two tabs.
 *
 * <b>Estate</b> is what you are doing now, and has to stay short enough that
 * the Claim button is always on screen. <b>Deeds</b> is the map: what you hold,
 * grouped by kingdom and collapsed by default, over what is left to find.
 *
 * The panel is 225px wide and a tab costs about 46px of padding before its
 * text, so it holds three at the outside. See {@link WrapLayout}, which is what
 * makes an overflowing tab visible rather than silently absent.
 */
public class GielinorDeedsPanel extends PluginPanel
{
	private static final NumberFormat FMT = NumberFormat.getIntegerInstance();
	/** Gold, matching the Deed Log's landmark rows. */
	private static final Color LANDMARK = new Color(0xE8, 0xC0, 0x60);
	private static final Color DANGER = new Color(0xE0, 0x6C, 0x6C);

	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;

	private final JLabel balance = valueLabel();
	private final JLabel income = valueLabel();
	private final JLabel worth = valueLabel();
	private final JLabel chargesLabel = valueLabel();
	private final JLabel nextCharge = new JLabel();
	private final JLabel rangeName = new JLabel();
	private final JLabel rangeDetail = new JLabel();
	private final JButton upgrade = new JButton();
	/** The whole Survey range block, so a locked run can drop it entirely. */
	private JPanel rangeSection;
	private final JLabel priceMult = valueLabel();
	private final JLabel explored = valueLabel();

	private final JLabel parcelName = new JLabel();
	private final JLabel parcelTier = new JLabel();
	private final JLabel parcelPrice = new JLabel();
	private final JProgressBar survey = new JProgressBar(0, 100);
	private final JButton claim = new JButton("Claim");
	private final JLabel notice = new JLabel();

	private final DeedLogPanel logPanel = new DeedLogPanel();
	private final JPanel holdings = new JPanel();
	private String lastHoldings;
	private final JLabel holdingsHeader = new JLabel();
	/**
	 * Which kingdom groups are open.
	 *
	 * Kept out here rather than read off the components, so that rebuilding the
	 * list when ownership changes does not silently collapse everything the
	 * player had opened. Empty to begin with: the whole reason for grouping is
	 * that a large estate should not open as hundreds of rows.
	 */
	private final EnumSet<Kingdom> expanded = EnumSet.noneOf(Kingdom.class);

	@Inject
	private GielinorDeedsPanel(GielinorDeedsPlugin plugin, GielinorDeedsConfig config)
	{
		// wrap=true: the Deed Log lists 66 landmarks, so the panel has to scroll.
		super(true);
		this.plugin = plugin;
		this.config = config;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel estateTab = column();
		estateTab.add(buildEstate());
		estateTab.add(spacer());
		estateTab.add(buildCurrent());
		estateTab.add(spacer());
		// Hidden outright during a run rather than shown saying it does nothing
		// -- see renderRange. A locked run opens one deed per charge whatever
		// instrument you hold, so the section is not a choice being declined,
		// it is a feature that is not part of this game.
		estateTab.add(rangeSection = buildUpgrades());

		// Holdings above the Log: what you own is the thing you came looking
		// for, and the progress bars are the backdrop it sits against.
		JPanel deedsTab = column();
		deedsTab.add(buildHoldings());
		deedsTab.add(spacer());
		deedsTab.add(logPanel);

		// Estate has to stay short enough that the Claim button is always on
		// screen, which is why the holdings list lives on its own tab.
		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		// The shipped FlowLayout reports one row's height however many rows it
		// lays out, so an overflowing tab is absent rather than squashed.
		// WrapLayout is what keeps the next tab added visible.
		tabs.setLayout(new WrapLayout(FlowLayout.CENTER, 8, 0));
		MaterialTab estate = new MaterialTab("Estate", tabs, topped(estateTab));
		MaterialTab deeds = new MaterialTab("Deeds", tabs, topped(deedsTab));
		tabs.addTab(estate);
		tabs.addTab(deeds);
		tabs.select(estate);

		add(tabs, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		update();
	}

	/**
	 * Pin a tab's column to the top of the tab.
	 *
	 * A BoxLayout hands leftover vertical space to whichever children will
	 * accept it, and a section panel accepts all of it -- so a short tab came
	 * out as a few sections stretched down the whole panel with their text
	 * floating in the middle of each. This mattered less when the Estate tab
	 * was six sections deep and always overflowed; now that each tab holds one
	 * job, most of them do not fill the panel. NORTH gives every section its
	 * preferred height and leaves the slack at the bottom where it belongs.
	 */
	private static JPanel topped(JPanel column)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.add(column, BorderLayout.NORTH);
		return p;
	}

	/** A tab body: a vertical stack on the panel background. */
	private static JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	private JPanel buildEstate()
	{
		JPanel p = section("Estate");
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.add(keyLabel("Balance"));
		grid.add(balance);
		JLabel incomeKey = keyLabel("Income");
		incomeKey.setToolTipText("Rent while you play. Time logged out pays "
			+ Math.round(Balance.OFFLINE_RENT_RATE * 100) + "% of this, up to "
			+ Balance.OFFLINE_RENT_HOURS + " hours' worth.");
		grid.add(incomeKey);
		grid.add(income);
		grid.add(keyLabel("Land value"));
		grid.add(worth);
		grid.add(keyLabel("Charges"));
		grid.add(chargesLabel);
		grid.add(keyLabel("Surveyed"));
		grid.add(explored);
		// Progressive pricing: how many parcels you already own, not anything
		// to do with the survey instrument. See Balance.PRICE_EXPONENT.
		grid.add(keyLabel("Land prices"));
		grid.add(priceMult);
		nextCharge.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nextCharge.setFont(FontManager.getRunescapeSmallFont());

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.add(grid);
		body.add(nextCharge);
		p.add(body, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildCurrent()
	{
		JPanel p = section("Standing on");
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		parcelName.setFont(FontManager.getRunescapeBoldFont());
		parcelName.setForeground(Color.WHITE);
		parcelTier.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		parcelPrice.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		survey.setStringPainted(true);
		survey.setPreferredSize(new Dimension(PANEL_WIDTH - 40, 16));
		survey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		survey.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		survey.setBackground(ColorScheme.DARK_GRAY_COLOR);

		claim.setFocusPainted(false);
		// One button, two jobs: it starts the survey, then becomes the buy
		// button once the survey finishes. Two separate buttons would leave one
		// of them dead at all times.
		claim.addActionListener(e ->
		{
			if (plugin.isCurrentKnown())
			{
				plugin.claimCurrent();
			}
			else
			{
				plugin.surveyCurrent();
			}
		});

		notice.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		notice.setFont(FontManager.getRunescapeSmallFont());

		body.add(parcelName);
		body.add(parcelTier);
		body.add(parcelPrice);
		body.add(spacer());
		body.add(survey);
		body.add(spacer());
		body.add(claim);
		body.add(notice);
		p.add(body, BorderLayout.CENTER);
		return p;
	}

 	private JPanel buildUpgrades()
	{
		JPanel p = section("Survey range");
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		rangeName.setFont(FontManager.getRunescapeBoldFont());
		rangeName.setForeground(Color.WHITE);
		rangeDetail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rangeDetail.setFont(FontManager.getRunescapeSmallFont());
		upgrade.setFocusPainted(false);
		upgrade.addActionListener(e -> plugin.buyRangeUpgrade());

		body.add(rangeName);
		body.add(rangeDetail);
		body.add(spacer());
		body.add(upgrade);
		p.add(body, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildHoldings()
	{
		JPanel p = section("Holdings");
		holdings.setLayout(new BoxLayout(holdings, BoxLayout.Y_AXIS));
		holdings.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		holdingsHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		holdingsHeader.setFont(FontManager.getRunescapeSmallFont());

		JLabel reset = new JLabel("new estate");
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		reset.setToolTipText("Wipe this character's land, money and surveys");
		reset.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				reset.setForeground(DANGER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				reset.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				confirmReset();
			}
		});

		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		head.add(holdingsHeader, BorderLayout.WEST);
		head.add(reset, BorderLayout.EAST);

		p.add(head, BorderLayout.NORTH);
		p.add(holdings, BorderLayout.CENTER);
		return p;
	}

	/** Repaint from current state. Safe to call from any thread. */
	public void update()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::update);
			return;
		}

		Estate e = plugin.getEstate();
		ParcelGrid grid = plugin.getGrid();

		balance.setText(FMT.format(e.getBalance()));
		income.setText(String.format("%.2f/s", e.rps(grid)));
		worth.setText(FMT.format(e.portfolioValue()));
		explored.setText(FMT.format(e.surveyedCount()));

		priceMult.setText(String.format("%.1fx base", plugin.priceMultiplier()));

		SurveyCharges ch = e.getCharges();
		chargesLabel.setText(FMT.format(ch.getCharges()));
		int perHour = Balance.CHARGES_PER_HOUR;
		int allowance = ch.allowanceRemaining(System.currentTimeMillis(), perHour);
		long need = Math.max(0, plugin.xpCostNow() - ch.getXpProgress());
		// Two different reasons the next charge might be far away, and the player
		// can act on each: earn more XP, or wait for the hourly cap to refill.
		nextCharge.setText(allowance > 0
			? "<html>" + FMT.format(need) + " XP to next &middot; "
				+ allowance + "/" + perHour + " hourly left</html>"
			: "<html>hourly cap reached &middot; level-ups still pay</html>");

		List<Parcel> owned = new ArrayList<>();
		if (grid != null)
		{
			for (String pid : e.getOwned().keySet())
			{
				Parcel p = grid.byPid(pid);
				if (p != null)
				{
					owned.add(p);
				}
			}
		}

		renderRange(e);
		renderCurrent(e);
		renderHoldings(owned);
		DeedLog deedLog = plugin.getDeedLog();
		if (deedLog != null)
		{
			logPanel.update(deedLog.snapshot(e));
		}
	}

	/**
	 * What the instrument actually buys, and when it buys nothing.
	 *
	 * The old version printed "N parcels per charge -- land costs 1.4x base" in
	 * one line, which read as though the theodolite was what made land dearer.
	 * The multiplier is progressive pricing and has nothing to do with range;
	 * it lives with the estate figures now.
	 *
	 * More to the point, this section sold an upgrade that does nothing during
	 * a Deed Locked run, because a locked run opens exactly the deed it paid
	 * for whatever you are holding. Now it says so instead of taking the money.
	 */
	private void renderRange(Estate e)
	{
		if (config.deedLocked())
		{
			// One charge opens one deed in a run, whatever instrument you hold,
			// so there is nothing here to read or buy.
			rangeSection.setVisible(false);
			return;
		}
		rangeSection.setVisible(true);

		SurveyRange cur = SurveyRange.forLevel(e.getSurveyRange());
		rangeName.setText(cur.getDisplayName());
		upgrade.setVisible(true);
		int side = cur.getRadius() * 2 + 1;
		rangeDetail.setText("<html>One charge surveys " + cur.coverage() + " deed"
			+ (cur.coverage() == 1 ? "" : "s")
			+ (cur.getRadius() == 0
				? " -- the one you are standing on."
				: ", a " + side + "x" + side + " block centred on you.")
			+ "</html>");

		SurveyRange next = SurveyRange.next(e.getSurveyRange());
		if (next == null)
		{
			upgrade.setEnabled(false);
			upgrade.setText("Fully upgraded");
			upgrade.setToolTipText(null);
			return;
		}
		boolean afford = e.getBalance() >= next.getCost();
		upgrade.setEnabled(afford);
		upgrade.setText(afford
			? next.getDisplayName() + " -- " + FMT.format(next.getCost())
			: "Need " + FMT.format(next.getCost() - e.getBalance()) + " more");
		upgrade.setToolTipText(next.getDisplayName() + ": one charge would cover "
			+ next.coverage() + " deeds instead of " + cur.coverage());
	}

	private void renderCurrent(Estate e)
	{
		Parcel p = plugin.getCurrentParcel();
		notice.setText(plugin.getNotice() == null ? " "
			: "<html>" + plugin.getNotice() + "</html>");

		if (p == null)
		{
			parcelName.setText("Not on surveyed ground");
			parcelTier.setText(" ");
			parcelPrice.setText(" ");
			survey.setValue(0);
			survey.setString("--");
			claim.setEnabled(false);
			claim.setText("Survey");
			return;
		}

		parcelName.setText("Parcel " + p.getPid());

		// Everything below this point is gated on knowledge. Until the parcel is
		// surveyed the panel must not show tier, colour or price -- that is the
		// information the survey exists to earn, and leaking it here would make
		// surveying a pure delay again.
		if (!plugin.isCurrentKnown())
		{
			parcelTier.setText("Unknown");
			parcelTier.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			int have = e.getCharges().getCharges();
			if (plugin.isSurveying())
			{
				// The work takes a few seconds, so the button says so rather
				// than sitting there inviting a second click that would only
				// be told there is already one running.
				parcelPrice.setText("1 charge to survey");
				survey.setValue(100);
				survey.setString(have + " charge" + (have == 1 ? "" : "s"));
				claim.setEnabled(false);
				claim.setText("Surveying...");
			}
			else if (have > 0)
			{
				parcelPrice.setText("1 charge to survey");
				survey.setValue(100);
				survey.setString(have + " charge" + (have == 1 ? "" : "s"));
				claim.setEnabled(true);
				claim.setText("Survey this parcel");
			}
			else
			{
				// The button is dark for a reason the player can act on: go play.
				parcelPrice.setText("survey to reveal");
				survey.setValue(0);
				survey.setString("no charges");
				claim.setEnabled(false);
				claim.setText("Earn charges by playing");
			}
			return;
		}

		parcelTier.setText(p.getTier().getDisplayName());
		parcelTier.setForeground(p.getTier().color());
		survey.setValue(100);

		if (e.owns(p.getPid()))
		{
			parcelPrice.setText(String.format("yours -- %.3f/s", p.rps()));
			survey.setString("owned");
			claim.setEnabled(false);
			claim.setText("Already yours");
		}
		else if (!p.isClaimable())
		{
			parcelPrice.setText("not claimable");
			survey.setString("surveyed");
			claim.setEnabled(false);
			claim.setText("Cannot be claimed");
		}
		else if (e.getBalance() < plugin.priceOf(p))
		{
			// The survey is done; the button is dark for a reason the player
			// can act on.
			parcelPrice.setText(FMT.format(plugin.priceOf(p)) + " to buy");
			survey.setString("surveyed");
			claim.setEnabled(false);
			claim.setText("Need " + FMT.format(plugin.priceOf(p) - e.getBalance()) + " more");
		}
		else
		{
			parcelPrice.setText(FMT.format(plugin.priceOf(p)) + " to buy");
			survey.setString("surveyed");
			claim.setEnabled(true);
			claim.setText("Buy for " + FMT.format(plugin.priceOf(p)));
		}
	}

	/**
	 * The land you hold, grouped by kingdom and collapsed by default.
	 *
	 * A flat list works until it does not. Three hundred deeds is three hundred
	 * rows, and finding the one you meant to sell means reading all of them --
	 * so the list is cut by the same kingdoms the Deed Log already counts, and
	 * each group's header carries the two things worth knowing without opening
	 * it: how many deeds are in there and what they pay.
	 *
	 * Everything starts closed, even for a small estate, so the panel behaves
	 * the same way however much land is held.
	 */
	private void renderHoldings(List<Parcel> owned)
	{
		// Rebuilt only when ownership actually changes. The panel refreshes every
		// time rent ticks the balance, and tearing down and rebuilding a few
		// hundred rows for that would churn Swing constantly for no visible
		// change -- and would eat the mouse-over state mid-hover.
		StringBuilder sig = new StringBuilder();
		for (Parcel p : owned)
		{
			sig.append(p.getPid()).append(',');
		}
		if (sig.toString().equals(lastHoldings))
		{
			return;
		}
		lastHoldings = sig.toString();

		holdings.removeAll();
		if (owned.isEmpty())
		{
			holdingsHeader.setText("No parcels yet");
			holdings.revalidate();
			holdings.repaint();
			return;
		}

		Map<Kingdom, List<Parcel>> byKingdom = new EnumMap<>(Kingdom.class);
		for (Parcel p : owned)
		{
			byKingdom.computeIfAbsent(Kingdom.of(p), k -> new ArrayList<>()).add(p);
		}
		holdingsHeader.setText(owned.size() + " parcel" + (owned.size() == 1 ? "" : "s")
			+ " in " + byKingdom.size() + " kingdom" + (byKingdom.size() == 1 ? "" : "s"));

		// Most land first: the kingdom you are working through is the one you
		// are most likely to have come here to look at.
		List<Kingdom> order = new ArrayList<>(byKingdom.keySet());
		order.sort(Comparator.comparingInt((Kingdom k) -> byKingdom.get(k).size()).reversed());

		for (Kingdom k : order)
		{
			List<Parcel> in = byKingdom.get(k);
			// Every parcel, not a top-N: the list is also how land is sold, and
			// a capped list makes everything below the cut unsellable.
			in.sort(Comparator.comparingDouble(Parcel::rps).reversed());
			holdings.add(kingdomGroup(k, in));
		}
		holdings.revalidate();
		holdings.repaint();
	}

	/** One kingdom: a header that opens and closes, and the rows under it. */
	private JPanel kingdomGroup(Kingdom k, List<Parcel> in)
	{
		double rps = 0;
		for (Parcel p : in)
		{
			rps += p.rps();
		}

		JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (Parcel p : in)
		{
			rows.add(holdingRow(p));
		}
		rows.setVisible(expanded.contains(k));

		JLabel caret = new JLabel();
		caret.setFont(FontManager.getRunescapeSmallFont());
		caret.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel name = new JLabel(k.getDisplayName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		JLabel count = new JLabel(in.size() + " \u00b7 " + String.format("%.3f/s", rps));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel left = new JPanel(new BorderLayout(4, 0));
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		left.add(caret, BorderLayout.WEST);
		left.add(name, BorderLayout.CENTER);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
		header.add(left, BorderLayout.WEST);
		header.add(count, BorderLayout.EAST);
		header.setToolTipText(in.size() + " deed" + (in.size() == 1 ? "" : "s")
			+ " in " + k.getDisplayName());
		caret.setText(rows.isVisible() ? "\u25be" : "\u25b8");

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Toggled in place rather than by rebuilding the list: a rebuild
				// is keyed on ownership changing, and opening a group is not
				// that. The set is what survives the next real rebuild.
				boolean open = !rows.isVisible();
				rows.setVisible(open);
				caret.setText(open ? "\u25be" : "\u25b8");
				if (open)
				{
					expanded.add(k);
				}
				else
				{
					expanded.remove(k);
				}
				holdings.revalidate();
				holdings.repaint();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				left.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		JPanel group = new JPanel(new BorderLayout());
		group.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		group.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
			ColorScheme.DARK_GRAY_COLOR));
		group.add(header, BorderLayout.NORTH);
		group.add(rows, BorderLayout.CENTER);
		return group;
	}

	private JPanel holdingRow(Parcel p)
	{
		Estate estate = plugin.getEstate();
		Integer paidObj = estate.getOwned().get(p.getPid());
		// What abandoning actually returns is half of what was PAID, not half of
		// today's tier price. They differ for landmarks and for any parcel whose
		// price moved when the survey was regenerated, and showing the wrong one
		// is how a player gets a nasty surprise.
		int paid = paidObj == null ? p.getPrice() : paidObj;
		int refund = paid / 2;

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		JLabel name = new JLabel(p.isLandmark()
			? p.getLandmark().getDisplayName() : p.getTier().getDisplayName());
		name.setForeground(p.isLandmark() ? LANDMARK : p.getTier().color());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setToolTipText(p.displayName() + " -- paid " + FMT.format(paid));

		JLabel rate = new JLabel(String.format("%.3f/s", p.rps()));
		rate.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rate.setFont(FontManager.getRunescapeSmallFont());

		JLabel sell = new JLabel("✕");
		sell.setFont(FontManager.getRunescapeSmallFont());
		sell.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		sell.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
		sell.setToolTipText("Abandon for " + FMT.format(refund));

		JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		right.add(rate, BorderLayout.WEST);
		right.add(sell, BorderLayout.EAST);

		row.add(name, BorderLayout.WEST);
		row.add(right, BorderLayout.EAST);

		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				sell.setForeground(DANGER);
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				right.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				sell.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				confirmAbandon(p, paid, refund);
			}
		};
		// Only the cross sells. Hovering anywhere on the row highlights it so the
		// control is discoverable, but a stray click on the name must not cost
		// the player a parcel.
		sell.addMouseListener(hover);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover.mouseEntered(e);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover.mouseExited(e);
			}
		});
		return row;
	}

	private void confirmAbandon(Parcel p, int paid, int refund)
	{
		String what = p.isLandmark()
			? p.getLandmark().getDisplayName() + " (a landmark)"
			: p.getTier().getDisplayName() + " parcel " + p.getPid();

		int answer = JOptionPane.showConfirmDialog(this,
			"<html><body style='width:230px'>"
				+ "Abandon <b>" + what + "</b>?<br><br>"
				+ "You paid " + FMT.format(paid) + " and will get back <b>"
				+ FMT.format(refund) + "</b>, losing "
				+ FMT.format(paid - refund) + ".<br><br>"
				+ "It stays surveyed, so you keep what you learned -- but the deed "
				+ "is gone and you would have to buy it again."
				+ "</body></html>",
			"Abandon land?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (answer == JOptionPane.YES_OPTION)
		{
			plugin.abandon(p.getPid());
		}
	}

	/** Wipe this character's estate. Guarded, because it cannot be undone. */
	private void confirmReset()
	{
		Estate e = plugin.getEstate();
		int answer = JOptionPane.showConfirmDialog(this,
			"<html><body style='width:230px'>"
				+ "Start a <b>new estate</b> for this character?<br><br>"
				+ "You would lose " + FMT.format(e.getOwned().size()) + " parcel(s), "
				+ FMT.format(e.getBalance()) + " in the bank, and everything you have "
				+ "surveyed (" + FMT.format(e.surveyedCount()) + " parcels).<br><br>"
				+ "This cannot be undone."
				+ "</body></html>",
			"New estate?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (answer == JOptionPane.YES_OPTION)
		{
			plugin.resetEstate();
		}
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

	private static JLabel keyLabel(String s)
	{
		JLabel l = new JLabel(s);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(FontManager.getRunescapeSmallFont());
		return l;
	}

	private static JLabel valueLabel()
	{
		JLabel l = new JLabel("--");
		l.setForeground(Color.WHITE);
		l.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD));
		return l;
	}

	private static JPanel spacer()
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setPreferredSize(new Dimension(1, 6));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		return p;
	}
}
