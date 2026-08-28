/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Hides unsurveyed ground by declining to draw it.
 *
 * {@link VeilOverlay} paints over the world instead. Skipping the draw is
 * sharper and cheaper, and it takes objects and actors with it.
 *
 * <h2>Two rendering paths</h2>
 *
 * GpuPlugin draws whole zones through drawZoneOpaque, drawZoneAlpha and
 * drawDynamic. The software renderer uses drawScenePaint, drawSceneTileModel
 * and the eight-argument draw. Both are intercepted, because which one runs
 * depends on whether a GPU plugin is loaded.
 *
 * <h2>The hook is contested, so this wraps rather than replaces</h2>
 *
 * Only one object can hold the client's draw callbacks and GpuPlugin already
 * claims it, so this keeps a reference to whatever was installed and forwards
 * every call except the ones it means to drop. All methods are forwarded: one
 * left unimplemented is one the GPU plugin never receives.
 *
 * Ownership can be lost when a GPU plugin restarts. {@link #isInstalled} lets
 * the plugin notice and re-wrap, and the overlay remains as a fallback.
 */
@Slf4j
public class VeilRenderer implements DrawCallbacks
{
	/** Tiles a scene zone spans, which is also a parcel. */
	private static final int ZONE = 8;
	/**
	 * Zone indices arrive in EXTENDED scene space, which is 184 tiles against
	 * the ordinary 104 -- a margin of (184-104)/2 = 40 tiles, or 5 zones, on
	 * every side. GpuPlugin subtracts the same offset before it renders. Miss
	 * it and every lookup lands 40 tiles from where it should, which is why the
	 * first attempt blacked out surveyed land as readily as anything else.
	 */
	private static final int ZONE_OFFSET =
		((net.runelite.api.Constants.EXTENDED_SCENE_SIZE
			- net.runelite.api.Constants.SCENE_SIZE) / 2) / ZONE;

	private final Client client;
	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;

	/** The callbacks that were installed before us. Every call reaches these. */
	private final DrawCallbacks inner;

	/**
	 * Per-scene-tile visibility, rebuilt when the estate or the scene changes.
	 * A grid lookup per tile per frame would be ~10,000 map probes a frame; a
	 * flat array read is free.
	 */
	private final boolean[] visible =
		new boolean[Perspective.SCENE_SIZE * Perspective.SCENE_SIZE];
	private int builtForBaseX = Integer.MIN_VALUE;
	private int builtForBaseY = Integer.MIN_VALUE;
	private int builtForSurveyed = -1;

	/**
	 * Zones hidden since the last frame started, and the count from the frame
	 * before it.
	 *
	 * Being installed and actually hiding anything are different things, so
	 * this counts what was skipped rather than assuming the hooks fired.
	 */
	private int skipping;
	private volatile int skippedLastFrame;


	VeilRenderer(Client client, GielinorDeedsPlugin plugin, GielinorDeedsConfig config,
		DrawCallbacks inner)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.inner = inner;
	}

	/** Whether this wrapper still owns the hook. */
	boolean isInstalled()
	{
		return client.getDrawCallbacks() == this;
	}

	/** Off entirely unless a locked run has asked for the blackout. */
	private boolean active()
	{
		return config.deedLocked() && config.veilUnsurveyed()
			&& config.veilStyle() == VeilStyle.HIDDEN;
	}

	/**
	 * True when this scene tile is on ground the estate has surveyed.
	 *
	 * The cache is keyed on the scene origin and the number of deeds surveyed,
	 * so it rebuilds when the player crosses into a new scene or opens more
	 * land, and not otherwise.
	 */
	private boolean tileVisible(int tileX, int tileY)
	{
		if (tileX < 0 || tileY < 0
			|| tileX >= Perspective.SCENE_SIZE || tileY >= Perspective.SCENE_SIZE)
		{
			return true;                  // outside the scene: not ours to hide
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return true;
		}
		int baseX = wv.getBaseX(), baseY = wv.getBaseY();
		int surveyed = plugin.getEstate().surveyedCount();
		if (baseX != builtForBaseX || baseY != builtForBaseY || surveyed != builtForSurveyed)
		{
			rebuild(baseX, baseY);
			builtForBaseX = baseX;
			builtForBaseY = baseY;
			builtForSurveyed = surveyed;
		}
		return visible[tileY * Perspective.SCENE_SIZE + tileX];
	}

	/**
	 * Whether the thing being drawn stands on ground the estate has seen.
	 *
	 * Asks the object rather than reading the coordinates. The draw hooks are
	 * handed four ints that look like a position; logged from a live client
	 * they divide out to tile indices spanning 0..15 in x and -4..0 in z, so
	 * whatever they are, they are not where the model is standing. A TileObject
	 * and an Actor both know their own world position, which needs no
	 * assumption about a coordinate space.
	 *
	 * Renderables that are neither have no location to ask for and are drawn.
	 */
	private boolean renderableVisible(@Nullable TileObject tileObject,
		@Nullable Renderable renderable)
	{
		net.runelite.api.coords.WorldPoint wp = null;
		if (tileObject != null)
		{
			wp = tileObject.getWorldLocation();
		}
		else if (renderable instanceof net.runelite.api.Actor)
		{
			wp = ((net.runelite.api.Actor) renderable).getWorldLocation();
		}
		if (wp == null)
		{
			return true;                  // nothing to ask, so draw it
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return true;
		}
		return tileVisible(wp.getX() - wv.getBaseX(), wp.getY() - wv.getBaseY());
	}

	/**
	 * Whether a whole 8x8 zone is on surveyed ground.
	 *
	 * A zone and a parcel are both 8x8 tiles and both sit on world multiples of
	 * eight, so they line up and the answer is the same for every tile inside.
	 * Checked at the zone's middle rather than a corner, so a zone that happens
	 * to straddle nothing in particular still resolves to one parcel.
	 */
	private boolean zoneVisible(Scene scene, int zx, int zz)
	{
		int off = scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL
			? ZONE_OFFSET : 0;
		return tileVisible((zx - off) * ZONE + ZONE / 2, (zz - off) * ZONE + ZONE / 2);
	}

	/**
	 * Whether a tile named in EXTENDED scene coordinates is on surveyed ground.
	 *
	 * The same shift the zone callbacks need, in tiles rather than zones, and
	 * derived from the same constant so the two cannot drift apart. Getting
	 * this wrong is not subtle: every lookup lands forty tiles from where it
	 * should, which is what blacked out surveyed land the first time the zone
	 * path was written.
	 */
	private boolean sceneTileVisible(Scene scene, int msx, int msy)
	{
		int off = scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL
			? ZONE_OFFSET * ZONE : 0;
		return tileVisible(msx - off, msy - off);
	}

	/**
	 * Rebuild the visibility grid for a scene.
	 *
	 * Deliberately takes no plane. A deed is a piece of ground and the floors
	 * above it belong to it, so the answer is the same on every storey -- which
	 * is also what makes the cache above sound, because climbing a ladder
	 * changes the plane without changing the scene origin or the surveyed
	 * count, and so would not have invalidated anything.
	 */
	private void rebuild(int baseX, int baseY)
	{
		for (int x = 0; x < Perspective.SCENE_SIZE; x++)
		{
			for (int y = 0; y < Perspective.SCENE_SIZE; y++)
			{
				net.runelite.api.coords.WorldPoint wp =
					new net.runelite.api.coords.WorldPoint(baseX + x, baseY + y, 0);
				visible[y * Perspective.SCENE_SIZE + x] = !plugin.isVeiledGround(wp);
			}
		}
	}

	// ── the software path, used when no GPU plugin is running ───────────

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane,
		int tileX, int tileY)
	{
		if (active() && !tileVisible(tileX, tileY))
		{
			skipping++;
			return;
		}
		inner.drawScenePaint(scene, paint, plane, tileX, tileY);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY)
	{
		if (active() && !tileVisible(tileX, tileY))
		{
			skipping++;
			return;
		}
		inner.drawSceneTileModel(scene, model, tileX, tileY);
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable,
		int orientation, int x, int y, int z, long hash)
	{
		if (active() && !renderableVisible(null, renderable))
		{
			return;                       // trees and buildings out there too
		}
		inner.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	// ── everything else is passed straight through ───────────────────────
	// A method missing here is a method GpuPlugin never receives.

	@Override
	public void draw(int overlayColor)
	{
		inner.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ,
		double cameraPitch, double cameraYaw, int plane)
	{
		inner.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		inner.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		inner.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		inner.loadScene(scene);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		inner.loadScene(worldView, scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		builtForBaseX = Integer.MIN_VALUE;    // new scene, cache is stale
		inner.swapScene(scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		inner.despawnWorldView(worldView);
	}

	/**
	 * Drop whole tiles the estate has not surveyed.
	 *
	 * Asked before the client walks a tile's contents, so a false here removes
	 * the ground, the objects, the items and the actors together. The default
	 * is true, so forwarding it blind disables the cull silently.
	 *
	 * Never called by GpuPlugin, which culls by zone; this is the software
	 * renderer's path.
	 */
	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos,
		float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ,
		int plane, int msx, int msy)
	{
		if (active() && !sceneTileVisible(scene, msx, msy))
		{
			skipping++;
			return false;
		}
		return inner.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos,
			cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	/**
	 * Forwarded unchanged.
	 *
	 * This one defaults to FALSE, so false cannot mean "cull this zone" -- any
	 * renderer not implementing it would hide the world. tileInFrustum is the
	 * cull.
	 */
	@Override
	public boolean zoneInFrustum(int a, int b, int c, int d)
	{
		return inner.zoneInFrustum(a, b, c, d);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection projection, float a, float b,
		float c, float d, float e, int f, int g, int h, Set<Integer> zones)
	{
		inner.preSceneDraw(scene, projection, a, b, c, d, e, f, g, h, zones);
	}

	@Override
	public void preSceneDraw(Scene scene, float a, float b, float c, float d,
		float e, int f, int g, int h, Set<Integer> zones)
	{
		inner.preSceneDraw(scene, a, b, c, d, e, f, g, h, zones);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		// A debugging aid, so it goes to the log rather than the sidebar.
		if (skipping != skippedLastFrame)
		{
			log.debug("Deed Locked veil skipped {} zones", skipping);
		}
		skippedLastFrame = skipping;
		skipping = 0;
		inner.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		inner.drawPass(projection, scene, pass);
	}

	// ── the zone path, which is what a GPU plugin actually uses ──────────
	// GpuPlugin draws whole zones and does not implement the per-tile hooks.
	// A zone is 8x8 tiles, the same unit as a parcel, so one test per zone
	// answers for every tile in it.

	@Override
	public void drawZoneOpaque(Projection projection, Scene scene, int zx, int zz)
	{
		if (active() && !zoneVisible(scene, zx, zz))
		{
			skipping++;
			return;
		}
		inner.drawZoneOpaque(projection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection projection, Scene scene, int level,
		int zx, int zz)
	{
		// The third argument is the LEVEL, not a coordinate. Reading it as one
		// shifted every zone lookup by a place and scrambled the whole map.
		if (active() && !zoneVisible(scene, zx, zz))
		{
			return;
		}
		inner.drawZoneAlpha(projection, scene, level, zx, zz);
	}

	/**
	 * The older drawDynamic, without a pass index.
	 *
	 * The client picks between the two overloads, so both must be filtered or
	 * hidden ground comes out half hidden.
	 *
	 * The trailing four are the same four the other overload names -- x, y, z,
	 * orientation -- this being the same call before a pass index was added in
	 * front of it. y is height; the two that place a model on the map are x
	 * and z.
	 */
	@Override
	public void drawDynamic(Projection projection, Scene scene, TileObject tileObject,
		Renderable renderable, Model model, int x, int y, int z, int orientation)
	{
		if (active() && !renderableVisible(tileObject, renderable))
		{
			return;
		}
		inner.drawDynamic(projection, scene, tileObject, renderable, model,
			x, y, z, orientation);
	}

	@Override
	public void drawDynamic(int pass, Projection projection, Scene scene,
		TileObject tileObject, Renderable renderable, Model model,
		int x, int y, int z, int orientation)
	{
		// Objects and actors, so trees and buildings out there go too.
		if (active() && !renderableVisible(tileObject, renderable))
		{
			return;
		}
		inner.drawDynamic(pass, projection, scene, tileObject, renderable, model,
			x, y, z, orientation);
	}

	/**
	 * Objects the client is drawing transiently -- doors mid-swing and the like.
	 *
	 * Filtered for the same reason as drawDynamic: anything that puts a model
	 * on the map has to answer the same question, or the ground is hidden and
	 * the things standing on it are not.
	 */
	@Override
	public void drawTemp(Projection projection, Scene scene, GameObject gameObject,
		Model model, int x, int y, int z, int orientation)
	{
		if (active() && !renderableVisible(gameObject, null))
		{
			return;
		}
		inner.drawTemp(projection, scene, gameObject, model, x, y, z, orientation);
	}

	@Override
	public void invalidateZone(Scene scene, int a, int b)
	{
		inner.invalidateZone(scene, a, b);
	}

	/**
	 * Wrap whatever is installed, or return null if there is nothing to wrap.
	 *
	 * Nothing to wrap means no GPU plugin, and without one the client does not
	 * route drawing through these callbacks at all -- so the caller should fall
	 * back to the overlay rather than install a wrapper that never fires.
	 */
	@Nullable
	static VeilRenderer install(Client client, GielinorDeedsPlugin plugin,
		GielinorDeedsConfig config)
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null || current instanceof VeilRenderer)
		{
			return current instanceof VeilRenderer ? (VeilRenderer) current : null;
		}
		VeilRenderer wrapper = new VeilRenderer(client, plugin, config, current);
		client.setDrawCallbacks(wrapper);
		log.debug("Deed Locked veil wrapped {}", current.getClass().getName());
		return wrapper;
	}

	/**
	 * Put the original callbacks back, if we are still the ones installed.
	 *
	 * Must run on the client thread: setDrawCallbacks asserts it, and shutDown
	 * arrives on the Swing thread when the plugin is switched off in the list.
	 */
	void uninstall()
	{
		if (isInstalled())
		{
			client.setDrawCallbacks(inner);
		}
	}
}
