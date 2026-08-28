/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Tints ground the estate has not surveyed, for Deed Locked runs.
 *
 * <h2>Drawn per parcel</h2>
 *
 * Granularity is the whole cost story here. Measured on a 1400x1000 canvas,
 * one frame:
 *
 * <pre>
 *   8,000 tile quads            build  3.4 ms   fill  42.7 ms
 *   169 parcel quads            build  0.3 ms   fill  15.2 ms
 *   one full-canvas rectangle   build  0.0 ms   fill   6.9 ms
 * </pre>
 *
 * At 50 fps the whole frame budget is 20 ms, so per-tile can never fit.
 * Surveying is per parcel, so a parcel is the smallest shape the veil needs.
 * When nothing in view is surveyed it fills the canvas instead.
 *
 * The veil sits ABOVE_SCENE, so this hides ground without stopping anyone
 * walking onto it. Enforcement in this mode is on the player's honour.
 */
public class VeilOverlay extends Overlay
{
	/** Scene tiles a parcel spans. Matches ParcelGrid's cell. */
	private static final int CELL = 8;
	/** Pixels each parcel outline is grown by, to close seams. See bleed. */
	private static final int SEAM_BLEED = 2;
	/**
	 * Tile corners along one side of the scene. A corner sits between tiles, so
	 * a 104-tile scene has 105 of them on each axis.
	 */
	private static final int CORNERS = Perspective.SCENE_SIZE + 1;
	/** A corner that could not be put on the screen at all. */
	private static final int UNPROJECTED = Integer.MIN_VALUE;
	/** Halvings used to walk a corner back in front of the near plane. */
	private static final int CLIP_STEPS = 10;
	/**
	 * How far off the canvas a clipped corner is allowed to land.
	 *
	 * A point just in front of the near plane projects to an enormous
	 * coordinate, because the projection divides by a depth close to zero. A
	 * point a few screens out already covers everything visible, so the walk
	 * keeps going until it is inside this box rather than stopping at the first
	 * position that projects at all.
	 */
	private static final int CLIP_LIMIT = 30000;

	private final Client client;
	private final GielinorDeedsPlugin plugin;
	private final GielinorDeedsConfig config;

	/**
	 * Where each tile corner of the scene landed on screen this frame.
	 *
	 * Two parcels either side of a boundary have to run their outlines through
	 * exactly the same points, or the seam between them shows as a line of
	 * untinted ground. Projecting each corner once and handing both parcels the
	 * same answer makes that true by construction, rather than by both sides
	 * doing the same arithmetic and trusting it to agree. It also halves the
	 * work, since every interior corner would otherwise be projected twice.
	 */
	private final int[] cornerX = new int[CORNERS * CORNERS];
	private final int[] cornerY = new int[CORNERS * CORNERS];
	private final int[] cornerFrame = new int[CORNERS * CORNERS];
	/** Bumped once per render, so last frame's corners read as stale. */
	private int frame;
	/** The scene's corner heights for this frame, indexed [plane][x][y]. */
	private int[][][] heights;
	/** Local point a corner behind the near plane is walked toward. */
	private int anchorX;
	private int anchorY;

	@Inject
	private VeilOverlay(Client client, GielinorDeedsPlugin plugin, GielinorDeedsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/**
	 * Append a polygon with a known winding direction.
	 *
	 * Everything the veil draws goes into one Path2D filled with NON_ZERO
	 * winding, and under that rule two overlapping shapes wound in opposite
	 * directions cancel: the overlap comes out at alpha 0, a hole. Measured,
	 * not guessed -- a quad drawn clockwise over one drawn anticlockwise leaves
	 * transparent ground where they meet.
	 *
	 * The ground quads are sorted by angle so they always agree, but a curtain
	 * quad is built from a fixed vertex order whose orientation on screen flips
	 * with the camera. A curtain a thousand units tall covers a great deal of
	 * screen, so once it disagreed it erased most of the veil behind it -- the
	 * veil looked like it had simply stopped working.
	 *
	 * So every shape is normalised here rather than trusting how it was built.
	 */
	private static void appendWound(Path2D.Float into, Polygon p)
	{
		double twiceArea = 0;
		for (int i = 0, n = p.npoints; i < n; i++)
		{
			int j = (i + 1) % n;
			twiceArea += (double) p.xpoints[i] * p.ypoints[j]
				- (double) p.xpoints[j] * p.ypoints[i];
		}
		if (twiceArea >= 0)
		{
			into.append(p, false);
			return;
		}
		Polygon flipped = new Polygon();
		for (int i = p.npoints - 1; i >= 0; i--)
		{
			flipped.addPoint(p.xpoints[i], p.ypoints[i]);
		}
		into.append(flipped, false);
	}

	/**
	 * Ground height at a tile corner, read straight out of the scene.
	 *
	 * Corner heights are what the client builds terrain from, so reading one is
	 * exact -- no interpolation, and no dependence on which tile the corner was
	 * asked about from. That is what lets two parcels agree on a shared edge.
	 */
	private int cornerHeight(int plane, int sx, int sy)
	{
		if (heights == null || plane < 0 || plane >= heights.length)
		{
			return 0;
		}
		int[][] level = heights[plane];
		if (level == null || level.length == 0)
		{
			return 0;                     // scene not loaded yet
		}
		int x = Math.max(0, Math.min(sx, level.length - 1));
		if (level[x] == null || level[x].length == 0)
		{
			return 0;
		}
		int y = Math.max(0, Math.min(sy, level[x].length - 1));
		return level[x][y];
	}

	/**
	 * Project a local point at ground height, or null if it is unusable.
	 *
	 * Unusable means behind the camera's near plane, or close enough to it that
	 * the projection throws the point most of the way to infinity.
	 *
	 * Uses the raw three-argument localToCanvas, which unlike
	 * getCanvasTilePoly accepts points outside the viewport, so a parcel
	 * hanging off the edge of the screen is still drawn.
	 */
	@Nullable
	private Point project(int lx, int ly, int plane)
	{
		int h = cornerHeight(plane, lx >> Perspective.LOCAL_COORD_BITS,
			ly >> Perspective.LOCAL_COORD_BITS);
		Point p = Perspective.localToCanvas(client, lx, ly, h);
		if (p == null || Math.abs(p.getX()) > CLIP_LIMIT || Math.abs(p.getY()) > CLIP_LIMIT)
		{
			return null;
		}
		return p;
	}

	/**
	 * Walk a corner toward the frame's anchor until it can be projected.
	 *
	 * A corner behind the camera has no screen position, and dropping the parcel
	 * that owned one is what left untinted ground along the bottom of the screen
	 * whenever the camera came down. Moving the corner forward until it clears
	 * the near plane keeps the parcel, and because every parcel in the frame
	 * walks toward the same anchor, two parcels sharing a clipped corner still
	 * end up in the same place.
	 */
	@Nullable
	private Point clipToNearPlane(int lx, int ly, int plane)
	{
		int lo = 0, hi = 1 << CLIP_STEPS;
		Point best = null;
		for (int i = 0; i < CLIP_STEPS; i++)
		{
			int mid = (lo + hi) >>> 1;
			int mx = lx + (int) (((long) (anchorX - lx) * mid) >> CLIP_STEPS);
			int my = ly + (int) (((long) (anchorY - ly) * mid) >> CLIP_STEPS);
			Point p = project(mx, my, plane);
			if (p == null)
			{
				lo = mid;
			}
			else
			{
				hi = mid;
				best = p;
			}
		}
		return best;
	}

	/**
	 * Screen position of one tile corner of the scene, cached for the frame.
	 *
	 * Writes the answer into {@code xy} and returns whether there was one.
	 */
	private boolean corner(int sx, int sy, int plane, int[] xy)
	{
		int idx = sy * CORNERS + sx;
		if (cornerFrame[idx] != frame)
		{
			cornerFrame[idx] = frame;
			int lx = sx << Perspective.LOCAL_COORD_BITS;
			int ly = sy << Perspective.LOCAL_COORD_BITS;
			Point p = project(lx, ly, plane);
			if (p == null)
			{
				p = clipToNearPlane(lx, ly, plane);
			}
			cornerX[idx] = p == null ? UNPROJECTED : p.getX();
			cornerY[idx] = p == null ? UNPROJECTED : p.getY();
		}
		if (cornerX[idx] == UNPROJECTED)
		{
			return false;
		}
		xy[0] = cornerX[idx];
		xy[1] = cornerY[idx];
		return true;
	}

	/**
	 * Grow a polygon outward from its own middle by a few pixels.
	 *
	 * Shared corners already agree to the pixel, but the rasteriser still has to
	 * decide which side of a boundary line a pixel centre falls on, and a
	 * hairline of untinted ground shows wherever it decides against both
	 * parcels. Overlap costs nothing here: the whole veil is one Path2D filled
	 * once, so alpha lands on a pixel a single time however many shapes cover
	 * it. Separate fills would have darkened the seams instead of hiding them.
	 */
	private static Polygon bleed(Polygon p)
	{
		double cx = 0, cy = 0;
		for (int i = 0; i < p.npoints; i++)
		{
			cx += p.xpoints[i];
			cy += p.ypoints[i];
		}
		cx /= p.npoints;
		cy /= p.npoints;

		Polygon out = new Polygon();
		for (int i = 0; i < p.npoints; i++)
		{
			double dx = p.xpoints[i] - cx, dy = p.ypoints[i] - cy;
			double len = Math.hypot(dx, dy);
			if (len < 1e-6)
			{
				out.addPoint(p.xpoints[i], p.ypoints[i]);
				continue;
			}
			out.addPoint((int) Math.round(p.xpoints[i] + dx / len * SEAM_BLEED),
				(int) Math.round(p.ypoints[i] + dy / len * SEAM_BLEED));
		}
		return out;
	}

	/**
	 * The screen outline of one 8x8 parcel, traced along the ground.
	 *
	 * Walks the perimeter one point per tile corner rather than joining four
	 * corners with straight lines, for two reasons. A straight line between
	 * corners eight tiles apart cuts across whatever the ground does in
	 * between, and picking a corner by screen distance chooses differently on a
	 * slope, so two parcels either side of a boundary disagreed about the
	 * corner they share and left a wedge of untinted ground.
	 *
	 * Every point comes from {@link #corner}, addressed by its position in the
	 * scene, so the parcel next door is handed the identical run of points
	 * along the edge they share.
	 */
	@Nullable
	private Polygon parcelPoly(int wx, int wy, int plane, int baseX, int baseY)
	{
		int sx = wx - baseX, sy = wy - baseY;
		if (sx < 0 || sy < 0 || sx + CELL >= CORNERS || sy + CELL >= CORNERS)
		{
			return null;                  // outside the scene: no ground to trace
		}

		Polygon out = new Polygon();
		int[] xy = new int[2];
		// Anticlockwise in world coordinates -- south, east, north, west. Each
		// side stops one short of its far corner, which the next side starts on.
		for (int i = 0; i < CELL; i++)
		{
			if (corner(sx + i, sy, plane, xy))
			{
				out.addPoint(xy[0], xy[1]);
			}
		}
		for (int i = 0; i < CELL; i++)
		{
			if (corner(sx + CELL, sy + i, plane, xy))
			{
				out.addPoint(xy[0], xy[1]);
			}
		}
		for (int i = CELL; i > 0; i--)
		{
			if (corner(sx + i, sy + CELL, plane, xy))
			{
				out.addPoint(xy[0], xy[1]);
			}
		}
		for (int i = CELL; i > 0; i--)
		{
			if (corner(sx, sy + i, plane, xy))
			{
				out.addPoint(xy[0], xy[1]);
			}
		}
		return out.npoints < 3 ? null : bleed(out);
	}

	/**
	 * Fall back to tracing a parcel one tile at a time.
	 *
	 * parcelPoly needs a closed run of corners around the parcel's edge, and a
	 * parcel most of which sits behind the camera cannot always give one. Only a
	 * rim of parcels ever lands here: the ones fully on screen take the cheap
	 * path, and the ones fully off it contribute nothing.
	 *
	 * The tiles are traced from the same shared corners as the outline, so a
	 * parcel drawn this way still meets its neighbours exactly.
	 */
	private boolean appendParcelTiles(Path2D.Float into, int wx, int wy, int plane,
		int baseX, int baseY, Rectangle canvas)
	{
		int sx = wx - baseX, sy = wy - baseY;
		boolean any = false;
		int[] xy = new int[2];
		for (int dx = 0; dx < CELL; dx++)
		{
			for (int dy = 0; dy < CELL; dy++)
			{
				int tx = sx + dx, ty = sy + dy;
				if (tx < 0 || ty < 0 || tx + 1 >= CORNERS || ty + 1 >= CORNERS)
				{
					continue;
				}
				int[][] around = {{tx, ty}, {tx + 1, ty},
					{tx + 1, ty + 1}, {tx, ty + 1}};
				Polygon tile = new Polygon();
				for (int[] c : around)
				{
					if (corner(c[0], c[1], plane, xy))
					{
						tile.addPoint(xy[0], xy[1]);
					}
				}
				if (tile.npoints < 4 || !tile.getBounds().intersects(canvas))
				{
					continue;
				}
				appendWound(into, bleed(tile));
				any = true;
			}
		}
		return any;
	}


	@Override
	public Dimension render(Graphics2D g)
	{
		ParcelGrid grid = plugin.getGrid();
		if (!config.deedLocked() || !config.veilUnsurveyed() || grid == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		// The colour carries its own alpha, so a tint and a blackout are the
		// same drawing with different settings.
		Color ink = config.veilStyle() == VeilStyle.BLACKOUT
			? new Color(0, 0, 0, 255) : config.veilColor();
		Rectangle canvas = g.getClipBounds() != null ? g.getClipBounds()
			: new Rectangle(client.getCanvasWidth(), client.getCanvasHeight());

		// Walk the scene a parcel at a time. The scene is 104 tiles square, so
		// this is about 169 steps rather than 10,816.
		int baseX = wv.getBaseX(), baseY = wv.getBaseY();
		int plane = wv.getPlane();
		int span = Perspective.SCENE_SIZE;

		// Corner projections are cached for the frame, so last frame's are
		// marked stale here and the height map is taken once rather than per
		// parcel. The anchor is where a corner behind the camera gets walked to;
		// it has to be the same for every parcel, or two of them sharing a
		// clipped corner disagree about where it went and the seam opens again.
		frame++;
		heights = wv.getTileHeights();
		Player me = client.getLocalPlayer();
		LocalPoint anchor = me == null ? null : me.getLocalLocation();
		int middle = Perspective.LOCAL_TILE_SIZE * Perspective.SCENE_SIZE / 2;
		anchorX = anchor == null ? middle : anchor.getX();
		anchorY = anchor == null ? middle : anchor.getY();

		Path2D.Float veil = new Path2D.Float(Path2D.WIND_NON_ZERO);
		boolean anyVeiled = false;
		// Ground in view that the veil is not covering -- yours, or somewhere
		// the survey never reached. Either way there is something to see, so
		// the whole-canvas shortcut below must not fire.
		boolean anyClearInView = false;

		int startX = baseX - Math.floorMod(baseX, CELL);
		int startY = baseY - Math.floorMod(baseY, CELL);

		// Skipped entirely when the draw-callback veil is installed: that hides
		// the ground outright, so painting over the hole would only cost fill.
		boolean paintGround = plugin.paintGroundVeil();
		for (int wx = startX; paintGround && wx < baseX + span; wx += CELL)
		{
			for (int wy = startY; wy < baseY + span; wy += CELL)
			{
				WorldPoint sw = new WorldPoint(wx, wy, plane);
				boolean veiled = plugin.isVeiledGround(sw);

				Polygon poly = parcelPoly(wx, wy, plane, baseX, baseY);
				if (poly != null && !poly.getBounds().intersects(canvas))
				{
					continue;             // off screen, and off the cost sheet
				}
				if (!veiled)
				{
					if (poly != null)
					{
						anyClearInView = true;
					}
					continue;
				}
				if (poly != null)
				{
					appendWound(veil, poly);
					anyVeiled = true;
				}
				else if (appendParcelTiles(veil, wx, wy, plane, baseX, baseY, canvas))
				{
					// The quad could not be built, so this parcel is drawn a
					// tile at a time instead. See appendParcelTiles.
					anyVeiled = true;
				}
			}
		}

		if (paintGround && anyVeiled && !anyClearInView)
		{
			// Everything on screen is veiled, so the shape does not matter
			// and one canvas fill is the cheapest correct answer. Conditional
			// on something actually being veiled, or a cave and an upper floor
			// would both be painted edge to edge.
			g.setColor(ink);
			g.fill(canvas);
			return null;
		}
		if (!anyVeiled)
		{
			return null;
		}
		g.setColor(ink);
		g.fill(veil);
		return null;
	}
}
