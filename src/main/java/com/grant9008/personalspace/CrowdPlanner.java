package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Decides, once per game tick, which crowded tiles are spread out and where everyone on them stands.
 *
 * <p>For each tile with players standing still on it:
 * <ul>
 * <li>Only players the game is actually showing count. Someone the game hides, such as a player the
 * server keeps invisible, never pushes a visible player aside.</li>
 * <li>A tile is spread only while at least two of them are there. When a crowd shrinks to one, that
 * player walks back to the middle of their tile and stays there. (Their spot is still remembered
 * for a few seconds, so if the others come straight back everyone returns to the same places.)</li>
 * <li>The tile's shape comes from {@link ShapeMemory}, its spots from {@link StackSpreader#spots},
 * and who stands where from {@link SlotBook}.</li>
 * </ul>
 *
 * <p>Client thread only. Knows nothing about RuneLite; unit tested.
 */
final class CrowdPlanner
{
	/** What the planner needs to know about the world around a tile. */
	interface Surroundings
	{
		/** Whether a player on this tile could be drawn at (dx, dz) from its middle. */
		boolean canStand(long tile, int dx, int dz);

		/** Whether players on this tile facing {@code angle} are facing something, such as an anvil, tree, booth or water. */
		boolean facesObstacle(long tile, double angle);

		/** Whether a row on this tile facing {@code angle} is up against a bank counter or row of booths. */
		boolean isCounter(long tile, double angle);

		/** Whether a row on this tile facing {@code angle} is facing a fire. */
		boolean facesFire(long tile, double angle);

		/** Fires on this tile or the eight around it, as {east, north} tile steps from it. */
		List<int[]> firesNear(long tile);
	}

	/** One tick's decisions. */
	static final class Plan
	{
		/** Players given a spot, by id. */
		final List<StackSpreader.Placement> placements = new ArrayList<>();
		/**
		 * Everyone else standing still on a tile with company: players the game isn't showing (yet),
		 * you when you stay put, and anyone left over on a full tile. All at the middle of their tile.
		 */
		final List<StackSpreader.Placement> unplaced = new ArrayList<>();
		/** Tiles laid out as a curved row, whose players turn to face what the row faces. */
		final Set<Long> curvedRows = new HashSet<>();
		/**
		 * Tiles at a counter: everyone on them turns towards what they face, so the people at the
		 * ends of a row look round at the booth instead of straight ahead at the wall beside it.
		 */
		final Set<Long> facingIn = new HashSet<>();
		/**
		 * Tiles whose middle is between you and the camera: nobody left waiting there is drawn,
		 * or whoever the game shows in the middle of the tile would stand in front of you.
		 */
		final Set<Long> middleOutOfSight = new HashSet<>();
		/** Spread tiles gathered round a fire: where the fire is, in local units from the tile centre. Everyone there faces it. */
		final Map<Long, int[]> fires = new HashMap<>();
		/** Small groups in the open that are posed (angled or facing each other). */
		final Map<Long, PersonalSpaceConfig.Pose> poses = new HashMap<>();
		/** What was decided for each spread tile, for the troubleshooting report. */
		final Map<Long, TileReport> tiles = new HashMap<>();
		/** Players standing still on a tile with company whom the game isn't showing. */
		int unseen;
	}

	/** What was decided for one spread tile. */
	static final class TileReport
	{
		final String shape;
		final int spacing;
		final int shown;
		final int moved;
		final int spots;
		final int blocked;

		TileReport(String shape, int spacing, int shown, int moved, int spots, int blocked)
		{
			this.shape = shape;
			this.spacing = spacing;
			this.shown = shown;
			this.moved = moved;
			this.spots = spots;
			this.blocked = blocked;
		}

		@Override
		public String toString()
		{
			return shape + ", spacing " + spacing + ", " + shown + " players, " + moved + " moved, "
				+ spots + " spots, " + blocked + " blocked by walls or objects";
		}
	}

	private static final int HALF_TILE = 64;
	/** Someone is facing a fire if it's within this angle of straight ahead (45 degrees). */
	private static final double FACING_FIRE = Math.cos(Math.toRadians(45));

	private final SlotBook slots = new SlotBook();
	private final ShapeMemory shapes = new ShapeMemory();
	private final LineBook lineBook = new LineBook();

	/** The "Small groups stay close" setting: whether spacing grows with the size of the group. Set by the plugin each tick. */
	boolean smallGroupsClose = true;

	/** How the sidebar says crowds should be drawn up. Only Line and Arc change what happens here. */
	PersonalSpaceConfig.Arrangement arrangement = PersonalSpaceConfig.Arrangement.AUTO;

	/**
	 * Where the camera is on the ground, in scene units, or {@link Integer#MIN_VALUE} when unknown.
	 * It never moves you: once you've stood a moment, whoever would stand between you and it steps
	 * aside to a free spot, so you aren't buried in the middle of a crowd seen end on.
	 */
	int cameraX = Integer.MIN_VALUE;
	int cameraY = Integer.MIN_VALUE;

	/** The camera's direction from you, as one of eight, once it has settled there; -1 when unknown. */
	private int viewSector = -1;
	private int sectorSeen = -1;
	private int sectorSince;

	/** Last tick's spots on each laid-out tile, so the ones in front of you can be kept clear. */
	private Map<Long, Map<Integer, Integer>> lastSlots = new HashMap<>();
	/** Where you were drawn last tick, east then north in scene units; null when you weren't. */
	private double[] lastYouAt;
	/** The tick you last changed spot: nobody steps aside until you've stood a moment. */
	private int youStillSince;
	/** Which spot you had last tick, as tile and spot, so a crowd's spacing changing isn't a move. */
	private String lastYouKey;
	private long lastYouTile;
	private int lastYouSlot = -1;
	/** Tiles further than this from where you were drawn aren't checked for standing in your way. */
	private static final int VIEW_TILES = 6;

	/** Directions the camera's bearing is rounded to. */
	static final int VIEW_SECTORS = 8;
	/**
	 * Ticks the camera must stay in a new direction, about three seconds, before the people in your
	 * way step aside for it. Glancing round the room moves nobody; only resting the camera somewhere
	 * new does, once. Someone busy with an action can't walk, so they glide to their new spot, and
	 * this keeps that rare.
	 */
	static final int VIEW_SETTLE_TICKS = 5;
	/** Closer to the camera than this (in units) and the camera's direction from you isn't reliable. */
	private static final int VIEW_MIN_DISTANCE = 128;
	/** The "Small group pose" setting. Set by the plugin each tick. */
	PersonalSpaceConfig.Pose pose = PersonalSpaceConfig.Pose.NATURAL;

	/** Largest group the pose applies to. */
	static final int POSED_GROUP = 3;

	/** How many players each tile's spacing is sized for, and the last tick the tile was that big. */
	private Map<Long, int[]> previousSizes = new HashMap<>();
	private Map<Long, int[]> currentSizes = new HashMap<>();

	/** Tiles lined up because most of the group faces something, and which way. */
	private Map<Long, Double> previousMostFaced = new HashMap<>();
	private Map<Long, Double> currentMostFaced = new HashMap<>();

	/** Tiles laid out on a shared line, last tick and this. A tile on a line stays on it while it's spread. */
	private Set<Long> previousLineTiles = new HashSet<>();
	private Set<Long> currentLineTiles = new HashSet<>();

	/** Directions a row can face, straight ones first so a bank counter wins a tie with a diagonal. */
	private static final int[] EIGHTHS = {0, 2, 4, 6, 1, 3, 5, 7};

	/**
	 * The direction most of this group faces, if it has something in it (a booth, anvil, range or
	 * tree) and at least half of them face it. Null otherwise.
	 *
	 * <p>Everyone on a tile rarely faces exactly the same way: at a bank, a couple of people casting
	 * Superheat Item or trading can face anywhere. Once a tile has lined up this way it stays lined
	 * up for as long as it's spread, so someone turning around doesn't flip it back to a ring.
	 */
	private Double facedByMost(long tile, List<StackSpreader.Entry> group, Surroundings around)
	{
		Double remembered = previousMostFaced.get(tile);
		if (remembered != null)
		{
			currentMostFaced.put(tile, remembered);
			return remembered;
		}
		Double best = null;
		int bestCount = 0;
		for (int eighth : EIGHTHS)
		{
			double angle = eighth * Math.PI / 4;
			if (!around.facesObstacle(tile, angle))
			{
				continue;
			}
			int count = 0;
			for (StackSpreader.Entry e : group)
			{
				if (e.orientation < 0)
				{
					continue;
				}
				double a = StackSpreader.toRadians(e.orientation);
				double dot = Math.sin(a) * Math.sin(angle) + Math.cos(a) * Math.cos(angle);
				if (dot >= FACING_FIRE)
				{
					count++;
				}
			}
			if (count > bestCount)
			{
				best = angle;
				bestCount = count;
			}
		}
		if (best == null || bestCount * 2 < group.size())
		{
			return null;
		}
		currentMostFaced.put(tile, best);
		return best;
	}

	/**
	 * Spacing for a group of {@code players}: at most {@link PersonalSpaceConfig#PAIR_SPACING} for
	 * two, growing evenly to the slider's {@code spacing} at {@link PersonalSpaceConfig#FULL_CROWD}.
	 */
	static int spacingFor(int spacing, int players)
	{
		int pair = PersonalSpaceConfig.PAIR_SPACING;
		if (spacing <= pair)
		{
			return spacing;
		}
		int grown = Math.max(0, Math.min(players, PersonalSpaceConfig.FULL_CROWD) - 2);
		return pair + (spacing - pair) * grown / (PersonalSpaceConfig.FULL_CROWD - 2);
	}

	/**
	 * The fire this group is gathered round: of the fires next to their tile, the one most of them
	 * face, if at least half of them face it. Null if there is none.
	 */
	static int[] fireFaced(List<StackSpreader.Entry> group, List<int[]> fires)
	{
		int[] best = null;
		int bestCount = 0;
		for (int[] fire : fires)
		{
			double length = Math.hypot(fire[0], fire[1]);
			if (length == 0)
			{
				continue;
			}
			int count = 0;
			for (StackSpreader.Entry e : group)
			{
				if (e.orientation < 0)
				{
					continue;
				}
				double a = StackSpreader.toRadians(e.orientation);
				double dot = (-Math.sin(a) * fire[0] - Math.cos(a) * fire[1]) / length;
				if (dot >= FACING_FIRE)
				{
					count++;
				}
			}
			if (count > bestCount)
			{
				best = fire;
				bestCount = count;
			}
		}
		return best != null && bestCount * 2 >= group.size() ? best : null;
	}

	/** Closest a curved row sharing an object with another side is squeezed to. */
	static final int MIN_SHARED_SPACING = 32;

	/**
	 * Closest anyone is drawn to the people on the tiles around them: about a body's width, so
	 * nobody is drawn inside anybody else.
	 */
	static final int NEIGHBOUR_GAP = 48;
	/** Furthest a crowd moves over to make room, as a whole: half a tile. */
	static final int MAX_SHIFT = HALF_TILE;
	/**
	 * Closest a crowd closes up to make room for its neighbours: its own people are then 40 apart
	 * (its ring is 0.8 of the spacing across), about as close as a busy bank line. Closer than
	 * that it would only trade people drawn inside their neighbours for people drawn inside each
	 * other, so past it the neighbours share the gap.
	 */
	static final int ROOM_FLOOR = 50;
	/** How long a crowd that made room stays that way after its neighbours leave, about 6 seconds. */
	static final int RELAX_TICKS = 10;
	/**
	 * How long someone must have stood by a settled crowd before it makes room for them, on top of
	 * the moment it takes to count as standing still at all: a passer-by pausing for a second
	 * doesn't set a whole group sliding over and back.
	 */
	static final int SETTLE_TICKS = 2;

	/** Who stands on a tile, as a crowd making room sees it: nobody (0), someone alone (1) or a group (2). */
	private static final class Presence
	{
		int state;
		int since;
		/** The state once it has lasted {@link #SETTLE_TICKS}; fewer people count at once. */
		int settled;
		int lastAlone = Integer.MIN_VALUE / 2;
		int lastGroup = Integer.MIN_VALUE / 2;

		void observe(int now, int tick)
		{
			if (now != state)
			{
				state = now;
				since = tick;
			}
			if (tick - since >= SETTLE_TICKS || now < settled)
			{
				settled = now;
			}
			if (settled >= 1)
			{
				lastAlone = tick;
			}
			if (settled >= 2)
			{
				lastGroup = tick;
			}
		}

		/**
		 * Who counts as standing here: whoever has settled, and whoever left less than
		 * {@link #RELAX_TICKS} ago. A crowd that has only just formed takes them as they are, so
		 * two groups arriving together make room for each other at once.
		 */
		int counted(int tick, boolean justFormed)
		{
			int remembered = tick - lastGroup <= RELAX_TICKS ? 2 : tick - lastAlone <= RELAX_TICKS ? 1 : 0;
			return justFormed ? Math.max(remembered, state) : remembered;
		}
	}

	private final Map<Long, Presence> presence = new HashMap<>();

	/**
	 * Note who stands on each tile this tick. Only players the game is drawing count: a group
	 * doesn't make room for people another plugin or the game itself keeps hidden.
	 */
	private void observePresence(Map<Long, List<StackSpreader.Entry>> byTile, IntPredicate shown, int tick)
	{
		Set<Long> seen = new HashSet<>();
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			int n = 0;
			for (StackSpreader.Entry en : e.getValue())
			{
				n += shown.test(en.id) ? 1 : 0;
			}
			if (n > 0)
			{
				seen.add(e.getKey());
				presence.computeIfAbsent(e.getKey(), k -> new Presence()).observe(Math.min(n, 2), tick);
			}
		}
		for (java.util.Iterator<Map.Entry<Long, Presence>> it = presence.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Long, Presence> e = it.next();
			if (!seen.contains(e.getKey()))
			{
				e.getValue().observe(0, tick);
				if (e.getValue().counted(tick, false) == 0)
				{
					it.remove();
				}
			}
		}
	}

	/** Whether this tile's group has only just formed. */
	private boolean justFormed(long tile, int tick)
	{
		Presence p = presence.get(tile);
		return p != null && p.state >= 2 && tick - p.since < SETTLE_TICKS;
	}

	/**
	 * The ground a tile shares with the people on the tiles around it, as half-planes {ux, uz, b}:
	 * every spot p, from this tile's middle, must keep {@code ux * p.x + uz * p.z <= b}. Towards a
	 * group, that is halfway less half of {@link #NEIGHBOUR_GAP}, so each keeps to its own side of
	 * the ground between them. Towards someone standing alone, who stays in the middle of their
	 * tile, it is a gap short of them. Only neighbours near enough to matter to spots within
	 * {@code reach} are included, and only those who count ({@link Presence#counted}).
	 *
	 * @param skip      tiles to leave out, which the layout already shares ground with some other way
	 * @param aloneAsPoint give someone standing alone as {x, z, NaN}: keep a gap from that point,
	 *                  rather than a straight line a gap short of it
	 */
	List<double[]> sharesOf(long tile, double reach, java.util.function.LongPredicate skip, int tick, boolean aloneAsPoint)
	{
		List<double[]> out = new ArrayList<>();
		int plane = StackRegistry.plane(tile);
		boolean fresh = justFormed(tile, tick);
		for (Map.Entry<Long, Presence> e : presence.entrySet())
		{
			long other = e.getKey();
			if (other == tile || StackRegistry.plane(other) != plane || (skip != null && skip.test(other)))
			{
				continue;
			}
			int who = e.getValue().counted(tick, fresh);
			if (who == 0 || who == 1 && other == yourTileNow)
			{
				// Nobody, or just you: a group doesn't back away from you standing beside it. It
				// parted like the sea wherever you stood. Your personal space keeps anyone from
				// being drawn inside you, and only whoever would be steps aside.
				continue;
			}
			double x = (StackRegistry.sceneX(other) - StackRegistry.sceneX(tile)) * 2.0 * HALF_TILE;
			double z = (StackRegistry.sceneY(other) - StackRegistry.sceneY(tile)) * 2.0 * HALF_TILE;
			double d = Math.hypot(x, z);
			double b = who == 1 ? d - NEIGHBOUR_GAP : d / 2 - NEIGHBOUR_GAP / 2.0;
			if (b < reach)
			{
				out.add(who == 1 && aloneAsPoint ? new double[]{x, z, Double.NaN} : new double[]{x / d, z / d, b});
			}
		}
		return out;
	}

	/** Whether a spot keeps to the ground its tile shares with the people around it. */
	static boolean withinShares(List<double[]> shares, double dx, double dz)
	{
		for (double[] h : shares)
		{
			if (Double.isNaN(h[2]) ? Math.hypot(dx - h[0], dz - h[1]) < NEIGHBOUR_GAP - 1 : h[0] * dx + h[1] * dz > h[2] + 1)
			{
				return false;
			}
		}
		return true;
	}

	/** How a crowd makes room for its neighbours: moved over by (x, z), at this spacing. */
	static final class Room
	{
		final double x;
		final double z;
		final int spacing;
		/** The spacing it would have with nobody around. */
		final int wanted;
		/** Whether its people keep to their share of the ground; false when there's no way they can. */
		final boolean fits;

		Room(double x, double z, int spacing, int wanted, boolean fits)
		{
			this.x = x;
			this.z = z;
			this.spacing = spacing;
			this.wanted = wanted;
			this.fits = fits;
		}

		boolean moved()
		{
			return x != 0 || z != 0;
		}
	}

	private Map<Long, Room> rooms = new HashMap<>();
	private Map<Long, Room> roomsNow = new HashMap<>();
	/** The plane you stand on. */
	private static int yourPlane(List<StackSpreader.Entry> still)
	{
		for (StackSpreader.Entry e : still)
		{
			if (e.local)
			{
				return StackRegistry.plane(e.tile);
			}
		}
		return -1;
	}

	/**
	 * Nobody is given a spot closer to you than this: just inside a busy bank line's spacing, so the
	 * people beside you at a counter keep their places, but nobody is drawn inside you.
	 */
	static final int YOUR_SPACE = 40;

	/** Spots past the players-per-tile limit that tiles near you lay out, for stepping out of your way. */
	static final int SPARE_SPOTS = 4;
	private final Set<Long> nearYou = new HashSet<>();

	/** How many spots a tile lays out: the limit, and some spare near you. */
	private int spotsFor(long tile, int capacity)
	{
		return capacity + (nearYou.contains(tile) ? SPARE_SPOTS : 0);
	}

	/** Your player id, remembered while you walk about (when you aren't among the still). */
	private int knownYou = -1;
	/** The tile you're standing still on this tick, or none. */
	private long yourTileNow = Long.MIN_VALUE;

	/** Tiles where someone the game shows was left in the middle without a spot last tick. */
	private Set<Long> waited = new HashSet<>();

	/**
	 * How a crowd of {@code people} makes room for the groups and people on the tiles around it.
	 * The whole ring moves over, by up to half a tile, and closes up if it must, but keeps its
	 * shape: nobody swaps places or walks round the others. It takes the widest spacing, up to
	 * {@code wanted}, at which it keeps to its share of the ground, moved over as little as possible.
	 * Crowds in the open used to take no notice of each other, and two groups side by side each
	 * spread into the gap between them until people were drawn inside each other.
	 *
	 * <p>It stays as it is while that still keeps to its shares and moving would gain it nothing:
	 * it only eases back out once the neighbours have gone for {@link #RELAX_TICKS} (their shares
	 * are remembered that long), and it never slides over just to stand a little nearer its tile.
	 *
	 * @param middle whether the middle of the tile is left out of its spots
	 * @param guard  whether anyone may be standing in the middle of the tile, whom its ring must
	 *               keep clear of however it moves over
	 */
	Room roomFor(long tile, List<double[]> shares, int wanted, boolean middle, boolean guard, int people)
	{
		Room best;
		if (shares.isEmpty())
		{
			best = new Room(0, 0, wanted, wanted, true);
		}
		else
		{
			int floor = Math.min(wanted, ROOM_FLOOR);
			int spacing = wanted;
			List<double[]> area = roomAt(shares, middle, guard, spacing, people);
			if (area.isEmpty())
			{
				// The widest spacing that fits, found by halving: a closer ring nearly always fits
				// where a wider one does.
				int lo = floor;
				int hi = wanted;
				while (hi - lo > 1)
				{
					int mid = (lo + hi) / 2;
					if (roomAt(shares, middle, guard, mid, people).isEmpty())
					{
						hi = mid;
					}
					else
					{
						lo = mid;
					}
				}
				spacing = lo;
				area = roomAt(shares, middle, guard, spacing, people);
			}
			if (area.isEmpty())
			{
				double[] c = leastCrowded(shares, StackSpreader.spots(false, false, 0, middle, spacing, people, null),
					farAt(guard, spacing));
				best = new Room(c[0], c[1], spacing, wanted, false);
			}
			else
			{
				double[] c = nearestTo(area, 0, 0);
				best = new Room(Math.round(c[0]), Math.round(c[1]), spacing, wanted, true);
			}
		}

		// Tightening happens at once, or people would stand inside each other, and by as little as
		// will do from where the crowd stands now. Otherwise it stays put unless moving gains it
		// more room or lets it stop making room altogether.
		Room was = rooms.get(tile);
		Room chosen = best;
		if (was != null && was.wanted == wanted && best.fits)
		{
			if (was.fits && fitsAt(shares, middle, guard, was, people))
			{
				chosen = best.spacing > was.spacing || !best.moved() ? best : was;
			}
			else
			{
				int spacing = Math.min(was.spacing, best.spacing);
				List<double[]> area = roomAt(shares, middle, guard, spacing, people);
				if (!area.isEmpty())
				{
					double[] c = nearestTo(area, was.x, was.z);
					chosen = new Room(Math.round(c[0]), Math.round(c[1]), spacing, wanted, true);
				}
			}
		}
		roomsNow.put(tile, chosen);
		return chosen;
	}

	/**
	 * A crowd's spot check, for spots laid out around its moved middle: the ground there, its
	 * shares, and when it has moved over with someone in the middle of the tile, a gap clear of them.
	 */
	private static StackSpreader.SpotCheck movedOver(StackSpreader.SpotCheck check, List<double[]> shares, Room room, boolean guard)
	{
		final double mx = room.x;
		final double mz = room.z;
		final boolean keepToShares = room.fits;
		final boolean clearOfMiddle = guard && room.moved();
		return (dx, dz) -> check.canStand((int) Math.round(dx + mx), (int) Math.round(dz + mz))
			&& (!keepToShares || withinShares(shares, dx + mx, dz + mz))
			&& (!clearOfMiddle || Math.hypot(dx + mx, dz + mz) >= NEIGHBOUR_GAP - 1);
	}

	/**
	 * Where a crowd's middle may be moved to, within half a tile, for its first {@code people}
	 * spots at this spacing to keep to their shares: a convex polygon, empty if nowhere will do.
	 */
	private static List<double[]> roomAt(List<double[]> shares, boolean middle, boolean guard, int spacing, int people)
	{
		List<int[]> spots = StackSpreader.spots(false, false, 0, middle, spacing, people, null);
		double far = farAt(guard, spacing);
		List<double[]> poly = new ArrayList<>();
		poly.add(new double[]{-far, -far});
		poly.add(new double[]{far, -far});
		poly.add(new double[]{far, far});
		poly.add(new double[]{-far, far});
		for (double[] h : shares)
		{
			double furthest = -Double.MAX_VALUE;
			for (int[] p : spots)
			{
				furthest = Math.max(furthest, h[0] * p[0] + h[1] * p[1]);
			}
			poly = clip(poly, h[0], h[1], h[2] - furthest);
			if (poly.isEmpty())
			{
				break;
			}
		}
		return poly;
	}

	/**
	 * How far a ring at this spacing may move over. With someone standing in the middle of the tile
	 * (you, when your character stays put, or someone without a spot), moving the ring over would
	 * bring its near side onto them: it may only move as far as keeps its ring a gap clear of them.
	 */
	private static double farAt(boolean guard, int spacing)
	{
		return guard ? Math.max(0, Math.min(MAX_SHIFT, (0.8 * spacing - NEIGHBOUR_GAP) / Math.sqrt(2))) : MAX_SHIFT;
	}

	/** Whether a crowd standing as {@code room} says keeps to its shares, moved over no further than it may. */
	private static boolean fitsAt(List<double[]> shares, boolean middle, boolean guard, Room room, int people)
	{
		double far = farAt(guard, room.spacing);
		if (Math.abs(room.x) > far + 1 || Math.abs(room.z) > far + 1)
		{
			return false;
		}
		for (int[] p : StackSpreader.spots(false, false, 0, middle, room.spacing, people, null))
		{
			if (!withinShares(shares, p[0] + room.x, p[1] + room.z))
			{
				return false;
			}
		}
		return true;
	}

	/** The part of a convex polygon where {@code ux * x + uz * z <= k}. */
	private static List<double[]> clip(List<double[]> poly, double ux, double uz, double k)
	{
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < poly.size(); i++)
		{
			double[] a = poly.get(i);
			double[] b = poly.get((i + 1) % poly.size());
			double da = ux * a[0] + uz * a[1] - k;
			double db = ux * b[0] + uz * b[1] - k;
			if (da <= 1e-9)
			{
				out.add(a);
			}
			if ((da < -1e-9 && db > 1e-9) || (da > 1e-9 && db < -1e-9))
			{
				double t = da / (da - db);
				out.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])});
			}
		}
		return out;
	}

	/** The point of a convex polygon nearest (x, z). */
	private static double[] nearestTo(List<double[]> poly, double x, double z)
	{
		// A polygon squeezed to a line (a crowd with exactly enough room between two neighbours)
		// has no inside: every point along that line would pass the test below.
		double area = 0;
		for (int i = 0; i < poly.size(); i++)
		{
			double[] a = poly.get(i);
			double[] b = poly.get((i + 1) % poly.size());
			area += a[0] * b[1] - b[0] * a[1];
		}
		boolean inside = Math.abs(area) > 1e-6;
		for (int i = 0; i < poly.size() && inside; i++)
		{
			double[] a = poly.get(i);
			double[] b = poly.get((i + 1) % poly.size());
			inside = (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]) >= -1e-9;
		}
		if (inside || poly.size() == 1)
		{
			return inside ? new double[]{x, z} : poly.get(0);
		}
		double[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < poly.size(); i++)
		{
			double[] a = poly.get(i);
			double[] b = poly.get((i + 1) % poly.size());
			double ex = b[0] - a[0];
			double ez = b[1] - a[1];
			double length = ex * ex + ez * ez;
			double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * ex + (z - a[1]) * ez) / length));
			double[] p = {a[0] + t * ex, a[1] + t * ez};
			double d = Math.hypot(p[0] - x, p[1] - z);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = p;
			}
		}
		return best;
	}

	/**
	 * Where to move a crowd that can't keep to its shares at all, as in a solid block of full
	 * tiles: wherever it overlaps its neighbours least, within half a tile.
	 */
	private static double[] leastCrowded(List<double[]> shares, List<int[]> spots, double far)
	{
		double[] c = {0, 0};
		for (int round = 0; round < 24; round++)
		{
			double worst = 0;
			double[] push = null;
			for (double[] h : shares)
			{
				double furthest = -Double.MAX_VALUE;
				for (int[] p : spots)
				{
					furthest = Math.max(furthest, h[0] * (p[0] + c[0]) + h[1] * (p[1] + c[1]));
				}
				if (furthest - h[2] > worst)
				{
					worst = furthest - h[2];
					push = h;
				}
			}
			if (push == null)
			{
				break;
			}
			c[0] = Math.max(-far, Math.min(far, c[0] - push[0] * worst / 2));
			c[1] = Math.max(-far, Math.min(far, c[1] - push[1] * worst / 2));
		}
		return new double[]{Math.round(c[0]), Math.round(c[1])};
	}

	/**
	 * How far round a curved row on this tile may reach before it meets someone standing on another
	 * side of the same thing: {anticlockwise, clockwise}, in radians, each half the angle to the
	 * nearest occupied tile around what the row faces (or {@link Double#MAX_VALUE} if there's none).
	 */
	private static double[] arcLimits(Map<Long, List<StackSpreader.Entry>> byTile, int plane, int sceneX, int sceneY, double angle, long ignore)
	{
		int aheadX = (int) Math.round(-Math.sin(angle));
		int aheadY = (int) Math.round(-Math.cos(angle));
		double focusX = -Math.sin(angle) * StackSpreader.LOOK_AHEAD;
		double focusZ = -Math.cos(angle) * StackSpreader.LOOK_AHEAD;
		double[] limits = {Double.MAX_VALUE, Double.MAX_VALUE};
		for (int ox = -1; ox <= 1; ox++)
		{
			for (int oy = -1; oy <= 1; oy++)
			{
				int nx = sceneX + aheadX + ox;
				int ny = sceneY + aheadY + oy;
				if ((ox == 0 && oy == 0) || (nx == sceneX && ny == sceneY)
					|| !byTile.containsKey(StackRegistry.key(plane, nx, ny)) || StackRegistry.key(plane, nx, ny) == ignore)
				{
					continue;
				}
				double theta = signedAngle(-focusX, -focusZ,
					(nx - sceneX) * 2.0 * HALF_TILE - focusX, (ny - sceneY) * 2.0 * HALF_TILE - focusZ);
				if (theta > 0)
				{
					limits[0] = Math.min(limits[0], theta / 2);
				}
				else if (theta < 0)
				{
					limits[1] = Math.min(limits[1], -theta / 2);
				}
			}
		}
		return limits;
	}

	/** Angle from direction (ax, az) round to (bx, bz), in (-pi, pi]; positive is anticlockwise seen from above (east to north). */
	static double signedAngle(double ax, double az, double bx, double bz)
	{
		return Math.atan2(ax * bz - az * bx, ax * bx + az * bz);
	}

	/**
	 * How far a straight counter row may reach towards one side: short of the next tile along the
	 * counter if anyone stands there, short of the tile after that if anyone stands there (its row
	 * may reach back into the gap), and otherwise as far as the pattern goes.
	 */
	private static double counterReach(Map<Long, List<StackSpreader.Entry>> byTile, int plane, int sceneX, int sceneY,
		int sideX, int sideY, int spacing, long ignore)
	{
		for (int k = 1; k <= LINE_LOOKOUT; k++)
		{
			long key = StackRegistry.key(plane, sceneX + k * sideX, sceneY + k * sideY);
			List<StackSpreader.Entry> there = key == ignore ? null : byTile.get(key);
			if (there != null)
			{
				return reachTowards(there.size(), k, spacing);
			}
		}
		return Double.MAX_VALUE;
	}

	/**
	 * A tile's spots: its own shape first. If walls, water or neighbours leave too few for everyone,
	 * a tight ring on the tile is tried too, first at the tile's spacing and then closer, and used if
	 * it fits more people. Better close together than hidden in the middle.
	 *
	 * @param wanted   how many people need a spot
	 * @param blocked  set to how many spots the chosen layout had blocked
	 * @param squeezed set to whether the ring was used instead of the tile's own shape
	 */
	private static List<int[]> spotsWithRoom(boolean row, boolean straight, double angle, boolean middleTaken, int spacing,
		int capacity, int wanted, StackSpreader.SpotCheck check, int[] blocked, boolean[] squeezed, int arcRadius, boolean bow,
		double wrap, int[] used)
	{
		blocked[0] = 0;
		List<int[]> best = StackSpreader.spots(row, straight, angle, middleTaken, spacing, capacity, check, arcRadius, bow, wrap);
		int bestBlocked = blocked[0];
		squeezed[0] = false;
		used[0] = spacing;
		// Too little room for what they wanted: give up room a step at a time until everyone fits,
		// keeping their shape for as long as it works and only then falling back to a ring. The steps
		// are the same whatever the slider says, so a wider setting always lands on the same step as a
		// narrower one or a wider step, never a tighter one.
		for (int s = PersonalSpaceConfig.MAX_SPACING; best.size() < wanted; s = Math.max(MIN_SHARED_SPACING, s * 3 / 4))
		{
			if (s <= spacing)
			{
				if (row && s != spacing)
				{
					blocked[0] = 0;
					List<int[]> closer = StackSpreader.spots(true, straight, angle, middleTaken, s, capacity, check, arcRadius,
						bow, wrap);
					if (closer.size() > best.size())
					{
						best = closer;
						bestBlocked = blocked[0];
						squeezed[0] = false;
						used[0] = s;
					}
				}
				if (best.size() < wanted && (row || s != spacing))
				{
					blocked[0] = 0;
					List<int[]> ring = StackSpreader.spots(false, false, angle, middleTaken, s, capacity, check);
					if (ring.size() > best.size())
					{
						best = ring;
						bestBlocked = blocked[0];
						squeezed[0] = true;
						used[0] = s;
					}
				}
			}
			if (s == MIN_SHARED_SPACING)
			{
				break;
			}
		}
		blocked[0] = bestBlocked;
		return best;
	}

	/**
	 * How far a row may reach towards a tile {@code tilesAway} along the edge with {@code people} on
	 * it. Someone alone stays in the middle of their tile, so the row only keeps a spacing clear of
	 * them. A group spreads into a row of its own, so the two rows meet halfway.
	 */
	private static double reachTowards(int people, int tilesAway, int spacing)
	{
		// Never less than the least room a squeezed ring needs. Keeping clear of the neighbours is
		// worth giving up ground for, but not the whole tile: a wide spacing with someone standing
		// either side used to leave a reach of nothing, no spot anywhere passed the check, and
		// everybody was drawn stacked on the tile's middle - the very thing this plugin is for.
		return people == 1
			? Math.max(MIN_SHARED_SPACING, tilesAway * 2.0 * HALF_TILE - spacing)
			: Math.max(MIN_SHARED_SPACING, tilesAway * HALF_TILE - spacing / 2.0);
	}

	/**
	 * A line shared along a counter or riverbank stands {@link PersonalSpaceConfig#COUNTER_SPACING}
	 * apart until one of its tiles has more people than this, when it closes up to
	 * {@link PersonalSpaceConfig#LINE_SPACING} to fit them, and stays that way for
	 * {@link #BUSY_LINE_TICKS} after it thins out again: changing the spacing lays the whole line
	 * out afresh, so a bank where people keep coming and going stays compact rather than
	 * rearranging itself every few seconds.
	 */
	static final int ROOMY_LINE_MAX = 8;
	/** A closed-up line only starts counting down to easing back out once no tile on it has more than this. */
	static final int ROOMY_LINE_CALM = 6;
	/** How long a line that had to close up stays that way: about half a minute. */
	static final int BUSY_LINE_TICKS = 50;

	/** Tiles on a line that is closed up for being busy, and the tick until which it stays so. */
	private final Map<Long, Integer> busyLineUntil = new HashMap<>();

	private static boolean lineUpOrCurve(PersonalSpaceConfig.Arrangement arrangement)
	{
		return arrangement == PersonalSpaceConfig.Arrangement.ROW || arrangement == PersonalSpaceConfig.Arrangement.ARC;
	}

	/**
	 * Which way the bank counter or row of booths next to a tile is, looking square on, or null if
	 * there is none. With counters on more than one side (a corner), the one most of the group
	 * faces nearest.
	 */
	static Double counterBeside(long tile, List<StackSpreader.Entry> group, Surroundings around)
	{
		Double best = null;
		double bestScore = -Double.MAX_VALUE;
		for (int side = 0; side < 4; side++)
		{
			double angle = side * Math.PI / 2;
			if (!around.isCounter(tile, angle))
			{
				continue;
			}
			double score = 0;
			for (StackSpreader.Entry e : group)
			{
				if (e.orientation >= 0)
				{
					score += Math.cos(StackSpreader.toRadians(e.orientation) - angle);
				}
			}
			if (score > bestScore)
			{
				bestScore = score;
				best = angle;
			}
		}
		return best;
	}

	/** How many tiles along a counter or bank to look for someone else's row. */
	private static final int LINE_LOOKOUT = 4;

	/** A tile lined up along a counter or riverbank, waiting to be given its spots. */
	private static final class Straight
	{
		final long tile;
		final int plane;
		final int sceneX;
		final int sceneY;
		final int sideX;
		final int sideY;
		final double angle;
		final int spacing;
		/** The spacing if it ends up sharing a line with its neighbours: closer at a busy counter. */
		final int lineSpacing;
		final boolean middleTaken;
		final int wanted;
		final StackSpreader.SpotCheck check;
		final int[] blocked;
		/** Position along the line, in tiles. */
		final int along;
		/** Which line this is: the other scene coordinate. */
		final int across;

		Straight(long tile, int plane, int sceneX, int sceneY, int sideX, int sideY, double angle, int spacing,
			int lineSpacing, boolean middleTaken, int wanted, StackSpreader.SpotCheck check, int[] blocked)
		{
			this.lineSpacing = lineSpacing;
			this.tile = tile;
			this.plane = plane;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
			this.sideX = sideX;
			this.sideY = sideY;
			this.angle = angle;
			this.spacing = spacing;
			this.middleTaken = middleTaken;
			this.wanted = wanted;
			this.check = check;
			this.blocked = blocked;
			this.along = sceneX * sideX + sceneY * sideY;
			this.across = sideX != 0 ? sceneY : sceneX;
		}

		double centre()
		{
			return along * 2.0 * HALF_TILE;
		}

		long keyAt(int alongTiles)
		{
			return sideX != 0
				? StackRegistry.key(plane, alongTiles * sideX, across)
				: StackRegistry.key(plane, across, alongTiles * sideY);
		}
	}

	/**
	 * Give every counter or riverbank tile its spots. A tile on its own keeps its own row.
	 * Neighbouring tiles along the same edge become one shared line, laid out by {@link LineBook};
	 * those tiles are taken out of {@code movableByTile} and returned.
	 */
	@SuppressWarnings("SameParameterValue")
	private List<LineBook.Line> layOutStraightRows(List<Straight> rows, Map<Long, List<StackSpreader.Entry>> byTile, int capacity,
		Map<Long, List<Integer>> movableByTile, Map<Long, List<int[]>> spotsByTile, Map<Long, String> shapeByTile,
		Map<Long, Integer> spacingByTile, Map<Long, Integer> blockedByTile, Map<Long, Integer> shownByTile,
		Surroundings around, IntPredicate shown, boolean includeLocal, int tick)
	{
		busyLineUntil.values().removeIf(until -> until < tick);
		boolean bowRows = arrangement != PersonalSpaceConfig.Arrangement.ROW;
		List<LineBook.Line> shared = new ArrayList<>();
		Map<String, List<Straight>> lines = new LinkedHashMap<>();

		// Someone alone on the next tile along, facing the same edge, joins the line rather than ending
		// it. Busy fishing tiles drop to one person and back all the time; if that ended the line each
		// time, everyone standing over that tile would be sent to a row behind at once.
		Map<Long, Integer> loneId = new HashMap<>();
		Set<Long> rowTiles = new HashSet<>();
		for (Straight r : rows)
		{
			rowTiles.add(r.tile);
		}
		List<Straight> withLone = new ArrayList<>(rows);
		for (Straight r : rows)
		{
			for (int direction : new int[]{-1, 1})
			{
				long next = r.keyAt(r.along + direction);
				List<StackSpreader.Entry> there = byTile.get(next);
				if (there == null || there.size() != 1 || rowTiles.contains(next) || loneId.containsKey(next))
				{
					continue;
				}
				StackSpreader.Entry lone = there.get(0);
				if ((lone.local && !includeLocal) || lone.orientation < 0 || !shown.test(lone.id)
					|| !around.facesObstacle(next, r.angle))
				{
					continue;
				}
				double facing = StackSpreader.toRadians(lone.orientation);
				if (Math.sin(facing) * Math.sin(r.angle) + Math.cos(facing) * Math.cos(r.angle) < FACING_FIRE)
				{
					continue; // standing by the edge, but not facing it
				}
				loneId.put(next, lone.id);
				withLone.add(new Straight(next, r.plane, StackRegistry.sceneX(next), StackRegistry.sceneY(next), r.sideX, r.sideY,
					r.angle, r.spacing, r.lineSpacing, false, 1, r.check, new int[1]));
			}
		}
		rows = withLone;
		for (Straight r : rows)
		{
			lines.computeIfAbsent(r.plane + ":" + r.sideX + ":" + r.sideY + ":" + r.across, k -> new ArrayList<>()).add(r);
		}
		for (List<Straight> line : lines.values())
		{
			line.sort(Comparator.comparingInt(r -> r.along));
			int start = 0;
			for (int i = 1; i <= line.size(); i++)
			{
				if (i < line.size() && line.get(i).along == line.get(i - 1).along + 1)
				{
					continue;
				}
				List<Straight> chain = line.subList(start, i);
				// A shared line needs two busy tiles side by side to start. Once a tile is on a line it
				// stays on it, even if its neighbour empties, so its people keep their places; and one
				// busy tile isn't re-laid as a line just because someone stops beside it for a moment.
				int real = 0;
				boolean wasLine = false;
				for (Straight r : chain)
				{
					boolean lone = loneId.containsKey(r.tile);
					real += lone ? 0 : 1;
					wasLine |= !lone && previousLineTiles.contains(r.tile);
				}
				if (real == 0 || (real < 2 && !wasLine))
				{
					for (Straight r : chain)
					{
						if (loneId.containsKey(r.tile))
						{
							continue;
						}
						boolean[] squeezed = {false};
						int[] used = {r.spacing};
						List<int[]> spots = spotsWithRoom(true, true, r.angle, r.middleTaken, r.spacing, spotsFor(r.tile, capacity), r.wanted,
							r.check, r.blocked, squeezed, StackSpreader.LOOK_AHEAD, bowRows, StackSpreader.WRAP_ARC, used);
						if (!r.middleTaken && spots.size() < r.wanted)
						{
							// Someone will be left in the middle without a spot: don't give the middle away too.
							spots = spotsWithRoom(true, true, r.angle, true, r.spacing, spotsFor(r.tile, capacity), r.wanted, r.check, r.blocked, squeezed,
								StackSpreader.LOOK_AHEAD, bowRows, StackSpreader.WRAP_ARC, used);
						}
						if (squeezed[0])
						{
							shapeByTile.put(r.tile, shapeByTile.get(r.tile) + ", squeezed into a ring");
						}
						spotsByTile.put(r.tile, spots);
						blockedByTile.put(r.tile, r.blocked[0]);
					}
				}
				else
				{
					// Roomy, unless one of its tiles is busy now or was a moment ago.
					boolean busy = false;
					for (Straight r : chain)
					{
						busy |= r.wanted > ROOMY_LINE_MAX || busyLineUntil.getOrDefault(r.tile, Integer.MIN_VALUE) >= tick;
					}
					int spacing = Integer.MAX_VALUE;
					for (Straight r : chain)
					{
						if (r.wanted > (busy ? ROOMY_LINE_CALM : ROOMY_LINE_MAX))
						{
							for (Straight other : chain)
							{
								busyLineUntil.put(other.tile, tick + BUSY_LINE_TICKS);
							}
						}
						spacing = Math.min(spacing, busy ? r.lineSpacing : r.spacing);
					}
					List<LineBook.Member> members = new ArrayList<>(chain.size());
					for (Straight r : chain)
					{
						Integer lone = loneId.get(r.tile);
						List<Integer> ids = movableByTile.remove(r.tile);
						if (lone != null)
						{
							ids = new ArrayList<>();
							ids.add(lone);
							shownByTile.put(r.tile, 1);
						}
						members.add(new LineBook.Member(r.tile, r.along, ids, r.middleTaken, capacity));
						if (lone == null)
						{
							currentLineTiles.add(r.tile);
						}
						shapeByTile.put(r.tile, "counter row shared by " + chain.size() + " tiles");
						spacingByTile.put(r.tile, spacing);
					}
					Straight first = chain.get(0);
					Straight last = chain.get(chain.size() - 1);
					// A line only curves where there is nobody behind it to curve into: the wings of a bow
					// fall back from the edge, and a row behind falls back further still.
					boolean roomToBow = bowRows;
					for (Straight r : chain)
					{
						for (int back = 1; back <= 2 && roomToBow; back++)
						{
							int aheadX = (int) Math.round(-Math.sin(r.angle));
							int aheadZ = (int) Math.round(-Math.cos(r.angle));
							roomToBow = !byTile.containsKey(
								StackRegistry.key(r.plane, r.sceneX - aheadX * back, r.sceneY - aheadZ * back));
						}
					}
					shared.add(new LineBook.Line(first.plane, first.sideX, first.sideY, first.across, first.angle, spacing, members,
						first.centre() - lineEnd(byTile, loneId, first, -1, spacing),
						last.centre() + lineEnd(byTile, loneId, last, 1, spacing), roomToBow));
				}
				start = i;
			}
		}
		return shared;
	}

	/** How far a shared line may reach past its end: halfway to the next row along it, or three tiles. */
	private static double lineEnd(Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, Integer> loneId, Straight end,
		int direction, int spacing)
	{
		for (int k = 1; k <= LINE_LOOKOUT; k++)
		{
			long key = end.keyAt(end.along + direction * k);
			List<StackSpreader.Entry> there = byTile.get(key);
			if (there != null)
			{
				// Someone standing alone who has joined a line of their own stands out on that line,
				// not in the middle of their tile, so leave them the room a pair needs. Two lines
				// either side of them would otherwise both reach for the same ground.
				return reachTowards(loneId.containsKey(key) ? 2 : there.size(), k, spacing);
			}
		}
		return StackSpreader.MAX_LINE_EXTENT;
	}

	/**
	 * Which way the camera is from you, as a unit vector on the ground (east, north), rounded to one
	 * of {@link #VIEW_SECTORS} and only changed once it has settled; null when there is no camera to
	 * go by or you aren't standing still.
	 */
	private double[] viewFrom(List<StackSpreader.Entry> still, int tick)
	{
		StackSpreader.Entry you = null;
		for (StackSpreader.Entry e : still)
		{
			you = e.local ? e : you;
		}
		if (you == null || cameraX == Integer.MIN_VALUE || cameraY == Integer.MIN_VALUE)
		{
			viewSector = -1;
			sectorSeen = -1;
			return null;
		}
		double east = cameraX - (StackRegistry.sceneX(you.tile) * 2.0 * HALF_TILE + HALF_TILE);
		double north = cameraY - (StackRegistry.sceneY(you.tile) * 2.0 * HALF_TILE + HALF_TILE);
		if (Math.hypot(east, north) >= VIEW_MIN_DISTANCE)
		{
			double slice = 2 * Math.PI / VIEW_SECTORS;
			int sector = Math.floorMod((int) Math.round(Math.atan2(north, east) / slice), VIEW_SECTORS);
			if (viewSector < 0)
			{
				// Start the settling afresh: left over from before you walked, it let a one-tick
				// glance straight after you stopped count as settled.
				viewSector = sector;
				sectorSeen = sector;
				sectorSince = tick;
			}
			else if (sector == viewSector)
			{
				sectorSeen = sector;
			}
			else if (sector != sectorSeen)
			{
				sectorSeen = sector;
				sectorSince = tick;
			}
			else if (tick - sectorSince >= VIEW_SETTLE_TICKS)
			{
				viewSector = sector;
			}
		}
		if (viewSector < 0)
		{
			return null;
		}
		double a = viewSector * 2 * Math.PI / VIEW_SECTORS;
		return new double[]{Math.cos(a), Math.sin(a)};
	}

	/**
	 * For every tile near you that is laid out on its own, the spots that stand between where you
	 * were drawn last tick and the camera. Your own tile and the booths beside it alike: seen from
	 * the side, a counter is one long row, and the people at the next booth along are the ones in
	 * the way.
	 */
	private Map<Long, Set<Integer>> spotsInFront(double[] view, Map<Long, List<int[]>> spotsByTile, long lastYouTile, int lastYouSlot)
	{
		Map<Long, Set<Integer>> out = new HashMap<>();
		if (this.lastYouAt == null && lastYouSlot < 0)
		{
			return out;
		}
		double[] lastYouAt = this.lastYouAt;
		List<int[]> yours = lastYouSlot < 0 ? null : spotsByTile.get(lastYouTile);
		if (yours != null && lastYouSlot < yours.size())
		{
			// Where your spot is drawn this tick, so a change of spacing isn't judged from the old one.
			lastYouAt = new double[]{StackRegistry.sceneX(lastYouTile) * 2.0 * HALF_TILE + HALF_TILE + yours.get(lastYouSlot)[0],
				StackRegistry.sceneY(lastYouTile) * 2.0 * HALF_TILE + HALF_TILE + yours.get(lastYouSlot)[1]};
		}
		if (lastYouAt == null)
		{
			return out;
		}
		for (Map.Entry<Long, List<int[]>> e : spotsByTile.entrySet())
		{
			long tile = e.getKey();
			double tileX = StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE;
			double tileY = StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE;
			if (Math.abs(tileX - lastYouAt[0]) > VIEW_TILES * 2 * HALF_TILE
				|| Math.abs(tileY - lastYouAt[1]) > VIEW_TILES * 2 * HALF_TILE)
			{
				continue;
			}
			Set<Integer> clear = new HashSet<>();
			List<int[]> spots = e.getValue();
			for (int s = 0; s < spots.size(); s++)
			{
				if (tile == lastYouTile && s == lastYouSlot)
				{
					continue;
				}
				double east = tileX + spots.get(s)[0] - lastYouAt[0];
				double north = tileY + spots.get(s)[1] - lastYouAt[1];
				// Your personal space: nobody stands inside you, whichever way the camera looks. In a
				// cramped corner, with a wall behind and nowhere to go, people were squeezed right on
				// top of you.
				boolean onYou = Math.hypot(east, north) < YOUR_SPACE;
				boolean inFront = view != null && east * view[0] + north * view[1] > VIEW_IN_FRONT
					&& Math.abs(north * view[0] - east * view[1]) < VIEW_OVERLAP;
				if (onYou || inFront)
				{
					clear.add(s);
				}
			}
			if (!clear.isEmpty())
			{
				out.put(tile, clear);
			}
		}
		return out;
	}

	/** Someone is in front of you when they are at least this much nearer the camera, in units. */
	private static final double VIEW_IN_FRONT = 24;
	/** ...and this close to your line of sight from the side, about a player's width. */
	private static final double VIEW_OVERLAP = 48;

	/**
	 * The way most of a group is facing, rounded to a quarter turn, so a line runs square across it;
	 * {@code fallback} when nobody's facing is known.
	 */
	private static double facingOfMost(List<StackSpreader.Entry> group, double fallback)
	{
		int[] quarters = new int[4];
		for (StackSpreader.Entry e : group)
		{
			if (e.orientation >= 0)
			{
				int q = (int) Math.round(StackSpreader.toRadians(e.orientation) / (Math.PI / 2));
				quarters[((q % 4) + 4) % 4]++;
			}
		}
		int best = -1;
		int most = 0;
		for (int q = 0; q < 4; q++)
		{
			if (quarters[q] > most)
			{
				most = quarters[q];
				best = q;
			}
		}
		return best < 0 ? fallback : best * (Math.PI / 2);
	}

	/**
	 * The group size a tile's spacing is based on. It grows as soon as someone arrives, so the group
	 * makes room for them, but only shrinks once people have been gone for {@link SlotBook#HOLD_TICKS},
	 * together with the gap filling, so a player stepping away and back doesn't make everyone move.
	 */
	private int sizeFor(long tile, int players, int tick)
	{
		int[] old = previousSizes.get(tile);
		int[] now;
		if (old == null || players >= old[0] || tick - old[1] > SlotBook.HOLD_TICKS)
		{
			now = new int[]{players, tick};
		}
		else
		{
			now = old;
		}
		currentSizes.put(tile, now);
		return now[0];
	}

	/**
	 * Plan this tick.
	 *
	 * @param still        everyone standing still, with their tile and facing
	 * @param shown        whether the game is showing a player (by id)
	 * @param spacing      the spacing setting
	 * @param capacity     most spots per tile
	 * @param smart        the Smart arrangement (rows allowed) rather than Circle
	 * @param includeLocal whether your own character may be moved
	 * @param tick         this tick's number, going up by one each game tick
	 */
	Plan plan(List<StackSpreader.Entry> still, IntPredicate shown, int spacing, int capacity, boolean smart,
		boolean includeLocal, int tick, Surroundings around)
	{
		Map<Long, List<StackSpreader.Entry>> byTile = new LinkedHashMap<>();
		for (StackSpreader.Entry e : still)
		{
			byTile.computeIfAbsent(e.tile, k -> new ArrayList<>(4)).add(e);
		}
		observePresence(byTile, shown, tick);

		int localId = -1;
		yourTileNow = Long.MIN_VALUE;
		for (StackSpreader.Entry e : still)
		{
			knownYou = e.local ? e.id : knownYou;
			yourTileNow = e.local ? e.tile : yourTileNow;
			localId = includeLocal && e.local ? e.id : localId;
		}
		slots.localId = localId;
		lineBook.localId = localId;
		// Who you are even while you're walking about, so a spot held for you stays yours.
		slots.yourId = knownYou;
		lineBook.yourId = knownYou;
		double[] view = viewFrom(still, tick);
		// Nobody on a line steps out of your line of sight: a crowd huddles as it would, and
		// seeing yourself is the draw order's job ("Draw me in front").
		lineBook.view = null;
		// Tiles near you get spare spots, for people to step out from between you and the camera
		// even when every ordinary spot is taken.
		nearYou.clear();
		slots.busy = new HashSet<>();
		for (StackSpreader.Entry e : still)
		{
			if (e.busy && !e.local)
			{
				slots.busy.add(e.id);
			}
			if (e.id == localId && view != null)
			{
				for (long other : byTile.keySet())
				{
					if (other == e.tile && StackRegistry.plane(other) == StackRegistry.plane(e.tile)
						&& Math.abs(StackRegistry.sceneX(other) - StackRegistry.sceneX(e.tile)) <= VIEW_TILES
						&& Math.abs(StackRegistry.sceneY(other) - StackRegistry.sceneY(e.tile)) <= VIEW_TILES)
					{
						nearYou.add(other);
					}
				}
			}
		}
		lineBook.bow = arrangement != PersonalSpaceConfig.Arrangement.ROW;

		Plan plan = new Plan();
		shapes.startTick();
		previousSizes = currentSizes;
		currentSizes = new HashMap<>();
		previousMostFaced = currentMostFaced;
		currentMostFaced = new HashMap<>();
		previousLineTiles = currentLineTiles;
		currentLineTiles = new HashSet<>();
		Map<Long, List<Integer>> movableByTile = new HashMap<>();
		Map<Long, List<int[]>> spotsByTile = new HashMap<>();
		Map<Long, String> shapeByTile = new HashMap<>();
		Map<Long, Integer> spacingByTile = new HashMap<>();
		Map<Long, Integer> shownByTile = new HashMap<>();
		Map<Long, Integer> blockedByTile = new HashMap<>();
		List<Straight> pendingStraight = new ArrayList<>();
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			List<StackSpreader.Entry> everyone = e.getValue();
			if (everyone.size() < 2)
			{
				continue;
			}
			long tile = e.getKey();
			List<StackSpreader.Entry> group = new ArrayList<>(everyone.size());
			for (StackSpreader.Entry en : everyone)
			{
				if (shown.test(en.id))
				{
					group.add(en);
				}
			}
			plan.unseen += everyone.size() - group.size();
			if (group.size() < 2)
			{
				continue;
			}
			List<Integer> movable = new ArrayList<>(group.size());
			for (StackSpreader.Entry en : group)
			{
				if ((includeLocal || !en.local) && (!en.busy || en.local))
				{
					movable.add(en.id);
				}
			}
			// Anyone busy with something comes last: with more people than spots, they wait.
			for (StackSpreader.Entry en : group)
			{
				if ((includeLocal || !en.local) && en.busy && !en.local)
				{
					movable.add(en.id);
				}
			}
			if (movable.isEmpty())
			{
				continue;
			}
			boolean middleTaken = movable.size() < group.size() || movable.size() > capacity;
			ShapeMemory.Shape shape = shapes.decide(tile, group, smart);
			// A row only makes sense in front of something. Out in the open, people who happen to face
			// the same way form a crowd instead, so a pair facing east doesn't line up one behind the
			// other as seen from the usual camera.
			boolean lineUp = arrangement == PersonalSpaceConfig.Arrangement.ROW;
			boolean curve = arrangement == PersonalSpaceConfig.Arrangement.ARC;
			boolean row = shape.row && (around.facesObstacle(tile, shape.angle) || around.facesFire(tile, shape.angle));
			double rowAngle = shape.angle;
			if (smart && !row)
			{
				Double faced = facedByMost(tile, group, around);
				if (faced != null)
				{
					row = true;
					rowAngle = faced;
				}
			}
			if (smart && !row && !lineUpOrCurve(arrangement))
			{
				// At a bank counter, line up along it whichever way people happen to face. People
				// alching or chatting at the bank face every way, and a tile of them was packed into a
				// ring squeezed between the booths instead, burying whoever stood in it.
				Double counterSide = counterBeside(tile, group, around);
				if (counterSide != null)
				{
					row = true;
					rowAngle = counterSide;
				}
			}

			// At a bank counter or row of booths, people stand close together in a straight line
			// along it: spread wide, a bank crowd reads as a queue. Around a fire they stay close
			// too, or it looks deserted.
			// Line and Arc: drawn up wherever they are, even in the open with nothing to face, which
			// is where Smart would make a ring. The shape still runs along whatever they are facing.
			if ((lineUp || curve) && !row)
			{
				row = true;
				rowAngle = facingOfMost(group, shape.angle);
			}

			int lagged = sizeFor(tile, group.size(), tick);
			int tileSpacing = smallGroupsClose ? spacingFor(spacing, lagged) : spacing;
			double angle = rowAngle;
			boolean straight = false;
			String kind = row ? "curved row" : "crowd";
			// People face a bank booth at whatever slant they walked up at, and a counter only counts as
			// one when you look at it square on, since counters run along the edges of tiles. So the
			// square-on direction is tested as well: without it, a tile in the middle of a bank formed
			// a curve in the open instead of a row along the counter, and one person standing at a
			// slant was enough to decide it for everybody.
			double squareOn = Math.round(rowAngle / (Math.PI / 2)) * (Math.PI / 2);
			boolean counter = row && !curve
				&& (around.isCounter(tile, rowAngle) || around.isCounter(tile, squareOn));
			if (counter || (row && lineUp))
			{
				if (counter && smallGroupsClose)
				{
					tileSpacing = Math.min(tileSpacing, PersonalSpaceConfig.COUNTER_SPACING);
				}
				// Run exactly along the counter, even if the row was formed by someone facing it at a slant.
				angle = Math.round(rowAngle / (Math.PI / 2)) * (Math.PI / 2);
				straight = true;
				kind = counter ? "counter row" : "line";
				if (counter)
				{
					plan.facingIn.add(tile);
				}
			}
			else
			{
				// Gathered round a fire, in a row or not: stay close, whatever the slider says, and
				// everyone faces the fire.
				int[] fire = fireFaced(group, around.firesNear(tile));
				if (fire != null || (row && around.facesFire(tile, rowAngle)))
				{
					tileSpacing = smallGroupsClose
						? Math.min(tileSpacing, PersonalSpaceConfig.FIRE_SPACING)
						: tileSpacing;
					kind = row ? "fire row" : "crowd round a fire";
				}
				if (fire != null)
				{
					plan.fires.put(tile, new int[]{fire[0] * 2 * HALF_TILE, fire[1] * 2 * HALF_TILE});
				}
				else if (!row && pose != PersonalSpaceConfig.Pose.NATURAL && group.size() <= POSED_GROUP)
				{
					plan.poses.put(tile, pose);
				}
			}
			if (row && !straight)
			{
				plan.curvedRows.add(tile);
			}

			// Neighbouring booth tiles each get their own row: a straight row stops short of a tile
			// beside it along the counter where anyone is standing, or the two rows would be drawn
			// on top of each other.
			double alongX = -Math.cos(angle);
			double alongZ = Math.sin(angle);
			int plane = StackRegistry.plane(tile);
			int sceneX = StackRegistry.sceneX(tile);
			int sceneY = StackRegistry.sceneY(tile);
			int sideX = (int) Math.round(alongX);
			int sideY = (int) Math.round(alongZ);
			// You standing alone next door don't cut a counter row or a curve short: they reached
			// round you as if you weren't there only for the crowd to re-form when you stepped off.
			// Your personal space keeps anyone from being drawn inside you.
			long youAlone = yourTileNow != Long.MIN_VALUE && byTile.containsKey(yourTileNow) && byTile.get(yourTileNow).size() == 1
				? yourTileNow : Long.MIN_VALUE;
			double reachAhead = straight ? counterReach(byTile, plane, sceneX, sceneY, sideX, sideY, tileSpacing, youAlone) : Double.MAX_VALUE;
			double reachBehind = straight ? counterReach(byTile, plane, sceneX, sceneY, -sideX, -sideY, tileSpacing, youAlone) : Double.MAX_VALUE;

			// Around a tree, anvil or fire with people on more than one side, each tile's curve keeps
			// to its own share of the ring, squeezing its players closer rather than reaching round
			// behind the people on the next side.
			double[] arc = row && !straight
				? arcLimits(byTile, plane, sceneX, sceneY, angle, youAlone)
				: new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
			// Sharing what you face with the tile next door limits how much of the ring is yours, and
			// so how far apart your crowd can stand within it. The whole share, not half of it: what
			// doesn't fit across the front stands in a row behind.
			// A curve normally hugs what everyone is facing, which limits how far apart it can stand
			// people. It opens out instead when the slider is in charge (auto-spacing off), or when
			// it was asked for with nothing to hug, where hugging means nothing anyway.
			final boolean freeCurve = row && !straight
				&& !around.facesObstacle(tile, angle) && !around.facesFire(tile, angle) && !plan.fires.containsKey(tile);
			boolean openOut = !smallGroupsClose || freeCurve;
			double tightest = Math.min(arc[0], arc[1]);
			if (tightest < Double.MAX_VALUE)
			{
				// A share of the ring is an angle, so what it is worth in room depends on how far out
				// the curve stands: a crowd that has opened onto a wider circle really does have more
				// room in its share, and may use it.
				int onCircle = StackSpreader.arcRadius(tileSpacing, openOut);
				tileSpacing = Math.min(tileSpacing, Math.max(MIN_SHARED_SPACING, (int) Math.floor(onCircle * tightest)));
			}
			final int arcRadius = StackSpreader.arcRadius(tileSpacing, openOut);
			final double margin = row && !straight ? StackSpreader.curvedTurn(tileSpacing, arcRadius) / 2 : 0;
			final double focusX = -Math.sin(angle) * StackSpreader.LOOK_AHEAD;
			final double focusZ = -Math.cos(angle) * StackSpreader.LOOK_AHEAD;

			// A crowd makes room for the people around it as a whole, below. A row keeps to its
			// share spot by spot, leaving out the tiles it already shares ground with: those round
			// what a curve faces, and those along a counter.
			int lookX = (int) Math.round(-Math.sin(angle));
			int lookY = (int) Math.round(-Math.cos(angle));
			final boolean alongCounter = straight;
			final List<double[]> rowShares = !row ? new ArrayList<>() : sharesOf(tile, StackSpreader.MAX_LINE_EXTENT,
				other -> alongCounter
					? (StackRegistry.sceneX(other) - sceneX) * sideY == (StackRegistry.sceneY(other) - sceneY) * sideX
					: Math.abs(StackRegistry.sceneX(other) - sceneX - lookX) <= 1 && Math.abs(StackRegistry.sceneY(other) - sceneY - lookY) <= 1,
				tick, true);

			int[] blocked = {0};
			StackSpreader.SpotCheck check =
				(dx, dz) ->
				{
					double along = dx * alongX + dz * alongZ;
					double round = arc[0] == Double.MAX_VALUE && arc[1] == Double.MAX_VALUE
						? 0 : signedAngle(-focusX, -focusZ, dx - focusX, dz - focusZ);
					boolean inner = Math.abs(round) <= margin + 1e-6;
					boolean outsideArc = !inner && (round > arc[0] - margin || round < -(arc[1] - margin));
					if (along > reachAhead || along < -reachBehind || outsideArc || !around.canStand(tile, dx, dz)
						|| !withinShares(rowShares, dx, dz))
					{
						blocked[0]++;
						return false;
					}
					return true;
				};
			if (straight)
			{
				// Laid out after every tile is known, so neighbours along the same counter or bank can
				// share one line.
				int wanted = Math.min(capacity, Math.max(movable.size(), lagged - (group.size() - movable.size())));
				// A booth with its own row closes up by itself when it runs short of room, but a line
				// shared along a busy counter doesn't: when it gets busy it closes up to this instead.
				int lineSpacing = counter && smallGroupsClose ? Math.min(tileSpacing, PersonalSpaceConfig.LINE_SPACING) : tileSpacing;
				pendingStraight.add(new Straight(tile, plane, sceneX, sceneY, sideX, sideY, angle, tileSpacing, lineSpacing,
					middleTaken, wanted, check, blocked));
				movableByTile.put(tile, movable);
				shapeByTile.put(tile, kind);
				spacingByTile.put(tile, tileSpacing);
				shownByTile.put(tile, group.size());
				continue;
			}
			// A crowd with people on the tiles around it moves over and closes up as a whole to keep
			// to its share of the ground, and its spots keep their order, so nobody walks round the
			// others to get anywhere.
			StackSpreader.SpotCheck laidOut = check;
			double[] moveOver = {0, 0};
			List<double[]> shares = null;
			Room room = null;
			// Someone may be standing in the middle of the tile: you, when your character stays put,
			// anyone past the players-per-tile limit, or someone who was left waiting for a spot.
			boolean guard = middleTaken || waited.contains(tile);
			int people = Math.min(capacity, Math.max(lagged, movable.size()));
			if (!row)
			{
				// Everyone near enough to matter to any of this crowd's spots, moved over as far as it
				// may go (to a corner of its half-tile square): the same list for choosing how it
				// makes room and for checking its spots, or a spot the choice allowed could be turned
				// down and everyone after it in the list renumbered.
				shares = sharesOf(tile, StackSpreader.MAX_LINE_EXTENT + MAX_SHIFT * Math.sqrt(2) + 1, null, tick, false);
				room = roomFor(tile, shares, tileSpacing, middleTaken, guard, people);
				moveOver = new double[]{room.x, room.z};
				if (room.spacing < tileSpacing || room.moved())
				{
					kind += ", making room";
				}
				tileSpacing = room.spacing;
				laidOut = movedOver(check, shares, room, guard);
			}
			boolean[] squeezed = {false};
			int[] used = {tileSpacing};
			boolean middleNow = middleTaken;
			List<int[]> spots = spotsWithRoom(row, false, angle, middleTaken, tileSpacing, spotsFor(tile, capacity),
				Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, used);
			if (!middleTaken && spots.size() < movable.size() && (!row || squeezed[0]))
			{
				// Walls leave too few spots for everyone: someone stays in the middle without a spot,
				// so don't also give the middle to someone else.
				boolean wasRow = row && squeezed[0];
				middleNow = true;
				spots = spotsWithRoom(false, false, angle, true, tileSpacing, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, used);
				squeezed[0] |= wasRow;
			}
			if (room != null && room.moved() && (used[0] < room.spacing || middleNow != middleTaken))
			{
				// A wall closed the ring up further than the room allowed for, around the same moved
				// middle, or left someone in the middle: move it over again for the ring it has now,
				// or its near side lands on whoever stands in the middle of the tile.
				boolean g = guard || middleNow;
				List<double[]> area = roomAt(shares, middleNow, g, used[0], people);
				double far = farAt(g, used[0]);
				double[] c = area.isEmpty()
					? new double[]{Math.max(-far, Math.min(far, room.x)), Math.max(-far, Math.min(far, room.z))}
					: nearestTo(area, room.x, room.z);
				Room again = new Room(Math.round(c[0]), Math.round(c[1]), used[0], room.wanted, room.fits && !area.isEmpty());
				moveOver = new double[]{again.x, again.z};
				laidOut = movedOver(check, shares, again, g);
				int closer = used[0];
				spots = spotsWithRoom(false, false, angle, middleNow, closer, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, used);
			}
			if (room != null && !room.fits)
			{
				// No layout keeps a crowd's first spots all to their shares (a full tile boxed in by
				// its neighbours), but the ring can still have enough spots further round that do:
				// take those, rather than handing out spots that lean into the neighbours.
				Room within = new Room(moveOver[0], moveOver[1], used[0], room.wanted, true);
				List<int[]> kept = StackSpreader.spots(false, false, angle, middleNow, used[0], spotsFor(tile, capacity),
					movedOver(check, shares, within, guard || middleNow));
				if (kept.size() >= Math.min(capacity, movable.size()))
				{
					spots = kept;
				}
			}
			if (row && squeezed[0] && used[0] < ROOM_FLOOR && !rowShares.isEmpty())
			{
				// Keeping to its share of the ground would squeeze this row into a ring tighter than a
				// crowd ever closes up for its neighbours. Better a row that leans into them a little.
				List<double[]> kept = new ArrayList<>(rowShares);
				rowShares.clear();
				boolean[] plain = {false};
				int[] plainUsed = {tileSpacing};
				int[] plainBlocked = {0};
				List<int[]> loose = spotsWithRoom(row, false, angle, middleTaken, tileSpacing, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), check, plainBlocked, plain, arcRadius, false, StackSpreader.WRAP_ARC, plainUsed);
				if (!plain[0] && loose.size() >= Math.min(spots.size(), movable.size()))
				{
					spots = loose;
					squeezed[0] = false;
					used[0] = plainUsed[0];
					blocked[0] = plainBlocked[0];
				}
				else
				{
					rowShares.addAll(kept);
				}
			}
			if (squeezed[0])
			{
				kind += ", squeezed into a ring";
			}
			if (moveOver[0] != 0 || moveOver[1] != 0)
			{
				List<int[]> moved = new ArrayList<>(spots.size());
				for (int[] s : spots)
				{
					moved.add(new int[]{(int) Math.round(s[0] + moveOver[0]), (int) Math.round(s[1] + moveOver[1])});
				}
				spots = moved;
			}
			spotsByTile.put(tile, spots);
			movableByTile.put(tile, movable);
			shapeByTile.put(tile, kind);
			spacingByTile.put(tile, used[0]);
			shownByTile.put(tile, group.size());
			blockedByTile.put(tile, blocked[0]);
		}

		rooms = roomsNow;
		roomsNow = new HashMap<>();
		List<LineBook.Line> shared = layOutStraightRows(pendingStraight, byTile, capacity, movableByTile, spotsByTile, shapeByTile,
			spacingByTile, blockedByTile, shownByTile, around, shown, includeLocal, tick);

		// When just you and one other are about to be given spots on your tile, stand on the
		// camera's side: see SlotBook.pairSideForYou.
		slots.pairSideForYou = new HashMap<>();
		long youTileNow = Long.MIN_VALUE;
		for (StackSpreader.Entry e : still)
		{
			if (e.id == localId)
			{
				youTileNow = e.tile;
			}
		}
		List<StackSpreader.Entry> withYou = youTileNow == Long.MIN_VALUE ? null : byTile.get(youTileNow);
		List<int[]> yourSpots = youTileNow == Long.MIN_VALUE ? null : spotsByTile.get(youTileNow);
		if (view != null && withYou != null && withYou.size() == 2 && yourSpots != null && yourSpots.size() >= 2)
		{
			double east = yourSpots.get(1)[0] - yourSpots.get(0)[0];
			double north = yourSpots.get(1)[1] - yourSpots.get(0)[1];
			if (east * view[0] + north * view[1] > VIEW_IN_FRONT && Math.abs(north * view[0] - east * view[1]) < VIEW_OVERLAP)
			{
				slots.pairSideForYou.put(youTileNow, 1);
			}
		}
		// Your personal space and line of sight are judged from where you'll stand. The tick you
		// are first given a spot you were in the middle of your tile a moment ago, and judged from
		// there, the spot beside you fell inside you: the other player went round you first and
		// came back beside you a tick later.
		long judgeTile = lastYouTile;
		int judgeSlot = lastYouSlot;
		if (lastYouSlot < 0 && yourSpots != null)
		{
			judgeTile = youTileNow;
			judgeSlot = Math.min(slots.pairSideForYou.getOrDefault(youTileNow, 0), yourSpots.size() - 1);
		}
		// Only your personal space is kept clear: nobody is drawn inside you. Nobody steps out of
		// your line of sight any more, on your tile or any other: people moving aside for you
		// parted a crowd like the sea wherever you stood. A crowd huddles as it would, and seeing
		// yourself over it is the draw order's job ("Draw me in front").
		slots.keepClear = spotsInFront(null, spotsByTile, judgeTile, judgeSlot);
		slots.spare = new HashMap<>();
		for (Map.Entry<Long, List<int[]>> e : spotsByTile.entrySet())
		{
			if (nearYou.contains(e.getKey()) && e.getValue().size() > capacity)
			{
				slots.spare.put(e.getKey(), e.getValue().size() - capacity);
			}
		}
		slots.stepAside = tick - youStillSince >= SlotBook.LOCAL_SWAP_DELAY;
		slots.viewKey = viewSector;
		slots.inFrontOf = (tile, spot) ->
		{
			Set<Integer> hide = new HashSet<>();
			List<int[]> spots = spotsByTile.get(tile);
			if (view == null || spots == null || spot >= spots.size())
			{
				return hide;
			}
			for (int s = 0; s < spots.size(); s++)
			{
				double east = spots.get(s)[0] - spots.get(spot)[0];
				double north = spots.get(s)[1] - spots.get(spot)[1];
				if (s != spot && east * view[0] + north * view[1] > VIEW_IN_FRONT
					&& Math.abs(north * view[0] - east * view[1]) < VIEW_OVERLAP)
				{
					hide.add(s);
				}
			}
			return hide;
		};
		slots.nearestFirst = new HashMap<>();
		for (long tile : slots.keepClear.keySet())
		{
			List<int[]> spots = spotsByTile.get(tile);
			List<Integer> order = new ArrayList<>();
			for (int s = 0; s < spots.size(); s++)
			{
				order.add(s);
			}
			order.sort(Comparator.comparingDouble(s -> Math.hypot(spots.get(s)[0], spots.get(s)[1])));
			slots.nearestFirst.put(tile, order);
		}
		Map<Long, Map<Integer, Integer>> assigned = slots.update(movableByTile, tile -> spotsByTile.get(tile).size(), tick);
		lastSlots = assigned;
		waited = new HashSet<>();
		for (StackSpreader.Placement p : plan.unplaced)
		{
			if (shown.test(p.id))
			{
				waited.add(p.tile);
			}
		}

		Set<Integer> placed = new HashSet<>();
		Map<Long, LineBook.LineReport> lineReports = new HashMap<>();
		// Where everyone who isn't on a shared line is drawn, so a line (and its rows behind, which
		// reach onto the tiles behind it) keeps its spots clear of them.
		Set<Long> onLine = new HashSet<>();
		for (LineBook.Line l : shared)
		{
			for (LineBook.Member m : l.members)
			{
				onLine.add(m.tile);
			}
		}
		List<int[]> others = new ArrayList<>();
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			long tile = e.getKey();
			if (onLine.contains(tile))
			{
				continue;
			}
			int cx = StackRegistry.sceneX(tile) * 2 * HALF_TILE;
			int cz = StackRegistry.sceneY(tile) * 2 * HALF_TILE;
			Map<Integer, Integer> given = assigned.get(tile);
			if (given != null)
			{
				for (int index : given.values())
				{
					int[] spot = spotsByTile.get(tile).get(index);
					others.add(new int[]{StackRegistry.plane(tile), cx + spot[0], cz + spot[1]});
				}
			}
			if (given == null || given.size() < e.getValue().size())
			{
				others.add(new int[]{StackRegistry.plane(tile), cx, cz});
			}
		}
		List<StackSpreader.Placement> onLines = lineBook.update(shared, tick, new LineBook.Terrain()
		{
			@Override
			public boolean canStand(long tile, int dx, int dz)
			{
				int x = StackRegistry.sceneX(tile) * 2 * HALF_TILE + dx;
				int z = StackRegistry.sceneY(tile) * 2 * HALF_TILE + dz;
				int gap = spacingByTile.get(tile);
				for (int[] other : others)
				{
					if (other[0] == StackRegistry.plane(tile) && Math.hypot(other[1] - x, other[2] - z) < gap)
					{
						return false;
					}
				}
				return around.canStand(tile, dx, dz);
			}

			@Override
			public boolean facesEdge(long tile, double angle)
			{
				return around.facesObstacle(tile, angle);
			}
		}, lineReports);
		Map<Long, Integer> placedOnTile = new HashMap<>();
		for (StackSpreader.Placement p : onLines)
		{
			plan.placements.add(p);
			placed.add(p.id);
			placedOnTile.merge(p.tile, 1, Integer::sum);
		}
		// A roomy line that left anyone without a spot closes up: headcounts alone don't tell when a
		// line has less room than usual, such as with people standing right behind it.
		for (LineBook.Line line : shared)
		{
			boolean short_ = false;
			for (LineBook.Member m : line.members)
			{
				short_ |= placedOnTile.getOrDefault(m.tile, 0) < Math.min(m.capacity, m.ids.size());
			}
			for (LineBook.Member m : line.members)
			{
				if (short_ && line.spacing > PersonalSpaceConfig.LINE_SPACING)
				{
					busyLineUntil.put(m.tile, tick + BUSY_LINE_TICKS);
				}
			}
		}
		for (Map.Entry<Long, LineBook.LineReport> e : lineReports.entrySet())
		{
			long tile = e.getKey();
			plan.tiles.put(tile, new TileReport(shapeByTile.get(tile), spacingByTile.get(tile), shownByTile.get(tile),
				placedOnTile.getOrDefault(tile, 0), e.getValue().placed, e.getValue().blocked));
		}
		for (Map.Entry<Long, Map<Integer, Integer>> e : assigned.entrySet())
		{
			long tile = e.getKey();
			List<int[]> spots = spotsByTile.get(tile);
			for (Map.Entry<Integer, Integer> a : e.getValue().entrySet())
			{
				int[] spot = spots.get(a.getValue());
				plan.placements.add(new StackSpreader.Placement(a.getKey(), tile, spot[0], spot[1]));
				placed.add(a.getKey());
			}
			plan.tiles.put(tile, new TileReport(shapeByTile.get(tile), spacingByTile.get(tile), shownByTile.get(tile),
				e.getValue().size(), spots.size(), blockedByTile.get(tile)));
		}
		plan.placements.sort(Comparator.comparingInt(p -> p.id));

		// You first: the draw shim shows only one player in the middle of a tile, and it should be you.
		for (boolean localPass : new boolean[]{true, false})
		{
			for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
			{
				if (e.getValue().size() < 2)
				{
					continue;
				}
				for (StackSpreader.Entry en : e.getValue())
				{
					if (en.local == localPass && !placed.contains(en.id))
					{
						plan.unplaced.add(new StackSpreader.Placement(en.id, e.getKey(), 0, 0));
					}
				}
			}
		}
		double[] youAt = null;
		for (StackSpreader.Placement p : plan.placements)
		{
			if (p.id == localId)
			{
				youAt = new double[]{StackRegistry.sceneX(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dx,
					StackRegistry.sceneY(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dz};
			}
		}
		if (youAt == null)
		{
			// Without a spot of your own (alone on your tile, or staying put) you're drawn in the
			// middle of your tile, and people around still keep out of your space and your view.
			for (StackSpreader.Entry e : still)
			{
				if (e.local)
				{
					youAt = new double[]{StackRegistry.sceneX(e.tile) * 2.0 * HALF_TILE + HALF_TILE,
						StackRegistry.sceneY(e.tile) * 2.0 * HALF_TILE + HALF_TILE};
				}
			}
		}
		if (youAt != null)
		{
			// Nobody waiting in the middle of a tile is drawn right up against you: someone who gave
			// up their spot for you waits there. (In front of you is fine: that's how it looks.)
			for (long tile : byTile.keySet())
			{
				double east = StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE - youAt[0];
				double north = StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE - youAt[1];
				boolean against = Math.hypot(east, north) < NEIGHBOUR_GAP;
				if (against && StackRegistry.plane(tile) == yourPlane(still))
				{
					plan.middleOutOfSight.add(tile);
				}
			}
		}
		// Your spot's identity, not where it is drawn: a crowd's spacing growing and shrinking as
		// people come and go moves where your spot is drawn without you going anywhere.
		String youKey = null;
		lastYouSlot = -1;
		for (Map.Entry<Long, Map<Integer, Integer>> e : assigned.entrySet())
		{
			Integer s = e.getValue().get(localId);
			if (s != null)
			{
				youKey = e.getKey() + "/" + s;
				lastYouTile = e.getKey();
				lastYouSlot = s;
			}
		}
		if (youKey == null && youAt != null)
		{
			youKey = youAt[0] + "," + youAt[1];
		}
		if (youKey == null || !youKey.equals(lastYouKey))
		{
			youStillSince = tick;
		}
		lastYouKey = youKey;
		lastYouAt = youAt;
		return plan;
	}

	/** Times a tile switched between a row and a crowd, or a row turned; total. */
	long shapeChanges()
	{
		return shapes.changes;
	}

	/** Times a player with a spot was moved to a different spot to fill a gap; total. */
	long spotMoves()
	{
		return slots.moves + lineBook.moves;
	}

	void clear()
	{
		knownYou = -1;
		slots.clear();
		lineBook.clear();
		shapes.clear();
		previousSizes.clear();
		currentSizes.clear();
		previousMostFaced.clear();
		currentMostFaced.clear();
		previousLineTiles.clear();
		currentLineTiles.clear();
		rooms.clear();
		roomsNow.clear();
		presence.clear();
		waited.clear();
		busyLineUntil.clear();
	}
}
