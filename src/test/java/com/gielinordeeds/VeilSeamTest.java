/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Polygon;
import java.awt.geom.Path2D;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The veil must not leave untinted ground between two locked deeds.
 *
 * <h2>What went wrong</h2>
 *
 * A parcel used to be drawn as a quad joining the outermost screen vertices of
 * its four corner tiles. Both halves of that are wrong over uneven ground.
 *
 * "Outermost" was decided by screen distance from the middle of the parcel, and
 * on a slope a raised neighbouring vertex projects further away than the true
 * outer corner does -- so the parcels either side of a boundary picked
 * different points for the corner they share. And a straight line between
 * corners eight tiles apart cuts underneath whatever the ground does between
 * them, so on a hill neither parcel reached the deed line at all. Both leave a
 * sliver of bright terrain running along the boundary.
 *
 * <h2>How this pins it</h2>
 *
 * The camera and a hilly height map are faked, two adjacent parcels are traced,
 * and the seam between them is sampled at every tile corner and every midpoint
 * along it. Every sample must land inside the filled veil.
 *
 * {@link #aFlatQuadWouldLeaveTheSeamOpen} keeps the test honest by building the
 * old four-corner quad from the same terrain and showing it fails the same
 * check -- otherwise a bug that stopped the veil drawing entirely would still
 * let the seam tests pass.
 */
public class VeilSeamTest
{
	private static final int PLANE = 0;
	private static final int BASE_X = 3200;
	private static final int BASE_Y = 3200;
	private static final int CELL = 8;
	/** Height map size: a corner per tile boundary, so scene + 1 each way. */
	private static final int CORNERS = Perspective.SCENE_SIZE + 1;

	/**
	 * A client that answers only what Perspective's projection asks for.
	 *
	 * Client has hundreds of methods and there is no mocking library in this
	 * build, so a proxy answering eleven of them and defaulting the rest is far
	 * less code than a hand-written stub.
	 */
	private static Client fakeClient()
	{
		InvocationHandler h = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "isGpu":
					return false;
				// Above the parcels under test and to the south, looking down.
				case "getCameraX":
					return 1024;
				case "getCameraY":
					return -2400;
				case "getCameraZ":
					return -1600;
				case "getCameraPitch":
					return 380;
				case "getCameraYaw":
					return 0;
				case "getScale":
					return 512;
				case "getViewportWidth":
					return 800;
				case "getViewportHeight":
					return 600;
				case "getViewportXOffset":
				case "getViewportYOffset":
					return 0;
				default:
					return defaultOf(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, h);
	}

	private static Object defaultOf(Class<?> t)
	{
		if (!t.isPrimitive())
		{
			return null;
		}
		if (t == boolean.class)
		{
			return false;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t == double.class)
		{
			return 0d;
		}
		if (t == float.class)
		{
			return 0f;
		}
		return 0;
	}

	/**
	 * Ground that rolls in both directions, steeply enough that a chord across
	 * eight tiles misses it by more than a pixel. Flat ground would pass this
	 * test with the old code and prove nothing.
	 */
	private static int[][][] hills()
	{
		int[][][] heights = new int[4][CORNERS][CORNERS];
		for (int x = 0; x < CORNERS; x++)
		{
			for (int y = 0; y < CORNERS; y++)
			{
				// Negative is up in the client's coordinates.
				heights[PLANE][x][y] = (int) Math.round(
					-360 * Math.sin(x * 0.34) - 280 * Math.cos(y * 0.27));
			}
		}
		return heights;
	}

	/** A VeilOverlay wired to the fake client, mid-frame. */
	private static VeilOverlay armed(Client client) throws Exception
	{
		Constructor<VeilOverlay> ctor = VeilOverlay.class.getDeclaredConstructor(
			Client.class, GielinorDeedsPlugin.class, GielinorDeedsConfig.class);
		ctor.setAccessible(true);
		VeilOverlay overlay = ctor.newInstance(client, null, null);

		set(overlay, "heights", hills());
		set(overlay, "frame", 1);
		set(overlay, "anchorX", 1024);
		set(overlay, "anchorY", 1024);
		return overlay;
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		java.lang.reflect.Field f = VeilOverlay.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Polygon parcelPoly(VeilOverlay overlay, int wx, int wy) throws Exception
	{
		Method m = VeilOverlay.class.getDeclaredMethod("parcelPoly",
			int.class, int.class, int.class, int.class, int.class);
		m.setAccessible(true);
		return (Polygon) m.invoke(overlay, wx, wy, PLANE, BASE_X, BASE_Y);
	}

	/** Screen position of one scene tile corner, as the overlay computes it. */
	private static double[] corner(VeilOverlay overlay, int sx, int sy) throws Exception
	{
		Method m = VeilOverlay.class.getDeclaredMethod("corner",
			int.class, int.class, int.class, int[].class);
		m.setAccessible(true);
		int[] xy = new int[2];
		boolean ok = (Boolean) m.invoke(overlay, sx, sy, PLANE, xy);
		return ok ? new double[]{xy[0], xy[1]} : null;
	}

	private static Path2D.Float veilOf(Polygon... parts) throws Exception
	{
		Method m = VeilOverlay.class.getDeclaredMethod("appendWound",
			Path2D.Float.class, Polygon.class);
		m.setAccessible(true);
		Path2D.Float veil = new Path2D.Float(Path2D.WIND_NON_ZERO);
		for (Polygon p : parts)
		{
			m.invoke(null, veil, p);
		}
		return veil;
	}

	/**
	 * Every tile corner along the boundary, and every midpoint between them.
	 *
	 * The corners alone are not enough: two quads can meet at their endpoints
	 * and still bow apart everywhere in between, which is exactly what a
	 * straight line across a hill does.
	 */
	private static java.util.List<double[]> seamSamples(VeilOverlay overlay,
		int sx, int sy, int dx, int dy) throws Exception
	{
		java.util.List<double[]> pts = new java.util.ArrayList<>();
		double[] prev = null;
		for (int i = 0; i <= CELL; i++)
		{
			double[] p = corner(overlay, sx + dx * i, sy + dy * i);
			assertNotNull("seam corner " + i + " did not project -- check the "
				+ "fake camera, not the veil", p);
			if (prev != null)
			{
				pts.add(new double[]{(prev[0] + p[0]) / 2, (prev[1] + p[1]) / 2});
			}
			pts.add(p);
			prev = p;
		}
		return pts;
	}

	@Test
	public void theOutlineFollowsEveryTileCornerOfTheParcel() throws Exception
	{
		VeilOverlay overlay = armed(fakeClient());
		Polygon p = parcelPoly(overlay, BASE_X + 16, BASE_Y + 16);

		assertNotNull("a parcel well inside the scene must trace", p);
		assertEquals("the outline must have a point per tile corner around the "
			+ "parcel, not four corners with straight lines between them",
			4 * CELL, p.npoints);
	}

	@Test
	public void neighbouringParcelsLeaveNoGapEastToWest() throws Exception
	{
		VeilOverlay overlay = armed(fakeClient());
		int wx = BASE_X + 16, wy = BASE_Y + 16;

		Polygon west = parcelPoly(overlay, wx, wy);
		Polygon east = parcelPoly(overlay, wx + CELL, wy);
		assertNotNull(west);
		assertNotNull(east);

		Path2D.Float veil = veilOf(west, east);
		// The boundary runs north from the corner the two parcels share.
		int sx = wx - BASE_X + CELL, sy = wy - BASE_Y;
		for (double[] pt : seamSamples(overlay, sx, sy, 0, 1))
		{
			assertTrue("untinted ground on the deed boundary at "
				+ (int) pt[0] + "," + (int) pt[1],
				veil.contains(pt[0], pt[1]));
		}
	}

	@Test
	public void neighbouringParcelsLeaveNoGapSouthToNorth() throws Exception
	{
		VeilOverlay overlay = armed(fakeClient());
		int wx = BASE_X + 16, wy = BASE_Y + 16;

		Polygon south = parcelPoly(overlay, wx, wy);
		Polygon north = parcelPoly(overlay, wx, wy + CELL);
		assertNotNull(south);
		assertNotNull(north);

		Path2D.Float veil = veilOf(south, north);
		int sx = wx - BASE_X, sy = wy - BASE_Y + CELL;
		for (double[] pt : seamSamples(overlay, sx, sy, 1, 0))
		{
			assertTrue("untinted ground on the deed boundary at "
				+ (int) pt[0] + "," + (int) pt[1],
				veil.contains(pt[0], pt[1]));
		}
	}

	/**
	 * The old shape, on the same ground, fails the same check.
	 *
	 * Without this the seam tests would still pass if the veil stopped drawing
	 * anything at all in some future edit -- contains() on an empty path is
	 * false, so they would fail loudly, but nothing would show that the samples
	 * are demanding enough to catch a real seam. This does.
	 */
	@Test
	public void aFlatQuadWouldLeaveTheSeamOpen() throws Exception
	{
		VeilOverlay overlay = armed(fakeClient());
		int wx = BASE_X + 16, wy = BASE_Y + 16;
		int sx = wx - BASE_X, sy = wy - BASE_Y;

		Path2D.Float flat = veilOf(quad(overlay, sx, sy), quad(overlay, sx + CELL, sy));

		boolean anyOpen = false;
		for (double[] pt : seamSamples(overlay, sx + CELL, sy, 0, 1))
		{
			anyOpen |= !flat.contains(pt[0], pt[1]);
		}
		assertTrue("a four-corner quad is supposed to miss the ground between "
			+ "its corners -- if it no longer does, this terrain is too flat "
			+ "for the seam tests to mean anything", anyOpen);
	}

	/** The old shape: the parcel's four outer corners, joined by straight lines. */
	private static Polygon quad(VeilOverlay overlay, int sx, int sy) throws Exception
	{
		int[][] at = {{sx, sy}, {sx + CELL, sy}, {sx + CELL, sy + CELL}, {sx, sy + CELL}};
		Polygon p = new Polygon();
		for (int[] c : at)
		{
			double[] pt = corner(overlay, c[0], c[1]);
			assertNotNull(pt);
			p.addPoint((int) pt[0], (int) pt[1]);
		}
		return p;
	}
}
