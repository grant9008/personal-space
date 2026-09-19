package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
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
		/** Whether this line may curve round each tile's booth: only where nobody stands behind it. */
		final boolean bow;

		Line(int plane, int sideX, int sideY, int across, double angle, int spacing, List<Member> members, double from, double to)
		{
			this(plane, sideX, sideY, across, angle, spacing, members, from, to, false);
		}

		Line(int plane, int sideX, int sideY, int across, double angle, int spacing, List<Member> members, double from, double to,
			boolean bow)
		{
			this.bow = bow;
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

	/** A line's key without its spacing: the same stretch of counter at another spacing. */
	private static String withoutSpacing(String key)
	{
		return key.substring(0, key.lastIndexOf(':'));
	}

	/** How far along its line a spot is, whatever spacing that line had. */
	private static double alongAt(Spot spot)
	{
		int spacing = Integer.parseInt(spot.line.substring(spot.line.lastIndexOf(':') + 1));
		return spot.j * (double) spacing + (spot.row % 2 == 1 ? spacing / 2.0 : 0);
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

	private int tick;

	/**
	 * Your own player's id when your character may be moved, else -1. If you end up in a row behind
	 * while someone from your tile stands at the edge, you swap with them, once: what you're doing
	 * (fishing, banking) should look right on your own screen.
	 */
	int localId = -1;

	/**
	 * Which way the camera is from you on the ground (east, north), or null when unknown. It never
	 * moves you: whoever on the line would stand between you and it steps aside to a free spot, so
	 * you aren't hidden behind the rest of the line when it is seen end on.
	 */
	double[] view;

	/**
	 * The usable spots on this line that stand between you and the camera, to keep empty. None when
	 * there is no camera to go by, you aren't on this line or haven't stood here a moment yet, and
	 * never one being held for someone coming back to it.
	 */
	private List<String> inFrontOfYou(Line line, String key, int tick, Set<String> heldHere, Map<String, Boolean> usable)
	{
		List<String> out = new ArrayList<>();
		Spot mine = spotOf.get(localId);
		if (view == null || mine == null || !mine.line.equals(key) || mine.tile != localTile
			|| tick - localSince < SlotBook.LOCAL_SWAP_DELAY)
		{
			return out;
		}
		// The whole line: seen from the side, everyone between you and the end nearest the camera is
		// in the way, however far along they stand.
		double[] at = ground(line, mine.row, mine.j);
		for (Map.Entry<String, Boolean> u : usable.entrySet())
		{
			String[] rowAndStep = u.getKey().split("/");
			int row = Integer.parseInt(rowAndStep[0]);
			long j = Long.parseLong(rowAndStep[1]);
			if (row == mine.row && j == mine.j || !Boolean.TRUE.equals(u.getValue()))
			{
				continue;
			}
			{
				double[] there = ground(line, row, j);
				double east = there[0] - at[0];
				double north = there[1] - at[1];
				double nearer = east * view[0] + north * view[1];
				double across = Math.abs(north * view[0] - east * view[1]);
				String point = new Spot(key, row, j, mine.tile).point();
				if (nearer > VIEW_IN_FRONT && across < VIEW_OVERLAP && !heldHere.contains(point))
				{
					out.add(point);
				}
			}
		}
		return out;
	}

	/** Someone is in front of you when they are at least this much nearer the camera, in units. */
	private static final double VIEW_IN_FRONT = 24;
	/** ...and this close to your line of sight from the side, about a player's width. */
	private static final double VIEW_OVERLAP = 48;

	/**
	 * Where a spot on the line is drawn on the ground, east then north, curve round the booth and
	 * all: judged without the curve, someone placed just out of your line of sight was drawn just
	 * inside it.
	 */
	private double[] ground(Line line, int row, long j)
	{
		double along = line.along(row, j);
		double depth = row * (double) line.rowGap();
		if (bow && line.bow && !line.members.isEmpty())
		{
			double centre = line.members.get(0).centre();
			double booth = Math.round((along - centre) / TILE) * TILE + centre;
			depth += StackSpreader.bowBack(along - booth);
		}
		return new double[]{line.sideX * along - line.aheadX() * depth, line.sideY * along - line.aheadZ() * depth};
	}

	/**
	 * People who stepped aside for you, and where you stood and which way the camera looked when
	 * they did. They stay put while neither changes: stepped back out of your way and then forward
	 * into a free place at the counter the next tick, they walked twice.
	 */
	private final Set<Integer> asideForYou = new HashSet<>();
	/** This tick, people on a line who would have stood in front of you with nowhere else to go. */
	final Set<Integer> hiddenForYou = new HashSet<>();
	private String asideAim;

	/** Whether each tile's people curve gently round their own booth, rather than standing dead flat. */
	boolean bow = true;

	private long localTile = Long.MIN_VALUE;
	private int localSince;
	private boolean sawLocal;

	/**
	 * Work out this tick's spots on every shared line.
	 *
	 * @param reports filled with what happened on each tile's line, by tile
	 * @return a placement for everyone given a spot; anyone left out has no spot
	 */
	List<StackSpreader.Placement> update(List<Line> lines, int tick, Terrain terrain, Map<Long, LineReport> reports)
	{
		sawLocal = false;
		this.tick = tick;
		hiddenForYou.clear();
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

		// A line whose spacing changed (it closed up for being busy, or eased back out): everyone
		// keeps their place in its order, at the nearest spot of the new spacing, rather than the
		// whole line being laid out afresh. That filled every spot at once, the ones in front of
		// you included, and whoever had stepped aside for you had nowhere left to step to.
		List<Integer> respaced = new ArrayList<>();
		for (Map.Entry<Integer, Spot> e : spotOf.entrySet())
		{
			Member m = memberOf.get(e.getKey());
			Line line = lineOf.get(e.getKey());
			Spot spot = e.getValue();
			if (m != null && m.tile == spot.tile && !line.key().equals(spot.line)
				&& withoutSpacing(line.key()).equals(withoutSpacing(spot.line)))
			{
				respaced.add(e.getKey());
			}
		}
		respaced.sort(Comparator.comparing((Integer id) -> spotOf.get(id).line)
			.thenComparingInt(id -> spotOf.get(id).row)
			.thenComparingDouble(id -> alongAt(spotOf.get(id))));
		Map<String, Long> lastStep = new HashMap<>();
		for (int id : respaced)
		{
			Spot spot = spotOf.get(id);
			Line line = lineOf.get(id);
			double offset = spot.row % 2 == 1 ? line.spacing / 2.0 : 0;
			long j = Math.round((alongAt(spot) - offset) / line.spacing);
			String row = line.key() + "/" + spot.row;
			Long before = lastStep.get(row);
			if (before != null && j <= before)
			{
				j = before + 1;
			}
			lastStep.put(row, j);
			Spot moved = new Spot(line.key(), spot.row, j, spot.tile);
			if (id == localId && asideAim != null && asideAim.startsWith(spot.point() + "@"))
			{
				asideAim = moved.point() + asideAim.substring(asideAim.indexOf('@'));
			}
			spotOf.put(id, moved);
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
		if (!sawLocal)
		{
			localTile = Long.MIN_VALUE;
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
			// You take your pick of what's free before the others on your tile, so arriving at an
			// edge settles in one step instead of settling and then swapping forward.
			if (waiting.remove((Integer) localId))
			{
				waiting.add(0, localId);
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

		// Once you've stood here a moment, you get a place at the edge if anyone from your tile has
		// one: swap with whoever of them stands nearest your tile's middle. Not while an edge spot is
		// being held for someone from your tile: when that hold ends you step forward into it instead,
		// and nobody else has to move.
		Spot mine = spotOf.get(localId);
		if (mine != null && mine.line.equals(key))
		{
			sawLocal = true;
			if (localTile != mine.tile)
			{
				localTile = mine.tile;
				localSince = tick;
			}
		}
		boolean swappedYou = false;
		int keptAside = -1;
		// An edge spot held for someone from your tile who stepped away is yours the moment you
		// have a spot behind: you step into it now rather than wait for their hold to run out, and
		// nobody else moves. If they come back they take a free spot, like anyone arriving.
		if (mine != null && mine.line.equals(key) && mine.row > 0 && tick - localSince >= SlotBook.LOCAL_SWAP_DELAY)
		{
			for (Iterator<Map.Entry<Integer, Spot>> it = held.entrySet().iterator(); it.hasNext(); )
			{
				Map.Entry<Integer, Spot> h = it.next();
				Spot spot = h.getValue();
				if (spot.line.equals(key) && spot.row == 0 && spot.tile == mine.tile && Boolean.TRUE.equals(usable.get(spot.row + "/" + spot.j)))
				{
					it.remove();
					heldUntil.remove(h.getKey());
					taken.remove(mine.point());
					taken.add(spot.point());
					spotOf.put(localId, spot);
					mine = spot;
					swappedYou = true;
					moves++;
					break;
				}
			}
		}
		// Else a place at the edge if anyone from your tile has one: swap with whoever of them
		// stands nearest your tile's middle. That is the only time the line moves you; seeing
		// yourself is taken care of by keeping the spots in front of you empty.
		if (mine != null && mine.line.equals(key) && mine.row > 0 && tick - localSince >= SlotBook.LOCAL_SWAP_DELAY)
		{
			for (Member m : members)
			{
				if (!m.ids.contains(localId))
				{
					continue;
				}
				int swapWith = -1;
				double nearest = Double.MAX_VALUE;
				for (int id : m.ids)
				{
					Spot theirs = spotOf.get(id);
					if (id == localId || theirs == null || theirs.row != 0)
					{
						continue;
					}
					double distance = Math.abs(line.along(0, theirs.j) - m.centre());
					if (distance < nearest)
					{
						nearest = distance;
						swapWith = id;
					}
				}
				if (swapWith >= 0)
				{
					Spot theirs = spotOf.get(swapWith);
					spotOf.put(localId, new Spot(key, 0, theirs.j, m.tile));
					Spot partner = new Spot(key, mine.row, mine.j, m.tile);
					// Your old spot is behind your new one, and from a camera behind the line that's
					// in front of you: send them straight somewhere out of your way instead, so they
					// move once rather than to your old spot and then aside again.
					Set<String> heldNow = new HashSet<>();
					for (Spot spot : held.values())
					{
						if (spot.line.equals(key))
						{
							heldNow.add(spot.point());
						}
					}
					List<String> wouldHide = inFrontOfYou(line, key, tick, heldNow, usable);
					if (wouldHide.contains(partner.point()))
					{
						int i = members.indexOf(m);
						Set<String> avoid = new HashSet<>(taken);
						avoid.addAll(wouldHide);
						avoid.add(spotOf.get(localId).point());
						String best = bestFree(line, i, swapWith, target[i], lo[i], hi[i], usable, avoid, 1);
						if (best == null)
						{
							best = bestFree(line, i, swapWith, target[i], lo[i], hi[i], usable, avoid, 0);
						}
						if (best != null)
						{
							String[] parts = best.split("/");
							partner = new Spot(key, Integer.parseInt(parts[0]), Long.parseLong(parts[1]), m.tile);
							taken.remove(mine.point());
							taken.add(partner.point());
							keptAside = swapWith;
						}
					}
					spotOf.put(swapWith, partner);
					swappedYou = true;
					moves++;
				}
			}
		}

		// Personal space for you. From a camera off to one side a straight line is one person half
		// behind the next, so whoever stands beside you on the camera's side covers you, wherever on
		// the line you are - and so can someone in the row behind, half a step towards the camera.
		// Once everyone has a spot, anyone standing between you and the camera moves to a free spot
		// out of the way, and the spots in front of you are kept empty for the rest of the tick. Only
		// spots nobody has are ever used, so nobody is left without one for your sake; someone with
		// nowhere else to go simply stays.
		Set<String> heldHere = new HashSet<>();
		for (Spot spot : held.values())
		{
			if (spot.line.equals(key))
			{
				heldHere.add(spot.point());
			}
		}
		// Not on a tick you were swapped to the edge: the spots in front of you are about to change.
		List<String> clear = swappedYou ? new ArrayList<>() : inFrontOfYou(line, key, tick, heldHere, usable);
		Spot mineNow = spotOf.get(localId);
		String aim = mineNow == null || !mineNow.line.equals(key) || view == null
			? null : mineNow.point() + "@" + Math.round(Math.atan2(view[1], view[0]) * 100);
		// Only the line you're on decides this: every line is laid out in turn, and one you aren't
		// on used to wipe the memory, so people who had stepped aside for you stepped forward again.
		if ((mineNow == null || mineNow.line.equals(key)) && (aim == null || !aim.equals(asideAim)))
		{
			// You or the camera changed: whoever stepped aside for the old view may move again.
			asideForYou.clear();
			asideAim = aim;
		}
		if (keptAside >= 0)
		{
			asideForYou.add(keptAside);
		}
		if (!clear.isEmpty())
		{
			Set<String> avoid = new HashSet<>(taken);
			avoid.addAll(clear);
			for (int i = 0; i < members.size(); i++)
			{
				Member m = members.get(i);
				for (int id : m.ids)
				{
					Spot spot = spotOf.get(id);
					if (id == localId || spot == null || !clear.contains(spot.point()))
					{
						continue;
					}
					// A little back, near their own booth, if there's room there: sent to the best spot at
					// the counter instead, they ended up at the far end of it, against the wall.
					String best = bestFree(line, i, id, target[i], lo[i], hi[i], usable, avoid, 1);
					if (best == null)
					{
						best = bestFree(line, i, id, target[i], lo[i], hi[i], usable, avoid, 0);
					}
					if (best == null)
					{
						// Nowhere out of your way along a packed line: they wait without a spot, like
						// anyone past the players-per-tile limit, rather than stand in front of you.
						spotOf.remove(id);
						taken.remove(spot.point());
						hiddenForYou.add(id);
						moves++;
						continue;
					}
					String[] parts = best.split("/");
					Spot aside = new Spot(key, Integer.parseInt(parts[0]), Long.parseLong(parts[1]), m.tile);
					spotOf.put(id, aside);
					taken.remove(spot.point());
					taken.add(aside.point());
					avoid.add(aside.point());
					asideForYou.add(id);
					moves++;
				}
			}
			taken.addAll(clear);
		}

		// One person a tick steps forward into a free spot at the edge in their own stretch.
		stepForward:
		for (int pass = 0; pass < 2; pass++)
		{
			for (int i = 0; i < members.size(); i++)
			{
				for (int id : members.get(i).ids)
				{
					// You first, then everyone else in order along the line.
					if ((pass == 0) != (id == localId))
					{
						continue;
					}
					Spot spot = spotOf.get(id);
					if (spot == null || spot.row == 0 || asideForYou.contains(id))
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
		}

		// Anyone left without a spot is drawn in the middle of their tile, and so is whoever stays put
		// there: nobody at the edge is drawn on top of them. They keep their spot, so nothing
		// reshuffles; they just aren't shown while it's taken.
		Set<Integer> hidden = new HashSet<>();
		for (boolean changed = true; changed; )
		{
			changed = false;
			for (Member m : members)
			{
				boolean inMiddle = m.middleTaken;
				for (int id : m.ids)
				{
					inMiddle |= !spotOf.containsKey(id) || hidden.contains(id);
				}
				if (!inMiddle)
				{
					continue;
				}
				for (Member o : members)
				{
					for (int id : o.ids)
					{
						Spot spot = spotOf.get(id);
						if (spot != null && spot.row == 0 && Math.abs(line.along(0, spot.j) - m.centre()) < s && hidden.add(id))
						{
							changed = true;
						}
					}
				}
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
				if (spot == null || hidden.contains(id))
				{
					continue;
				}
				double d = line.along(spot.row, spot.j) - m.centre();
				// Each tile's people curve round their own booth while their places along the line stay
				// exactly where they were: the line is still shared, it just isn't a flat wall of
				// people. Rows behind get back the depth the bow can cost them, since they stand half a
				// spacing along from the row in front, where the bow has moved away from them.
				int depth = spot.row * line.rowGap();
				if (bow && line.bow)
				{
					// A gentle curve round each booth along the line: whoever stands opposite one is at
					// the counter, and the people to either side of them fall back a little. It is
					// measured from the booth a spot sits at, not from the person's own tile and not
					// from the middle of the line, so it doesn't jump where two tiles meet and doesn't
					// change when a tile further along empties.
					double alongHere = line.along(spot.row, spot.j);
					double booth = Math.round((alongHere - m.centre()) / (double) TILE) * TILE + m.centre();
					depth += StackSpreader.bowBack(alongHere - booth);
				}
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
				// Someone staying put in the middle of a tile counts as that tile's person at the edge.
				if (row == 0 && line.members.get(i).middleTaken)
				{
					if (i < index)
					{
						before = Math.max(before, line.members.get(i).centre());
					}
					else
					{
						after = Math.min(after, line.members.get(i).centre());
					}
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
		localTile = Long.MIN_VALUE;
		spotOf.clear();
		held.clear();
		heldUntil.clear();
	}
}
