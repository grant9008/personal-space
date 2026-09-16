package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure layout logic: given who is standing on which tile, decide who gets nudged and by how much.
 * Knows nothing about RuneLite so it can be unit tested on its own.
 *
 * <p>Players on the same tile are sorted by id, so a player keeps the same spot tick after tick
 * while the group is unchanged.
 *
 * <p>Two arrangements:
 * <ul>
 * <li><b>Row</b>: shoulder to shoulder in a curve around the thing the group is facing. Right when
 * everyone faces the same thing (an anvil, bank booth, range, furnace or fire): nobody is put inside
 * it, nobody ends up past its sides, and everyone still faces it.</li>
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
	/** A tile already in a row stays a row until facings disagree by more than this (about 45 degrees), so it doesn't flip back and forth. */
	static final double STILL_SAME_FACING = 0.7;

	/** How far ahead of a player the thing they're facing is assumed to be: the next tile over. */
	static final int LOOK_AHEAD = 128;

	/** How far round a row may curve either side of straight ahead, in radians (70 degrees). */
	static final double MAX_ARC = Math.toRadians(70);

	/** Row spots, best first: either side of the middle, then further out, then the middle itself. */
	private static final int[] ROW_STEPS = {1, -1, 2, -2, 0};
	private static final int ROWS = 3;
	/** Furthest a row stands behind the row in front, whatever the spacing: just under a tile. */
	static final int ROW_DEPTH = 112;
	/** Crowd spots, best first: an inner ring, the middle, then an outer ring. Opposite sides alternate so any number looks balanced. */
	private static final double[] INNER_RING = {0, 180, 120, 300, 60, 240};
	private static final double[] OUTER_RING = {30, 210, 150, 330, 90, 270, 0, 180, 60, 240, 120, 300};
	static final int ROW_PATTERN_SIZE = ROW_STEPS.length * ROWS;
	static final int CROWD_PATTERN_SIZE = INNER_RING.length + 1 + OUTER_RING.length;

	/** Where a spot can be, relative to the tile centre. */
	interface SpotCheck
	{
		boolean canStand(int dx, int dz);
	}

	/** Furthest a player is placed from the tile centre along a line, in local units (three tiles). */
	static final int MAX_LINE_EXTENT = 384;

	/** Looks up where a player is currently headed. */
	interface Targets
	{
		boolean has(int id);

		int dx(int id);

		int dz(int id);
	}

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
		return place(entries, includeLocal, maxStack, spacing, layout, java.util.Collections.emptySet(), null, null);
	}

	/**
	 * As above, remembering which tiles were rows last time so a tile only switches between a row
	 * and a circle when the facings clearly change.
	 *
	 * @param wasRow tiles laid out as a row last time
	 * @param isRow  filled with the tiles laid out as a row this time; may be null
	 */
	static List<Placement> place(List<Entry> entries, boolean includeLocal, int maxStack, int spacing, Layout layout,
		Set<Long> wasRow, Set<Long> isRow)
	{
		return place(entries, includeLocal, maxStack, spacing, layout, wasRow, isRow, null);
	}

	/**
	 * As above, also filling {@code rowFacing} with the direction each row faces (radians, game
	 * convention), so the crowd layout can keep rows in front of what they're using.
	 */
	static List<Placement> place(List<Entry> entries, boolean includeLocal, int maxStack, int spacing, Layout layout,
		Set<Long> wasRow, Set<Long> isRow, Map<Long, Double> rowFacing)
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

			long tile = group.get(0).tile;
			double needed = wasRow.contains(tile) ? STILL_SAME_FACING : SAME_FACING;
			Double facing = layout == Layout.RING ? null : sharedFacing(group, needed);
			boolean line = layout == Layout.LINE || (layout == Layout.AUTO && facing != null);
			double angle = facing != null ? facing : 0.0;
			if (line && isRow != null)
			{
				isRow.add(tile);
			}
			if (line && rowFacing != null)
			{
				rowFacing.put(tile, angle);
			}

			for (int i = 0; i < n; i++)
			{
				int[] off = line
					? lineOffset(i, n, centreTaken, spacing, angle)
					: ringOffset(i, n, ringRadius(n, centreTaken, spacing));
				Entry e = movable.get(i);
				out.add(new Placement(e.id, e.tile, off[0], off[1]));
			}
		}
		return out;
	}

	/**
	 * Keep each player's current spot unless the new one is clearly different. A crowd shifts a
	 * little every time someone nearby arrives, leaves or turns; without this everyone would keep
	 * shuffling by a few units.
	 */
	static List<Placement> keepCurrentSpots(List<Placement> fresh, Targets current, int tolerance)
	{
		List<Placement> out = new ArrayList<>(fresh.size());
		for (Placement p : fresh)
		{
			if (current.has(p.id) && Math.hypot(p.dx - current.dx(p.id), p.dz - current.dz(p.id)) < tolerance)
			{
				out.add(new Placement(p.id, p.tile, current.dx(p.id), current.dz(p.id)));
			}
			else
			{
				out.add(p);
			}
		}
		return out;
	}

	/** True if pattern spot {@code index} is the middle of the tile. */
	static boolean isMiddleSpot(int index, boolean row)
	{
		return row ? index == ROW_STEPS.length - 1 : index == INNER_RING.length;
	}

	/**
	 * Pattern spot {@code index}, relative to the tile centre. The pattern never depends on how many
	 * players there are, so adding or removing a player never moves anyone else's spot.
	 *
	 * <p>Rows curve around what everyone faces ({@link #LOOK_AHEAD} ahead): a front row, then a row
	 * behind it, then another. Crowds fill an inner ring, the middle, then an outer ring.
	 */
	static int[] spotOffset(int index, boolean row, double angle, int spacing)
	{
		if (row)
		{
			int rowNumber = index / ROW_STEPS.length;
			int step = ROW_STEPS[index % ROW_STEPS.length];
			double radius = LOOK_AHEAD + rowNumber * (double) Math.min(spacing, ROW_DEPTH);
			double turn = Math.min(spacing / radius, MAX_ARC / 2);
			double phi = step * turn;
			double fwdX = -Math.sin(angle);
			double fwdZ = -Math.cos(angle);
			double focusX = fwdX * LOOK_AHEAD;
			double focusZ = fwdZ * LOOK_AHEAD;
			double backX = -fwdX * radius;
			double backZ = -fwdZ * radius;
			double x = focusX + backX * Math.cos(phi) - backZ * Math.sin(phi);
			double z = focusZ + backX * Math.sin(phi) + backZ * Math.cos(phi);
			return new int[]{(int) Math.round(x), (int) Math.round(z)};
		}
		if (index < INNER_RING.length)
		{
			return polar(0.8 * spacing, INNER_RING[index]);
		}
		if (index == INNER_RING.length)
		{
			return new int[]{0, 0};
		}
		return polar(Math.min(1.6 * spacing, MAX_LINE_EXTENT), OUTER_RING[index - INNER_RING.length - 1]);
	}

	private static int[] polar(double radius, double degrees)
	{
		double a = Math.toRadians(degrees);
		return new int[]{(int) Math.round(radius * Math.cos(a)), (int) Math.round(radius * Math.sin(a))};
	}

	/**
	 * The usable spots for a tile, best first: the pattern with the middle left out when someone who
	 * stays put is standing there, and any spot a player couldn't stand on (a booth, stall, anvil or
	 * wall) skipped. At most {@code capacity} spots.
	 */
	static List<int[]> spots(boolean row, double angle, boolean middleTaken, int spacing, int capacity, SpotCheck check)
	{
		List<int[]> out = new ArrayList<>(capacity);
		int size = row ? ROW_PATTERN_SIZE : CROWD_PATTERN_SIZE;
		for (int i = 0; i < size && out.size() < capacity; i++)
		{
			if (middleTaken && isMiddleSpot(i, row))
			{
				continue;
			}
			int[] spot = spotOffset(i, row, angle, spacing);
			if (check == null || check.canStand(spot[0], spot[1]))
			{
				out.add(spot);
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
		return sharedFacing(group, SAME_FACING);
	}

	static Double sharedFacing(List<Entry> group, double needed)
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
		if (agreement < needed)
		{
			return null;
		}
		return Math.atan2(-sumX, -sumZ);
	}

	/**
	 * Which way a player moved by (dx, dz) should face to keep looking at the same spot they were
	 * looking at from their real position, {@link #LOOK_AHEAD} in front of them. So the players at
	 * the ends of a row at an anvil turn in towards the anvil instead of staring past it.
	 */
	static int faceSameSpot(int orientation, int dx, int dz)
	{
		if (dx == 0 && dz == 0)
		{
			return orientation;
		}
		double a = toRadians(orientation);
		double lookX = -Math.sin(a) * LOOK_AHEAD - dx;
		double lookZ = -Math.cos(a) * LOOK_AHEAD - dz;
		if (Math.hypot(lookX, lookZ) < 1)
		{
			return orientation;
		}
		return OffsetTable.facing(lookX, lookZ);
	}

	static double toRadians(int orientation)
	{
		return 2 * Math.PI * (((orientation % FULL_TURN) + FULL_TURN) % FULL_TURN) / FULL_TURN;
	}

	/**
	 * Slot {@code slot} of {@code count} in a row facing {@code angle}, curved around what the row is
	 * facing. Everyone stands the same distance ({@link #LOOK_AHEAD}) from that spot, neighbours about
	 * {@code spacing} apart along the curve, and the curve never wraps further round than
	 * {@link #MAX_ARC} either side, so a wide spacing can't push people off the anvil or booth.
	 * With the centre free the row is centred on the tile; with the centre taken, players fill the
	 * spots either side of it, nearest first.
	 */
	static int[] lineOffset(int slot, int count, boolean centreTaken, int spacing, double angle)
	{
		double step;
		double furthest;
		if (centreTaken)
		{
			int side = slot / 2 + 1;
			step = slot % 2 == 0 ? side : -side;
			furthest = (count + 1) / 2;
		}
		else
		{
			furthest = (count - 1) / 2.0;
			step = slot - furthest;
		}
		double turn = furthest == 0 ? 0 : Math.min((double) spacing / LOOK_AHEAD, MAX_ARC / furthest);
		double phi = step * turn;

		// The spot everyone is facing, straight ahead of the tile centre.
		double focusX = -Math.sin(angle) * LOOK_AHEAD;
		double focusZ = -Math.cos(angle) * LOOK_AHEAD;
		// Swing the line from that spot back to the tile centre round by phi.
		double backX = -focusX;
		double backZ = -focusZ;
		double x = focusX + backX * Math.cos(phi) - backZ * Math.sin(phi);
		double z = focusZ + backX * Math.sin(phi) + backZ * Math.cos(phi);
		return new int[]{(int) Math.round(x), (int) Math.round(z)};
	}

	/**
	 * Ring radius that puts neighbours {@code spacing} apart, and also {@code spacing} away from
	 * anyone standing in the middle.
	 */
	static int ringRadius(int count, boolean centreTaken, int spacing)
	{
		double r = count <= 1 ? spacing : spacing / (2 * Math.sin(Math.PI / count));
		if (centreTaken)
		{
			r = Math.max(r, spacing);
		}
		return (int) Math.round(r);
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
