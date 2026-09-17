package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spots along a shared edge: neighbouring tiles lined up along the same bank counter or riverbank,
 * all facing it.
 *
 * <p>Spots sit on a grid fixed to the map: every spacing along the edge, and rows behind it, each
 * row in the gaps of the one in front. The rules keep a busy edge calm:
 * <ul>
 * <li>Anyone who has a spot keeps it for as long as they stay, whoever else comes and goes.</li>
 * <li>A newcomer takes the free spot at the edge nearest their own tile, and only if the edge is
 * full, the nearest spot in a row behind their tile's stretch of it (its share of the line between
 * its neighbours, in proportion to how many people each has). Nobody is ever put past someone from
 * the next tile along, so tiles keep their people in order.</li>
 * <li>Someone who steps away has their spot kept for them for {@link SlotBook#HOLD_TICKS}.</li>
 * <li>At most one person a tick steps forward from a row behind into a free spot at the edge.</li>
 * <li>Edge spots only count where the edge is really in front (water or a counter), so a line never
 * runs on past the end of the water.</li>
 * </ul>
 *
 * <p>Client thread only. Knows nothing about RuneLite; unit tested.
 */
final class LineBook
{
	/** Rows that may stand behind the edge. */
	static final int ROWS_BEHIND = 3;
	/** Least distance between rows, so rods and axes from behind clear the people in front. */
	static final int ROW_GAP = 48;

	private static final int TILE = 128;
	/** Any edge spot in a tile's stretch is better than any spot in a row behind. */
	private static final double ROW_COST = 1_000_000;

	/** What the book needs to know about the ground. */
	interface Terrain
	{
		/** Whether a player on this tile could be drawn at (dx, dz) from its middle. */
		boolean canStand(long tile, int dx, int dz);

		/** Whether a player on this tile facing {@code angle} is up against the edge (water or a counter). */
		boolean facesEdge(long tile, double angle);
	}

	/** One tile on a shared line this tick. */
	static final class Member
	{
		final long tile;
		/** Position along the line, in tiles. */
		final int along;
		/** Players who may be given a spot, in the order they're given one. */
		final List<Integer> ids;
		/** Someone who stays put (you, or anyone past the per-tile limit) is standing in the middle. */
		final boolean middleTaken;
		/** Most people this tile shows. */
		final int capacity;

		Member(long tile, int along, List<Integer> ids, boolean middleTaken, int capacity)
		{
			this.tile = tile;
			this.along = along;
			this.ids = ids;
			this.middleTaken = middleTaken;
			this.capacity = capacity;
		}

		double centre()
		{
			return along * (double) TILE;
		}
	}

	/** Tiles next to each other along one edge, all facing it. */
	static final class Line
	{
		final int plane;
		final int sideX;
		final int sideY;
		final int across;
		final double angle;
		final int spacing;
		/** In order along the line. */
		final List<Member> members;
		/** How far along the line spots may go, in local units: halfway to anyone else's row. */
		final double from;
		final double to;

		Line(int plane, int sideX, int sideY, int across, double angle, int spacing, List<Member> members, double from, double to)
		{
			this.plane = plane;
			this.sideX = sideX;
			this.sideY = sideY;
			this.across = across;
			this.angle = angle;
			this.spacing = spacing;
			this.members = members;
			this.from = from;
			this.to = to;
		}

		String key()
		{
			return plane + ":" + sideX + ":" + sideY + ":" + across + ":" + spacing;
		}

		long tileAt(int alongTiles)
		{
			return sideX != 0
				? StackRegistry.key(plane, alongTiles * sideX, across)
				: StackRegistry.key(plane, across, alongTiles * sideY);
		}

		int aheadX()
		{
			return (int) Math.round(-Math.sin(angle));
		}

		int aheadZ()
		{
			return (int) Math.round(-Math.cos(angle));
		}

		int rowGap()
		{
			return Math.max(spacing, ROW_GAP);
		}

		/** Where spot (row, j) is along the line. */
		double along(int row, long j)
		{
			return j * (double) spacing + (row % 2 == 1 ? spacing / 2.0 : 0);
		}
	}

	/** A spot someone has: which line, which row, which step along it, and the tile it was given on. */
	private static final class Spot
	{
		final String line;
		final int row;
		final long j;
		final long tile;

		Spot(String line, int row, long j, long tile)
		{
			this.line = line;
			this.row = row;
			this.j = j;
			this.tile = tile;
		}

		String point()
		{
			return line + "/" + row + "/" + j;
		}
	}

	/** What happened on a line this tick, for the troubleshooting report. */
	static final class LineReport
	{
		final int placed;
		final int blocked;

		LineReport(int placed, int blocked)
		{
			this.placed = placed;
			this.blocked = blocked;
		}
	}

	private final Map<Integer, Spot> spotOf = new HashMap<>();
	private final Map<Integer, Spot> held = new HashMap<>();
	private final Map<Integer, Integer> heldUntil = new HashMap<>();

	/** Diagnostics: people who stepped forward into a free spot at the edge; total. */
	long moves;

	/**
	 * Work out this tick's spots on every shared line.
	 *
	 * @param reports filled with what happened on each tile's line, by tile
	 * @return a placement for everyone given a spot; anyone left out has no spot
	 */
	List<StackSpreader.Placement> update(List<Line> lines, int tick, Terrain terrain, Map<Long, LineReport> reports)
	{
		// Who is where this tick.
		Map<Integer, Member> memberOf = new HashMap<>();
		Map<Integer, Line> lineOf = new HashMap<>();
		for (Line line : lines)
		{
			for (Member m : line.members)
			{
				for (int id : m.ids)
				{
					memberOf.put(id, m);
					lineOf.put(id, line);
				}
			}
		}

		// People who left, or moved to another tile or line.
		for (Iterator<Map.Entry<Integer, Spot>> it = spotOf.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Spot> e = it.next();
			Member m = memberOf.get(e.getKey());
			Line line = lineOf.get(e.getKey());
			if (m == null)
			{
				held.put(e.getKey(), e.getValue());
				heldUntil.put(e.getKey(), tick + SlotBook.HOLD_TICKS);
				it.remove();
			}
			else if (m.tile != e.getValue().tile || !line.key().equals(e.getValue().line))
			{
				it.remove();
			}
		}
		// Holds that ran out; people coming back to a spot held for them.
		for (Iterator<Map.Entry<Integer, Integer>> it = heldUntil.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Integer> e = it.next();
			Member m = memberOf.get(e.getKey());
			Spot spot = held.get(e.getKey());
			if (m != null && m.tile == spot.tile && lineOf.get(e.getKey()).key().equals(spot.line))
			{
				spotOf.put(e.getKey(), spot);
			}
			if (m != null || e.getValue() < tick)
			{
				held.remove(e.getKey());
				it.remove();
			}
		}

		List<StackSpreader.Placement> out = new ArrayList<>();
		for (Line line : lines)
		{
			layOut(line, terrain, reports, out);
		}
		return out;
	}

	private void layOut(Line line, Terrain terrain, Map<Long, LineReport> reports, List<StackSpreader.Placement> out)
	{
		String key = line.key();
		int s = line.spacing;
		List<Member> members = line.members;

		// Every spot the line may use, and whether a player could stand there.
		long firstJ = (long) Math.floor(line.from / s) - 1;
		long lastJ = (long) Math.ceil(line.to / s) + 1;
		Map<String, Boolean> usable = new HashMap<>();
		int blocked = 0;
		for (int row = 0; row <= ROWS_BEHIND; row++)
		{
			for (long j = firstJ; j <= lastJ; j++)
			{
				double p = line.along(row, j);
				if (p < line.from || p > line.to)
				{
					continue;
				}
				boolean ok = standable(line, row, p, terrain);
				usable.put(row + "/" + j, ok);
				blocked += ok ? 0 : 1;
			}
		}

		// Spots in use: people keeping theirs, and spots held for people who stepped away.
		Set<String> taken = new HashSet<>();
		for (Spot spot : held.values())
		{
			if (spot.line.equals(key))
			{
				taken.add(spot.point());
			}
		}
		Map<Member, List<Integer>> newcomers = new HashMap<>();
		for (Member m : members)
		{
			List<Integer> waiting = new ArrayList<>();
			int kept = 0;
			for (int id : m.ids)
			{
				Spot spot = spotOf.get(id);
				if (spot != null && kept < m.capacity && Boolean.TRUE.equals(usable.get(spot.row + "/" + spot.j))
					&& !blockedByMiddle(line, spot.row, line.along(spot.row, spot.j)) && taken.add(spot.point()))
				{
					kept++;
					continue;
				}
				spotOf.remove(id);
				waiting.add(id);
			}
			newcomers.put(m, waiting);
		}

		// Each tile's stretch of the line, shared in proportion to how many people each has.
		double[] lo = new double[members.size()];
		double[] hi = new double[members.size()];
		for (int i = 0; i < members.size(); i++)
		{
			lo[i] = i == 0 ? line.from : hi[i - 1];
			if (i == members.size() - 1)
			{
				hi[i] = line.to;
			}
			else
			{
				int here = Math.max(1, members.get(i).ids.size());
				int next = Math.max(1, members.get(i + 1).ids.size());
				hi[i] = members.get(i).centre() + TILE * (double) here / (here + next);
			}
		}

		// Where each tile's people would ideally stand: the whole line side by side, centred where the
		// people are, each tile's share in order. Newcomers aim for their tile's share, so a busy tile
		// between two quieter ones still gets room at the edge.
		double[] target = new double[members.size()];
		int total = 0;
		double weighted = 0;
		for (Member m : members)
		{
			int n = Math.min(m.capacity, m.ids.size());
			total += n;
			weighted += n * m.centre();
		}
		double lineCentre = total == 0 ? 0 : weighted / total;
		int before = 0;
		for (int i = 0; i < members.size(); i++)
		{
			int n = Math.min(members.get(i).capacity, members.get(i).ids.size());
			double t = lineCentre + (before + n / 2.0 - total / 2.0) * s;
			target[i] = Math.max(line.from, Math.min(line.to, t));
			before += n;
		}

		// Newcomers, taking turns one tile at a time, so every tile claims its share of the edge
		// before any tile spreads out along someone else's.
		int[] next = new int[members.size()];
		int[] assigned = new int[members.size()];
		for (int i = 0; i < members.size(); i++)
		{
			assigned[i] = members.get(i).ids.size() - newcomers.get(members.get(i)).size();
		}
		boolean progress = true;
		while (progress)
		{
			progress = false;
			for (int i = 0; i < members.size(); i++)
			{
				Member m = members.get(i);
				List<Integer> waiting = newcomers.get(m);
				if (next[i] >= waiting.size() || assigned[i] >= m.capacity)
				{
					continue;
				}
				int id = waiting.get(next[i]++);
				String best = bestFree(line, i, id, target[i], lo[i], hi[i], usable, taken, 0);
				if (best == null)
				{
					// Nothing left for this tile; nobody else from it will fit either.
					next[i] = waiting.size();
					continue;
				}
				String[] parts = best.split("/");
				Spot spot = new Spot(key, Integer.parseInt(parts[0]), Long.parseLong(parts[1]), m.tile);
				spotOf.put(id, spot);
				taken.add(spot.point());
				assigned[i]++;
				progress = true;
			}
		}

		// One person a tick steps forward into a free spot at the edge in their own stretch.
		stepForward:
		for (int i = 0; i < members.size(); i++)
		{
			for (int id : members.get(i).ids)
			{
				Spot spot = spotOf.get(id);
				if (spot == null || spot.row == 0)
				{
					continue;
				}
				taken.remove(spot.point());
				String best = bestFree(line, i, id, target[i], lo[i], hi[i], usable, taken, 0);
				if (best != null && best.startsWith("0/"))
				{
					Spot moved = new Spot(key, 0, Long.parseLong(best.split("/")[1]), spot.tile);
					spotOf.put(id, moved);
					taken.add(moved.point());
					moves++;
					break stepForward;
				}
				taken.add(spot.point());
			}
		}

		// Placements.
		int placed = 0;
		int aheadX = line.aheadX();
		int aheadZ = line.aheadZ();
		for (Member m : members)
		{
			for (int id : m.ids)
			{
				Spot spot = spotOf.get(id);
				if (spot == null)
				{
					continue;
				}
				double d = line.along(spot.row, spot.j) - m.centre();
				int depth = spot.row * line.rowGap();
				out.add(new StackSpreader.Placement(id, m.tile,
					(int) Math.round(line.sideX * d - aheadX * depth), (int) Math.round(line.sideY * d - aheadZ * depth)));
				placed++;
			}
		}
		LineReport report = new LineReport(placed, blocked);
		for (Member m : members)
		{
			reports.put(m.tile, report);
		}
	}

	/**
	 * The free spot nearest {@code aim} for member {@code index}: anywhere along the edge first, then in a
	 * row behind its own stretch [lo, hi). Within a row, never past anyone from a tile further along
	 * or before anyone from a tile earlier on.
	 */
	private String bestFree(Line line, int index, int self, double aim, double lo, double hi, Map<String, Boolean> usable,
		Set<String> taken, int firstRow)
	{
		int s = line.spacing;
		String best = null;
		double bestCost = Double.MAX_VALUE;
		for (int row = firstRow; row <= ROWS_BEHIND; row++)
		{
			// Keep tiles in order along this row: nobody past someone from a tile further along.
			double before = -Double.MAX_VALUE;
			double after = Double.MAX_VALUE;
			for (int i = 0; i < line.members.size(); i++)
			{
				if (i == index)
				{
					continue;
				}
				for (int id : line.members.get(i).ids)
				{
					Spot spot = spotOf.get(id);
					if (spot == null || id == self || spot.row != row)
					{
						continue;
					}
					double p = line.along(spot.row, spot.j);
					if (i < index)
					{
						before = Math.max(before, p);
					}
					else
					{
						after = Math.min(after, p);
					}
				}
			}
			double rowFrom = row == 0 ? line.from : lo;
			double rowTo = row == 0 ? line.to + 1 : hi;
			for (long j = (long) Math.floor(rowFrom / s) - 1; j <= (long) Math.ceil(rowTo / s) + 1; j++)
			{
				double p = line.along(row, j);
				String point = row + "/" + j;
				if (p < rowFrom || p >= rowTo || p <= before || p >= after
					|| !Boolean.TRUE.equals(usable.get(point)) || taken.contains(line.key() + "/" + point)
					|| blockedByMiddle(line, row, p))
				{
					continue;
				}
				double cost = row * ROW_COST + Math.abs(p - aim);
				if (cost < bestCost)
				{
					bestCost = cost;
					best = point;
				}
			}
		}
		return best;
	}

	/** Whether a spot at the edge is too close to someone staying put in the middle of their tile. */
	private static boolean blockedByMiddle(Line line, int row, double p)
	{
		if (row != 0)
		{
			return false;
		}
		for (Member m : line.members)
		{
			if (m.middleTaken && Math.abs(p - m.centre()) < line.spacing)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether a player could be drawn at spot {@code p} in {@code row}, checked from the nearest tile on the line. */
	private static boolean standable(Line line, int row, double p, Terrain terrain)
	{
		Member owner = line.members.get(0);
		for (Member m : line.members)
		{
			if (Math.abs(p - m.centre()) < Math.abs(p - owner.centre()))
			{
				owner = m;
			}
		}
		double d = p - owner.centre();
		int depth = row * line.rowGap();
		if (!terrain.canStand(owner.tile, (int) Math.round(line.sideX * d - line.aheadX() * depth),
			(int) Math.round(line.sideY * d - line.aheadZ() * depth)))
		{
			return false;
		}
		// An edge spot needs the edge in front of it: past the end of the water, nobody lines up.
		return row != 0 || terrain.facesEdge(line.tileAt((int) Math.round(p / TILE)), line.angle);
	}

	void clear()
	{
		spotOf.clear();
		held.clear();
		heldUntil.clear();
	}
}
