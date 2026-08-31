/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Provider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Tile;
import net.runelite.api.MenuEntry;
import net.runelite.api.WorldView;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Gielinor Deeds -- a single-player land game played over the real OSRS map.
 *
 * Everything is local. There is no server, no account, and no network traffic
 * of any kind: the survey of all 33,398 claimable parcels ships inside the jar,
 * and your estate is saved in RuneLite's own config store on your PC.
 */
@Slf4j
@PluginDescriptor(
	name = "Gielinor Deeds",
	description = "Claim parcels of Gielinor by standing on them. Single-player, saved locally.",
	tags = {"land", "deeds", "map", "parcel", "territory"}
)
public class GielinorDeedsPlugin extends Plugin
{
	private static final String ESTATE_KEY = "estate";

	@Inject private Client client;
	@Inject private GielinorDeedsConfig config;
	@Inject private ConfigManager configManager;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private ParcelOverlay parcelOverlay;
	@Inject private DeedWorldMapOverlay worldMapOverlay;
	@Inject private DeedMinimapOverlay minimapOverlay;
	@Inject private VeilOverlay veilOverlay;
	@Inject private ClientToolbar clientToolbar;
	@Inject private Provider<GielinorDeedsPanel> panelProvider;
	@Inject private Gson gson;

	@Getter private ParcelGrid grid;
	@Getter private Estate estate = new Estate();
	@Getter private DeedLog deedLog;
	@Getter @Nullable private Parcel currentParcel;

	@Getter @Nullable private String notice;

	/** The character this estate was loaded for. Estates are per character. */
	@Nullable private String loadedFor;
	/** Last rendered panel state, so a repaint only happens on a real change. */
	@Nullable private String lastPanelState;
	/** XP seen so far per skill, so a gain can be told from a total. */
	private final SkillBaseline baseline = new SkillBaseline();

	/**
	 * The draw-callback veil, when we have managed to install it. Null means we
	 * are painting instead -- see VeilRenderer for why that can happen.
	 */
	@Nullable private VeilRenderer veilRenderer;
	private boolean wasShouting;
	private static final String TRESPASS_SHOUT = "GET BACK ON YOUR PROPERTY!!";

	/**
	 * How long a survey takes.
	 *
	 * Long enough to read as field work, short enough not to tax expansion.
	 * Checked on the game tick, so the real wait is 5.0 to 5.6 seconds.
	 */
	private static final int SURVEY_MILLIS = 5000;

	/**
	 * The pose held while surveying: sighting through a telescope.
	 *
	 * A theodolite is a telescope on a tripod, which is what the range
	 * upgrades are named after.
	 *
	 * Client-side and on the local player only: nobody else sees it and
	 * nothing is sent to the server.
	 */
	private static final int RESEARCH_ANIMATION = AnimationID.HUMAN_USE_TELESCOPE;

	/**
	 * The deed being surveyed and when its work is done, or null and unset.
	 *
	 * Volatile because they are written from the Swing thread -- a panel button
	 * -- and read from the client thread on the game tick.
	 */
	@Nullable private volatile Parcel surveying;
	private volatile long surveyDoneAt;
	/** Client cycles the bubble stays up. ~150 is a couple of seconds. */
	private static final int SHOUT_CYCLES = 150;
	/**
	 * What a charge cost the last time XP came in. The price depends on which
	 * skill earned it, so there is no single answer to show until some XP has
	 * actually arrived -- until then the panel falls back to the standing
	 * figure, which is the price at 99.
	 */
	private long lastXpCost;

	/** XP needed for the next charge at the price last paid. */
	public long xpCostNow()
	{
		return lastXpCost > 0 ? lastXpCost : Balance.XP_PER_CHARGE;
	}
	private GielinorDeedsPanel panel;
	private NavigationButton navButton;

	@Provides
	GielinorDeedsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GielinorDeedsConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		try
		{
			grid = ParcelGrid.load();
		}
		catch (IOException e)
		{
			// Without the survey there is no plugin -- fail loudly at startup
			// rather than silently drawing nothing forever.
			log.error("could not load the parcel survey", e);
			throw e;
		}
		// Logged so there is a positive signal in the client log that the
		// survey loaded, rather than only an error if it did not.
		deedLog = new DeedLog(grid);
		log.info("Gielinor Deeds: survey loaded, {} parcels, {} claimable",
			grid.size(), deedLog.getBuyableTotal());
		overlayManager.add(parcelOverlay);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(veilOverlay);

		// Built after the grid loads: the panel reads it on construction to
		// render holdings, and a null grid there would show an empty estate to
		// someone who owns half of Varrock.
		panel = panelProvider.get();
		navButton = NavigationButton.builder()
			.tooltip("Gielinor Deeds")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		save();
		overlayManager.remove(parcelOverlay);
		overlayManager.remove(worldMapOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(veilOverlay);
		if (veilRenderer != null)
		{
			// setDrawCallbacks asserts the client thread, and shutDown arrives
			// on Swing when the plugin is switched off in the plugin list.
			VeilRenderer doomed = veilRenderer;
			veilRenderer = null;
			clientThread.invoke(doomed::uninstall);
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		deedLog = null;
		surveying = null;             // see stopResearching: the pose lapses
		currentParcel = null;
		loadedFor = null;
		grid = null;
	}

	// ── persistence ──────────────────────────────────────────────────────

	/**
	 * Estates are per character. Alts keep separate holdings, because a parcel
	 * is a record that a particular character walked somewhere -- pooling them
	 * would make the walking meaningless.
	 */
	private void loadFor(String rsn)
	{
		if (rsn == null || rsn.equals(loadedFor))
		{
			return;
		}
		save();                                     // flush the previous character
		String json = configManager.getConfiguration(GielinorDeedsConfig.GROUP,
			ESTATE_KEY + "." + rsn);
		Estate loaded = null;
		if (json != null && !json.isEmpty())
		{
			try
			{
				loaded = gson.fromJson(json, Estate.class);
			}
			catch (Exception e)
			{
				// A corrupt save must not brick the plugin. Losing an estate is
				// bad; refusing to start ever again is worse.
				log.warn("could not read the estate for {}, starting fresh", rsn, e);
			}
		}
		estate = loaded != null ? loaded : new Estate();
		loadedFor = rsn;
		if (estate.reconcileOwnedAsSurveyed(grid) > 0)
		{
			save();
		}
		accrueRent();
		refreshPanel();
	}

	private void save()
	{
		if (loadedFor != null && estate != null)
		{
			estate.packSurveyed();
			configManager.setConfiguration(GielinorDeedsConfig.GROUP,
				ESTATE_KEY + "." + loadedFor, gson.toJson(estate));
		}
	}

	// ── rent ─────────────────────────────────────────────────────────────

	/**
	 * Settle rent up to now. The arithmetic lives in Estate so it is testable.
	 *
	 * A payout that covers time away is reported in chat. Rent arriving in
	 * silence is rent the player never notices: the balance is simply larger
	 * than they remember, which reads as a number that drifts rather than as
	 * land that earned something while they were gone.
	 */
	private void accrueRent()
	{
		long now = System.currentTimeMillis();
		long cap = Duration.ofHours(Balance.OFFLINE_RENT_HOURS).toMillis();
		// Measured before the payout, because accrue moves the clock it is
		// measured against.
		long since = estate.getLastAccrued();
		long away = since == 0 ? 0 : Math.min(now - since, cap);

		long earned = estate.accrue(estate.rps(grid), now, cap,
			Balance.ONLINE_GRACE_MILLIS, Balance.OFFLINE_RENT_RATE);

		if (earned > 0 && away > Balance.ONLINE_GRACE_MILLIS)
		{
			reportTimeAway(earned, away);
		}
	}

	/**
	 * Say what the estate earned while nobody was looking.
	 *
	 * The span quoted is the span actually PAID for, not the span away: eight
	 * hours is the most that ever pays, so a player back after a fortnight
	 * should be told they were paid for eight hours rather than left to work
	 * out why a fortnight came to so little.
	 *
	 * Queued onto the client thread because rent settles on a scheduled timer
	 * and from panel buttons, neither of which is the thread a chat message may
	 * be written from.
	 */
	private void reportTimeAway(long earned, long awayMillis)
	{
		long minutes = awayMillis / 60_000L;
		String span = minutes >= 60
			? (minutes / 60) + "h " + (minutes % 60) + "m"
			: minutes + "m";
		boolean capped = awayMillis >= Duration.ofHours(Balance.OFFLINE_RENT_HOURS)
			.toMillis() - 1000;
		String message = "Your estate earned " + earned + " gp over " + span
			+ " away, at " + Math.round(Balance.OFFLINE_RENT_RATE * 100) + "%"
			+ (capped ? " (the " + Balance.OFFLINE_RENT_HOURS + " hour cap)" : "")
			+ ".";
		clientThread.invoke(() -> say(message, CHAT_GOOD));
	}

	// ── earning charges ──────────────────────────────────────────────────

	/**
	 * XP and level-ups are the only things that pay. Nothing here fires while
	 * logged out or idle, which is the point: rent accrues on its own, the right
	 * to survey does not.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (grid == null || loadedFor == null)
		{
			return;
		}
		SkillBaseline.Delta d = baseline.observe(event.getSkill(), event.getXp());
		if (d == null)
		{
			return;                       // first sighting: baseline only
		}

		int gained = 0;
		if (d.hasXp())
		{
			// Priced on the level of the skill that earned it, so a low-level
			// account is not locked out of the hourly cap it is measured
			// against. See SurveyCharges.xpCostFor.
			lastXpCost = SurveyCharges.xpCostFor(d.getToLevel(), Balance.XP_PER_CHARGE);
			gained += estate.getCharges().addXp(d.getXpGained(), System.currentTimeMillis(),
				lastXpCost, Balance.CHARGES_PER_HOUR);
		}
		if (d.leveled())
		{
			// Every level crossed pays, so a lamp or a burst of levels is
			// counted in full.
			int lvlCharges = 0;
			for (int lvl = d.getFromLevel() + 1; lvl <= d.getToLevel(); lvl++)
			{
				lvlCharges += estate.getCharges().addLevel(lvl);
			}
			if (lvlCharges > 0)
			{
				gained += lvlCharges;
				notice = "Level " + d.getToLevel() + " " + event.getSkill().getName()
					+ ": +" + lvlCharges + " survey charges";
			}
		}

		if (gained > 0)
		{
			save();
			refreshPanelIfChanged();
		}
	}

	// ── surveying ────────────────────────────────────────────────────────
	//
	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			save();
			baseline.reset();
			currentParcel = null;
			// Nobody is standing anywhere to survey from any more, and the
			// deadline would otherwise fire against the next character's estate.
			surveying = null;
			notice = null;
			refreshPanel();
		}
	}

	/**
	 * Survey the block around the player, spending one charge.
	 *
	 * The charge is only spent if something new is actually revealed -- walking
	 * back over ground you already know must never cost anything.
	 */
	/**
	 * Strip menu options that point at ground outside the estate.
	 *
	 * Run on ClientTick rather than MenuEntryAdded because entries are still
	 * being appended while that event fires; filtering the finished array is
	 * the only point where the whole menu is known.
	 *
	 * This removes entries, nothing more. The client still accepts a click on
	 * a tile with no menu entry via other paths, and the player can turn the
	 * setting off at any time -- the run is on their honour, and a plugin that
	 * genuinely blocked movement would be a problem both for the Plugin Hub and
	 * under Jagex's third-party client rules.
	 */
	/**
	 * Refuse actions aimed off the estate.
	 *
	 * The menu is left exactly as the game wrote it and the click is consumed
	 * instead, which is the shape Hold Your Ground uses. RuneLite lists
	 * conditional menu entry removal among its rejected features, and Jagex's
	 * third party client guidelines name removing player-based options
	 * outright.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.deedLocked() || !config.hideOffEstateMenus() || grid == null)
		{
			return;
		}
		MenuAction type = event.getMenuAction();
		if (type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_HIGH_PRIORITY)
		{
			return;                       // our own Survey deed option
		}
		if (aimsOffEstate(type, event.getId()))
		{
			event.consume();
			sayRefused("That is not your land -- survey it first.");
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (grid == null || client.isMenuOpen())
		{
			return;
		}
		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return;
		}

		// The tile under the cursor, straight from the client. Reading it out
		// of param0/param1 was wrong twice: those fields hold widget and item
		// ids for most actions, and the five GAME_OBJECT option ids are not
		// consecutive, so neither a type range nor a coordinate read could be
		// trusted. This is one value with one meaning.
		Tile hovered = client.getSelectedSceneTile();
		WorldPoint wp = hovered == null ? null : hovered.getWorldLocation();
		// Both questions are asked of the ground rather than the storey, so the
		// filter agrees with what the player can see: the veil covers an upper
		// floor over land you do not own, and a menu the veil is hiding ground
		// under should not still be clickable. Asking about the storey answered
		// "not surveyable, therefore not off your estate" for every floor above
		// the first, which left the whole upstairs interactive under the tint.
		Parcel parcel = deedUnder(wp);

		// Menu entries are no longer removed here. Hold Your Ground, which is
		// on the plugin hub doing the same job, leaves every option in the menu
		// and refuses the click instead -- and RuneLite lists "conditional menu
		// entry removing" among the things it has rejected, while Jagex's
		// guidelines name removing player-based options outright. Refusing an
		// action you chose to forbid yourself is a different thing from editing
		// what the game offers, and only one of the two has a precedent.
		List<MenuEntry> keep = new ArrayList<>(entries.length + 1);
		for (MenuEntry e : entries)
		{
			keep.add(e);
		}

		boolean changed = false;

		// Only when the menu is about the world. getSelectedSceneTile keeps
		// answering with the last tile the cursor crossed, so right-clicking a
		// coin pouch while the mouse happened to pass over unsurveyed ground
		// put "Survey deed" at the bottom of the inventory menu. An inventory
		// menu contains no scene action; a menu aimed at the ground always
		// contains at least Walk here.
		boolean aimedAtTheWorld = false;
		for (MenuEntry e : entries)
		{
			if (isSceneAction(e.getType()))
			{
				aimedAtTheWorld = true;
				break;
			}
		}

		if (config.surveyMenuOption() && aimedAtTheWorld && canSurveyFromHere(parcel))
		{
			// The client takes the LAST entry as the left-click action, so an
			// option appended to the end becomes the default and a misplaced
			// click on ground you were walking across spends a charge. Always
			// at the front, always deprioritised: right-click only, with no
			// setting to make it otherwise. The setting decides whether the
			// option is offered at all, which is the question worth asking.
			final Parcel target = parcel;
			MenuEntry survey = client.createMenuEntry(0)
				.setOption("Survey deed (1 charge)")
				.setTarget("<col=d0a040>" + target.displayName() + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(m -> surveyAt(target));
			survey.setDeprioritized(true);
			keep.add(0, survey);
			changed = true;
		}

		// Tracked rather than inferred from the size. Hiding one entry and
		// adding the survey option leaves the count unchanged, so a length
		// comparison would conclude nothing had happened and write nothing
		// back.
		if (changed)
		{
			client.setMenuEntries(keep.toArray(new MenuEntry[0]));
		}
	}

	/**
	 * Whether the deed under the cursor can be surveyed from where you stand.
	 *
	 * Offered in ordinary play as well as in a locked run. The frontier rule
	 * is the part that belongs to Deed Locked: outside one there is no
	 * frontier, and the charge is the only thing rationing surveys.
	 */
	private boolean canSurveyFromHere(@Nullable Parcel p)
	{
		if (p == null || grid == null || surveying != null)
		{
			return false;
		}
		int idx = grid.indexOf(p);
		if (idx < 0 || estate.hasSurveyed(idx))
		{
			return false;
		}
		if (estate.getCharges().getCharges() <= 0)
		{
			return false;
		}
		return !config.deedLocked() || DeedLock.onFrontier(grid, estate, p);
	}

	/**
	 * Menu actions whose param0/param1 are scene coordinates.
	 *
	 * Listed rather than range-checked. The first version tested
	 * {@code id >= GAME_OBJECT_FIRST_OPTION && id <= GAME_OBJECT_FIFTH_OPTION}
	 * on the assumption those five were consecutive. They are not:
	 * GAME_OBJECT_FIRST_OPTION is 3 and GAME_OBJECT_FIFTH_OPTION is 1001, so
	 * that range covered every action in the game -- NPCs, inventory, widgets,
	 * player options. Their params are widget and item ids, which read as
	 * nonsense scene coordinates, so every entry resolved to unsurveyed ground
	 * and the filter removed the entire right-click menu.
	 */
	private static final java.util.EnumSet<MenuAction> SCENE_ACTIONS =
		java.util.EnumSet.of(
			MenuAction.WALK,
			MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
			MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
			MenuAction.GAME_OBJECT_FIFTH_OPTION,
			MenuAction.ITEM_USE_ON_GAME_OBJECT, MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
			MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
			MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
			MenuAction.GROUND_ITEM_FIFTH_OPTION,
			MenuAction.ITEM_USE_ON_GROUND_ITEM, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM);

	/**
	 * Actions aimed at an actor rather than a tile.
	 *
	 * These carry an index in getIdentifier(), not a position, so the actor has
	 * to be looked up to find out where it is standing. Without this you could
	 * still attack, trade and pickpocket your way across ground you do not own.
	 */
	private static final java.util.EnumSet<MenuAction> NPC_ACTIONS =
		java.util.EnumSet.of(
			MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
			MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
			MenuAction.NPC_FIFTH_OPTION,
			MenuAction.ITEM_USE_ON_NPC, MenuAction.WIDGET_TARGET_ON_NPC);

	private static boolean isSceneAction(MenuAction t)
	{
		return t != null && SCENE_ACTIONS.contains(t);
	}

	/**
	 * Where an action is aimed, or null when it is not aimed at the world at
	 * all -- inventory, interfaces, the bank. Those are always left alone: a
	 * locked run still has to be able to use its own gear.
	 */
	@Nullable
	private WorldPoint targetOf(MenuAction type, int identifier)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || type == null)
		{
			return null;
		}
		if (isSceneAction(type))
		{
			// The tile under the cursor, from the client. Reading param0/param1
			// was wrong every time it was tried: most actions keep widget and
			// item ids in those fields.
			Tile t = client.getSelectedSceneTile();
			return t == null ? null : t.getWorldLocation();
		}
		if (NPC_ACTIONS.contains(type))
		{
			NPC npc = wv.npcs().byIndex(identifier);
			return npc == null ? null : npc.getWorldLocation();
		}
		// Deliberately no player lookup. Jagex's third party client guidelines
		// name "reorders or removes player-based options, such as 'Trade with'"
		// as unacceptable, and Hold Your Ground -- which is on the hub doing
		// this same job -- resolves NPCs only and never touches another player.
		// Standing on your own land is a rule about ground, so ground and the
		// things standing on it is as far as it needs to reach.
		return null;
	}

	/**
	 * Fill the XP baseline from the client's own totals.
	 *
	 * Cheap and idempotent: seed() only writes a skill it has not seen, so once
	 * filled this does nothing. It exists because the baseline was filled only
	 * by StatChanged events, so enabling the plugin mid-session left every
	 * skill unseeded and swallowed the first gain in each one as a baseline.
	 */
	private void seedSkills()
	{
		if (baseline.isSeeded())
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			baseline.seed(skill, client.getSkillExperience(skill));
		}
	}

	/** True when this action reaches ground the veil is covering. */
	private boolean aimsOffEstate(MenuAction type, int identifier)
	{
		return isVeiledGround(targetOf(type, identifier));
	}

	/**
	 * Colours for anything this plugin writes to the chatbox.
	 *
	 * Dark and saturated, because the chatbox is a light panel and the pale
	 * greens and reds that read well against a dark UI vanish against it -- the
	 * first version of the rent message was a mid green on beige and was, in
	 * the reporter's words, barely visible. These sit at a contrast ratio that
	 * survives the transparent chatbox too, where the background is whatever
	 * happens to be behind it.
	 */
	private static final java.awt.Color CHAT_GOOD = new java.awt.Color(0x0A, 0x66, 0x1E);
	private static final java.awt.Color CHAT_BAD = new java.awt.Color(0x9B, 0x00, 0x00);

	/**
	 * Write one line to the chatbox in the plugin's voice.
	 *
	 * Every message the plugin prints goes through here, so contrast is decided
	 * once rather than at each call site.
	 */
	private void say(String message, java.awt.Color colour)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			ColorUtil.wrapWithColorTag(message, colour), null);
	}

	/**
	 * Tell the player why a click did nothing.
	 *
	 * A refused click that says nothing is indistinguishable from a dropped
	 * one, and a player will click again, and then again harder. Hold Your
	 * Ground prints a line every time it swallows a step, which is the right
	 * shape: the message is the whole interface for a feature that otherwise
	 * has none.
	 */
	private void sayRefused(String message)
	{
		say(message, CHAT_BAD);
	}

	// Test seam. The action list was got wrong once by assuming the five
	// GAME_OBJECT ids were consecutive, so it is pinned in CompassAndMenuTest.
	static boolean isSceneActionForTest(MenuAction t)
	{
		return isSceneAction(t);
	}

	/**
	 * Put the warning over the player's own head while they are off their land.
	 *
	 * Overhead text is a client-side display on the local player only -- nobody
	 * else sees it and nothing is sent to the server. It is refreshed while the
	 * player is still trespassing because the cycle counter is what makes the
	 * bubble expire, and left to lapse on its own once they are home rather
	 * than cleared outright, so it fades the way ordinary chat does.
	 *
	 * Only ever written while it is this plugin's own message. Stamping over
	 * whatever the player actually said would be rude and confusing.
	 */
	private void shoutIfTrespassing(Player local, boolean off)
	{
		if (local == null)
		{
			return;
		}
		if (!off)
		{
			wasShouting = false;
			return;
		}
		String current = local.getOverheadText();
		boolean mine = TRESPASS_SHOUT.equals(current);
		if (current != null && !current.isEmpty() && !mine && !wasShouting)
		{
			return;                       // the player is saying something
		}
		local.setOverheadText(TRESPASS_SHOUT);
		local.setOverheadCycle(SHOUT_CYCLES);
		wasShouting = true;
	}

	/**
	 * Keep the draw-callback veil installed, or give up and let the overlay do
	 * it.
	 *
	 * Checked every tick because the hook is contested: GpuPlugin owns it and
	 * re-claims it whenever it restarts, which silently drops our wrapper. One
	 * comparison a tick is nothing, and toggling the GPU plugin mid-run then
	 * repairs itself rather than leaving the blackout off.
	 */
	private void syncVeilRenderer()
	{
		boolean want = config.deedLocked() && config.veilUnsurveyed()
			&& config.veilStyle() == VeilStyle.HIDDEN;
		if (!want)
		{
			if (veilRenderer != null)
			{
				veilRenderer.uninstall();
				veilRenderer = null;
			}
			return;
		}
		if (veilRenderer != null && veilRenderer.isInstalled())
		{
			return;
		}
		veilRenderer = VeilRenderer.install(client, this, config);
	}

	/**
	 * Whether the painted veil should draw the ground blackout.
	 *
	 * Only when the renderer is not doing it. Both at once would be a black
	 * film over ground that is already absent, which costs a screenful of
	 * alpha for nothing.
	 */
	public boolean paintGroundVeil()
	{
		return veilRenderer == null || !veilRenderer.isInstalled();
	}

	public void surveyCurrent()
	{
		surveyAt(currentParcel);
	}

	/**
	 * The deeds a survey centred here would newly open.
	 *
	 * A locked run opens exactly the deed it paid for. Range upgrades are an
	 * unlocked-play convenience; letting a 5x5 theodolite sweep open
	 * twenty-five deeds at once would flatten the whole progression.
	 */
	private List<Parcel> newDeedsAround(Parcel p)
	{
		int r = config.deedLocked()
			? 0 : SurveyRange.forLevel(estate.getSurveyRange()).getRadius();
		List<Parcel> fresh = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++)
		{
			for (int dy = -r; dy <= r; dy++)
			{
				Parcel q = grid.at(p.getPx() + dx, p.getPy() + dy);
				int idx = grid.indexOf(q);
				if (q != null && idx >= 0 && !estate.hasSurveyed(idx))
				{
					fresh.add(q);
				}
			}
		}
		return fresh;
	}

	/**
	 * Begin surveying a parcel, which in a locked run need not be the one you
	 * are standing on.
	 *
	 * Frontier surveying is what makes Deed Locked playable at deed scale:
	 * blacked-out ground is ground you are not meant to walk onto, so requiring
	 * you to stand there would deadlock the run.
	 *
	 * Nothing is opened here. This checks the work is worth starting;
	 * {@link #finishSurvey} applies it once the delay has run. Touches nothing
	 * on the client, since a panel button calls it from the Swing thread.
	 */
	public void surveyAt(@Nullable Parcel p)
	{
		if (p == null || grid == null)
		{
			return;
		}
		Parcel running = surveying;
		if (running != null)
		{
			notice = "Already surveying " + running.displayName();
			refreshPanel();
			return;
		}
		if (config.deedLocked() && !estate.hasSurveyed(grid.indexOf(p))
			&& !DeedLock.onFrontier(grid, estate, p))
		{
			notice = "Only land touching a deed you own can be surveyed";
			refreshPanel();
			return;
		}
		if (newDeedsAround(p).isEmpty())
		{
			notice = "Nothing new here";
			refreshPanel();
			return;
		}
		// Checked, not spent. The charge goes at the end of the work, so a
		// survey that is interrupted or that finds nothing costs nothing.
		if (estate.getCharges().getCharges() <= 0)
		{
			notice = "No survey charges -- earn them by playing";
			refreshPanel();
			return;
		}
		// Deadline before the parcel: the game tick reads them in that order,
		// and must never see a live survey against a stale deadline.
		surveyDoneAt = System.currentTimeMillis() + SURVEY_MILLIS;
		surveying = p;
		notice = "Surveying " + p.displayName() + "...";
		refreshPanel();
	}

	/** True while a survey is running, for the panel's button state. */
	public boolean isSurveying()
	{
		return surveying != null;
	}

	/**
	 * Hold the study pose and count down, then apply the survey.
	 *
	 * On the game tick rather than the 600ms schedule because it touches the
	 * local player, and because the pose should run on the clock the world
	 * does rather than one next to it.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		Parcel p = surveying;
		if (p == null)
		{
			return;
		}
		long left = surveyDoneAt - System.currentTimeMillis();
		if (left <= 0)
		{
			finishSurvey(p);
			return;
		}
		Player local = client.getLocalPlayer();
		if (local != null && local.getAnimation() != RESEARCH_ANIMATION)
		{
			// Restarted rather than left running: the server overwrites the
			// animation whenever it has its own opinion about what the player
			// is doing, and without this the pose lasts a single tick.
			local.setAnimation(RESEARCH_ANIMATION);
			local.setAnimationFrame(0);
		}
		notice = "Surveying " + p.displayName() + "... " + ((left + 999) / 1000) + "s";
	}

	/**
	 * Apply the survey that has just finished.
	 *
	 * Everything is re-checked rather than trusted from five seconds ago, and
	 * the charge is spent here rather than at the start, so a survey that is
	 * overtaken -- the ground opened another way, the charge gone -- costs
	 * nothing and says why.
	 */
	private void finishSurvey(Parcel p)
	{
		surveying = null;
		stopResearching();
		if (grid == null)
		{
			return;
		}
		List<Parcel> fresh = newDeedsAround(p);
		if (fresh.isEmpty())
		{
			notice = "Nothing new here";
			refreshPanel();
			return;
		}
		if (!estate.getCharges().spend())
		{
			notice = "No survey charges -- earn them by playing";
			refreshPanel();
			return;
		}
		for (Parcel q : fresh)
		{
			estate.markSurveyed(grid.indexOf(q));
		}
		Parcel first = fresh.get(0);
		notice = fresh.size() == 1
			? "Surveyed: " + first.displayName() + " -- " + first.getTier().getDisplayName()
			: "Surveyed " + fresh.size() + " parcels";
		save();
		refreshPanel();
	}

	/**
	 * Drop the study pose, if it is still the one we set.
	 *
	 * Client thread only, so this is called from the game tick and from event
	 * handlers and not from shutDown, which arrives on the Swing thread. There
	 * the pose is simply left to lapse -- the server overwrites it within a
	 * tick, the same way the trespass shout is left to fade.
	 */
	private void stopResearching()
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getAnimation() == RESEARCH_ANIMATION)
		{
			local.setAnimation(-1);
			local.setAnimationFrame(0);
		}
	}

	/**
	 * Buy the next survey range upgrade.
	 *
	 * Refused outright during a Deed Locked run. A locked run opens exactly the
	 * deed it paid for whatever instrument you hold -- see newDeedsAround -- so
	 * the upgrade genuinely does nothing there, and the panel sold it anyway
	 * and took 400,000 for a change the player could not see. A money sink that
	 * buys nothing is a bug, not a hard choice.
	 */
	public void buyRangeUpgrade()
	{
		if (config.deedLocked())
		{
			notice = "Range upgrades do nothing in a Deed Locked run -- "
				+ "a charge opens one deed";
			refreshPanel();
			return;
		}
		SurveyRange next = SurveyRange.next(estate.getSurveyRange());
		if (next == null || estate.getBalance() < next.getCost())
		{
			return;
		}
		accrueRent();                 // settle before the balance moves
		estate.setBalance(estate.getBalance() - next.getCost());
		estate.setSurveyRange(next.ordinal());
		notice = next.getDisplayName() + " acquired -- " + next.coverage()
			+ " parcels per charge";
		save();
		refreshPanel();
	}

	public boolean canSurvey()
	{
		return currentParcel != null && grid != null
			&& !estate.hasSurveyed(grid.indexOf(currentParcel))
			&& estate.getCharges().getCharges() > 0;
	}

	@Schedule(period = 600, unit = ChronoUnit.MILLIS)
	public void tick()
	{
		if (grid == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		loadFor(local.getName());
		seedSkills();

		WorldPoint wp = local.getWorldLocation();
		currentParcel = grid.isSurveyable(wp) ? grid.at(wp) : null;

		if (config.deedLocked())
		{
			// The opening grant still wants a parcel you are standing on, and
			// a storey is not one -- beginning a run from a first floor would
			// be beginning it from nowhere in particular.
			if (currentParcel != null)
			{
				int opened = DeedLock.grantStart(grid, estate, currentParcel);
				if (opened > 0)
				{
					notice = "Deed Locked: your run begins here. This deed is "
						+ "yours and everything else is dark. You have "
						+ DeedLock.GRANT_CHARGES + " survey charges -- spend "
						+ "them whichever way you like.";
					save();
				}
			}
			// Trespass is asked of the ground, not of currentParcel, which is
			// null on every upper floor because a storey cannot be bought. A
			// first floor over land you do not own is still land you do not
			// own, and the veil already says so -- so the warning has to say it
			// too, rather than falling silent the moment you climb a ladder.
			if (config.warnTrespass())
			{
				shoutIfTrespassing(local, isVeiledGround(wp));
			}
		}

		syncVeilRenderer();
		accrueRent();
		refreshPanelIfChanged();
	}

	/**
	 * Has this character surveyed the parcel? Everything is unknown until then --
	 * tier, price, even whether it can be claimed. Surveying is how you find out
	 * what a piece of Gielinor is, and the knowledge is permanent once earned.
	 */

	/**
	 * True when the veil covers this ground.
	 *
	 * The one question the locked mode asks about a position -- both veil
	 * styles, the menu filter and the trespass warning all come through here,
	 * so they cannot disagree about the same patch of ground. See
	 * {@link DeedLock#isVeiled} for what it decides and why.
	 */
	public boolean isVeiledGround(@Nullable WorldPoint wp)
	{
		return DeedLock.isVeiled(grid, estate, wp);
	}

	/**
	 * The deed a point stands over, upper floors included.
	 *
	 * Deliberately not {@link #getCurrentParcel}, which stays null on a storey
	 * because a storey cannot be surveyed, priced or bought. This answers the
	 * narrower question the menu filter asks -- whose ground is this -- so that
	 * the survey option is still reachable from a floor the veil is covering.
	 */
	@Nullable
	private Parcel deedUnder(@Nullable WorldPoint wp)
	{
		WorldPoint ground = ParcelGrid.groundOf(wp);
		return grid != null && grid.isSurveyable(ground) ? grid.at(ground) : null;
	}

	public boolean isKnown(@Nullable Parcel p)
	{
		if (p == null || grid == null)
		{
			return false;
		}
		return estate.hasSurveyed(grid.indexOf(p));
	}

	public boolean isCurrentKnown()
	{
		return isKnown(currentParcel);
	}

	/**
	 * Everything that must be true to buy the parcel underfoot.
	 *
	 * Knowledge, not a running survey: once surveyed, a parcel stays known, so
	 * you can walk away, earn the money and come back to buy it without
	 * surveying again.
	 */
	public boolean canClaim()
	{
		Parcel p = currentParcel;
		return p != null
			&& isKnown(p)
			&& p.isClaimable()
			&& !estate.owns(p.getPid())
			&& estate.getBalance() >= priceOf(p);
	}

	/** What this parcel costs this estate, after progressive pricing. */
	public long priceOf(Parcel p)
	{
		return estate.effectivePrice(p, Balance.PRICE_SCALE, Balance.PRICE_EXPONENT);
	}

	public double priceMultiplier()
	{
		return estate.priceMultiplier(Balance.PRICE_SCALE, Balance.PRICE_EXPONENT);
	}

	/** Called from the side panel's Claim button. */
	public void claimCurrent()
	{
		Parcel p = currentParcel;
		if (!canClaim() || p == null)
		{
			return;
		}
		// Settle at the current rate before the rate changes, otherwise time
		// accrued while owning less gets paid out at the new higher rate.
		accrueRent();
		// Priced at the moment of purchase, and the paid figure is what the
		// refund is based on -- so a parcel bought cheaply early stays cheap in
		// the ledger even after prices have climbed.
		long price = priceOf(p);
		estate.setBalance(estate.getBalance() - price);
		estate.getOwned().put(p.getPid(), (int) Math.min(Integer.MAX_VALUE, price));
		save();
		notice = "Bought " + p.displayName() + " for " + price;
		refreshPanel();
	}

	/** Wipe this character's estate and start over. */
	public void resetEstate()
	{
		estate = new Estate();
		surveying = null;              // do not land an old survey on a new run
		lastPanelState = null;         // force a full repaint of the new state
		notice = "New estate started";
		save();
		refreshPanel();
	}

	/** Sell a parcel back for half what was paid, the way PTW's abandon worked. */
	public void abandon(String pid)
	{
		accrueRent();
		Integer paid = estate.getOwned().remove(pid);
		if (paid != null)
		{
			estate.setBalance(estate.getBalance() + paid / 2);
			save();
			refreshPanel();
		}
	}

	/**
	 * Repaint only when something the panel actually shows has changed.
	 *
	 * The first version gated this on "a survey is still running", which stopped
	 * repainting on the very tick the survey completed -- leaving the panel
	 * frozen one frame short, showing a countdown and a dead button while the
	 * ground overlay already said the parcel was ready. Comparing a signature of
	 * the rendered state cannot go stale that way: the completion tick changes
	 * the signature like any other.
	 */
	private void refreshPanelIfChanged()
	{
		String sig = (currentParcel == null ? "-" : currentParcel.getPid())
			+ "|" + estate.getCharges().getCharges()
			+ "|" + estate.getCharges().getXpProgress()
			+ "|" + estate.getBalance()
			+ "|" + estate.getOwned().size()
			+ "|" + estate.surveyedCount()
			+ "|" + notice;
		if (!sig.equals(lastPanelState))
		{
			lastPanelState = sig;
			refreshPanel();
		}
	}


	private void refreshPanel()
	{
		GielinorDeedsPanel p = panel;
		if (p != null)
		{
			p.update();
		}
	}
}
