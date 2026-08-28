/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * The static survey of Gielinor, loaded once from parcels.bin.
 *
 * Every parcel's tier and price was decided offline by build_grid.py against
 * the cache-derived LULC raster, so this class never talks to the network and
 * never guesses: a WorldPoint either falls inside the surveyed bounds and has
 * a definite tier, or it does not and is Unsurveyed. There is no "pending"
 * state to render, which is the single biggest simplification over the
 * Earth-based original -- Gielinor is finite, so the whole world ships in
 * 345 KB of resource.
 */
@Slf4j
public final class ParcelGrid
{
	private static final byte[] MAGIC = {'G', 'D', 'P', '1'};

	@Getter private final int cols;
	@Getter private final int rows;
	@Getter private final int cell;
	@Getter private final int minX;
	@Getter private final int maxY;

	/** cols*rows records of {tier u8, price u16 LE}. */
	private final byte[] data;
	/** parcel index -> the famous place standing on it. Built once at load. */
	private final Map<Integer, Landmark> landmarks = new HashMap<>();

	private ParcelGrid(int cols, int rows, int cell, int minX, int maxY, byte[] data)
	{
		this.cols = cols;
		this.rows = rows;
		this.cell = cell;
		this.minX = minX;
		this.maxY = maxY;
		this.data = data;
		// Done here rather than by the caller: a grid that has not indexed its
		// landmarks looks perfectly healthy and silently prices every famous
		// place as ordinary ground.
		indexLandmarks();
	}

	public static ParcelGrid load() throws IOException
	{
		try (InputStream in = ParcelGrid.class.getResourceAsStream("parcels.bin"))
		{
			if (in == null)
			{
				throw new IOException("parcels.bin missing from the jar");
			}
			DataInputStream dis = new DataInputStream(in);
			byte[] magic = new byte[4];
			dis.readFully(magic);
			for (int i = 0; i < 4; i++)
			{
				if (magic[i] != MAGIC[i])
				{
					throw new IOException("parcels.bin has a bad magic header");
				}
			}
			byte[] head = new byte[9];
			dis.readFully(head);
			ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
			int cols = hb.getShort() & 0xFFFF;
			int rows = hb.getShort() & 0xFFFF;
			int cell = hb.get() & 0xFF;
			int minX = hb.getShort() & 0xFFFF;
			int maxY = hb.getShort() & 0xFFFF;

			byte[] data = new byte[cols * rows * 3];
			dis.readFully(data);
			log.debug("loaded {}x{} parcel grid, {} game tiles per parcel", cols, rows, cell);
			return new ParcelGrid(cols, rows, cell, minX, maxY, data);
		}
	}

	/**
	 * Resolve every landmark to its parcel index once, so lookups during
	 * rendering are a hash hit rather than a scan of the table.
	 */
	private void indexLandmarks()
	{
		for (Landmark lm : Landmark.values())
		{
			int px = (lm.getX() - minX) / cell;
			int py = (maxY - 1 - lm.getY()) / cell;
			if (px >= 0 && px < cols && py >= 0 && py < rows)
			{
				landmarks.put(py * cols + px, lm);
			}
			else
			{
				log.warn("landmark {} is outside the surveyed grid", lm.getDisplayName());
			}
		}
	}

	/** How many landmarks the survey could place. Should be all of them. */
	public int landmarkCount()
	{
		return landmarks.size();
	}

	/**
	 * Parcel column/row for a world point, or -1 if it falls outside the
	 * surveyed bounds. The grid is north-up (py 0 is the northernmost row,
	 * matching build_grid.py's raster order), which is why y is mirrored
	 * through maxY rather than measured up from minY.
	 */
	public int pxOf(WorldPoint wp)
	{
		int px = (wp.getX() - minX) / cell;
		return (px < 0 || px >= cols) ? -1 : px;
	}

	public int pyOf(WorldPoint wp)
	{
		int py = (maxY - 1 - wp.getY()) / cell;
		return (py < 0 || py >= rows) ? -1 : py;
	}

	/** Only plane 0 was surveyed; upper floors belong to the deed below them. */
	public boolean isSurveyable(WorldPoint wp)
	{
		return wp != null && wp.getPlane() == 0 && pxOf(wp) >= 0 && pyOf(wp) >= 0;
	}

	/**
	 * The ground a point stands over: the same place, on plane 0.
	 *
	 * A deed is a piece of ground and the floors above it belong to it, so
	 * callers asking which deed a point is on put it through here first.
	 */
	@Nullable
	public static WorldPoint groundOf(@Nullable WorldPoint wp)
	{
		return wp == null || wp.getPlane() == 0 ? wp
			: new WorldPoint(wp.getX(), wp.getY(), 0);
	}

	@Nullable
	public Parcel at(WorldPoint wp)
	{
		return at(pxOf(wp), pyOf(wp));
	}

	/**
	 * Parcel by grid position. Returns null rather than throwing on an
	 * out-of-range index: pids arrive from the server, and a server that has
	 * been reseeded from a differently-sized grid should degrade to "I cannot
	 * draw that one" instead of taking the overlay down mid-frame.
	 */
	@Nullable
	public Parcel at(int px, int py)
	{
		if (px < 0 || px >= cols || py < 0 || py >= rows)
		{
			return null;
		}
		int idx = py * cols + px;
		int off = idx * 3;
		int tier = data[off] & 0xFF;
		int price = (data[off + 1] & 0xFF) | ((data[off + 2] & 0xFF) << 8);
		Landmark lm = landmarks.get(idx);
		return new Parcel(px + "_" + py, px, py, Tier.byCode(tier),
			lm != null ? Landmark.PRICE : price, southWestOf(px, py), lm);
	}

	/**
	 * Flat index of a parcel, matching parcels.bin's own record order.
	 * This is the bit position used by the surveyed-set bitset in Estate, so it
	 * must stay stable: regenerating the survey at a different cell size
	 * invalidates every saved estate.
	 */
	public int indexOf(int px, int py)
	{
		return (px < 0 || px >= cols || py < 0 || py >= rows) ? -1 : py * cols + px;
	}

	public int indexOf(Parcel p)
	{
		return p == null ? -1 : indexOf(p.getPx(), p.getPy());
	}

	/** Parcel at a flat index, the inverse of {@link #indexOf(int, int)}. */
	@Nullable
	public Parcel atIndex(int index)
	{
		return (index < 0 || index >= cols * rows) ? null
			: at(index % cols, index / cols);
	}

	/** Total parcels in the grid -- the size of the surveyed bitset. */
	public int size()
	{
		return cols * rows;
	}

	/** Parcel by its "px_py" id. */
	@Nullable
	public Parcel byPid(String pid)
	{
		if (pid == null)
		{
			return null;
		}
		int sep = pid.indexOf('_');
		if (sep <= 0 || sep == pid.length() - 1)
		{
			return null;
		}
		try
		{
			return at(Integer.parseInt(pid.substring(0, sep)),
				Integer.parseInt(pid.substring(sep + 1)));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** South-west corner of a parcel, in game coords -- the overlay's anchor. */
	public WorldPoint southWestOf(int px, int py)
	{
		return new WorldPoint(minX + px * cell, maxY - (py + 1) * cell, 0);
	}
}
