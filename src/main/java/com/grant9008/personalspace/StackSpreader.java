package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure layout logic: given who is standing on which tile, decide who gets nudged and by how much.
 * Knows nothing about RuneLite so it can be unit tested on its own.
 *
 * <p>Players on the same tile are sorted by id, so a player keeps the same spot tick after tick
 * while the group is unchanged.
 *
 * <p>Two arrangements:
 * <ul>
 * <li><b>Line</b>: shoulder to shoulder, across the direction the group is facing. Right when
 * everyone faces the same thing (an anvil, bank booth, range, furnace or fire), because nobody is
 * put inside the thing they're using.</li>
 * <li><b>Ring</b>: evenly around the tile. Right for a crowd facing every which way.</li>
 * </ul>
 * {@link Layout#AUTO} picks the line when the group faces the same way, else the ring.
 */
final class StackSpreader
{
	enum Layout
	{
		AUTO,
		RING,
		LINE
	}

	/** Orientation units in a full turn, as used by the game. 0 faces south, 512 west, 1024 north, 1536 east. */
	static final int FULL_TURN = 2048;

	/** How closely a group's facings must agree to count as "facing the same way" (about 25 degrees either side). */
	static final double SAME_FACING = 0.9;

	/** Furthest a player is placed from the tile centre along a line, in local units. A tile is 128, so this stays inside. */
	static final int MAX_LINE_EXTENT = 56;

	/** One standing-still player on a tile. */
	static final class Entry
	{
		final int id;
		final long tile;
		final boolean local;
		/** Facing in game orientation units, or -1 if unknown. */
		final int orientation;

		Entry(int id, long tile, boolean local)
		{
			this(id, tile, local, -1);
		}

		Entry(int id, long tile, boolean local, int orientation)
		{
			this.id = id;
			this.tile = tile;
			this.local = local;
			this.orientation = orientation;
		}
	}

	/** Where one player should be drawn relative to their real spot, in local units (x east, z north). */
	static final class Placement
	{
		final int id;
		final long tile;
		final int dx;
		final int dz;

		Placement(int id, long tile, int dx, int dz)
		{
			this.id = id;
			this.tile = tile;
			this.dx = dx;
			this.dz = dz;
		}
	}

	private StackSpreader()
	{
	}

	/** Ring layout for every tile; kept for callers and tests that don't care about facing. */
	static List<Placement> place(List<Entry> entries, boolean includeLocal, int maxStack, int radius)
	{
		return place(entries, includeLocal, maxStack, radius, Layout.RING);
	}

	/**
	 * @param entries      every standing-still player and the tile they stand on
	 * @param includeLocal whether the local player takes a spot too, or stays put in the middle
	 * @param maxStack     spread at most this many players per tile; the rest stay centred
	 * @param spacing      ring radius, or the gap between neighbours in a line, in local units
	 * @param layout       arrangement to use
	 * @return one placement per player that should move; anyone not listed stays where they are
	 */
	static List<Placement> place(List<Entry> entries, boolean includeLocal, int maxStack, int spacing, Layout layout)
	{
		Map<Long, List<Entry>> byTile = new LinkedHashMap<>();
		for (Entry e : entries)
		{
			byTile.computeIfAbsent(e.tile, k -> new ArrayList<>(4)).add(e);
		}

		List<Placement> out = new ArrayList<>();
		for (List<Entry> group : byTile.values())
		{
			if (group.size() < 2)
			{
				continue; // nobody to be stacked with
			}
			group.sort(Comparator.comparingInt(e -> e.id));

			List<Entry> movable = new ArrayList<>(group.size());
			for (Entry e : group)
			{
				if (includeLocal || !e.local)
				{
					movable.add(e);
				}
			}
			int n = Math.min(movable.size(), maxStack);
			if (n == 0)
			{
				continue;
			}
			// Someone stays in the middle (you, when "move my character" is off, or anyone past the cap).
			boolean centreTaken = movable.size() < group.size() || n < movable.size();

			Double facing = layout == Layout.RING ? null : sharedFacing(group);
			boolean line = layout == Layout.LINE || (layout == Layout.AUTO && facing != null);
			double angle = facing != null ? facing : 0.0;

			for (int i = 0; i < n; i++)
			{
				int[] off = line
					? lineOffset(i, n, centreTaken, spacing, angle)
					: ringOffset(i, n, spacing);
				Entry e = movable.get(i);
				out.add(new Placement(e.id, e.tile, off[0], off[1]));
			}
		}
		return out;
	}

	/** How many tiles have two or more of the given players on them. */
	static int stackedTiles(List<Entry> entries)
	{
		Map<Long, Integer> counts = new LinkedHashMap<>();
		int stacked = 0;
		for (Entry e : entries)
		{
			if (counts.merge(e.tile, 1, Integer::sum) == 2)
			{
				stacked++;
			}
		}
		return stacked;
	}

	/**
	 * The direction the whole group faces, in radians (game convention: 0 south, pi/2 west), or
	 * null if their facings are unknown or don't agree closely enough.
	 */
	static Double sharedFacing(List<Entry> group)
	{
		double sumX = 0;
		double sumZ = 0;
		for (Entry e : group)
		{
			if (e.orientation < 0)
			{
				return null;
			}
			double a = toRadians(e.orientation);
			sumX += -Math.sin(a);
			sumZ += -Math.cos(a);
		}
		double agreement = Math.hypot(sumX, sumZ) / group.size();
		if (agreement < SAME_FACING)
		{
			return null;
		}
		return Math.atan2(-sumX, -sumZ);
	}

	static double toRadians(int orientation)
	{
		return 2 * Math.PI * (((orientation % FULL_TURN) + FULL_TURN) % FULL_TURN) / FULL_TURN;
	}

	/**
	 * Slot {@code slot} of {@code count} in a line across the facing direction {@code angle}.
	 * With the centre free the line is centred on the tile; with the centre taken the players fill
	 * the spots either side of it, nearest first.
	 */
	static int[] lineOffset(int slot, int count, boolean centreTaken, int spacing, double angle)
	{
		double step;
		double gap;
		if (centreTaken)
		{
			int side = slot / 2 + 1;
			step = slot % 2 == 0 ? side : -side;
			int furthest = (count + 1) / 2;
			gap = Math.min(spacing, (double) MAX_LINE_EXTENT / furthest);
		}
		else
		{
			double furthest = (count - 1) / 2.0;
			step = slot - furthest;
			gap = furthest == 0 ? 0 : Math.min(spacing, MAX_LINE_EXTENT / furthest);
		}
		// Across the facing: facing south (angle 0) gives an east-west line.
		double acrossX = Math.cos(angle);
		double acrossZ = -Math.sin(angle);
		return new int[]{
			(int) Math.round(step * gap * acrossX),
			(int) Math.round(step * gap * acrossZ)
		};
	}

	/**
	 * Slot {@code slot} of {@code count} on a ring of the given radius.
	 * Slot 0 is due east and the rest go round anticlockwise, so two players end up
	 * side by side (east/west), which reads best from the default north-facing camera.
	 */
	static int[] ringOffset(int slot, int count, int radius)
	{
		if (count <= 0)
		{
			return new int[]{0, 0};
		}
		double angle = 2 * Math.PI * slot / count;
		return new int[]{
			(int) Math.round(radius * Math.cos(angle)),
			(int) Math.round(radius * Math.sin(angle))
		};
	}
}
