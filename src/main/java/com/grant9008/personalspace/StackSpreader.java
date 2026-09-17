package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The geometry of a crowded tile: where its spots are, which way a group faces, and which way a
 * moved player should turn. Knows nothing about RuneLite so it can be unit tested on its own.
 *
 * <p>Two shapes (chosen by {@link ShapeMemory}):
 * <ul>
 * <li><b>Row</b>: side by side in front of the thing everyone faces. Around an anvil, range or fire
 * the row curves round it; along a bank counter it runs straight.</li>
 * <li><b>Crowd</b>: rings around the tile, for players facing every which way.</li>
 * </ul>
 */
final class StackSpreader
{
	/** Orientation units in a full turn, as used by the game. 0 faces south, 512 west, 1024 north, 1536 east. */
	static final int FULL_TURN = 2048;

	/** How closely a group's facings must agree to count as "facing the same way" (about 25 degrees either side). */
	static final double SAME_FACING = 0.9;
	/** A tile already in a row stays a row until facings disagree by more than this (about 45 degrees), so it doesn't flip back and forth. */
	static final double STILL_SAME_FACING = 0.7;

	/** How far ahead of a player the thing they're facing is assumed to be: the next tile over. */
	static final int LOOK_AHEAD = 128;

	/** Most a curved row turns between neighbours is this divided by {@link #ARC_REACH}: 28 degrees. */
	static final double MAX_ARC = Math.toRadians(70);
	/**
	 * How far round what everyone faces a curved row may wrap either side of straight ahead
	 * (135 degrees). People gather all the way round a tree, fire or anvil before anyone has to stand
	 * behind someone else, where an axe or hammer would look like it's swinging at them.
	 */
	static final double WRAP_ARC = Math.toRadians(135);

	/** Most players in one straight row before the next row starts. */
	static final int ROW_WIDTH = 8;
	/** Most players in one curved row before the next row starts. */
	static final int CURVED_ROW_WIDTH = 10;
	private static final int ROWS = 3;
	/** Furthest a row stands behind the row in front, whatever the spacing: just under a tile. */
	static final int ROW_DEPTH = 112;
	/** Spacings used to size the largest turn between curved-row neighbours; see {@link #MAX_ARC}. */
	static final double ARC_REACH = 2.5;
	/** Furthest a straight row reaches either side of the middle, in spacings: a little further, so a row blocked on one side can grow along the other. */
	static final double STRAIGHT_REACH = 3.5;
	/** Crowd spots, best first: an inner ring, the middle, then an outer ring. Opposite sides alternate so any number looks balanced. */
	private static final double[] INNER_RING = {0, 180, 120, 300, 60, 240};
	private static final double[] OUTER_RING = {30, 210, 150, 330, 90, 270, 0, 180, 60, 240, 120, 300};
	static final int CROWD_PATTERN_SIZE = INNER_RING.length + 1 + OUTER_RING.length;

	/** Where a spot can be, relative to the tile centre. */
	interface SpotCheck
	{
		boolean canStand(int dx, int dz);
	}

	/** Furthest a player is placed from the tile centre along a line, in local units (three tiles). */
	static final int MAX_LINE_EXTENT = 384;
	/** Furthest back from its middle a bowed counter row reaches, in local units. */
	static final int MAX_BOW = 48;
	/**
	 * Steepest a bow gets. A row behind stands half a spacing along from the one in front, so a
	 * steeper curve than this would close the gap between them until the two of them touched.
	 */
	static final double BOW_SLOPE = 0.45;

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

	/**
	 * The usable spots for a tile, best first, at most {@code capacity} of them. The pattern never
	 * depends on how many players there are, so adding or removing a player never moves anyone
	 * else's spot. Spots a player couldn't stand on (a booth, stall, anvil or wall) are skipped.
	 *
	 * <p>Rows: a front row, then a row behind it, then another, up to {@link #ROW_WIDTH} usable spots
	 * each in a straight row and {@link #CURVED_ROW_WIDTH} in a curved one, which wraps up to
	 * {@link #WRAP_ARC} round what everyone faces before a row behind is started. The front row starts half a spacing either side of the middle, so two players share the
	 * space in front of what they face evenly, and it grows outwards from there. When someone who
	 * stays put stands in the middle, the front row leaves them a full spacing of room instead. Each
	 * row behind stands in the gaps of the row in front. A row curves around what everyone faces,
	 * except at a counter ({@code straight}), where it runs straight along it.
	 *
	 * <p>Crowds: an inner ring, the middle, then an outer ring.
	 */
	static List<int[]> spots(boolean row, boolean straight, double angle, boolean middleTaken, int spacing, int capacity, SpotCheck check)
	{
		return spots(row, straight, angle, middleTaken, spacing, capacity, check, LOOK_AHEAD);
	}

	/** As above, on a curve of {@code arcRadius}; see {@link #arcRadius(int, boolean)}. */
	static List<int[]> spots(boolean row, boolean straight, double angle, boolean middleTaken, int spacing, int capacity,
		SpotCheck check, int arcRadius)
	{
		return spots(row, straight, angle, middleTaken, spacing, capacity, check, arcRadius, false);
	}

	/** As above; {@code bow} curves a straight row gently round what it stands along. */
	static List<int[]> spots(boolean row, boolean straight, double angle, boolean middleTaken, int spacing, int capacity,
		SpotCheck check, int arcRadius, boolean bow)
	{
		return spots(row, straight, angle, middleTaken, spacing, capacity, check, arcRadius, bow, WRAP_ARC);
	}

	/** As above, wrapping {@code wrap} round what everyone faces before a row behind is started. */
	static List<int[]> spots(boolean row, boolean straight, double angle, boolean middleTaken, int spacing, int capacity,
		SpotCheck check, int arcRadius, boolean bow, double wrap)
	{
		List<int[]> out = new ArrayList<>(capacity);
		if (!row)
		{
			for (int i = 0; i < CROWD_PATTERN_SIZE && out.size() < capacity; i++)
			{
				if (middleTaken && i == INNER_RING.length)
				{
					continue;
				}
				int[] spot = crowdSpot(i, spacing);
				if (check == null || check.canStand(spot[0], spot[1]))
				{
					out.add(spot);
				}
			}
			return out;
		}

		double reach = straight ? STRAIGHT_REACH : wrap / curvedTurn(spacing, arcRadius) + 1e-9;
		int width = straight ? ROW_WIDTH : CURVED_ROW_WIDTH;
		// Which side of the middle a row fills first. The ring pattern always starts on the east
		// side, so a row does too where it can: a pair then keeps its places when their tile changes
		// between a ring and a row, instead of the two of them swapping sides for no reason.
		double alongX = -Math.cos(angle);
		int first = (Math.abs(alongX) > 1e-9 ? alongX : Math.sin(angle)) >= 0 ? 1 : -1;
		for (int rowNumber = 0; rowNumber < ROWS && out.size() < capacity; rowNumber++)
		{
			// Rows take turns between spots off the middle line and spots on it, so each row
			// stands in the gaps of the one in front.
			boolean offMiddle = (rowNumber % 2 == 0) != middleTaken;
			int inRow = 0;
			for (double step = offMiddle ? 0.5 : 0; step <= reach && inRow < width && out.size() < capacity; step++)
			{
				for (int side = 0; side < (step == 0 ? 1 : 2) && inRow < width && out.size() < capacity; side++)
				{
					if (step == 0 && rowNumber == 0 && middleTaken)
					{
						continue;
					}
					int[] spot = rowSpot(rowNumber, first * (side == 0 ? step : -step), straight, angle, spacing, arcRadius, bow);
					if (spot != null && (check == null || check.canStand(spot[0], spot[1])))
					{
						out.add(spot);
						inRow++;
					}
				}
			}
		}
		return out;
	}

	/**
	 * The spot {@code step} spacings along row {@code rowNumber} (0 is the front row), relative to the
	 * tile centre, or null if that is further out than a row may reach.
	 *
	 * <p>A curved row keeps everyone the same distance from what they face ({@link #LOOK_AHEAD}
	 * ahead of the tile centre, plus a row depth for each row behind). Front-row neighbours are about
	 * {@code spacing} apart along the curve, but never more than {@link #MAX_ARC} / {@link #ARC_REACH}
	 * round from each other; rows behind
	 * keep the same angle between neighbours, so their half-step offsets land in the gaps. A straight
	 * row runs sideways across the facing, each row behind a row depth further back.
	 */
	static int[] rowSpot(int rowNumber, double step, boolean straight, double angle, int spacing)
	{
		return rowSpot(rowNumber, step, straight, angle, spacing, LOOK_AHEAD);
	}

	/** As above, on a curve of {@code arcRadius}; see {@link #arcRadius(int, boolean)}. */
	static int[] rowSpot(int rowNumber, double step, boolean straight, double angle, int spacing, int arcRadius)
	{
		return rowSpot(rowNumber, step, straight, angle, spacing, arcRadius, false);
	}

	/** As above; {@code bow} curves a straight row gently round what it stands along. */
	static int[] rowSpot(int rowNumber, double step, boolean straight, double angle, int spacing, int arcRadius, boolean bow)
	{
		double fwdX = -Math.sin(angle);
		double fwdZ = -Math.cos(angle);
		// A row behind stands half a spacing along from the one in front, so where the row in front
		// has bowed away from it the gap between the two closes. Give every row behind that much
		// more depth and the two of them stay exactly as far apart as they were before the bow.
		double depth = rowNumber * (Math.min(spacing, ROW_DEPTH) + (bow ? BOW_SLOPE * spacing / 2 : 0));
		if (straight)
		{
			double along = step * spacing;
			if (Math.abs(along) > MAX_LINE_EXTENT)
			{
				return null;
			}
			double back = depth + (bow ? bowBack(along) : 0);
			double x = fwdZ * along - fwdX * back;
			double z = -fwdX * along - fwdZ * back;
			return new int[]{(int) Math.round(x), (int) Math.round(z)};
		}
		double radius = arcRadius + depth;
		double phi = step * curvedTurn(spacing, arcRadius);
		double focusX = fwdX * arcRadius;
		double focusZ = fwdZ * arcRadius;
		double backX = -fwdX * radius;
		double backZ = -fwdZ * radius;
		double x = focusX + backX * Math.cos(phi) - backZ * Math.sin(phi);
		double z = focusZ + backX * Math.sin(phi) + backZ * Math.cos(phi);
		if (arcRadius > LOOK_AHEAD && Math.hypot(x, z) > MAX_LINE_EXTENT)
		{
			// A curve opened out by the slider still reaches no further than a row does.
			return null;
		}
		return new int[]{(int) Math.round(x), (int) Math.round(z)};
	}

	/**
	 * How far back from the middle of a bowed row a spot {@code along} it stands, so a crowd at a
	 * bank booth curves round it the way one round an anvil does, instead of standing in a flat line.
	 * It is the same circle a curved row hugs, flattened out at {@link #MAX_BOW} so a wide row's ends
	 * don't curl back into the row behind.
	 */
	static int bowBack(double along)
	{
		// The circle, while it is shallow enough that the row behind keeps its gap over a half step,
		// then a straight taper at that same slope, then flat at MAX_BOW.
		double turn = BOW_SLOPE / Math.sqrt(1 + BOW_SLOPE * BOW_SLOPE);
		double straightFrom = LOOK_AHEAD * turn;
		double d = Math.abs(along);
		double back = d <= straightFrom
			? LOOK_AHEAD - Math.sqrt(LOOK_AHEAD * (double) LOOK_AHEAD - d * d)
			: LOOK_AHEAD - Math.sqrt(LOOK_AHEAD * (double) LOOK_AHEAD - straightFrom * straightFrom)
				+ BOW_SLOPE * (d - straightFrom);
		return (int) Math.round(Math.min(MAX_BOW, back));
	}

	/** The angle between neighbours in a curved row, in radians. */
	static double curvedTurn(int spacing)
	{
		return curvedTurn(spacing, LOOK_AHEAD);
	}

	/** The angle between neighbours in a curved row of {@code arcRadius}, in radians. */
	static double curvedTurn(int spacing, int arcRadius)
	{
		return Math.min((double) spacing / arcRadius, MAX_ARC / ARC_REACH);
	}

	/**
	 * The circle a curved row stands on. Normally {@link #LOOK_AHEAD}, so the curve hugs whatever
	 * everyone is facing: an anvil, a tree, a fire. Neighbours are then never more than
	 * {@link #MAX_ARC} / {@link #ARC_REACH} round from each other, which is about half a tile apart,
	 * however wide the spacing slider is set.
	 *
	 * <p>With {@code wide} - auto-spacing off, so the slider is in charge - the circle grows instead
	 * of the turn, until neighbours stand a full spacing apart. The curve opens out into a wide
	 * crescent rather than stopping at half a tile.
	 */
	static int arcRadius(int spacing, boolean wide)
	{
		return wide ? Math.max(LOOK_AHEAD, (int) Math.ceil(spacing / (MAX_ARC / ARC_REACH))) : LOOK_AHEAD;
	}

	/** Crowd spot {@code index}, relative to the tile centre: an inner ring, the middle, then an outer ring. */
	private static int[] crowdSpot(int index, int spacing)
	{
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

	/** True for the names of fires people gather round: a fire someone lit, a campfire, a fire pit. Not fireplaces. */
	static boolean isFireName(String name)
	{
		if (name == null)
		{
			return false;
		}
		String n = name.toLowerCase();
		return n.contains("fire") && !n.contains("fireplace");
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
	 * null if their facings are unknown or don't agree to at least {@code needed} (1 is identical).
	 */
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

	/**
	 * Which way a player drawn at (dx, dz) from the tile centre should face to look at the point
	 * (focusX, focusZ), also relative to the tile centre; {@code orientation} if they're standing on it.
	 */
	static int faceTowards(int orientation, int dx, int dz, int focusX, int focusZ)
	{
		double lookX = focusX - dx;
		double lookZ = focusZ - dz;
		if (Math.hypot(lookX, lookZ) < 1)
		{
			return orientation;
		}
		return OffsetTable.facing(lookX, lookZ);
	}

	/** The orientation halfway round from {@code from} to {@code to}, the short way. */
	static int halfway(int from, int to)
	{
		int diff = ((to - from) % FULL_TURN + FULL_TURN + FULL_TURN / 2) % FULL_TURN - FULL_TURN / 2;
		return ((from + diff / 2) % FULL_TURN + FULL_TURN) % FULL_TURN;
	}

	static double toRadians(int orientation)
	{
		return 2 * Math.PI * (((orientation % FULL_TURN) + FULL_TURN) % FULL_TURN) / FULL_TURN;
	}

}
