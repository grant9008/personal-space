package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

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
 * <li>Whoever walks onto someone keeps the side of the tile they came from, and whoever was there
 * steps to the other side: see {@link #sidesFor}. A pair's spots lie along the way they came, in
 * front and behind as much as left and right: see {@link #turnFor}.</li>
 * <li>People standing on a fire, which a Forester's Campfire allows, stand round it instead, and
 * the last one left there keeps their spot beside it: see {@link #layOutOnFires}.</li>
 * <li>With Smart, everyone using one thing nobody can stand on, such as an anvil, from the tiles
 * round it is one crowd, laid out all round it like the people at a campfire: see
 * {@link #layOutRoundObjects}.</li>
 * <li>With Smart, a busy fire is stood round by one crowd the same way, everyone standing on it
 * included (see {@link #FIRE_CROWD}): out of its flames, each on their own side of it.</li>
 * <li>...and any fire close beside it with it, the one crowd round them all (see {@link #FIRES_CLOSE}):
 * a fire lit in the crowd moves only those in its way, and when one goes out the crowd keeps its
 * places round it (see {@link #EMBER_TICKS}).</li>
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
		/**
		 * Casters given the middle of their tile, where they really stand (see {@link CrowdPlanner#casterFor}):
		 * placed there, and anyone stepping in from a spot only does so between casts, or as one ends.
		 */
		final Set<Integer> castersInTheMiddle = new HashSet<>();
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

	/**
	 * Who has cast a skilling spell lately (alching, enchanting, charging orbs, superheating: see
	 * {@link AlchStyler#CASTS}), by id, however Alchers behavior shows it. Set by the plugin each tick.
	 */
	IntPredicate casting = id -> false;
	/**
	 * Who has an action playing this tick that goes on past it (mid-cast, mid-emote), by id: a cast
	 * about to end counts as a break. Set by the plugin each tick.
	 */
	IntPredicate midAction = id -> false;
	/**
	 * Which way each player standing still came onto their tile, as {east, north} from its middle to
	 * where they were the tick before they got there (see {@link StillnessTracker#cameFrom}), or null
	 * when that isn't known. Set by the plugin each tick.
	 */
	IntFunction<int[]> cameFrom = id -> null;
	/** The tick each player came to stand where they are (see {@link StillnessTracker#arrivedTick}); 0 if unknown. Set by the plugin each tick. */
	IntUnaryOperator arrivedTick = id -> 0;
	/** Largest group in which whoever walks onto the others keeps the side they came from (see {@link #sidesFor}). */
	static final int SIDED_GROUP = 3;
	/**
	 * How much more the spot someone keeps must lie the way they came than the next spot does, as
	 * cosines: walking straight at a pair between its two sides, there's no side to keep.
	 */
	static final double SIDE_MARGIN = 0.25;
	/**
	 * How near a diagonal the way someone came onto their tile must be for their group's spots to
	 * lie along it (see {@link #turnFor}), in degrees; otherwise they lie square on. Running, the tick
	 * before someone stopped can be two steps back, and the game walks diagonally first: a diagonal
	 * step and then a straight one leaves where they were then 18 degrees off the diagonal, when
	 * they walked in square on.
	 */
	static final double DIAGONAL_WAY = 12;
	/**
	 * Which way each small group's spots lie across its tile, in degrees from east and west (see
	 * {@link #turnFor}), last tick and this: chosen as the group forms and kept while it lasts.
	 */
	private Map<Long, Double> turnsWere = new HashMap<>();
	private Map<Long, Double> turnsNow = new HashMap<>();
	/** Tiles whose spots go round the other way for the third of three (see {@link #clockwiseFor}), last tick and this. */
	private Set<Long> clockwiseWere = new HashSet<>();
	private Set<Long> clockwiseNow = new HashSet<>();
	/** Largest group whose caster keeps the middle of their tile (see {@link #casterFor}). */
	static final int CASTER_GROUP = 3;
	private static final int NO_CASTER = -1;
	/** From {@link #casterFor}: a caster with a spot waits to step into the middle of their tile. */
	private static final int CASTER_WAITS = -2;
	/** The caster drawn in the middle of each tile (or {@link #CASTER_WAITS}), last tick and this. */
	private Map<Long, Integer> castersWere = new HashMap<>();
	private Map<Long, Integer> castersNow = new HashMap<>();
	/** The tick each caster started casting, for as long as they keep at it. */
	private Map<Integer, Integer> castingSince = new HashMap<>();

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
			if (fire[0] == 0 && fire[1] == 0)
			{
				// A fire on their own tile (a Forester's Campfire can be stood on): moved off it,
				// they all face it, whichever way they happened to be facing.
				return fire;
			}
		}
		for (int[] fire : fires)
		{
			double length = Math.hypot(fire[0], fire[1]);
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

	/**
	 * A tile's spacing only changes by more than this. With people coming and going round a crowd
	 * its share of the ground changed by a hair every few ticks, and every change of spacing, even
	 * by one, moved everyone in it a little: at a busy campfire the whole crowd slid about.
	 */
	static final int SPACING_DEADBAND = 3;
	private Map<Long, Integer> spacingWas = new HashMap<>();
	private Map<Long, Integer> spacingNow = new HashMap<>();

	/**
	 * A row that had to squeeze into a ring stays a ring until it has had room as a row for this
	 * long: about fifteen seconds. Beside a busy neighbour a campfire crowd flipped between a row
	 * and a ring every few ticks, and the whole crowd was laid out afresh each time.
	 */
	static final int UNSQUEEZE_TICKS = 25;
	private final Map<Long, Integer> lastSqueezed = new HashMap<>();

	/**
	 * A crowd closes up at once when it has to, or people would stand inside each other, but only
	 * opens out again once the room has been there this long: about six seconds. Beside busy
	 * neighbours the room came and went every few ticks, and the whole crowd opened out and closed
	 * up with it.
	 */
	static final int WIDEN_TICKS = 10;
	private Map<Long, Integer> usedWas = new HashMap<>();
	private Map<Long, Integer> usedNow = new HashMap<>();
	private final Map<Long, Integer> widerSince = new HashMap<>();
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
	/** Diagnostics: people who'd have been drawn inside you, left waiting in the middle of their tile instead. */
	long insideYouHidden;

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
	 * How far from the middle of a fire's tile the nearest spots round it are: clear of the fire's
	 * flames, which reach a little past its tile. (Just past the tile's edge, people still stood in them.)
	 */
	static final int FIRE_SIDE = HALF_TILE + 36;
	/** How far along a side of the fire, from its middle, the next spots on that side are. */
	private static final int FIRE_ALONG = 24;
	/** The circle the people at a fire on the tiles beside it stand on. Every point of it clears the fire. */
	private static final int FIRE_CIRCLE = 2 * HALF_TILE;
	/**
	 * Nobody standing on a fire is given a spot closer than this to anyone else drawn: a body's width.
	 * At {@link #YOUR_SPACE}, a spot at the middle of a side of the fire was allowed 43 from someone
	 * standing in the middle of the tile beyond it, and the two, drawn almost in one place, took turns
	 * showing through each other. With no spot this clear, they wait in the fire.
	 */
	static final int FIRE_GAP = 56;
	/** ...and a spot is kept while nobody else is drawn closer than this: a little less, so it isn't given up and back. */
	static final int FIRE_KEEP = 48;
	/**
	 * ...and nobody standing on a fire is drawn closer than this to you: they take the other sides
	 * of the fire, and the side you're on is yours. Drawn a third of a tile from you, between you and
	 * the fire, someone in the flames took your place at it.
	 */
	static final int FIRE_YOUR_GAP = 112;
	/**
	 * Where people standing on a fire may be drawn, as {east, north} from the middle of its tile, best
	 * first: the middle of each side just clear of the fire, then along each side, then round the
	 * circle the people at the fire next door stand on. All of them are out over the tiles beside it.
	 */
	static final List<int[]> FIRE_SPOTS = fireSpots();
	/** Who stood at which of {@link #FIRE_SPOTS} last tick: id to {tile, spot}. */
	private Map<Integer, long[]> fireSpotOf = new HashMap<>();
	/** A spot round a fire kept a while for someone who lost it, to go back to: id to {tile, spot, until}. */
	private final Map<Integer, long[]> fireSpotHeld = new HashMap<>();
	/** Diagnostics: times someone standing on a fire stepped to another spot round it. */
	private long fireMoves;

	private static List<int[]> fireSpots()
	{
		List<int[]> out = new ArrayList<>();
		int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] s : sides)
		{
			out.add(new int[]{s[0] * FIRE_SIDE, s[1] * FIRE_SIDE});
		}
		for (int[] s : sides)
		{
			for (int k : new int[]{1, -1})
			{
				out.add(new int[]{s[0] * FIRE_SIDE - s[1] * FIRE_ALONG * k, s[1] * FIRE_SIDE + s[0] * FIRE_ALONG * k});
			}
		}
		for (int degrees = 0; degrees < 360; degrees += 15)
		{
			double a = Math.toRadians(degrees);
			out.add(new int[]{(int) Math.round(FIRE_CIRCLE * Math.cos(a)), (int) Math.round(FIRE_CIRCLE * Math.sin(a))});
		}
		// A wider ring, for a fire with every nearer spot taken: further out beats waiting in it.
		for (int degrees = 10; degrees < 360; degrees += 20)
		{
			double a = Math.toRadians(degrees);
			out.add(new int[]{(int) Math.round(FIRE_OUTER * Math.cos(a)), (int) Math.round(FIRE_OUTER * Math.sin(a))});
		}
		return out;
	}

	/** The wider ring round a fire. */
	private static final int FIRE_OUTER = 2 * HALF_TILE + 40;
	/**
	 * With no spot round a fire a body's width clear, someone standing on it takes the roomiest one
	 * that is at least as clear as a spot is kept, rather than wait in the flames: close beside
	 * someone, never close enough to show through them.
	 */
	static final int FIRE_FALLBACK_GAP = FIRE_KEEP;

	/** Whether one of these fires (as {@link Surroundings#firesNear} gives them) is on the tile itself. */
	static boolean fireUnderfoot(List<int[]> fires)
	{
		for (int[] fire : fires)
		{
			if (fire[0] == 0 && fire[1] == 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Lays out the crowds standing on a fire, once everyone else's spots are known. A Forester's
	 * Campfire can be stood on, and people tending one stand right on it, where nobody is drawn.
	 * Made room for like any other crowd, a busy campfire's own tile was closed up until none of its
	 * spots cleared the fire: everyone on it waited out of sight in the fire, and slid out and back
	 * whenever someone next door came or went. Tending never pauses, so every move was a glide.
	 *
	 * <p>Instead they stand round the fire at {@link #FIRE_SPOTS}, wherever nobody else is drawn
	 * within {@link #FIRE_GAP}. Everyone keeps their spot while it stays clear. Only when someone
	 * comes too close do they step to the clear spot nearest it, or wait in the middle if there is
	 * none, and they go back to the same spot once it's clear. Someone alone on the fire stands
	 * beside it too, so the last one left keeps their spot rather than going back into the fire and
	 * out again when someone else comes.
	 *
	 * <p>A busy fire isn't laid out here: everyone on it and round it is one crowd (see
	 * {@link #FIRE_CROWD} and {@link #layOutRoundObjects}).
	 *
	 * @param onFires the people on each fire's tile who may be moved, in the order they get spots
	 * @param placed  everyone given a spot so far; anyone given one here is added
	 */
	private void layOutOnFires(Plan plan, Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, List<Integer>> onFires,
		Set<Integer> placed, IntPredicate shown, int capacity, boolean includeLocal, int tick, Surroundings around)
	{
		fireSpotHeld.values().removeIf(h -> h[2] < tick);
		Set<Long> alone = new HashSet<>();
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			long tile = e.getKey();
			StackSpreader.Entry only = null;
			int seen = 0;
			for (StackSpreader.Entry en : e.getValue())
			{
				if (shown.test(en.id))
				{
					only = en;
					seen++;
				}
			}
			// Alone on a fire too: drawn standing in the flames, burning logs, they looked like nobody
			// had told them where to stand.
			if (!onFires.containsKey(tile) && seen == 1 && (includeLocal || !only.local) && !roundFires.contains(tile)
				&& fireUnderfoot(firesNear(tile, around)))
			{
				List<Integer> one = new ArrayList<>(1);
				one.add(only.id);
				onFires.put(tile, one);
				alone.add(tile);
			}
		}
		Map<Integer, long[]> now = new HashMap<>();
		if (!onFires.isEmpty())
		{
			layOutRoundFires(plan, byTile, onFires, alone, placed, shown, capacity, now, around);
		}
		// Whoever lost their spot round a fire (waiting in the middle, the last to leave, or the fire
		// gone) has it kept for them a while.
		for (Map.Entry<Integer, long[]> e : fireSpotOf.entrySet())
		{
			if (!now.containsKey(e.getKey()))
			{
				fireSpotHeld.put(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1], tick + SlotBook.HOLD_TICKS});
			}
		}
		fireSpotOf = now;
	}

	/**
	 * Spots round a fire for the people on its tile, as {id: index into {@link #FIRE_SPOTS}}, clear
	 * of everyone in {@code taken} (each spot given is added to it). Remembering, everyone keeps the
	 * spot they had while it stays clear and goes back to one kept for them; otherwise it's laid out
	 * afresh, in order.
	 */
	private Map<Integer, Integer> fireSpots(long tile, List<Integer> ids, boolean remember, boolean[] standable, List<double[]> taken, int capacity)
	{
		boolean[] used = new boolean[FIRE_SPOTS.size()];
		java.util.function.BiPredicate<Integer, Integer> clear = (i, gap) ->
		{
			if (used[i] || !standable[i])
			{
				return false;
			}
			for (double[] b : taken)
			{
				if (Math.hypot(b[0] - FIRE_SPOTS.get(i)[0], b[1] - FIRE_SPOTS.get(i)[1]) < (b.length > 2 && b[2] > 0 ? Math.max(gap, FIRE_YOUR_GAP) : gap))
				{
					return false;
				}
			}
			return true;
		};
		IntPredicate free = i -> clear.test(i, FIRE_GAP);
		IntPredicate stillFree = i -> clear.test(i, FIRE_KEEP);
		Map<Integer, Integer> given = new LinkedHashMap<>();
		List<Integer> need = new ArrayList<>();
		// Everyone whose spot is still clear keeps it.
		for (int id : ids)
		{
			long[] had = remember ? fireSpotOf.get(id) : null;
			int was = had != null && had[0] == tile ? (int) had[1] : -1;
			if (was >= 0 && given.size() < capacity && stillFree.test(was))
			{
				given.put(id, was);
				used[was] = true;
				taken.add(new double[]{FIRE_SPOTS.get(was)[0], FIRE_SPOTS.get(was)[1]});
			}
			else
			{
				need.add(id);
			}
		}
		// Then the rest: whoever had to leave their spot steps to the clear one nearest it,
		// whoever comes back from the middle goes back to the spot kept for them, and anyone
		// new takes the best clear spot.
		for (int id : need)
		{
			if (given.size() >= capacity)
			{
				break;
			}
			long[] had = remember ? fireSpotOf.get(id) : null;
			int was = had != null && had[0] == tile ? (int) had[1] : -1;
			// Drawn round this fire last tick by its crowd, now broken up: the spot nearest where they
			// stand. Given the first clear one, the last one left walked straight through the fire to it.
			double[] drawn = remember && was < 0 ? drawnLastTick(id, tile) : null;
			long[] held = remember ? fireSpotHeld.get(id) : null;
			int pick = was < 0 && drawn == null && held != null && held[0] == tile && free.test((int) held[1]) ? (int) held[1] : -1;
			if (pick < 0)
			{
				double nearest = Double.MAX_VALUE;
				for (int i = 0; i < FIRE_SPOTS.size(); i++)
				{
					if (!free.test(i))
					{
						continue;
					}
					if (was < 0 && drawn == null)
					{
						pick = i;
						break;
					}
					double d = drawn != null ? Math.hypot(FIRE_SPOTS.get(i)[0] - drawn[0], FIRE_SPOTS.get(i)[1] - drawn[1])
						: Math.hypot(FIRE_SPOTS.get(i)[0] - FIRE_SPOTS.get(was)[0], FIRE_SPOTS.get(i)[1] - FIRE_SPOTS.get(was)[1]);
					if (d < nearest)
					{
						nearest = d;
						pick = i;
					}
				}
			}
			if (pick < 0)
			{
				// Nothing a body's width clear: the roomiest spot that isn't inside anyone, and
				// not on your side.
				double roomiest = FIRE_FALLBACK_GAP;
				for (int i = 0; i < FIRE_SPOTS.size(); i++)
				{
					if (used[i] || !standable[i])
					{
						continue;
					}
					double room = Double.MAX_VALUE;
					for (double[] b : taken)
					{
						double d = Math.hypot(b[0] - FIRE_SPOTS.get(i)[0], b[1] - FIRE_SPOTS.get(i)[1]);
						room = b.length > 2 && b[2] > 0 && d < FIRE_YOUR_GAP ? -1 : Math.min(room, d);
						if (room < 0)
						{
							break;
						}
					}
					if (room >= roomiest)
					{
						roomiest = room;
						pick = i;
					}
				}
			}
			if (pick >= 0)
			{
				given.put(id, pick);
				used[pick] = true;
				taken.add(new double[]{FIRE_SPOTS.get(pick)[0], FIRE_SPOTS.get(pick)[1]});
			}
		}
		return given;
	}

	/** Diagnostics: fires laid out afresh to make room for someone left waiting in the flames. */
	long fireRepacks;

	/** How much nearer a fire a spot must be to count as the one nearest it. */
	static final int NEARER_THE_FIRE = 16;

	private static double fromFire(int[] spot, int[] fire)
	{
		return Math.hypot(spot[0] - fire[0], spot[1] - fire[1]);
	}

	/** {@link #layOutOnFires}, for the fires anyone stands on this tick: who stands where goes in {@code now}. */
	private void layOutRoundFires(Plan plan, Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, List<Integer>> onFires,
		Set<Long> alone, Set<Integer> placed, IntPredicate shown, int capacity, Map<Integer, long[]> now, Surroundings around)
	{
		// Everyone else drawn, as {plane, east, north, 1 if it's you}: at their spots, and one in the
		// middle of any tile where someone without a spot is drawn: you always are, and so is anyone
		// alone on their tile, whom the game draws whatever the middle of it is doing.
		int yourId = -1;
		for (List<StackSpreader.Entry> there : byTile.values())
		{
			for (StackSpreader.Entry en : there)
			{
				yourId = en.local ? en.id : yourId;
			}
		}
		List<double[]> bodies = new ArrayList<>();
		for (StackSpreader.Placement p : plan.placements)
		{
			bodies.add(new double[]{StackRegistry.plane(p.tile), StackRegistry.sceneX(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dx,
				StackRegistry.sceneY(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dz, p.id == yourId ? 1 : 0});
		}
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			long tile = e.getKey();
			List<Integer> layingOut = onFires.get(tile);
			for (StackSpreader.Entry en : e.getValue())
			{
				if (!placed.contains(en.id) && (layingOut == null || !layingOut.contains(en.id))
					&& (en.local || shown.test(en.id) && (e.getValue().size() == 1 || !plan.middleOutOfSight.contains(tile))))
				{
					bodies.add(new double[]{StackRegistry.plane(tile), StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE,
						StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE, en.local ? 1 : 0});
					break;
				}
			}
		}

		List<Long> fires = new ArrayList<>(onFires.keySet());
		fires.sort(Comparator.naturalOrder());
		for (long tile : fires)
		{
			int plane = StackRegistry.plane(tile);
			double fx = StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE;
			double fz = StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE;
			List<double[]> taken = new ArrayList<>();
			for (double[] b : bodies)
			{
				if (b[0] == plane && Math.hypot(b[1] - fx, b[2] - fz) < FIRE_OUTER + (b[3] > 0 ? FIRE_YOUR_GAP : FIRE_GAP))
				{
					taken.add(new double[]{b[1] - fx, b[2] - fz, b[3]});
				}
			}
			boolean[] standable = new boolean[FIRE_SPOTS.size()];
			int spots = 0;
			for (int i = 0; i < FIRE_SPOTS.size(); i++)
			{
				standable[i] = around.canStand(tile, FIRE_SPOTS.get(i)[0], FIRE_SPOTS.get(i)[1]);
				spots += standable[i] ? 1 : 0;
			}
			List<Integer> ids = onFires.get(tile);
			List<double[]> before = new ArrayList<>(taken);
			Map<Integer, Integer> given = fireSpots(tile, ids, true, standable, taken, capacity);
			if (given.size() < Math.min(ids.size(), capacity))
			{
				// Someone left waiting in the fire. Everyone keeping their spot can leave the gaps
				// between them too small for one more, where laid out afresh (turning the plugin off
				// and on) they all fit: then they're laid out afresh.
				List<double[]> afresh = new ArrayList<>(before);
				Map<Integer, Integer> repacked = fireSpots(tile, ids, false, standable, afresh, capacity);
				if (repacked.size() > given.size())
				{
					given = repacked;
					taken.clear();
					taken.addAll(afresh);
					fireRepacks++;
				}
			}
			for (Map.Entry<Integer, Integer> g : given.entrySet())
			{
				long[] had = fireSpotOf.get(g.getKey());
				fireMoves += had != null && had[0] == tile && had[1] != g.getValue() ? 1 : 0;
			}
			for (Map.Entry<Integer, Integer> g : given.entrySet())
			{
				int[] s = FIRE_SPOTS.get(g.getValue());
				plan.placements.add(new StackSpreader.Placement(g.getKey(), tile, s[0], s[1]));
				placed.add(g.getKey());
				now.put(g.getKey(), new long[]{tile, g.getValue()});
				fireSpotHeld.remove(g.getKey());
				// The fires after this one keep clear of them too.
				bodies.add(new double[]{plane, fx + s[0], fz + s[1], 0});
			}
			if (given.size() < ids.size() && !alone.contains(tile))
			{
				// Whoever is still left without a spot waits out of sight: nobody is drawn standing
				// in the flames. Unless it's you, whom the game draws there whatever.
				boolean youWait = false;
				for (int id : ids)
				{
					youWait |= id == yourId && !given.containsKey(id);
				}
				if (youWait)
				{
					bodies.add(new double[]{plane, fx, fz, 1});
				}
				else
				{
					plan.middleOutOfSight.add(tile);
				}
			}
			if (alone.contains(tile) && given.isEmpty())
			{
				continue;
			}
			int seen = 0;
			for (StackSpreader.Entry en : byTile.get(tile))
			{
				seen += shown.test(en.id) ? 1 : 0;
			}
			plan.fires.put(tile, new int[]{0, 0});
			plan.tiles.put(tile, new TileReport(alone.contains(tile) ? "alone by the fire" : "crowd on the fire", FIRE_SIDE, seen,
				given.size(), spots, FIRE_SPOTS.size() - spots));
		}
	}

	/**
	 * How far from the middle of an object the nearest ring of people round it stands: right up
	 * against it, a body's half-width clear of its edge, as someone working at an anvil stands. Round
	 * the middles of the tiles beside it (where the game draws someone using it) the crowd stood a
	 * step back from it, and the rest further out still.
	 */
	static final int OBJECT_NEAR_RING = HALF_TILE + 40;
	/** How much further out each ring round an object is than the one inside it. */
	static final int OBJECT_RING_STEP = 28;
	/**
	 * Furthest from the middle of an object anyone using it is drawn: just short of two tiles, a body
	 * behind the second row. Further out they're no longer at the anvil but across the room on their
	 * own, the hover arrow pointing back: past this they wait out of sight until there's room.
	 */
	static final int OBJECT_REACH = 240;
	/**
	 * Nobody round an object is given a spot closer than this to anyone else drawn: as close as two
	 * bodies stand without flickering through each other. A fire's wider gap left a busy anvil's
	 * crowd standing a step back from it.
	 */
	static final int OBJECT_GAP = FIRE_KEEP;
	/** ...a spot is kept while nobody is drawn closer than this, and with no spot a body's width clear, one this clear is taken. */
	static final int OBJECT_KEEP = FIRE_KEEP;
	/**
	 * ...and nobody round an object is drawn closer than this to you: right beside you, with a little
	 * more room than the rest have. Kept wider, so you could pick yourself out of a crowd of forty,
	 * the places either side of you stood empty, as if nobody would work the anvil next to you.
	 */
	static final int OBJECT_YOUR_GAP = 56;
	/**
	 * Nobody round an object is given a spot in the way of someone's cape (anything in the cape
	 * slot), behind them nearer than this, nor anyone wearing one a spot with someone that near behind
	 * them. Everyone there faces the object, so a cape hangs out towards the row behind, and with depth
	 * between players on it was drawn through the legs of whoever stood there. As the game's own
	 * models have it, standing and idling (a cape sways): a skill, max, fire, god or team cape or an
	 * Ava's device reaches 24 to 34 behind the middle of its wearer and 32 to 39 to either side, and
	 * the legs of someone behind reach up to 17 forward of the middle of theirs, so it passes through
	 * the legs of someone 44 to 52 straight behind, and of someone on the row behind off to one side
	 * out to about 66 apart.
	 */
	static final int CAPE_REACH = 68;
	/**
	 * ...and only behind them: within this far round from straight back (70 degrees, as a cosine).
	 * Further round, beside them, and in front of them, a cape touches nobody.
	 */
	static final double CAPE_BEHIND = Math.cos(Math.toRadians(70));
	/**
	 * A spot is kept while nobody is drawn in the way of a cape nearer than this, a little nearer
	 * than a spot is given at: someone putting a cape on, or drawn in the middle of their tile just
	 * behind one, doesn't walk everyone near them to the next spot and back.
	 */
	static final int CAPE_KEEP_REACH = 64;
	/** How far round a spot anyone drawn can crowd it, for any gap {@link Nearby#clear} is asked about, or be in the way of a cape worn there. */
	private static final int SPOT_NEIGHBOUR_REACH = 80;
	/**
	 * What a step nearer their own tile is worth to someone given a spot round an object, against a
	 * step nearer the object: a quarter. Within a tile of their tile, the nearest ring with room comes
	 * first, so a few at an anvil fan out round its corner right up against it, as round a fire,
	 * rather than standing behind each other.
	 */
	static final double OBJECT_TILE_WEIGHT = 0.25;
	/**
	 * ...but each step of their walk there further than a tile from their own tile counts this many
	 * times more. Taking the nearest ring with room wherever round the object it was sent a smith on
	 * the side by the crates right round the anvil to a gap on the far side, the hover arrow pointing
	 * back two tiles across the crowd, while there was room a ring further out on their own side. Now
	 * they stand a ring or two further out on their own side, and walk round to another side only
	 * once theirs is full.
	 */
	static final double OBJECT_FAR_WEIGHT = 2;
	/** Spots along each ring round an object are about this far apart, so a crowd packs as close as the gap lets it. */
	private static final int OBJECT_SPOT_STEP = 8;
	/** What the troubleshooting report calls a tile laid out as part of a crowd round an object. */
	static final String ROUND_OBJECT = "curved row, one crowd all round it";
	private static final long NO_OBJECT = Long.MIN_VALUE;

	/**
	 * A fire with at least this many at it, on its tile and the tiles round it (at least two of
	 * them), is stood round by one crowd, as an anvil is (see {@link #layOutRoundObjects}). Each
	 * tile round a busy campfire used to be laid out on its own, squeezed into its own slice of the
	 * ring until its people stood 26 apart, and the people standing on the fire, laid out last in
	 * whatever gaps were left, waited out of sight in it for minutes. A few at a fire keep their
	 * own rows, which suit them.
	 */
	static final int FIRE_CROWD = PersonalSpaceConfig.FULL_CROWD;
	/**
	 * ...and it stays one crowd while at least this many are still at it: a crowd that broke up
	 * and formed again as people came and went laid everyone at the fire out afresh each time.
	 */
	static final int FIRE_CROWD_KEEP = 2;
	/** What the troubleshooting report calls a tile laid out as part of the crowd round a fire. */
	static final String ROUND_FIRE = "one crowd round the fire";
	/**
	 * How far clear of a fire's tile, all round it, the people in the crowd round it stand: as far
	 * out as {@link #FIRE_SIDE}, past the flames.
	 */
	static final int FIRE_MARGIN = FIRE_SIDE - HALF_TILE;
	/**
	 * How far round a fire, either way from their own tile, the people on a tile beside it may stand
	 * in its crowd: anywhere there's room. They still take their own side first (the own-side cost),
	 * and never walk through the flames to get round. Held to their own side, they waited out of
	 * sight while open ground beside the fire stood empty, which looked better filled.
	 */
	static final double FIRE_SIDE_ARC = Math.PI;
	/** Nobody walks to their spot round a fire through this square round its tile: the flames, as the plugin keeps everyone out of them. */
	private static final int FLAMES = HALF_TILE + PersonalSpacePlugin.FIRE_CLEARANCE;
	/** Fires stood round by one crowd this tick, and last tick. */
	private Set<Long> fireCrowds = new HashSet<>();
	private Set<Long> fireCrowdsWas = new HashSet<>();
	/** The fire each tile's people stood round in a crowd last tick, and this tick. */
	private Map<Long, Long> previousFireOf = new HashMap<>();
	private Map<Long, Long> currentFireOf = new HashMap<>();
	/** The fires near each tile, looked up once a tick. */
	private final Map<Long, List<int[]>> firesNearNow = new HashMap<>();
	/** Tiles whose people are laid out in the crowd round a fire this tick. */
	private final Set<Long> roundFires = new HashSet<>();
	/**
	 * Everyone in the crowd round a fire left waiting out of sight for a spot, and the tick they
	 * started waiting, last tick and this. Whoever has waited longest on a tile is given the next
	 * spot from it: given in the order people stood on the tile, someone back from the bank went
	 * straight to the front while one who had waited there for minutes stayed out of sight.
	 */
	private Map<Integer, Integer> waitingSince = new HashMap<>();
	private Map<Integer, Integer> waitingSinceNow = new HashMap<>();

	/** {@link Surroundings#firesNear}, looked up once a tick for each tile. */
	private List<int[]> firesNear(long tile, Surroundings around)
	{
		return firesNearNow.computeIfAbsent(tile, around::firesNear);
	}

	/**
	 * The fire the people on this tile are at, or {@link #NO_OBJECT}: the one they stand on, the one
	 * their tile stood round in a crowd last tick while anyone there still faces it, else the one
	 * most of them face. If the one they stood round has gone out, another beside them that the same
	 * crowd stood round: their crowd closes in round what's left of it, rather than breaking up.
	 */
	private long fireOf(long tile, List<StackSpreader.Entry> group, Surroundings around)
	{
		List<int[]> fires = firesNear(tile, around);
		Long was = previousFireOf.get(tile);
		if (fires.isEmpty())
		{
			return was != null && embers.containsKey(was) && anchorOfWas.containsKey(was) ? was : NO_OBJECT;
		}
		if (fireUnderfoot(fires))
		{
			return tile;
		}
		boolean gone = was != null;
		for (int[] fire : fires)
		{
			if (was != null && StackRegistry.key(StackRegistry.plane(tile), StackRegistry.sceneX(tile) + fire[0], StackRegistry.sceneY(tile) + fire[1]) == was)
			{
				if (facingCount(group, tile, was) > 0 || atAnEmber(tile, group, was))
				{
					return was;
				}
				gone = false;
			}
		}
		Long crowd = gone ? anchorOfWas.get(was) : null;
		for (int[] fire : fires)
		{
			long at = StackRegistry.key(StackRegistry.plane(tile), StackRegistry.sceneX(tile) + fire[0], StackRegistry.sceneY(tile) + fire[1]);
			if (crowd != null && crowd.equals(anchorOfWas.get(at)))
			{
				return at;
			}
		}
		if (crowd != null && embers.containsKey(was))
		{
			return was;
		}
		int[] faced = fireFaced(group, fires);
		return faced == null ? NO_OBJECT
			: StackRegistry.key(StackRegistry.plane(tile), StackRegistry.sceneX(tile) + faced[0], StackRegistry.sceneY(tile) + faced[1]);
	}

	/**
	 * Whether the people on {@code tile} are at a fire gone out that the crowd round {@code fire}
	 * still stands round (see {@link #EMBER_TICKS}): on it, or beside it facing it. Moved over to the
	 * fire still burning beside them, they still faced the one gone out, and left the crowd.
	 */
	private boolean atAnEmber(long tile, List<StackSpreader.Entry> group, long fire)
	{
		Long crowd = anchorOfWas.get(fire);
		for (long ember : embers.keySet())
		{
			if (crowd != null && crowd.equals(anchorOfWas.get(ember)) && (ember == tile || isBeside(tile, ember) && facingCount(group, tile, ember) > 0))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Which fires are stood round by one crowd this tick (see {@link #FIRE_CROWD}), and which fire
	 * each tile's people are at.
	 */
	private Map<Long, Long> findFireCrowds(Map<Long, List<StackSpreader.Entry>> byTile, IntPredicate shown, Surroundings around, int tick)
	{
		// A fire just gone out is still stood round a while by whoever stands at it, out of its flames
		// now: those round it keep their places, and the crowd closes in on the ground it leaves. Round
		// the fire left, the spots past where its own crowd stands were gone at once, and everyone at
		// them walked across the crowd to the nearest gap, or broke away from it.
		Map<Long, Integer> stillWarm = new HashMap<>();
		for (Map.Entry<Long, Spots> e : spotsNow.entrySet())
		{
			// Last tick's spots round each crowd.
			Spots spots = e.getValue();
			for (int k = 0; spots.fires.length > 1 && k < spots.fires.length; k++)
			{
				long fire = StackRegistry.key(StackRegistry.plane(e.getKey()), StackRegistry.sceneX(e.getKey()) + spots.fires[k][0],
					StackRegistry.sceneY(e.getKey()) + spots.fires[k][1]);
				int since = embers.getOrDefault(fire, tick);
				if (!fireUnderfoot(firesNear(fire, around)) && tick - since < EMBER_TICKS && standingRound(e.getKey(), spots, k))
				{
					stillWarm.put(fire, since);
				}
			}
		}
		embers = stillWarm;
		Map<Long, Long> fireOfTile = new HashMap<>();
		Map<Long, int[]> atFire = new HashMap<>();
		for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
		{
			List<StackSpreader.Entry> group = new ArrayList<>(e.getValue().size());
			for (StackSpreader.Entry en : e.getValue())
			{
				if (shown.test(en.id))
				{
					group.add(en);
				}
			}
			long fire = group.isEmpty() ? NO_OBJECT : fireOf(e.getKey(), group, around);
			if (fire != NO_OBJECT)
			{
				fireOfTile.put(e.getKey(), fire);
			}
			if (fire != NO_OBJECT && !embers.containsKey(fire))
			{
				int[] count = atFire.computeIfAbsent(fire, k -> new int[2]);
				count[0] += group.size();
				count[1]++;
			}
		}
		for (Map.Entry<Long, int[]> e : atFire.entrySet())
		{
			int people = e.getValue()[0];
			if (e.getValue()[1] >= 2 && people >= FIRE_CROWD || fireCrowdsWas.contains(e.getKey()) && people >= FIRE_CROWD_KEEP)
			{
				fireCrowds.add(e.getKey());
			}
		}
		// A fire close beside a busy one (see FIRES_CLOSE) is stood round with it by one crowd, with
		// whoever is at it or with nobody at it. The people at one lit beside a busy campfire were laid
		// out on their own tiles in rows, or standing on it in its own few places: they piled up beside
		// the campfire's crowd or waited out of sight in the flames, while the campfire's crowd gave up
		// the ground round the new fire and waited out of sight beside an empty patch round it.
		for (long busy : new ArrayList<>(fireCrowds))
		{
			for (int east = 1 - FIRES_CLOSE; east < FIRES_CLOSE; east++)
			{
				for (int north = 1 - FIRES_CLOSE; north < FIRES_CLOSE; north++)
				{
					long tile = StackRegistry.key(StackRegistry.plane(busy), StackRegistry.sceneX(busy) + east, StackRegistry.sceneY(busy) + north);
					for (int[] f : firesNear(tile, around))
					{
						fireCrowds.add(StackRegistry.key(StackRegistry.plane(busy), StackRegistry.sceneX(tile) + f[0], StackRegistry.sceneY(tile) + f[1]));
					}
				}
			}
		}
		// Each crowd is laid out round one of its fires, the rest round it: the one it was laid out
		// round last tick, else the one most stand at.
		Set<Long> anchorsWas = new HashSet<>(anchorOfWas.values());
		List<Long> first = new ArrayList<>(fireCrowds);
		first.sort(Comparator.comparingInt((Long fire) -> anchorsWas.contains(fire) ? 0 : 1)
			.thenComparingInt(fire -> -atFire.getOrDefault(fire, new int[2])[0]).thenComparing(Comparator.naturalOrder()));
		for (long anchor : first)
		{
			if (anchorOf.containsKey(anchor))
			{
				continue;
			}
			anchorOf.put(anchor, anchor);
			List<Long> together = new ArrayList<>();
			for (long other : fireCrowds)
			{
				if (!anchorOf.containsKey(other) && closeTo(other, anchor))
				{
					together.add(other);
					anchorOf.put(other, anchor);
				}
			}
			if (!together.isEmpty())
			{
				together.sort(Comparator.naturalOrder());
				together.add(0, anchor);
				fireGroups.put(anchor, together);
			}
		}
		// Whoever stays at a fire gone out, beside no other, is still in the crowd that stood round it.
		for (long ember : embers.keySet())
		{
			for (long anchor : first)
			{
				if (anchorOf.get(anchor) == anchor && closeTo(ember, anchor))
				{
					emberGroups.computeIfAbsent(anchor, k -> new ArrayList<>()).add(ember);
					anchorOf.put(ember, anchor);
					break;
				}
			}
		}
		return fireOfTile;
	}

	/**
	 * How long a fire that has gone out is still stood round (see {@link #findFireCrowds}), at most,
	 * by whoever stood round it: five minutes. It's let go sooner, once they've all gone.
	 */
	static final int EMBER_TICKS = 500;
	/** Fires gone out that a crowd still stands round, and the tick each went out. */
	private Map<Long, Integer> embers = new HashMap<>();
	/** ...by the fire each of those crowds is laid out round. */
	private final Map<Long, List<Long>> emberGroups = new HashMap<>();

	/** Whether anyone was given one of the spots round fire {@code k} of these, round {@code object}, last tick. */
	private boolean standingRound(long object, Spots spots, int k)
	{
		for (long[] had : objectSpotOf.values())
		{
			if (had[0] == object && had[2] < spots.n && spots.round[(int) had[2]] == k)
			{
				return true;
			}
		}
		return false;
	}

	/** Each fire stood round by a crowd this tick, and the one its crowd is laid out round (see {@link #findFireCrowds}); and last tick. */
	private Map<Long, Long> anchorOf = new HashMap<>();
	private Map<Long, Long> anchorOfWas = new HashMap<>();
	/** The fires each crowd round more than one stands round, by the one it's laid out round: that one first. */
	private final Map<Long, List<Long>> fireGroups = new HashMap<>();

	/**
	 * How far apart, in tiles either way, fires are close enough to be stood round by one crowd
	 * while one of them is busy (see {@link #findFireCrowds}): near enough that someone between them
	 * is beside both.
	 */
	static final int FIRES_CLOSE = 2;

	/** Whether two fires are within {@link #FIRES_CLOSE} of each other. */
	private static boolean closeTo(long fire, long other)
	{
		return StackRegistry.plane(other) == StackRegistry.plane(fire)
			&& Math.abs(StackRegistry.sceneX(other) - StackRegistry.sceneX(fire)) <= FIRES_CLOSE
			&& Math.abs(StackRegistry.sceneY(other) - StackRegistry.sceneY(fire)) <= FIRES_CLOSE;
	}

	/** Puts the people on {@code tile} who may be moved in the crowd round {@code fire} (and the fires close beside it). */
	private void joinFireCrowd(long fire, long tile, List<Integer> movable, Map<Long, List<Long>> crowds, Map<Long, List<Integer>> ids)
	{
		crowds.computeIfAbsent(anchorOf.getOrDefault(fire, fire), k -> new ArrayList<>()).add(tile);
		ids.put(tile, movable);
		currentFireOf.put(tile, fire);
		roundFires.add(tile);
	}

	/**
	 * Where the people round an object may be drawn, as {east, north, ring} from its middle: rings
	 * {@link #OBJECT_RING_STEP} apart from {@link #OBJECT_NEAR_RING} out to {@link #OBJECT_REACH},
	 * each with a spot straight out from every tile round the object.
	 */
	static final List<int[]> OBJECT_SPOTS = objectSpots();
	private static final int[] SPOT_X = new int[OBJECT_SPOTS.size()];
	private static final int[] SPOT_Z = new int[OBJECT_SPOTS.size()];
	private static final int[] SPOT_RING = new int[OBJECT_SPOTS.size()];
	/** For someone on each tile round an object (see {@link #besideIndex}): every spot, nearest ring first, then nearest their tile. You choose by this. */
	private static final int[][] OBJECT_ORDER = new int[9][];
	/**
	 * What each spot costs someone on each tile round an object (see {@link #besideIndex}): how much
	 * further out than the nearest ring it is, plus {@link #OBJECT_TILE_WEIGHT} of how far it is from
	 * the middle of their tile, plus {@link #OBJECT_FAR_WEIGHT} times however much of that is further
	 * than a tile.
	 */
	private static final float[][] OBJECT_COST = new float[9][];
	/** For someone on each tile round an object (see {@link #besideIndex}): every spot, cheapest first (see {@link #OBJECT_COST}). */
	private static final int[][] OBJECT_CHEAPEST = new int[9][];
	/** How many spots the nearest ring round an object has. */
	private static final int NEAR_RING_SPOTS;
	/** How many spots each ring round an object has. */
	private static final int[] RING_SPOTS;
	/**
	 * For each of {@link #OBJECT_SPOTS}, the spots nearer to it than {@link #SPOT_NEIGHBOUR_REACH}: the
	 * only ones someone standing there can make too crowded, so the only ones worth checking.
	 */
	private static final short[][] SPOT_NEIGHBOURS = new short[OBJECT_SPOTS.size()][];
	/** Which of {@link #OBJECT_SPOTS} are {@link #FIRE_MARGIN} clear of a fire's tile all round, for the crowd round a fire. */
	private static final boolean[] CLEAR_OF_FLAMES = new boolean[OBJECT_SPOTS.size()];
	/**
	 * Where the people on each tile round an object (see {@link #besideIndex}) look, from its middle,
	 * if they face it: the plugin turns them to face the spot {@link StackSpreader#LOOK_AHEAD} in front
	 * of their tile (see {@link StackSpreader#faceSameSpot}). From a tile beside it that's its middle;
	 * from a corner, a little short of it. Their capes hang straight back from there. Only for someone
	 * whose facing isn't known: everyone else looks where they face (see {@link #lookingAt}).
	 */
	private static final double[][] OBJECT_FOCUS = new double[9][];
	/** ...and round a fire, everyone looks at its middle. */
	private static final double[] FIRE_FOCUS = {0, 0};
	/** Asked of {@link Nearby#clear} for someone who may stand in the way of anyone's cape, and anyone in the way of theirs. */
	private static final double[] ANY_CAPES = {};

	static
	{
		int nearRing = 0;
		for (int s = 0; s < OBJECT_SPOTS.size(); s++)
		{
			SPOT_X[s] = OBJECT_SPOTS.get(s)[0];
			SPOT_Z[s] = OBJECT_SPOTS.get(s)[1];
			SPOT_RING[s] = OBJECT_SPOTS.get(s)[2];
			nearRing += SPOT_RING[s] == 0 ? 1 : 0;
			CLEAR_OF_FLAMES[s] = Math.hypot(Math.max(0, Math.abs(SPOT_X[s]) - HALF_TILE), Math.max(0, Math.abs(SPOT_Z[s]) - HALF_TILE)) >= FIRE_MARGIN;
		}
		NEAR_RING_SPOTS = nearRing;
		RING_SPOTS = new int[SPOT_RING[SPOT_RING.length - 1] + 1];
		for (int ring : SPOT_RING)
		{
			RING_SPOTS[ring]++;
		}
		for (int s = 0; s < SPOT_X.length; s++)
		{
			short[] near = new short[SPOT_X.length];
			int count = 0;
			for (int t = 0; t < SPOT_X.length; t++)
			{
				int x = SPOT_X[t] - SPOT_X[s];
				int z = SPOT_Z[t] - SPOT_Z[s];
				if (x * x + z * z < SPOT_NEIGHBOUR_REACH * SPOT_NEIGHBOUR_REACH)
				{
					near[count++] = (short) t;
				}
			}
			SPOT_NEIGHBOURS[s] = java.util.Arrays.copyOf(near, count);
		}
		for (int beside = 0; beside < 9; beside++)
		{
			double east = (beside / 3 - 1) * 2.0 * HALF_TILE;
			double north = (beside % 3 - 1) * 2.0 * HALF_TILE;
			double away = Math.hypot(east, north);
			double shortOf = away == 0 ? 0 : Math.max(0, 1 - StackSpreader.LOOK_AHEAD / away);
			OBJECT_FOCUS[beside] = new double[]{east * shortOf, north * shortOf};
			Ranking ranked = rank(SPOT_X, SPOT_Z, SPOT_RING, null, east, north, beside == 4);
			OBJECT_ORDER[beside] = ranked.order;
			OBJECT_CHEAPEST[beside] = ranked.cheapest;
			OBJECT_COST[beside] = ranked.cost;
		}
	}

	/** For someone on one tile round an object: {@link #OBJECT_ORDER}, {@link #OBJECT_COST} and {@link #OBJECT_CHEAPEST}. */
	private static final class Ranking
	{
		int[] order;
		float[] cost;
		int[] cheapest;
	}

	/**
	 * Ranks these spots (from an object's middle, each this many rings out) for someone on the tile
	 * whose middle is at (east, north): the ones {@code use} allows, or all of them if it's null.
	 *
	 * @param onIt whether they stand on the object itself, which only a Forester's Campfire allows
	 */
	private static Ranking rank(int[] x, int[] z, int[] ring, IntPredicate use, double east, double north, boolean onIt)
	{
		float[] from = new float[x.length];
		List<Integer> order = new ArrayList<>(x.length);
		for (int s = 0; s < x.length; s++)
		{
			from[s] = (float) Math.hypot(x[s] - east, z[s] - north);
			if (use == null || use.test(s))
			{
				order.add(s);
			}
		}
		Ranking out = new Ranking();
		order.sort(Comparator.<Integer>comparingInt(s -> ring[s]).thenComparingDouble(s -> from[s]).thenComparingInt(s -> s));
		out.order = order.stream().mapToInt(Integer::intValue).toArray();
		float[] cost = new float[x.length];
		for (int s = 0; s < x.length; s++)
		{
			// Standing on the thing itself, which only a Forester's Campfire allows, they have no
			// side of their own: just the nearest ring with room, wherever round it that is. Charged
			// for their walk out to it as if from a tile beside it, they waited out of sight while
			// the people beside it took every place within reach.
			cost[s] = onIt ? ring[s] * OBJECT_RING_STEP : (float) (ring[s] * OBJECT_RING_STEP + OBJECT_TILE_WEIGHT * from[s]
				+ OBJECT_FAR_WEIGHT * Math.max(0, from[s] - 2.0 * HALF_TILE));
		}
		order.sort(Comparator.<Integer>comparingDouble(s -> cost[s]).thenComparingInt(s -> ring[s]).thenComparingDouble(s -> from[s]).thenComparingInt(s -> s));
		out.cheapest = order.stream().mapToInt(Integer::intValue).toArray();
		out.cost = cost;
		return out;
	}

	/**
	 * Where the people in one crowd round an object may be drawn, as {@link #OBJECT_SPOTS} has them,
	 * by index. Round most things, those. Round a fire with others close beside it, stood round by
	 * one crowd (see {@link #FIRES_CLOSE}), those and the same round each of the others, clear of all
	 * their flames: the crowd stands round the fires together, as close to each as round one, and
	 * anyone at one may stand at another. Laid out round each fire on its own, the busy fire's crowd
	 * waited out of sight while the ground round the new fire beside it stood empty.
	 */
	private static final class Spots
	{
		/** The crowd's fires, as {east, north} tile steps from the object, the object's own first; just it round anything else. */
		final int[][] fires;
		/** Which of them have gone out a moment ago (see {@link #EMBER_TICKS}): no flames, and nobody new is sent round them. */
		final boolean[] out;
		/** Where the fires are, as a key: two with the same have the same spots, by the same numbers. */
		final String layout;
		final int n;
		/** Each spot, from the object's middle. The first ones are {@link #OBJECT_SPOTS}, in their order. */
		final int[] x;
		final int[] z;
		/** Which fire each spot is one of the ring round: the first ones the object's, then each of the others' in turn. */
		final int[] round;
		/** How many rings out from the nearest of the fires still burning (or the object) each spot is. */
		final int[] ring;
		/** Whether each spot is {@link #FIRE_MARGIN} clear of the object's tile, and of every other fire of the crowd still burning. */
		final boolean[] clear;
		/**
		 * Whether anyone may be given each spot: clear, near a fire still burning, and not one of the
		 * ring round one gone out. Otherwise it's only kept by whoever stands there, so the fire gone
		 * out is let go once they've all gone (see {@link #EMBER_TICKS}).
		 */
		final boolean[] open;
		/** How many rings out from each of the fires each spot is. */
		private final int[][] rings;
		/** How far from the object's middle any of them is, at most. */
		final double reach;
		private final short[][] neighbours;
		private final Map<Integer, Ranking> ranked = new HashMap<>();

		/** Round one object (a fire or not). */
		private Spots()
		{
			fires = new int[][]{{0, 0}};
			out = new boolean[1];
			layout = "";
			n = SPOT_X.length;
			x = SPOT_X;
			z = SPOT_Z;
			round = new int[n];
			ring = SPOT_RING;
			rings = new int[][]{SPOT_RING};
			clear = CLEAR_OF_FLAMES;
			open = new boolean[n];
			java.util.Arrays.fill(open, true);
			neighbours = SPOT_NEIGHBOURS;
			reach = OBJECT_REACH;
		}

		/** Round these fires, as {east, north} tile steps from the first, of which these have gone out. */
		Spots(int[][] fires, boolean[] out)
		{
			this.fires = fires;
			this.out = out;
			StringBuilder key = new StringBuilder();
			double furthest = 0;
			for (int[] f : fires)
			{
				key.append(f[0]).append(',').append(f[1]).append(';');
				furthest = Math.max(furthest, Math.hypot(f[0], f[1]) * 2 * HALF_TILE);
			}
			layout = key.toString();
			reach = OBJECT_REACH + furthest;
			// The spots round the object first, in their order, so they keep their numbers whatever is
			// lit or goes out beside it. Then the same round each of the others, clear of all their
			// flames, as they were while they all burned. Where the rings round two fires cross they're
			// twice as close together: thinned out, someone whose crowd was laid out round another fire
			// once the first went out was moved to the nearest spot left, nearer someone else, and had
			// to step away.
			List<int[]> at = new ArrayList<>();
			for (int s = 0; s < SPOT_X.length; s++)
			{
				at.add(new int[]{SPOT_X[s], SPOT_Z[s], 0});
			}
			for (int k = 1; k < fires.length; k++)
			{
				for (int s = 0; s < SPOT_X.length; s++)
				{
					int px = SPOT_X[s] + fires[k][0] * 2 * HALF_TILE;
					int pz = SPOT_Z[s] + fires[k][1] * 2 * HALF_TILE;
					if (clearOf(px, pz, true))
					{
						at.add(new int[]{px, pz, k});
					}
				}
			}
			n = at.size();
			x = new int[n];
			z = new int[n];
			round = new int[n];
			ring = new int[n];
			clear = new boolean[n];
			open = new boolean[n];
			rings = new int[fires.length][n];
			neighbours = new short[n][];
			for (int s = 0; s < n; s++)
			{
				x[s] = at.get(s)[0];
				z[s] = at.get(s)[1];
				round[s] = at.get(s)[2];
				clear[s] = clearOf(x[s], z[s], false);
				ring[s] = Integer.MAX_VALUE;
				for (int k = 0; k < fires.length; k++)
				{
					double from = Math.hypot(x[s] - fires[k][0] * 2.0 * HALF_TILE, z[s] - fires[k][1] * 2.0 * HALF_TILE);
					rings[k][s] = k == 0 && s < SPOT_X.length ? SPOT_RING[s] : Math.max(0, (int) Math.round((from - OBJECT_NEAR_RING) / OBJECT_RING_STEP));
					if (!out[k])
					{
						ring[s] = Math.min(ring[s], rings[k][s]);
						open[s] |= clear[s] && !out[round[s]] && from <= OBJECT_REACH;
					}
				}
			}
		}

		/** Whether (east, north) is {@link #FIRE_MARGIN} clear of the tiles of the fires still burning, or of all of them. */
		private boolean clearOf(double east, double north, boolean all)
		{
			for (int k = 0; k < fires.length; k++)
			{
				if ((all || !out[k]) && Math.hypot(Math.max(0, Math.abs(east - fires[k][0] * 2.0 * HALF_TILE) - HALF_TILE),
					Math.max(0, Math.abs(north - fires[k][1] * 2.0 * HALF_TILE) - HALF_TILE)) < FIRE_MARGIN)
				{
					return false;
				}
			}
			return true;
		}

		/** Which of the fires still burning is on the tile (east, north) tile steps from the object, or -1. */
		int fireOn(int east, int north)
		{
			for (int k = 0; k < fires.length; k++)
			{
				if (!out[k] && fires[k][0] == east && fires[k][1] == north)
				{
					return k;
				}
			}
			return -1;
		}

		/** How many rings out from the fire {@code k} spot {@code s} is. */
		int ringRound(int k, int s)
		{
			return rings[k][s];
		}

		/**
		 * Whether spot {@code s} is in reach for someone on the tile (east, north) tile steps from the
		 * object: no further than {@link #OBJECT_REACH} from one of the fires beside it, as round one
		 * fire. Round a crowd's fires, someone from beside the furthest one was sent round to the far
		 * side of the nearest, then walked all the way back when it went out.
		 */
		boolean inReach(int east, int north, int s)
		{
			for (int[] f : fires)
			{
				// Squared, in whole units: asked of every spot for every tile.
				long dx = x[s] - f[0] * 2L * HALF_TILE;
				long dz = z[s] - f[1] * 2L * HALF_TILE;
				if (Math.abs(f[0] - east) <= 1 && Math.abs(f[1] - north) <= 1 && dx * dx + dz * dz <= (long) OBJECT_REACH * OBJECT_REACH)
				{
					return true;
				}
			}
			return false;
		}

		/** The spots nearer to spot {@code s} than {@link #SPOT_NEIGHBOUR_REACH} (see {@link #SPOT_NEIGHBOURS}), worked out once. */
		short[] neighbours(int s)
		{
			if (neighbours[s] == null)
			{
				short[] near = new short[n];
				int count = 0;
				for (int t = 0; t < n; t++)
				{
					int east = x[t] - x[s];
					int north = z[t] - z[s];
					if (east * east + north * north < SPOT_NEIGHBOUR_REACH * SPOT_NEIGHBOUR_REACH)
					{
						near[count++] = (short) t;
					}
				}
				neighbours[s] = java.util.Arrays.copyOf(near, count);
			}
			return neighbours[s];
		}

		/** The spots anyone may be given, ranked for someone on the tile (east, north) tile steps from the object (see {@link #rank}). */
		Ranking rankedFor(int east, int north)
		{
			if (fires.length == 1 && Math.abs(east) <= 1 && Math.abs(north) <= 1)
			{
				int beside = (east + 1) * 3 + north + 1;
				Ranking ranking = new Ranking();
				ranking.order = OBJECT_ORDER[beside];
				ranking.cost = OBJECT_COST[beside];
				ranking.cheapest = OBJECT_CHEAPEST[beside];
				return ranking;
			}
			return ranked.computeIfAbsent((east + 16) * 64 + north + 16, k ->
			{
				// Standing on one of the fires, how far out from it; beside them, from the nearest.
				int on = fireOn(east, north);
				// Only the spots they could ever be given (see Ground#walksFrom): round a crowd's fires
				// there are thousands, and going through every one for every tile, every tick, was
				// nearly half the planner's time beside a line of fires lit by people training.
				return rank(x, z, on >= 0 ? rings[on] : ring, s -> open[s] && inReach(east, north, s), east * 2.0 * HALF_TILE, north * 2.0 * HALF_TILE, on >= 0);
			});
		}
	}

	/** Round one object, or a fire on its own: {@link #OBJECT_SPOTS}. */
	private static final Spots ROUND_ONE = new Spots();

	/**
	 * Which way a cape worn at (x, z) hangs, its wearer looking at {@code focus} (see
	 * {@link #lookingAt}), all from an object's middle: a unit vector, or null if they stand on it.
	 */
	static double[] hangs(double x, double z, double[] focus)
	{
		double east = x - focus[0];
		double north = z - focus[1];
		double away = Math.hypot(east, north);
		return away < 1 ? null : new double[]{east / away, north / away};
	}

	/**
	 * Whether a body at (x, z) is in the way of a cape worn at (capeX, capeZ) hanging towards
	 * {@code back} (see {@link #hangs}): behind it, nearer than {@code reach} (see {@link #CAPE_REACH}).
	 */
	static boolean behind(double capeX, double capeZ, double[] back, double x, double z, int reach)
	{
		return back != null && behind(capeX, capeZ, back[0], back[1], x, z, reach);
	}

	/** {@link #behind}, the way it hangs given as (backX, backZ). */
	private static boolean behind(double capeX, double capeZ, double backX, double backZ, double x, double z, int reach)
	{
		double east = x - capeX;
		double north = z - capeZ;
		double apart = east * east + north * north;
		if (apart == 0 || apart >= (double) reach * reach)
		{
			return false;
		}
		double along = east * backX + north * backZ;
		return along > 0 && along * along > CAPE_BEHIND * CAPE_BEHIND * apart;
	}

	private static List<int[]> objectSpots()
	{
		List<int[]> out = new ArrayList<>();
		for (int ring = 0; OBJECT_NEAR_RING + ring * OBJECT_RING_STEP <= OBJECT_REACH; ring++)
		{
			int radius = OBJECT_NEAR_RING + ring * OBJECT_RING_STEP;
			// A multiple of eight, so every ring has a spot straight out from each tile round the object.
			int count = 8 * (int) Math.round(2 * Math.PI * radius / (8.0 * OBJECT_SPOT_STEP));
			for (int i = 0; i < count; i++)
			{
				double a = 2 * Math.PI * i / count;
				out.add(new int[]{(int) Math.round(radius * Math.cos(a)), (int) Math.round(radius * Math.sin(a)), ring});
			}
		}
		return out;
	}

	/** Which of the nine tiles centred on an object this tile is, 0 to 8, counting north then east from the south-west one. */
	private static int besideIndex(long tile, long object)
	{
		return (StackRegistry.sceneX(tile) - StackRegistry.sceneX(object) + 1) * 3 + StackRegistry.sceneY(tile) - StackRegistry.sceneY(object) + 1;
	}

	/** Tiles nobody can stand on, such as an anvil's, as found this tick. */
	private final Map<Long, Boolean> objectAt = new HashMap<>();
	/** The object each tile's crowd stood round last tick, and this tick. */
	private Map<Long, Long> previousObjectOf = new HashMap<>();
	private Map<Long, Long> currentObjectOf = new HashMap<>();
	/** Who stood at which of {@link #OBJECT_SPOTS} last tick: id to {object, their tile, spot}. */
	private Map<Integer, long[]> objectSpotOf = new HashMap<>();
	/** A spot round an object kept a while for someone who lost it, to go back to: id to {object, their tile, spot, until}. */
	private final Map<Integer, long[]> objectSpotHeld = new HashMap<>();
	/**
	 * Whoever is on their way back to the middle of their tile, with nowhere round the object they
	 * could walk to straight from where they stood: id to the last tick they're still on the way.
	 * They're given no spot till then, so their next walk starts from the middle, not from halfway.
	 */
	private final Map<Integer, Integer> steppingBack = new HashMap<>();
	/** This tick, for {@link #objectSpots}. */
	private int objectTick;
	/** The ground round each object a crowd stands round, kept while it stays the same. */
	private Map<Long, Ground> grounds = new HashMap<>();
	/** The spots round each object a crowd stands round this tick, and last tick (see {@link Spots}). */
	private Map<Long, Spots> spotsNow = new HashMap<>();
	private Map<Long, Spots> spotsWas = new HashMap<>();
	/** Spots round fires with others close beside them, by where the others are, kept while they're used. */
	private final Map<String, Spots> spotsKept = new HashMap<>();
	/** Round a crowd's fires, where the people on each tile look this tick (see {@link #fireFocus}). */
	private final Map<Long, double[]> fireFocusNow = new HashMap<>();
	/** Most objects whose ground is kept: walks round each take a moment to work out. */
	private static final int GROUNDS_KEPT = 32;
	/** Diagnostics: times someone round an object stepped to another spot round it. */
	private long objectMoves;

	/** Most tiles an object a crowd stands all round may cover: a furnace's four. */
	static final int OBJECT_MAX_TILES = 4;

	/**
	 * Whether this tile is a thing on its own that nobody can stand on, such as an anvil, range or
	 * tree: it can't be stood on, and nor can at most {@link #OBJECT_MAX_TILES} tiles touching it
	 * all told, all within two tiles of it. Not a wall, cliff or the sea, which run on: people facing
	 * those stand along them, and no crowd could stand round them.
	 */
	private boolean isObject(long tile, Surroundings around)
	{
		return objectAt.computeIfAbsent(tile, k ->
		{
			if (around.canStand(k, 0, 0))
			{
				return false;
			}
			int size = 5;
			boolean[] closed = new boolean[size * size];
			for (int i = 0; i < size; i++)
			{
				for (int j = 0; j < size; j++)
				{
					closed[i * size + j] = (i != size / 2 || j != size / 2) && !around.canStand(
						StackRegistry.key(StackRegistry.plane(k), StackRegistry.sceneX(k) + i - size / 2, StackRegistry.sceneY(k) + j - size / 2), 0, 0);
				}
			}
			// Everything that can't be stood on joined to it, corner to corner too.
			List<Integer> part = new ArrayList<>();
			boolean[] seen = new boolean[size * size];
			part.add(size * size / 2);
			seen[size * size / 2] = true;
			for (int p = 0; p < part.size(); p++)
			{
				int i = part.get(p) / size;
				int j = part.get(p) % size;
				if (i == 0 || j == 0 || i == size - 1 || j == size - 1 || part.size() > OBJECT_MAX_TILES)
				{
					return false;
				}
				for (int di = -1; di <= 1; di++)
				{
					for (int dj = -1; dj <= 1; dj++)
					{
						int q = (i + di) * size + j + dj;
						if (closed[q] && !seen[q])
						{
							seen[q] = true;
							part.add(q);
						}
					}
				}
			}
			return part.size() <= OBJECT_MAX_TILES;
		});
	}

	/**
	 * Which of the objects with a crowd round them someone alone on {@code tile} joins, or
	 * {@link #NO_OBJECT}: the one they stood round last tick while they still face it, else the one
	 * they face most squarely. Between two anvils, someone facing one of them at a slant joined
	 * the other, and walked across to it.
	 */
	private long objectForAlone(long tile, List<StackSpreader.Entry> there, Map<Long, List<Long>> crowds)
	{
		long[] had = objectSpotOf.get(there.get(0).id);
		if (had != null && had[1] == tile && crowds.containsKey(had[0]) && fireFacedFrom(tile, there, had[0]) != NO_OBJECT)
		{
			return had[0];
		}
		long best = NO_OBJECT;
		double squarest = Double.NEGATIVE_INFINITY;
		for (long object : crowds.keySet())
		{
			// Round a crowd's fires, any of them.
			for (long at : firesRound(object))
			{
				if (!isBeside(tile, at) || facingCount(there, tile, at) == 0)
				{
					continue;
				}
				double facing = facing(there.get(0), tile, at);
				if (facing > squarest + 1e-9 || Math.abs(facing - squarest) <= 1e-9 && object < best)
				{
					best = object;
					squarest = facing;
				}
			}
		}
		return best;
	}

	/** How squarely someone on {@code tile} faces the middle of {@code object} next to it: 1 straight at it. */
	private static double facing(StackSpreader.Entry e, long tile, long object)
	{
		double a = StackSpreader.toRadians(e.orientation);
		double east = StackRegistry.sceneX(object) - StackRegistry.sceneX(tile);
		double north = StackRegistry.sceneY(object) - StackRegistry.sceneY(tile);
		return (-Math.sin(a) * east - Math.cos(a) * north) / Math.hypot(east, north);
	}

	/**
	 * Of the fires the crowd round {@code object} stands round (just it, round anything else), the
	 * one beside {@code tile} its people face most squarely, or {@link #NO_OBJECT} if they face none.
	 */
	private long fireFacedFrom(long tile, List<StackSpreader.Entry> there, long object)
	{
		long best = NO_OBJECT;
		double squarest = Double.NEGATIVE_INFINITY;
		for (long at : firesRound(object))
		{
			if (isBeside(tile, at) && facingCount(there, tile, at) > 0 && facing(there.get(0), tile, at) > squarest + 1e-9)
			{
				best = at;
				squarest = facing(there.get(0), tile, at);
			}
		}
		return best;
	}

	/**
	 * The fires a crowd stands round, if it's the crowd round {@code object}, a fire with others
	 * close beside it (see {@link #FIRES_CLOSE}): it first, then the others. Otherwise just it.
	 */
	private List<Long> firesOf(long object)
	{
		List<Long> fires = fireGroups.get(object);
		return fires != null ? fires : Collections.singletonList(object);
	}

	/** {@link #firesOf}, and any of them gone out that the crowd still stands round (see {@link #EMBER_TICKS}). */
	private List<Long> firesRound(long object)
	{
		List<Long> gone = emberGroups.get(object);
		if (gone == null)
		{
			return firesOf(object);
		}
		List<Long> fires = new ArrayList<>(firesOf(object));
		fires.addAll(gone);
		return fires;
	}

	/** The spots round the crowd round {@code object} this tick (see {@link Spots}). */
	private Spots spotsOf(long object)
	{
		return spotsNow.getOrDefault(object, ROUND_ONE);
	}

	/** Spots round the crowd round {@code object}: round it, and round each other fire it stands round. */
	private Spots spotsFor(long object)
	{
		List<Long> fires = new ArrayList<>(firesRound(object));
		if (fires.size() < 2 || !fireCrowds.contains(object))
		{
			return ROUND_ONE;
		}
		// In the order they had while they all burned: it first, then the others.
		fires.subList(1, fires.size()).sort(Comparator.naturalOrder());
		int[][] steps = new int[fires.size()][];
		boolean[] out = new boolean[fires.size()];
		StringBuilder key = new StringBuilder();
		for (int k = 0; k < fires.size(); k++)
		{
			steps[k] = new int[]{StackRegistry.sceneX(fires.get(k)) - StackRegistry.sceneX(object), StackRegistry.sceneY(fires.get(k)) - StackRegistry.sceneY(object)};
			out[k] = embers.containsKey(fires.get(k));
			key.append(steps[k][0]).append(',').append(steps[k][1]).append(out[k] ? "x;" : ";");
		}
		Spots kept = spotsKept.get(key.toString());
		if (kept == null)
		{
			if (spotsKept.size() >= GROUNDS_KEPT)
			{
				spotsKept.clear();
			}
			kept = new Spots(steps, out);
			spotsKept.put(key.toString(), kept);
		}
		return kept;
	}

	/**
	 * Round a fire, where the people on {@code tile} look, from the middle of {@code object}: the
	 * middle of the fire they're at. The same array all tick (see {@link AtObject#cape}).
	 */
	private double[] fireFocus(long object, long tile)
	{
		Long fire = currentFireOf.get(tile);
		if (fire == null || fire == object)
		{
			return FIRE_FOCUS;
		}
		return fireFocusNow.computeIfAbsent(tile, k -> new double[]{(StackRegistry.sceneX(fire) - StackRegistry.sceneX(object)) * 2.0 * HALF_TILE,
			(StackRegistry.sceneY(fire) - StackRegistry.sceneY(object)) * 2.0 * HALF_TILE});
	}

	/** Whether {@code tile} is one of the eight round any of these. */
	private static boolean besideOneOf(long tile, List<Long> objects)
	{
		for (long object : objects)
		{
			if (isBeside(tile, object))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether {@code tile} is one of the eight round {@code object}. */
	private static boolean isBeside(long tile, long object)
	{
		return tile != object && StackRegistry.plane(tile) == StackRegistry.plane(object)
			&& Math.abs(StackRegistry.sceneX(tile) - StackRegistry.sceneX(object)) <= 1
			&& Math.abs(StackRegistry.sceneY(tile) - StackRegistry.sceneY(object)) <= 1;
	}

	/** How many of these people, on {@code tile}, face the middle of {@code object} next to it (within 45 degrees). */
	private static int facingCount(List<StackSpreader.Entry> group, long tile, long object)
	{
		double east = StackRegistry.sceneX(object) - StackRegistry.sceneX(tile);
		double north = StackRegistry.sceneY(object) - StackRegistry.sceneY(tile);
		double length = Math.hypot(east, north);
		int count = 0;
		for (StackSpreader.Entry e : group)
		{
			if (e.orientation >= 0 && length > 0)
			{
				double a = StackSpreader.toRadians(e.orientation);
				count += (-Math.sin(a) * east - Math.cos(a) * north) / length >= FACING_FIRE ? 1 : 0;
			}
		}
		return count;
	}

	/**
	 * The object a tile's crowd is gathered round, if Smart lays them out as one crowd all round it:
	 * the tile next to theirs that their row faces, if nobody can stand on it (an anvil, range or
	 * tree; not a wall between tiles, nor water along a bank, which make a counter). Once round one, a
	 * tile stays round it while anyone there still faces it, so someone turning to the door for a
	 * moment doesn't break the crowd up and put it back.
	 */
	private long objectFaced(long tile, boolean row, double angle, List<StackSpreader.Entry> group, Surroundings around)
	{
		if (row)
		{
			long ahead = StackRegistry.key(StackRegistry.plane(tile), StackRegistry.sceneX(tile) + (int) Math.round(-Math.sin(angle)),
				StackRegistry.sceneY(tile) + (int) Math.round(-Math.cos(angle)));
			if (ahead != tile && isObject(ahead, around))
			{
				return ahead;
			}
		}
		Long was = previousObjectOf.get(tile);
		return was != null && isObject(was, around) && facingCount(group, tile, was) > 0 ? was : NO_OBJECT;
	}

	/**
	 * The ground round an object: which of the five by five tiles centred on it can be stood on, and
	 * which ways each can be stepped off, as {@link Surroundings#canStand} says. From that, which spots
	 * round it someone on each tile beside it can walk to in a straight line, as their walk there is
	 * drawn, without passing through the object, a wall or anything else in the way.
	 */
	private static final class Ground
	{
		/** How many tiles across the ground round one object is: two out from it all round. */
		private static final int SIZE = 5;
		private static final int EAST = 0;
		private static final int WEST = 1;
		private static final int NORTH = 2;
		private static final int SOUTH = 3;
		/** How many tiles across it is (round a crowd's fires, far enough for every spot round them), and which is the object's. */
		private final int size;
		private final int middle;
		private final boolean[] open;
		private final boolean[] step;
		/** Worked out once while the ground stays the same: see {@link #walksFrom}. */
		private final boolean[][] walks;
		/** The spots it's worked out for. */
		private final Spots spots;
		/**
		 * Whether the object is a fire. Nobody walks through its flames, as through an anvil, but the
		 * people standing on it (a Forester's Campfire can be stood on) walk out of them.
		 */
		private final boolean fire;
		/**
		 * Round a fire, which of the tiles centred on it have another fire on them, or null: nobody
		 * walks through those flames either. Two campfires side by side, someone walked from one round
		 * the corner of the other.
		 */
		private final boolean[] otherFire;
		/** ...and where the middle of each of those tiles is, from the object's middle. */
		private final List<double[]> otherFires = new ArrayList<>();

		Ground(long object, Surroundings around, boolean fire, boolean[] otherFire, Spots spots)
		{
			this.fire = fire;
			this.otherFire = otherFire;
			this.spots = spots;
			size = otherFire == null ? SIZE : (int) Math.round(Math.sqrt(otherFire.length));
			middle = size * size / 2;
			for (int t = 0; otherFire != null && t < otherFire.length; t++)
			{
				if (otherFire[t])
				{
					otherFires.add(new double[]{(t / size - size / 2) * 2.0 * HALF_TILE, (t % size - size / 2) * 2.0 * HALF_TILE});
				}
			}
			open = new boolean[size * size];
			step = new boolean[size * size * 4];
			walks = new boolean[size * size][];
			int plane = StackRegistry.plane(object);
			for (int i = 0; i < size; i++)
			{
				for (int j = 0; j < size; j++)
				{
					long tile = StackRegistry.key(plane, StackRegistry.sceneX(object) + i - size / 2, StackRegistry.sceneY(object) + j - size / 2);
					int t = i * size + j;
					open[t] = around.canStand(tile, 0, 0);
					// A fire's own tile can't be stood on (the flames), but whether a wall keeps anyone
					// from stepping off it onto the tiles beside it says which way they can walk out. So
					// for another fire's: one may be lit under people standing there.
					if (open[t] || fire && t == middle || otherFire != null && otherFire[t])
					{
						step[t * 4 + EAST] = around.canStand(tile, 2 * HALF_TILE, 0);
						step[t * 4 + WEST] = around.canStand(tile, -2 * HALF_TILE, 0);
						step[t * 4 + NORTH] = around.canStand(tile, 0, 2 * HALF_TILE);
						step[t * 4 + SOUTH] = around.canStand(tile, 0, -2 * HALF_TILE);
					}
				}
			}
		}

		boolean sameAs(Ground other)
		{
			return other != null && fire == other.fire && spots == other.spots && java.util.Arrays.equals(otherFire, other.otherFire)
				&& java.util.Arrays.equals(open, other.open) && java.util.Arrays.equals(step, other.step);
		}

		/**
		 * Whether a body could be drawn at (east, north) from the object's middle: on ground that can be
		 * stood on, not pressed up against the object or a wall (as {@link CollisionTerrain} keeps them).
		 */
		boolean clearAt(double east, double north)
		{
			double x = east + (size / 2) * 2 * HALF_TILE + HALF_TILE;
			double z = north + (size / 2) * 2 * HALF_TILE + HALF_TILE;
			int i = (int) Math.floor(x / (2 * HALF_TILE));
			int j = (int) Math.floor(z / (2 * HALF_TILE));
			if (i < 0 || j < 0 || i >= size || j >= size || !open[i * size + j])
			{
				return false;
			}
			double ox = x - i * 2 * HALF_TILE;
			double oz = z - j * 2 * HALF_TILE;
			int t = (i * size + j) * 4;
			int margin = CollisionTerrain.EDGE_MARGIN;
			int far = 2 * HALF_TILE - 1 - margin;
			return !(ox < margin && !step[t + WEST] || ox > far && !step[t + EAST]
				|| oz < margin && !step[t + SOUTH] || oz > far && !step[t + NORTH]);
		}

		/** Which of the spots someone on the tile (east, north) tile steps from the object can walk to straight from the middle of their tile. */
		boolean[] walksFrom(int east, int north)
		{
			int t = (east + size / 2) * size + north + size / 2;
			if (walks[t] == null)
			{
				double fromX = east * 2.0 * HALF_TILE;
				double fromZ = north * 2.0 * HALF_TILE;
				// Standing on a fire, they walk out of it to stand round it: no further out than the
				// people on a fire nobody crowds round stand (FIRE_OUTER), so they're still at it. Round
				// a crowd's fires, as far out as anyone at them: lit beside the one they stood on, the
				// other fire took the places on that side, and they waited out of sight in the flames
				// while there was room a ring or two out (they still take the nearest first).
				int onFire = fire && spots == ROUND_ONE ? spots.fireOn(east, north) : -1;
				// Beside a fire, on their own side of it: no further round than the next tile's way
				// out from it. Sent round to whatever gap was left, someone from the north side of a
				// busy fire was drawn on its far side, the hover arrow pointing back across it.
				boolean ownSide = fire && onFire < 0;
				boolean[] out = new boolean[spots.n];
				for (int s = 0; s < out.length; s++)
				{
					out[s] = clearAt(spots.x[s], spots.z[s]) && (onFire < 0 || OBJECT_NEAR_RING + spots.ringRound(onFire, s) * OBJECT_RING_STEP <= FIRE_OUTER)
						&& (spots == ROUND_ONE || spots.inReach(east, north, s))
						&& (!ownSide || Math.abs(signedAngle(fromX, fromZ, spots.x[s], spots.z[s])) <= FIRE_SIDE_ARC + 1e-9)
						&& clearWalk(fromX, fromZ, spots.x[s], spots.z[s]);
				}
				walks[t] = out;
			}
			return walks[t];
		}

		/** Whether someone standing at {@code from} ({east, north} from the object's middle) can walk straight to one of the spots without passing through anything. */
		boolean walkBetween(double[] from, int to)
		{
			return from[0] == spots.x[to] && from[1] == spots.z[to] || clearWalk(from[0], from[1], spots.x[to], spots.z[to]);
		}

		/**
		 * Whether a straight walk between two points (from the object's middle) never passes where a
		 * body couldn't stand: through a tile that can't be stood on, or within
		 * {@link CollisionTerrain#EDGE_MARGIN} of an edge that can't be stepped over, or round a fire
		 * through its flames. A walk from inside a fire's flames, by someone standing on it or someone
		 * a fire was just lit beside or under, walks out of them, away from the fire: only a wall along
		 * the edges of the fire's tile stops that, and nothing the walk starts inside. (Not on through
		 * them to the far side: lit under a crowd, they walked across the new fire.)
		 */
		private boolean clearWalk(double ax, double az, double bx, double bz)
		{
			if (fire && crosses(ax, az, bx, bz, -FLAMES, -FLAMES, FLAMES, FLAMES) && !outOfFlames(ax, az, bx, bz, 0, 0))
			{
				return false;
			}
			boolean inFlames = fire && inside(ax, az, -FLAMES, -FLAMES, FLAMES, FLAMES);
			for (double[] f : otherFires)
			{
				double x = f[0];
				double z = f[1];
				if (crosses(ax, az, bx, bz, x - FLAMES, z - FLAMES, x + FLAMES, z + FLAMES))
				{
					if (!outOfFlames(ax, az, bx, bz, x, z))
					{
						return false;
					}
					inFlames = true;
				}
				// Short of its flames but nearer it than anyone stands (it was lit beside them): out of
				// the edge they start beside, too. Held to it, someone alone where a fire was lit had
				// nowhere to walk to, and was drawn in the middle of their tile, in the new fire.
				inFlames |= Math.hypot(Math.max(0, Math.abs(ax - x) - HALF_TILE), Math.max(0, Math.abs(az - z) - HALF_TILE)) < FIRE_MARGIN;
			}
			int margin = CollisionTerrain.EDGE_MARGIN;
			int tile = 2 * HALF_TILE;
			// The fire's tile they stand on, if they do: walking out of it, it's only in the way where a wall runs along it.
			int outOf = -1;
			int si = (int) Math.floor((ax + HALF_TILE) / tile) + size / 2;
			int sj = (int) Math.floor((az + HALF_TILE) / tile) + size / 2;
			if (si >= 0 && sj >= 0 && si < size && sj < size && (fire && si * size + sj == middle || otherFire != null && otherFire[si * size + sj]))
			{
				outOf = si * size + sj;
			}
			double westmost = Math.min(ax, bx);
			double eastmost = Math.max(ax, bx);
			double southmost = Math.min(az, bz);
			double northmost = Math.max(az, bz);
			// Only the tiles the walk could come into: round a crowd's fires the ground is wide.
			int fromI = Math.max(0, (int) Math.floor((westmost + HALF_TILE) / tile) + size / 2);
			int toI = Math.min(size - 1, (int) Math.floor((eastmost + HALF_TILE) / tile) + size / 2);
			int fromJ = Math.max(0, (int) Math.floor((southmost + HALF_TILE) / tile) + size / 2);
			int toJ = Math.min(size - 1, (int) Math.floor((northmost + HALF_TILE) / tile) + size / 2);
			for (int i = fromI; i <= toI; i++)
			{
				for (int j = fromJ; j <= toJ; j++)
				{
					int t = i * size + j;
					double x0 = (i - size / 2) * tile - HALF_TILE;
					double z0 = (j - size / 2) * tile - HALF_TILE;
					if (eastmost <= x0 || westmost >= x0 + tile || northmost <= z0 || southmost >= z0 + tile)
					{
						continue; // the walk never comes into this tile
					}
					if (!open[t] && t != outOf ? crosses(ax, az, bx, bz, x0, z0, x0 + tile, z0 + tile)
						: !steps(t, WEST, outOf) && edge(ax, az, bx, bz, x0, z0, x0 + margin, z0 + tile, inFlames)
						|| !steps(t, EAST, outOf) && edge(ax, az, bx, bz, x0 + tile - 1 - margin, z0, x0 + tile, z0 + tile, inFlames)
						|| !steps(t, SOUTH, outOf) && edge(ax, az, bx, bz, x0, z0, x0 + tile, z0 + margin, inFlames)
						|| !steps(t, NORTH, outOf) && edge(ax, az, bx, bz, x0, z0 + tile - 1 - margin, x0 + tile, z0 + tile, inFlames))
					{
						return false;
					}
				}
			}
			return true;
		}

		/**
		 * Whether a walk passes through the band along an edge that can't be stepped over: never, out of
		 * flames it starts in (or near another fire's), if it starts in the band, as someone a fire was
		 * lit beside may.
		 */
		private static boolean edge(double ax, double az, double bx, double bz, double x0, double z0, double x1, double z1, boolean inFlames)
		{
			return crosses(ax, az, bx, bz, x0, z0, x1, z1) && !(inFlames && inside(ax, az, x0, z0, x1, z1));
		}

		/** Whether a walk from (ax, az) to (bx, bz) starts in the flames of the fire whose tile's middle is (x, z), and goes away from it. */
		private static boolean outOfFlames(double ax, double az, double bx, double bz, double x, double z)
		{
			return inside(ax, az, x - FLAMES, z - FLAMES, x + FLAMES, z + FLAMES) && (bx - ax) * (ax - x) + (bz - az) * (az - z) >= 0;
		}

		/** Whether (x, z) is inside the box from (x0, z0) to (x1, z1). */
		private static boolean inside(double x, double z, double x0, double z0, double x1, double z1)
		{
			return x > x0 && x < x1 && z > z0 && z < z1;
		}

		/**
		 * Whether tile {@code t} can be stepped off that way. Walking out of a fire (from the tile
		 * {@code outOf}, else -1), the edge of a tile beside it towards the fire only counts as a wall
		 * if it is one: that it can't be stepped over otherwise just says the flames are on the other side.
		 */
		private boolean steps(int t, int direction, int outOf)
		{
			if (step[t * 4 + direction] || outOf < 0)
			{
				return step[t * 4 + direction];
			}
			return direction == WEST && t == outOf + size && step[outOf * 4 + EAST]
				|| direction == EAST && t == outOf - size && step[outOf * 4 + WEST]
				|| direction == SOUTH && t == outOf + 1 && outOf % size < size - 1 && step[outOf * 4 + NORTH]
				|| direction == NORTH && t == outOf - 1 && outOf % size > 0 && step[outOf * 4 + SOUTH];
		}

		/** Whether the segment from (ax, az) to (bx, bz) passes through the inside of the box from (x0, z0) to (x1, z1). */
		private static boolean crosses(double ax, double az, double bx, double bz, double x0, double z0, double x1, double z1)
		{
			// The part of the segment (0 to 1 along it) inside the box across, then up.
			double[] inside = {0, 1};
			return within(ax, bx - ax, x0, x1, inside) && within(az, bz - az, z0, z1, inside) && inside[0] < inside[1] - 1e-9;
		}

		/** Narrows {@code inside} to where a + t * d lies strictly between lo and hi; false if nowhere. */
		private static boolean within(double a, double d, double lo, double hi, double[] inside)
		{
			if (Math.abs(d) < 1e-9)
			{
				return a > lo && a < hi;
			}
			double t0 = (lo - a) / d;
			double t1 = (hi - a) / d;
			inside[0] = Math.max(inside[0], Math.min(t0, t1));
			inside[1] = Math.min(inside[1], Math.max(t0, t1));
			return true;
		}
	}

	/** How near each spot round an object is to the people drawn so far, as one object's crowd is laid out. */
	private static final class Nearby
	{
		final Spots spots;
		/** Distance from each spot to the nearest body that isn't you, and to you, squared. */
		final double[] others;
		final double[] you;
		final boolean[] used;
		/** Spots in the way of a cape someone is drawn wearing (see {@link #CAPE_REACH}), and nearer it than {@link #CAPE_KEEP_REACH}. */
		final boolean[] underCape;
		final boolean[] underCapeKept;
		/** Everyone drawn but not at a spot, as {east, north, 1 if one of this crowd or you}. */
		private List<double[]> loose = new ArrayList<>();

		Nearby(Spots spots)
		{
			this.spots = spots;
			others = new double[spots.n];
			you = new double[spots.n];
			used = new boolean[spots.n];
			underCape = new boolean[spots.n];
			underCapeKept = new boolean[spots.n];
			java.util.Arrays.fill(others, Double.MAX_VALUE);
			java.util.Arrays.fill(you, Double.MAX_VALUE);
		}

		Nearby copy()
		{
			Nearby out = new Nearby(spots);
			System.arraycopy(others, 0, out.others, 0, others.length);
			System.arraycopy(you, 0, out.you, 0, you.length);
			System.arraycopy(used, 0, out.used, 0, used.length);
			System.arraycopy(underCape, 0, out.underCape, 0, underCape.length);
			System.arraycopy(underCapeKept, 0, out.underCapeKept, 0, underCapeKept.length);
			out.loose = new ArrayList<>(loose);
			return out;
		}

		/**
		 * One of the crowd drawn at (east, north) from the object's middle without a spot, wearing a
		 * cape looking at {@code cape} (see {@link #lookingAt}), or none (null).
		 */
		void add(double east, double north, boolean isYou, double[] cape)
		{
			addBody(east, north, isYou, true);
			if (cape != null)
			{
				shade(east, north, hangs(east, north, cape), true);
			}
		}

		/**
		 * Someone drawn who isn't one of this crowd, as {east, north, 1 if it's you} from the object's
		 * middle, and if they wear a cape and which way it hangs is known (round another object, or
		 * you), {..., 1, which way it hangs}. Nobody is given a spot in the way of their cape, nor
		 * anyone with a cape a spot with them in the way of it, but they take a spot from nobody: the
		 * crowds on the tiles round about make room and come and go, and walked whoever had one to the
		 * next spot or out of sight. Except you: you stay where you stand, and with Give me a spot off,
		 * whoever stood behind you kept their spot in the way of your cape.
		 */
		void addElsewhere(double[] b)
		{
			boolean you = b[2] > 0;
			addBody(b[0], b[1], you, you);
			if (b.length > 5 && b[3] > 0)
			{
				shade(b[0], b[1], new double[]{b[4], b[5]}, you);
			}
		}

		private void addBody(double east, double north, boolean isYou, boolean ofCrowd)
		{
			double[] near = isYou ? you : others;
			for (int s = 0; s < near.length; s++)
			{
				double x = spots.x[s] - east;
				double z = spots.z[s] - north;
				near[s] = Math.min(near[s], x * x + z * z);
			}
			loose.add(new double[]{east, north, ofCrowd ? 1 : 0});
		}

		/** Marks the spots in the way of a cape at (x, z) hanging towards {@code back}: kept ones too if its wearer is one of this crowd. */
		private void shade(double x, double z, double[] back, boolean ofCrowd)
		{
			for (int s = 0; s < spots.n; s++)
			{
				if (behind(x, z, back, spots.x[s], spots.z[s], CAPE_REACH))
				{
					underCape[s] = true;
					underCapeKept[s] |= ofCrowd && behind(x, z, back, spots.x[s], spots.z[s], CAPE_KEEP_REACH);
				}
			}
		}

		/**
		 * {@link #add} for someone standing at spot {@code at}. Only the spots nearer it than
		 * {@link #SPOT_NEIGHBOUR_REACH} are updated: no gap {@link #clear} asks about is wider, nor
		 * does a cape reach further, so for the rest the answer is the same either way.
		 */
		void addSpot(int at, boolean isYou, double[] cape)
		{
			double[] near = isYou ? you : others;
			double[] back = cape == null ? null : hangs(spots.x[at], spots.z[at], cape);
			for (short s : spots.neighbours(at))
			{
				double x = spots.x[s] - spots.x[at];
				double z = spots.z[s] - spots.z[at];
				near[s] = Math.min(near[s], x * x + z * z);
				if (behind(spots.x[at], spots.z[at], back, spots.x[s], spots.z[s], CAPE_REACH))
				{
					underCape[s] = true;
					underCapeKept[s] |= behind(spots.x[at], spots.z[at], back, spots.x[s], spots.z[s], CAPE_KEEP_REACH);
				}
			}
		}

		/**
		 * Whether anyone is drawn in the way of a cape worn at spot {@code s} by someone looking at
		 * {@code cape}, nearer than {@code reach}: of this crowd only, or anyone.
		 */
		private boolean inTheWay(int s, double[] cape, int reach, boolean crowdOnly)
		{
			double east = spots.x[s] - cape[0];
			double north = spots.z[s] - cape[1];
			double away = Math.sqrt(east * east + north * north);
			if (away < 1)
			{
				return false;
			}
			double backX = east / away;
			double backZ = north / away;
			for (short t : spots.neighbours(s))
			{
				if (used[t] && behind(spots.x[s], spots.z[s], backX, backZ, spots.x[t], spots.z[t], reach))
				{
					return true;
				}
			}
			for (double[] b : loose)
			{
				if ((b[2] > 0 || !crowdOnly) && behind(spots.x[s], spots.z[s], backX, backZ, b[0], b[1], reach))
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether someone may be given this spot: it's free, nobody is drawn closer to it than
		 * {@code gap}, or than {@link #OBJECT_YOUR_GAP} if it's you, it isn't in the way of anyone's
		 * cape, and if they wear one, looking at {@code cape} (else null), nobody is in the way of theirs.
		 */
		boolean clear(int s, int gap, double[] cape)
		{
			return spaced(s, gap) && (cape == ANY_CAPES || !underCape[s] && (cape == null || !inTheWay(s, cape, CAPE_REACH, false)));
		}

		/**
		 * Whether someone already standing at this spot keeps it: as {@link #clear}, with capes judged
		 * by {@link #CAPE_KEEP_REACH}, and only this crowd in the way of theirs.
		 */
		boolean keeps(int s, int gap, double[] cape)
		{
			return spaced(s, gap) && !underCapeKept[s] && (cape == null || !inTheWay(s, cape, CAPE_KEEP_REACH, true));
		}

		private boolean spaced(int s, int gap)
		{
			int yourGap = Math.max(gap, OBJECT_YOUR_GAP);
			return !used[s] && others[s] >= gap * gap && you[s] >= yourGap * yourGap;
		}
	}

	/**
	 * Which spots round an object someone on one tile beside it may be drawn at: reached by a straight
	 * walk from the middle of their tile, on ground that can be stood on.
	 */
	private static final class Reach
	{
		private final boolean[] walk;
		/** Whether each spot can be stood on, asked only when it's needed: 0 not yet, 1 yes, 2 no. Shared by every tile round the object. */
		private final byte[] stand;
		private final IntPredicate standable;

		Reach(boolean[] walk, byte[] stand, IntPredicate standable)
		{
			this.walk = walk;
			this.stand = stand;
			this.standable = standable;
		}

		boolean ok(int s)
		{
			if (s >= walk.length || !walk[s])
			{
				return false;
			}
			if (stand[s] == 0)
			{
				stand[s] = (byte) (standable.test(s) ? 1 : 2);
			}
			return stand[s] == 1;
		}
	}

	/** Who stands where round one object. */
	private static final class AtObject
	{
		final int yourId;
		final long object;
		final boolean fire;
		/** Everyone round it wearing a cape, and which way they face (-1 if that isn't known). */
		final Map<Integer, Integer> capes;
		/** Where each of them looks, worked out once: see {@link #cape}. */
		private final Map<Integer, double[]> looks = new HashMap<>();
		/** Id to spot, in the order they were given. */
		final Map<Integer, Integer> spotOf = new LinkedHashMap<>();
		final Map<Integer, Long> tileOf = new HashMap<>();
		final Map<Long, Integer> onTile = new HashMap<>();
		/** Tiles where someone without a spot is drawn in the middle all the same: you, or someone alone. */
		final Set<Long> middles = new HashSet<>();
		final Spots spots;
		/** Round fires, where the people on each tile look: the middle of the one they're at. */
		private final java.util.function.LongFunction<double[]> fireFocus;
		Nearby near;

		AtObject(int yourId, long object, boolean fire, Map<Integer, Integer> capes, Spots spots, java.util.function.LongFunction<double[]> fireFocus)
		{
			this.yourId = yourId;
			this.object = object;
			this.fire = fire;
			this.capes = capes;
			this.spots = spots;
			this.fireFocus = fireFocus;
			near = new Nearby(spots);
		}

		/**
		 * Where someone on this tile wearing a cape looks (see {@link #lookingAt}), or null if they wear
		 * none: the same array for them all tick, which {@link #fillNearestFirst} keeps its search by.
		 */
		double[] cape(int id, long tile)
		{
			Integer facing = capes.get(id);
			if (facing == null)
			{
				return null;
			}
			return fire ? fireFocus.apply(tile) : looks.computeIfAbsent(id, k -> lookingAt(object, tile, facing));
		}

		void give(int id, long tile, int spot)
		{
			spotOf.put(id, spot);
			tileOf.put(id, tile);
			onTile.merge(tile, 1, Integer::sum);
			near.used[spot] = true;
			near.addSpot(spot, id == yourId, cape(id, tile));
		}

		int on(long tile)
		{
			return onTile.getOrDefault(tile, 0);
		}
	}

	/** The middle of a tile beside an object, from the object's middle. */
	private static double[] middleOf(long tile, long object)
	{
		return new double[]{(StackRegistry.sceneX(tile) - StackRegistry.sceneX(object)) * 2.0 * HALF_TILE,
			(StackRegistry.sceneY(tile) - StackRegistry.sceneY(object)) * 2.0 * HALF_TILE};
	}

	/** The first of these spots that can be reached and is clear by {@code gap} for someone wearing a cape (see {@link Nearby#clear}) or not, or -1. */
	private static int firstClear(int[] order, Reach reach, Nearby near, int gap, double[] cape)
	{
		for (int s : order)
		{
			if (near.clear(s, gap, cape) && reach.ok(s))
			{
				return s;
			}
		}
		return -1;
	}

	/**
	 * Of the spots that can be reached and are clear by {@code gap}, the one nearest {@code from}
	 * ({east, north} from the object's middle: where they stand), nearer the object on a tie, that
	 * someone standing there can walk to in a straight line without passing through the object, a
	 * wall or anything else; or -1. Their walk there is drawn straight from where they stand: the
	 * nearest clear spot was sometimes across the object, and a smith made room for you by walking
	 * through the anvil.
	 */
	private static int nearestClear(double[] from, Reach reach, Nearby near, int gap, double[] cape, Ground ground)
	{
		Spots spots = near.spots;
		List<Integer> clear = new ArrayList<>();
		for (int s = 0; s < spots.n; s++)
		{
			if (spots.open[s] && near.clear(s, gap, cape) && reach.ok(s))
			{
				clear.add(s);
			}
		}
		clear.sort(Comparator.<Integer>comparingDouble(s -> Math.hypot(spots.x[s] - from[0], spots.z[s] - from[1]))
			.thenComparingInt(s -> spots.ring[s]).thenComparingInt(s -> s));
		for (int s : clear)
		{
			if (ground.walkBetween(from, s))
			{
				return s;
			}
		}
		return -1;
	}

	/**
	 * Last tick someone at spot {@code s} (as {@code spots} number them) round {@code object} is still
	 * walking back to the middle of {@code tile}, a tile a tick from now.
	 */
	private int backBy(Spots spots, int s, long object, long tile)
	{
		double east = spots.x[s] + (StackRegistry.sceneX(object) - StackRegistry.sceneX(tile)) * 2.0 * HALF_TILE;
		double north = spots.z[s] + (StackRegistry.sceneY(object) - StackRegistry.sceneY(tile)) * 2.0 * HALF_TILE;
		return objectTick + Math.max(0, (int) Math.ceil(Math.hypot(east, north) / (2 * HALF_TILE)) - 1);
	}

	/**
	 * The spot someone on {@code tile} had round {@code object} last tick, or -1. Joining the crowd
	 * round a fire from a spot of their own on their tile (laid out on its own, before the fire got
	 * busy), the spot round it nearest where they were drawn: the crowd forms round where everyone
	 * already stands. Given the best places afresh, everyone at the fire walked somewhere new the
	 * moment it got busy, some of them straight through it. The same coming over from the crowd round
	 * another fire (the one it was laid out round gone out), and round fires one of which has been
	 * lit or gone out since, standing round one of the others: they went back into the middle of
	 * their tile first, out of sight, and came out somewhere new.
	 */
	private int remembered(int id, long object, long tile)
	{
		Spots spots = spotsOf(object);
		long[] had = objectSpotOf.get(id);
		boolean fromAnotherFire = had != null && had[0] != object && had[1] == tile && fireCrowdsWas.contains(had[0]);
		boolean moved = had != null && had[0] == object && had[1] == tile && had[2] >= SPOT_X.length
			&& !spotsWas.getOrDefault(object, ROUND_ONE).layout.equals(spots.layout);
		if (!fireCrowds.contains(object) || had != null && !fromAnotherFire && !moved)
		{
			return had != null && had[0] == object && had[1] == tile ? (int) had[2] : -1;
		}
		double[] drawn = drawnLastTick(id, tile);
		if (drawn == null)
		{
			return -1;
		}
		double east = drawn[0] + (StackRegistry.sceneX(tile) - StackRegistry.sceneX(object)) * 2.0 * HALF_TILE;
		double north = drawn[1] + (StackRegistry.sceneY(tile) - StackRegistry.sceneY(object)) * 2.0 * HALF_TILE;
		int nearest = -1;
		double best = Double.MAX_VALUE;
		for (int s = 0; s < spots.n; s++)
		{
			if (spots != ROUND_ONE && !spots.clear[s])
			{
				continue;
			}
			double d = Math.hypot(spots.x[s] - east, spots.z[s] - north);
			if (d < best)
			{
				best = d;
				nearest = s;
			}
		}
		return nearest;
	}

	/**
	 * Where someone with spot {@code s} round the object stands, as {east, north} from its middle:
	 * where they were drawn last tick, on their tile, else at the spot. Round fires one of which has
	 * been lit or gone out, their spot is the one nearest where they stand (see {@link #remembered}),
	 * and a walk worked out from there went through the corner of the new fire.
	 */
	private double[] standing(int id, long tile, int s, AtObject at)
	{
		double[] drawn = drawnLastTick(id, tile);
		return drawn == null ? new double[]{at.spots.x[s], at.spots.z[s]}
			: new double[]{drawn[0] + (StackRegistry.sceneX(tile) - StackRegistry.sceneX(at.object)) * 2.0 * HALF_TILE,
				drawn[1] + (StackRegistry.sceneY(tile) - StackRegistry.sceneY(at.object)) * 2.0 * HALF_TILE};
	}

	/** Everyone given a spot last tick, and the same by id, worked out only when it's needed. */
	private List<StackSpreader.Placement> placedBefore = new ArrayList<>();
	private Map<Integer, StackSpreader.Placement> drawnBefore;

	/**
	 * Whether a point (from a fire's middle) is nearer than {@link #FIRE_MARGIN} to any of the other
	 * fires near it ({@link Ground#otherFire}): the crowd round one fire stands as clear of the flames
	 * of the fire beside it as of its own.
	 */
	private static boolean nearOtherFire(double east, double north, boolean[] otherFire)
	{
		int size = (int) Math.round(Math.sqrt(otherFire.length));
		for (int t = 0; t < otherFire.length; t++)
		{
			double x = (t / size - size / 2) * 2.0 * HALF_TILE;
			double z = (t % size - size / 2) * 2.0 * HALF_TILE;
			if (otherFire[t] && Math.hypot(Math.max(0, Math.abs(east - x) - HALF_TILE), Math.max(0, Math.abs(north - z) - HALF_TILE)) < FIRE_MARGIN)
			{
				return true;
			}
		}
		return false;
	}

	/** Where someone on {@code tile} was given a spot last tick, as {east, north} from its middle, or null. */
	private double[] drawnLastTick(int id, long tile)
	{
		if (drawnBefore == null)
		{
			drawnBefore = new HashMap<>();
			for (StackSpreader.Placement p : placedBefore)
			{
				drawnBefore.put(p.id, p);
			}
		}
		StackSpreader.Placement was = drawnBefore.get(id);
		return was == null || was.tile != tile ? null : new double[]{was.dx, was.dz};
	}

	/**
	 * Lays out the crowds gathered round something nobody can stand on, such as an anvil, once
	 * everyone else's spots are known. Each tile round an anvil used to be laid out on its own, with
	 * an equal slice of the ring whether it had two people or twenty: the busy sides piled up rows
	 * deep or squeezed into a heap while the open sides stood empty, and the overflow was sent out
	 * backwards, a tile or two from anyone.
	 *
	 * <p>Instead, as on a campfire, everyone on the tiles round it (and anyone alone beside it, facing
	 * it, once there's a crowd) stands at {@link #OBJECT_SPOTS}: the nearest ring with room within a
	 * tile of their own tile, on whichever side that is, then a ring or two further out on their own
	 * side rather than further round, and round on whichever side has room once theirs is full (see
	 * {@link #OBJECT_COST}). So the crowd fills in all round it before anyone stands far out, each
	 * near their own tile. A spot is only used where it can be stood on and reached by a
	 * straight walk from their tile that passes nothing in the way, as that walk is how they're drawn
	 * getting there. Nobody is drawn closer than {@link #OBJECT_GAP} to anyone else, or
	 * {@link #OBJECT_YOUR_GAP} to you, nor in the way of anyone's cape (see {@link #CAPE_REACH}). You
	 * choose first: the spot nearest the object on your side.
	 * Everyone keeps their spot while it stays clear, whoever loses it steps to the clear spot nearest
	 * it, and anyone left over once the rings are full waits out of sight rather than being drawn out
	 * on their own.
	 *
	 * <p>A busy fire (see {@link #FIRE_CROWD}) is stood round the same way, with these differences.
	 * Nobody stands nearer it than {@link #FIRE_SIDE}, out of its flames, nor walks through them to
	 * their spot. Everyone at it faces it. The people beside it keep to their own side of it, no
	 * further round than {@link #FIRE_SIDE_ARC}, and the people standing on it, who have no side of
	 * their own, take the nearest ring with room wherever it is, out to {@link #FIRE_OUTER}. Whoever
	 * has waited longest on a tile is given its next spot. The crowd forms round where everyone at
	 * the fire already stands, and keeps clear of the middle of every tile two out from it.
	 *
	 * <p>Fires close together (see {@link #FIRES_CLOSE}) are stood round by one crowd, laid out round
	 * the first of them with the same rings round each of the others ({@link Spots}): everyone at any
	 * of them stands round them all, out of all their flames, nearest the fire they're at, and no
	 * further from one beside them than round a fire on its own. On a fire with others beside it,
	 * they may stand further out than {@link #FIRE_OUTER} once the nearer rings are full.
	 *
	 * @param crowds    for each object, the tiles round it laid out here
	 * @param idsByTile the people on each of those tiles who may be moved, in the order they get spots
	 * @param elsewhere tiles laid out some other way, which nobody alone on them joins from
	 * @param placed    everyone given a spot so far; anyone given one here is added
	 */
	private void layOutRoundObjects(Plan plan, Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, List<Long>> crowds,
		Map<Long, List<Integer>> idsByTile, Set<Long> elsewhere, Set<Integer> placed, IntPredicate shown, int capacity,
		boolean includeLocal, int tick, Surroundings around)
	{
		objectSpotHeld.values().removeIf(h -> h[3] < tick);
		steppingBack.values().removeIf(until -> until < tick);
		objectTick = tick;
		Map<Integer, long[]> now = new HashMap<>();
		Map<Long, Ground> groundsNow = new HashMap<>();
		spotsWas = spotsNow;
		spotsNow = new HashMap<>();
		fireFocusNow.clear();
		for (long object : crowds.keySet())
		{
			Spots spots = spotsFor(object);
			spotsNow.put(object, spots);
			if (!spots.layout.equals(spotsWas.getOrDefault(object, spots).layout))
			{
				// A fire lit or gone out beside it: the spots round the others are numbered afresh.
				objectSpotHeld.values().removeIf(h -> h[0] == object && h[2] >= SPOT_X.length);
			}
		}
		if (!crowds.isEmpty())
		{
			int yourId = -1;
			Map<Integer, Integer> capes = new HashMap<>();
			for (List<StackSpreader.Entry> there : byTile.values())
			{
				for (StackSpreader.Entry en : there)
				{
					yourId = en.local ? en.id : yourId;
					if (en.cape)
					{
						capes.put(en.id, en.orientation);
					}
				}
			}
			List<Long> objects = new ArrayList<>(crowds.keySet());
			objects.sort(Comparator.naturalOrder());
			// Someone alone beside it, facing it, is one of the crowd too: they keep a spot clear of the
			// others rather than being drawn in the middle of their tile, inside whoever has stepped
			// round there, and keep it as others come and go, rather than walking back to the middle
			// each time their tile is down to one and out again when someone joins them.
			Set<Long> alone = new HashSet<>();
			for (long object : objects)
			{
				List<Long> tiles = crowds.get(object);
				boolean fire = fireCrowds.contains(object);
				// Round each of its fires, round a crowd's fires.
				for (long at : firesRound(object))
				{
					for (int east = -1; east <= 1; east++)
					{
						for (int north = -1; north <= 1; north++)
						{
							long tile = StackRegistry.key(StackRegistry.plane(at), StackRegistry.sceneX(at) + east, StackRegistry.sceneY(at) + north);
							List<StackSpreader.Entry> there = byTile.get(tile);
							// Someone alone on a busy fire is one of its crowd too: drawn standing in the flames
							// otherwise, the one place nobody else is.
							boolean onTheFire = firesOf(object).contains(tile);
							if (onTheFire && !fire || there == null || there.size() != 1 || idsByTile.containsKey(tile) || elsewhere.contains(tile))
							{
								continue;
							}
							StackSpreader.Entry only = there.get(0);
							if (!shown.test(only.id) || (only.local && !includeLocal) || placed.contains(only.id)
								|| !onTheFire && (objectForAlone(tile, there, crowds) != object || fireUnderfoot(firesNear(tile, around))))
							{
								continue;
							}
							List<Integer> one = new ArrayList<>(1);
							one.add(only.id);
							idsByTile.put(tile, one);
							tiles.add(tile);
							alone.add(tile);
							if (fire)
							{
								currentFireOf.put(tile, onTheFire ? tile : fireFacedFrom(tile, there, object));
								roundFires.add(tile);
							}
							else
							{
								plan.curvedRows.add(tile);
							}
						}
					}
				}
				tiles.sort(Comparator.naturalOrder());
			}
			// A fire's crowd with nobody left to lay out (see plan).
			objects.removeIf(object -> crowds.get(object).isEmpty());
			// Everyone else drawn, as {plane, east, north, 1 if it's you}: at their spots, and one in the
			// middle of any tile where someone without a spot is drawn. Fires are laid out after, clear of these.
			// You in a cape, too: which way it hangs (see drawnBody).
			List<double[]> bodies = new ArrayList<>();
			Integer yourCape = capes.get(yourId);
			for (StackSpreader.Placement p : plan.placements)
			{
				bodies.add(drawnBody(plan, p.tile, p.dx, p.dz, p.id == yourId, p.id == yourId ? yourCape : null, null));
			}
			for (Map.Entry<Long, List<StackSpreader.Entry>> e : byTile.entrySet())
			{
				long tile = e.getKey();
				if (elsewhere.contains(tile))
				{
					continue;
				}
				List<Integer> layingOut = idsByTile.get(tile);
				boolean drawn = false;
				boolean you = false;
				for (StackSpreader.Entry en : e.getValue())
				{
					if (!placed.contains(en.id) && (layingOut == null || !layingOut.contains(en.id))
						&& (en.local || shown.test(en.id) && (e.getValue().size() == 1 || !plan.middleOutOfSight.contains(tile))))
					{
						drawn = true;
						you |= en.local;
					}
				}
				if (drawn)
				{
					bodies.add(drawnBody(plan, tile, 0, 0, you, you ? yourCape : null, you && yourCape != null ? fireFacedAt(tile, crowds) : null));
				}
			}
			// The crowd you're in first, so the crowds round the rest keep clear of you as well: beside a
			// furnace, which is one crowd for each of its tiles, someone stood right up against you.
			int you = yourId;
			List<Long> order = new ArrayList<>(objects);
			order.sort(Comparator.comparingInt((Long object) -> yoursFirst(crowds.get(object), idsByTile, you)).thenComparing(Comparator.naturalOrder()));
			// Where the people round each object stood last tick, while they're still there: each crowd
			// keeps clear of those round the objects not laid out yet as well, so a newcomer at one
			// anvil doesn't take the place of someone standing there from the anvil next to it.
			Map<Long, List<double[]>> standing = new HashMap<>();
			for (long object : order)
			{
				List<double[]> there = new ArrayList<>();
				for (long tile : crowds.get(object))
				{
					for (int id : idsByTile.get(tile))
					{
						int s = remembered(id, object, tile);
						if (s >= 0)
						{
							Integer facing = capes.get(id);
							there.add(atSpot(spotsOf(object), object, s, id == yourId, facing == null ? null : focusOf(object, tile, facing)));
						}
					}
				}
				standing.put(object, there);
			}
			for (int k = 0; k < order.size(); k++)
			{
				List<double[]> yetToCome = new ArrayList<>();
				for (int later = k + 1; later < order.size(); later++)
				{
					yetToCome.addAll(standing.get(order.get(later)));
				}
				layOutRoundObject(plan, order.get(k), crowds.get(order.get(k)), idsByTile, alone, byTile, bodies, yetToCome, placed, shown,
					capacity, around, yourId, capes, now, groundsNow);
			}
		}
		// Whoever lost their spot round an object (waiting, gone, or the crowd broken up) has it kept for them a while.
		for (Map.Entry<Integer, long[]> e : objectSpotOf.entrySet())
		{
			long[] had = e.getValue();
			// Not one round another fire, if a fire was lit or went out beside it this tick: those are
			// numbered afresh (see Spots), and kept by number it was a different spot.
			if (!now.containsKey(e.getKey()) && (had[2] < SPOT_X.length || spotsWas.getOrDefault(had[0], ROUND_ONE).layout.equals(spotsOf(had[0]).layout)))
			{
				objectSpotHeld.put(e.getKey(), new long[]{had[0], had[1], had[2], tick + SlotBook.HOLD_TICKS});
			}
		}
		objectSpotOf = now;
		waitingSince = waitingSinceNow;
		waitingSinceNow = new HashMap<>();
		// The ground round objects nobody stands round this tick is kept a while too, so a crowd that
		// breaks up and gathers again a moment later doesn't work out every walk round it afresh.
		for (Map.Entry<Long, Ground> e : grounds.entrySet())
		{
			if (groundsNow.size() >= GROUNDS_KEPT)
			{
				break;
			}
			groundsNow.putIfAbsent(e.getKey(), e.getValue());
		}
		grounds = groundsNow;
	}

	/**
	 * Someone standing at spot {@code s} round {@code object}, as {plane, east, north, 1 if it's you}
	 * in the scene, and with a cape, looking at {@code cape} (see {@link #lookingAt}), {..., 1, and
	 * which way it hangs, east and north}.
	 */
	private static double[] atSpot(Spots spots, long object, int s, boolean you, double[] cape)
	{
		double x = StackRegistry.sceneX(object) * 2.0 * HALF_TILE + HALF_TILE + spots.x[s];
		double z = StackRegistry.sceneY(object) * 2.0 * HALF_TILE + HALF_TILE + spots.z[s];
		double[] back = cape == null ? null : hangs(spots.x[s], spots.z[s], cape);
		return back != null ? new double[]{StackRegistry.plane(object), x, z, you ? 1 : 0, 1, back[0], back[1]}
			: new double[]{StackRegistry.plane(object), x, z, you ? 1 : 0};
	}

	/**
	 * Someone drawn at (dx, dz) from the middle of their tile, as {plane, east, north, 1 if it's you}
	 * in the scene, and if they wear a cape and face this way ({@code facing}, else null), {..., 1, and
	 * which way it hangs, east and north}: behind them as the plugin turns them (see
	 * {@link StackRegistry#drawOrientation}), towards {@code fire} (from their tile's middle, else null)
	 * if they're round one, else as this plan has laid out their tile so far.
	 */
	private static double[] drawnBody(Plan plan, long tile, int dx, int dz, boolean you, Integer facing, int[] fire)
	{
		double x = StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE + dx;
		double z = StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE + dz;
		if (facing == null || facing < 0)
		{
			return new double[]{StackRegistry.plane(tile), x, z, you ? 1 : 0};
		}
		int[] towards = fire != null ? fire : plan.fires.get(tile);
		int turned;
		if (towards != null)
		{
			turned = StackSpreader.faceTowards(facing, dx, dz, towards[0], towards[1]);
		}
		else if (plan.curvedRows.contains(tile) || plan.facingIn.contains(tile))
		{
			turned = StackSpreader.faceSameSpot(facing, dx, dz);
		}
		else
		{
			PersonalSpaceConfig.Pose pose = plan.poses.get(tile);
			int inwards = StackSpreader.faceTowards(facing, dx, dz, 0, 0);
			turned = pose == PersonalSpaceConfig.Pose.FACING ? inwards : pose == PersonalSpaceConfig.Pose.ANGLED ? StackSpreader.halfway(facing, inwards) : facing;
		}
		// Facing (-sin, -cos): the cape hangs the other way.
		double a = StackSpreader.toRadians(turned);
		return new double[]{StackRegistry.plane(tile), x, z, you ? 1 : 0, 1, Math.sin(a), Math.cos(a)};
	}

	/** Where the fire is, from this tile's middle, if its people are in the crowd round it this tick, else null. */
	private int[] fireFacedAt(long tile, Map<Long, List<Long>> crowds)
	{
		for (Map.Entry<Long, List<Long>> c : crowds.entrySet())
		{
			if (fireCrowds.contains(c.getKey()) && c.getValue().contains(tile))
			{
				long fire = currentFireOf.getOrDefault(tile, c.getKey());
				return new int[]{(StackRegistry.sceneX(fire) - StackRegistry.sceneX(tile)) * 2 * HALF_TILE, (StackRegistry.sceneY(fire) - StackRegistry.sceneY(tile)) * 2 * HALF_TILE};
			}
		}
		return null;
	}

	/** Where someone on this tile round this object, facing this way, looks: at the fire, round one, else see {@link #lookingAt}. */
	private double[] focusOf(long object, long tile, int facing)
	{
		return fireCrowds.contains(object) ? fireFocus(object, tile) : lookingAt(object, tile, facing);
	}

	/**
	 * Where someone on this tile round an object, facing this way (-1 if that isn't known), looks,
	 * from the object's middle. Wherever they're drawn, the plugin turns them to face the spot
	 * {@link StackSpreader#LOOK_AHEAD} in front of their tile the way they face (see
	 * {@link StackSpreader#faceSameSpot}), and their cape hangs straight back from it. Taken as the
	 * object's middle, or a corner's {@link #OBJECT_FOCUS}, the cape of anyone turned off it (towards
	 * the other of two anvils either side of their tile, a furnace's middle, the door) hung where
	 * nobody was kept out of.
	 */
	private static double[] lookingAt(long object, long tile, int facing)
	{
		if (facing < 0)
		{
			return OBJECT_FOCUS[besideIndex(tile, object)];
		}
		double[] m = middleOf(tile, object);
		double a = StackSpreader.toRadians(facing);
		return new double[]{m[0] - Math.sin(a) * StackSpreader.LOOK_AHEAD, m[1] - Math.cos(a) * StackSpreader.LOOK_AHEAD};
	}

	/** 0 if you're one of the people on these tiles laid out round an object, else 1. */
	private static int yoursFirst(List<Long> tiles, Map<Long, List<Integer>> idsByTile, int yourId)
	{
		for (long tile : tiles)
		{
			if (idsByTile.get(tile).contains(yourId))
			{
				return 0;
			}
		}
		return 1;
	}

	/**
	 * {@link #layOutRoundObjects} for one object: who stands where goes in {@code now}, and they're
	 * added to {@code bodies}. They keep clear of {@code bodies} and of {@code yetToCome}, where
	 * people round the objects laid out after this one stood last tick.
	 */
	private void layOutRoundObject(Plan plan, long object, List<Long> tiles, Map<Long, List<Integer>> idsByTile, Set<Long> alone,
		Map<Long, List<StackSpreader.Entry>> byTile, List<double[]> bodies, List<double[]> yetToCome, Set<Integer> placed, IntPredicate shown,
		int capacity, Surroundings around, int yourId, Map<Integer, Integer> capes, Map<Integer, long[]> now, Map<Long, Ground> groundsNow)
	{
		int plane = StackRegistry.plane(object);
		int objectX = StackRegistry.sceneX(object);
		int objectY = StackRegistry.sceneY(object);
		int middleX = objectX * 2 * HALF_TILE + HALF_TILE;
		int middleZ = objectY * 2 * HALF_TILE + HALF_TILE;
		boolean fire = fireCrowds.contains(object);
		Spots spots = spotsOf(object);
		// Round a fire, any other fire near it, on the five by five tiles centred on it: round a
		// crowd's fires, on the tiles out to the furthest anyone at them may stand.
		int size = fire ? 2 * (int) ((spots.reach + HALF_TILE) / (2 * HALF_TILE)) + 1 : Ground.SIZE;
		boolean[] otherFire = fire ? new boolean[size * size] : null;
		for (int east = -(size / 2 - 1); fire && east <= size / 2 - 1; east++)
		{
			for (int north = -(size / 2 - 1); north <= size / 2 - 1; north++)
			{
				for (int[] f : firesNear(StackRegistry.key(plane, objectX + east, objectY + north), around))
				{
					int i = east + f[0] + size / 2;
					int j = north + f[1] + size / 2;
					otherFire[i * size + j] |= i != size / 2 || j != size / 2;
				}
			}
		}
		Ground ground = new Ground(object, around, fire, otherFire, spots);
		Ground was = grounds.get(object);
		if (ground.sameAs(was))
		{
			ground = was;
		}
		groundsNow.put(object, ground);
		int n = spots.n;
		// Where the walk there is clear, the spot itself as the plugin judges it (which also keeps
		// clear of fires): asked only of the spots someone is looking at. Round a fire, never nearer
		// it than FIRE_SIDE, out of its flames.
		byte[] stand = new byte[n];
		IntPredicate standable = s ->
		{
			if (fire && (!spots.clear[s] || nearOtherFire(spots.x[s], spots.z[s], otherFire)))
			{
				return false;
			}
			int x = middleX + spots.x[s];
			int z = middleZ + spots.z[s];
			int tx = Math.floorDiv(x, 2 * HALF_TILE);
			int tz = Math.floorDiv(z, 2 * HALF_TILE);
			return around.canStand(StackRegistry.key(plane, tx, tz), x - tx * 2 * HALF_TILE - HALF_TILE, z - tz * 2 * HALF_TILE - HALF_TILE);
		};
		Map<Long, Reach> reach = new HashMap<>();
		for (long tile : tiles)
		{
			reach.put(tile, new Reach(ground.walksFrom(StackRegistry.sceneX(tile) - objectX, StackRegistry.sceneY(tile) - objectY), stand, standable));
		}
		List<double[]> taken = new ArrayList<>();
		List<double[]> near = new ArrayList<>(bodies);
		near.addAll(yetToCome);
		for (double[] b : near)
		{
			if (b[0] == plane && Math.hypot(b[1] - middleX, b[2] - middleZ) < spots.reach + OBJECT_YOUR_GAP)
			{
				// With a cape (round another object): which way it hangs, too.
				taken.add(b.length > 4 ? new double[]{b[1] - middleX, b[2] - middleZ, b[3], b[4], b[5], b[6]}
					: new double[]{b[1] - middleX, b[2] - middleZ, b[3]});
			}
		}
		if (fire)
		{
			// Round a fire, the middle of every other tile near it where anyone stands, and of every
			// tile two out from it, where anyone may: whoever stands alone there is drawn in its
			// middle, and the people at the fire standing there vanished every time one came.
			Set<Long> middles = new HashSet<>();
			for (long other : byTile.keySet())
			{
				if (StackRegistry.plane(other) == plane && other != object && !tiles.contains(other))
				{
					middles.add(other);
				}
			}
			// Round a crowd's fires, two out from any of them that isn't beside another.
			for (long at : firesOf(object))
			{
				for (int east = -2; east <= 2; east++)
				{
					for (int north = -2; north <= 2; north++)
					{
						long other = StackRegistry.key(plane, StackRegistry.sceneX(at) + east, StackRegistry.sceneY(at) + north);
						if ((Math.abs(east) == 2 || Math.abs(north) == 2) && !tiles.contains(other) && !besideOneOf(other, firesRound(object)) && around.canStand(other, 0, 0))
						{
							middles.add(other);
						}
					}
				}
			}
			for (long other : middles)
			{
				double x = (StackRegistry.sceneX(other) - objectX) * 2.0 * HALF_TILE;
				double z = (StackRegistry.sceneY(other) - objectY) * 2.0 * HALF_TILE;
				if (Math.hypot(x, z) < spots.reach + OBJECT_GAP)
				{
					taken.add(new double[]{x, z, 0});
				}
			}
		}
		// Unlike a fire's few spots, these are close enough together that keeping everyone where they
		// are wastes little room: laying the crowd out afresh to fit in someone waiting walked thirty
		// people somewhere new for one more spot, and soon left more waiting, not fewer.
		AtObject at = objectSpots(object, tiles, idsByTile, alone, reach, ground, taken, capacity, yourId, capes);
		for (Map.Entry<Integer, Integer> g : at.spotOf.entrySet())
		{
			int id = g.getKey();
			int s = g.getValue();
			long tile = at.tileOf.get(id);
			plan.placements.add(new StackSpreader.Placement(id, tile, spots.x[s] + (objectX - StackRegistry.sceneX(tile)) * 2 * HALF_TILE,
				spots.z[s] + (objectY - StackRegistry.sceneY(tile)) * 2 * HALF_TILE));
			placed.add(id);
			long[] had = objectSpotOf.get(id);
			objectMoves += had != null && had[0] == object && had[1] == tile && had[2] != s ? 1 : 0;
			now.put(id, new long[]{object, tile, s});
			objectSpotHeld.remove(id);
			bodies.add(atSpot(spots, object, s, id == yourId, at.cape(id, tile)));
		}
		for (long tile : tiles)
		{
			boolean youWait = false;
			boolean someoneWaits = false;
			for (int id : idsByTile.get(tile))
			{
				if (!at.spotOf.containsKey(id))
				{
					someoneWaits = true;
					youWait |= id == yourId;
					if (fire)
					{
						waitingSinceNow.put(id, waitingSince.getOrDefault(id, objectTick));
					}
				}
			}
			if (at.middles.contains(tile))
			{
				// Drawn in the middle of their tile all the same (you, or someone alone): the crowds
				// round the objects after this one keep clear of them, and of the way a cape of theirs hangs.
				double[] m = middleOf(tile, object);
				double[] focus = at.cape(youWait ? yourId : idsByTile.get(tile).get(0), tile);
				double[] back = focus == null ? null : hangs(m[0], m[1], focus);
				double x = StackRegistry.sceneX(tile) * 2.0 * HALF_TILE + HALF_TILE;
				double z = StackRegistry.sceneY(tile) * 2.0 * HALF_TILE + HALF_TILE;
				bodies.add(back == null ? new double[]{plane, x, z, youWait ? 1 : 0} : new double[]{plane, x, z, youWait ? 1 : 0, 1, back[0], back[1]});
			}
			else if (someoneWaits && !alone.contains(tile))
			{
				// Whoever is still left without a spot waits out of sight, rather than drawn in the
				// middle of their tile inside whoever is standing there now.
				plan.middleOutOfSight.add(tile);
			}
			int seen = 0;
			for (StackSpreader.Entry en : byTile.get(tile))
			{
				seen += shown.test(en.id) ? 1 : 0;
			}
			Reach r = reach.get(tile);
			int mine = at.on(tile);
			// Room for more from this tile right now, packed nearest first.
			Nearby room = at.near.copy();
			int more = 0;
			for (int s : spots.rankedFor(StackRegistry.sceneX(tile) - objectX, StackRegistry.sceneY(tile) - objectY).order)
			{
				if (mine + more >= capacity)
				{
					break;
				}
				if (room.clear(s, OBJECT_GAP, null) && r.ok(s))
				{
					more++;
					room.used[s] = true;
					room.addSpot(s, false, null);
				}
			}
			int blocked;
			if (fire)
			{
				// Round a fire, the places nearest it on this tile's side, within a tile of it, that it
				// can't use, past a wall or something else in the way, in bodies' widths. (Not the
				// flames, nor the other sides: nobody from here would stand there anyway.)
				double[] m = middleOf(tile, object);
				double width = 0;
				for (int s = 0; s < n; s++)
				{
					if (spots.ring[s] <= 1 && spots.clear[s] && Math.hypot(spots.x[s] - m[0], spots.z[s] - m[1]) <= 2 * HALF_TILE
						&& (firesOf(object).contains(tile) || Math.abs(signedAngle(m[0], m[1], spots.x[s], spots.z[s])) <= FIRE_SIDE_ARC + 1e-9) && !r.ok(s))
					{
						width += 2 * Math.PI * (OBJECT_NEAR_RING + spots.ring[s] * OBJECT_RING_STEP) / RING_SPOTS[Math.min(spots.ring[s], RING_SPOTS.length - 1)];
					}
				}
				blocked = (int) Math.round(width / OBJECT_GAP);
				// Everyone at the fire faces it: the one they're at, round a crowd's fires.
				long faced = currentFireOf.getOrDefault(tile, object);
				plan.fires.put(tile, new int[]{(StackRegistry.sceneX(faced) - StackRegistry.sceneX(tile)) * 2 * HALF_TILE, (StackRegistry.sceneY(faced) - StackRegistry.sceneY(tile)) * 2 * HALF_TILE});
			}
			else
			{
				// The front-row places round the object this tile can't use: behind it, or past a wall.
				int outOfReach = 0;
				for (int s = 0; s < n; s++)
				{
					outOfReach += SPOT_RING[s] == 0 && !r.ok(s) ? 1 : 0;
				}
				blocked = (int) Math.round(outOfReach * 2 * Math.PI * OBJECT_NEAR_RING / NEAR_RING_SPOTS / OBJECT_GAP);
			}
			plan.tiles.put(tile, new TileReport(fire ? ROUND_FIRE : ROUND_OBJECT, OBJECT_GAP, seen, mine, mine + more, blocked));
		}
	}

	/**
	 * Spots round one object for the people on the tiles round it, clear of everyone in {@code taken}
	 * ({east, north, 1 if it's you} from the object's middle). Everyone keeps the spot they had while
	 * it stays clear, and goes back to one kept for them if it's clear. Nobody is given a spot in the
	 * way of anyone's cape, nor anyone with a cape a spot with someone in the way of it (see
	 * {@link #CAPE_REACH}).
	 */
	private AtObject objectSpots(long object, List<Long> tiles, Map<Long, List<Integer>> idsByTile, Set<Long> alone,
		Map<Long, Reach> reach, Ground ground, List<double[]> taken, int capacity, int yourId, Map<Integer, Integer> capes)
	{
		AtObject at = new AtObject(yourId, object, fireCrowds.contains(object), capes, spotsOf(object), tile -> fireFocus(object, tile));
		for (double[] b : taken)
		{
			at.near.addElsewhere(b);
		}
		// You first: the spot nearest the object on your side, or the one you have while no ring
		// nearer it has room (that you can walk to straight from where you stand).
		for (long tile : tiles)
		{
			if (!idsByTile.get(tile).contains(yourId))
			{
				continue;
			}
			Reach r = reach.get(tile);
			int[] order = at.spots.rankedFor(StackRegistry.sceneX(tile) - StackRegistry.sceneX(object), StackRegistry.sceneY(tile) - StackRegistry.sceneY(object)).order;
			double[] yourCape = at.cape(yourId, tile);
			int best = firstClear(order, r, at.near, OBJECT_GAP, yourCape);
			int had = remembered(yourId, object, tile);
			int pick;
			if (steppingBack.containsKey(yourId))
			{
				pick = -1;
			}
			else if (had < 0)
			{
				pick = best >= 0 ? best : firstClear(order, r, at.near, OBJECT_KEEP, yourCape);
			}
			else
			{
				boolean keep = r.ok(had) && at.near.keeps(had, OBJECT_KEEP, yourCape);
				if (best >= 0 && (!keep || at.spots.ring[best] < at.spots.ring[had]) && ground.walkBetween(standing(yourId, tile, had, at), best))
				{
					pick = best;
				}
				else if (keep)
				{
					pick = had;
				}
				else
				{
					double[] from = standing(yourId, tile, had, at);
					pick = nearestClear(from, r, at.near, OBJECT_GAP, yourCape, ground);
					pick = pick >= 0 ? pick : nearestClear(from, r, at.near, OBJECT_KEEP, yourCape, ground);
					// You stay round it all the same, in the way of a cape or with someone in the way of yours.
					pick = pick >= 0 ? pick : nearestClear(from, r, at.near, OBJECT_KEEP, ANY_CAPES, ground);
				}
			}
			if (pick < 0 && had < 0 && !steppingBack.containsKey(yourId))
			{
				pick = firstClear(order, r, at.near, OBJECT_KEEP, ANY_CAPES);
			}
			if (pick >= 0)
			{
				at.give(yourId, tile, pick);
			}
			else
			{
				if (had >= 0)
				{
					steppingBack.put(yourId, backBy(at.spots, had, object, tile));
				}
				double[] m = middleOf(tile, object);
				at.middles.add(tile);
				at.near.add(m[0], m[1], true, yourCape);
			}
		}
		// Everyone whose spot is still clear keeps it.
		Map<Long, List<Integer>> waiting = new LinkedHashMap<>();
		Map<Integer, Integer> lost = new LinkedHashMap<>();
		Map<Integer, Long> tileOf = new HashMap<>();
		Set<Integer> switched = new HashSet<>();
		for (long tile : tiles)
		{
			List<Integer> left = new ArrayList<>();
			Reach r = reach.get(tile);
			for (int id : idsByTile.get(tile))
			{
				if (id == yourId)
				{
					continue;
				}
				tileOf.put(id, tile);
				int had = remembered(id, object, tile);
				if (had >= 0 && at.on(tile) < capacity && r.ok(had) && at.near.keeps(had, OBJECT_KEEP, at.cape(id, tile)))
				{
					at.give(id, tile, had);
					continue;
				}
				long[] before = objectSpotOf.get(id);
				if (had < 0 && before != null && before[1] == tile && before[0] != object)
				{
					// Standing round another object a moment ago (their tile has turned to this one): back
					// to the middle of their tile first, rather than straight across from there.
					// (Numbered as the spots round it last tick: a fire may have been lit or gone out beside it since.)
					steppingBack.put(id, backBy(spotsWas.getOrDefault(before[0], ROUND_ONE), (int) before[2], before[0], tile));
					switched.add(id);
				}
				if (steppingBack.containsKey(id))
				{
					switched.add(id);
					if (!alone.contains(tile))
					{
						continue;
					}
				}
				if (had >= 0)
				{
					lost.put(id, had);
				}
				left.add(id);
			}
			if (fireCrowds.contains(object))
			{
				left.sort(Comparator.comparingInt(id -> waitingSince.getOrDefault(id, objectTick)));
			}
			waiting.put(tile, left);
		}
		// Someone alone beside the object comes next: drawn where they stand if there's no spot for
		// them, so whoever would be drawn inside them there steps aside.
		for (long tile : tiles)
		{
			List<Integer> left = waiting.get(tile);
			if (!alone.contains(tile) || left.isEmpty())
			{
				continue;
			}
			int id = left.get(0);
			Reach r = reach.get(tile);
			int[] order = at.spots.rankedFor(StackRegistry.sceneX(tile) - StackRegistry.sceneX(object), StackRegistry.sceneY(tile) - StackRegistry.sceneY(object)).cheapest;
			Integer had = lost.get(id);
			double[] cape = at.cape(id, tile);
			double[] from = had == null ? null : standing(id, tile, had, at);
			int pick = switched.contains(id) ? -1
				: had != null ? nearestClear(from, r, at.near, OBJECT_GAP, cape, ground) : firstClear(order, r, at.near, OBJECT_GAP, cape);
			pick = pick >= 0 || switched.contains(id) ? pick
				: had != null ? nearestClear(from, r, at.near, OBJECT_KEEP, cape, ground) : firstClear(order, r, at.near, OBJECT_KEEP, cape);
			// With nowhere their cape is clear of everyone, somewhere it isn't, and with nowhere clear of
			// everyone's cape, somewhere in the way of one: drawn in the middle of their tile instead,
			// they stood inside whoever was there, or right up against you.
			pick = pick >= 0 || switched.contains(id) || cape == null ? pick
				: had != null ? nearestClear(from, r, at.near, OBJECT_KEEP, null, ground) : firstClear(order, r, at.near, OBJECT_KEEP, null);
			pick = pick >= 0 || switched.contains(id) ? pick
				: had != null ? nearestClear(from, r, at.near, OBJECT_KEEP, ANY_CAPES, ground) : firstClear(order, r, at.near, OBJECT_KEEP, ANY_CAPES);
			if (pick >= 0)
			{
				at.give(id, tile, pick);
				left.clear();
				continue;
			}
			if (had != null)
			{
				steppingBack.put(id, backBy(at.spots, had, object, tile));
			}
			double[] m = middleOf(tile, object);
			at.middles.add(tile);
			left.clear();
			List<Integer> inside = new ArrayList<>();
			for (Map.Entry<Integer, Integer> g : at.spotOf.entrySet())
			{
				int spot = g.getValue();
				double[] theirs = at.cape(g.getKey(), at.tileOf.get(g.getKey()));
				// ...and whoever would have them in the way of their cape there, or stands in the way of theirs.
				if (g.getKey() != yourId && !alone.contains(at.tileOf.get(g.getKey()))
					&& (Math.hypot(at.spots.x[spot] - m[0], at.spots.z[spot] - m[1]) < OBJECT_KEEP
					|| theirs != null && behind(at.spots.x[spot], at.spots.z[spot], hangs(at.spots.x[spot], at.spots.z[spot], theirs), m[0], m[1], CAPE_KEEP_REACH)
					|| cape != null && behind(m[0], m[1], hangs(m[0], m[1], cape), at.spots.x[spot], at.spots.z[spot], CAPE_KEEP_REACH)))
				{
					inside.add(g.getKey());
				}
			}
			if (!inside.isEmpty())
			{
				for (int other : inside)
				{
					long theirs = at.tileOf.get(other);
					lost.put(other, at.spotOf.get(other));
					at.spotOf.remove(other);
					at.tileOf.remove(other);
					at.onTile.merge(theirs, -1, Integer::sum);
					waiting.get(theirs).add(other);
				}
				Nearby again = new Nearby(at.spots);
				for (double[] b : taken)
				{
					again.addElsewhere(b);
				}
				for (Map.Entry<Integer, Integer> g : at.spotOf.entrySet())
				{
					again.used[g.getValue()] = true;
					again.addSpot(g.getValue(), g.getKey() == yourId, at.cape(g.getKey(), at.tileOf.get(g.getKey())));
				}
				for (long middle : at.middles)
				{
					// You, waiting in the middle of your tile, or the one alone on it.
					double[] mm = middleOf(middle, object);
					boolean yours = idsByTile.get(middle).contains(yourId);
					again.add(mm[0], mm[1], yours, at.cape(yours ? yourId : idsByTile.get(middle).get(0), middle));
				}
				at.near = again;
			}
			else
			{
				at.near.add(m[0], m[1], false, cape);
			}
		}
		// Whoever had to leave their spot steps to the clear one nearest it that they can walk to
		// straight from where they stand. With none, they step back into the middle of their tile the
		// way they came, out of sight, rather than through the anvil to a spot across it.
		for (Map.Entry<Integer, Integer> l : lost.entrySet())
		{
			int id = l.getKey();
			long tile = tileOf.get(id);
			if (alone.contains(tile) || at.spotOf.containsKey(id))
			{
				continue;
			}
			int pick = at.on(tile) < capacity ? nearestClear(standing(id, tile, l.getValue(), at), reach.get(tile), at.near, OBJECT_GAP, at.cape(id, tile), ground) : -1;
			waiting.get(tile).remove((Integer) id);
			if (pick >= 0)
			{
				at.give(id, tile, pick);
			}
			else
			{
				steppingBack.put(id, backBy(at.spots, l.getValue(), object, tile));
			}
		}
		// Whoever comes back goes back to the spot kept for them, if it's clear.
		for (long tile : tiles)
		{
			for (java.util.Iterator<Integer> it = waiting.get(tile).iterator(); it.hasNext(); )
			{
				int id = it.next();
				long[] held = objectSpotHeld.get(id);
				if (held != null && held[0] == object && held[1] == tile && at.on(tile) < capacity
					&& reach.get(tile).ok((int) held[2]) && at.spots.open[(int) held[2]] && at.near.clear((int) held[2], OBJECT_GAP, at.cape(id, tile)))
				{
					at.give(id, tile, (int) held[2]);
					it.remove();
				}
			}
		}
		// Everyone else, near the object and their own tile first and round it once their side is full, then a little closer together.
		fillNearestFirst(at, object, tiles, waiting, reach, OBJECT_GAP, capacity);
		fillNearestFirst(at, object, tiles, waiting, reach, OBJECT_KEEP, capacity);
		return at;
	}

	/**
	 * How much more a spot round an object costs someone (see {@link #OBJECT_COST}) for each other
	 * tile round it whose people could stand there too: about as much as a ring further out, or a
	 * tile's walk further round within a tile of their own. The people on the wall side of an anvil
	 * can only stand on the wall side, and those on the side next to them took it, when the far side
	 * of them was theirs alone.
	 */
	static final int SHARED_SPOT_COST = HALF_TILE / 2;

	/**
	 * Gives the people waiting on each tile spots clear by {@code gap}, one at a time: of each tile's
	 * cheapest spot ({@link #OBJECT_COST}, plus {@link #SHARED_SPOT_COST} for each other tile round
	 * the object that could stand there too), the cheapest of all goes first. So each side's own
	 * places near the object go to its own people before anyone reaches round for them, and nobody
	 * walks right round the object while there's room a ring or two further out on their own side.
	 * Only those waiting are placed: nobody already standing round the object is moved for it.
	 */
	private static void fillNearestFirst(AtObject at, long object, List<Long> tiles, Map<Long, List<Integer>> waiting,
		Map<Long, Reach> reach, int gap, int capacity)
	{
		// Spots only ever stop being clear as people are placed: each tile's cheapest is still its
		// cheapest while it stays clear, and each tile's search carries on from where it got to. Kept
		// apart for someone waiting without a cape and for each place someone with one looks (see
		// AtObject#cape): fewer spots are clear with one, and which depends on which way it hangs.
		Map<double[], Map<Long, double[]>> cheapest = new java.util.IdentityHashMap<>();
		Map<double[], Map<Long, Integer>> next = new java.util.IdentityHashMap<>();
		while (true)
		{
			long bestTile = NO_OBJECT;
			double[] best = null;
			int bestWho = 0;
			for (long tile : tiles)
			{
				List<Integer> queue = waiting.get(tile);
				if (queue.isEmpty() || at.on(tile) >= capacity)
				{
					continue;
				}
				// The next one waiting there, or with nowhere clear for them (their cape in the way of
				// someone, or someone's cape in theirs), the next one with a cape if they had none or
				// without one if they had: one stuck waiting doesn't keep everyone behind them waiting too.
				double[] cape = at.cape(queue.get(0), tile);
				int who = 0;
				double[] mine = cheapestFor(at, object, tile, tiles, reach, gap, cape, cheapest, next);
				for (int i = 1; mine[0] < 0 && i < queue.size(); i++)
				{
					double[] theirs = at.cape(queue.get(i), tile);
					if ((theirs == null) != (cape == null))
					{
						who = i;
						mine = cheapestFor(at, object, tile, tiles, reach, gap, theirs, cheapest, next);
						break;
					}
				}
				if (mine[0] >= 0 && (best == null || mine[1] < best[1]))
				{
					bestTile = tile;
					best = mine;
					bestWho = who;
				}
			}
			if (best == null)
			{
				return;
			}
			at.give(waiting.get(bestTile).remove(bestWho), bestTile, (int) best[0]);
		}
	}

	/** {@link #cheapestFor}, kept for each tile while it stays clear (see {@link #fillNearestFirst}). */
	private static double[] cheapestFor(AtObject at, long object, long tile, List<Long> tiles, Map<Long, Reach> reach, int gap, double[] cape,
		Map<double[], Map<Long, double[]>> cheapest, Map<double[], Map<Long, Integer>> next)
	{
		Map<Long, double[]> kept = cheapest.computeIfAbsent(cape, k -> new HashMap<>());
		double[] mine = kept.get(tile);
		if (mine == null || mine[0] >= 0 && !at.near.clear((int) mine[0], gap, cape))
		{
			mine = cheapestFor(at, object, tile, tiles, reach, gap, cape, next.computeIfAbsent(cape, k -> new HashMap<>()));
			kept.put(tile, mine);
		}
		return mine;
	}

	/**
	 * The cheapest spot clear by {@code gap} for someone on {@code tile}, wearing a cape or not (see
	 * {@link #fillNearestFirst}), as {spot, cost}, or {-1, MAX_VALUE}.
	 */
	private static double[] cheapestFor(AtObject at, long object, long tile, List<Long> tiles, Map<Long, Reach> reach, int gap, double[] cape,
		Map<Long, Integer> next)
	{
		Ranking ranked = at.spots.rankedFor(StackRegistry.sceneX(tile) - StackRegistry.sceneX(object), StackRegistry.sceneY(tile) - StackRegistry.sceneY(object));
		int[] order = ranked.cheapest;
		float[] cost = ranked.cost;
		Reach r = reach.get(tile);
		int p = next.getOrDefault(tile, 0);
		while (p < order.length && !(at.near.clear(order[p], gap, cape) && r.ok(order[p])))
		{
			p++;
		}
		next.put(tile, p);
		// Of the spots about as cheap, the one fewest others round the object can reach.
		int pick = -1;
		double pickCost = Double.MAX_VALUE;
		for (int q = p; q < order.length && cost[order[q]] < pickCost; q++)
		{
			int s = order[q];
			if (!at.near.clear(s, gap, cape) || !r.ok(s))
			{
				continue;
			}
			double c = cost[s];
			for (long other : tiles)
			{
				c += other != tile && reach.get(other).ok(s) ? SHARED_SPOT_COST : 0;
			}
			if (c < pickCost)
			{
				pick = s;
				pickCost = c;
			}
		}
		return new double[]{pick, pickCost};
	}

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
	 * @param turn   how far its ring is turned round, and {@code clockwise} which way it goes round
	 *               (see {@link StackSpreader#crowdSpots})
	 */
	Room roomFor(long tile, List<double[]> shares, int wanted, boolean middle, boolean guard, int people, double turn, boolean clockwise)
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
			List<double[]> area = roomAt(shares, middle, guard, spacing, people, turn, clockwise);
			if (area.isEmpty())
			{
				// The widest spacing that fits, found by halving: a closer ring nearly always fits
				// where a wider one does.
				int lo = floor;
				int hi = wanted;
				while (hi - lo > 1)
				{
					int mid = (lo + hi) / 2;
					if (roomAt(shares, middle, guard, mid, people, turn, clockwise).isEmpty())
					{
						hi = mid;
					}
					else
					{
						lo = mid;
					}
				}
				spacing = lo;
				area = roomAt(shares, middle, guard, spacing, people, turn, clockwise);
			}
			if (area.isEmpty())
			{
				double[] c = leastCrowded(shares, StackSpreader.crowdSpots(middle, spacing, people, null, turn, clockwise),
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
			if (was.fits && fitsAt(shares, middle, guard, was, people, turn, clockwise))
			{
				chosen = best.spacing > was.spacing + SPACING_DEADBAND || !best.moved() ? best : was;
			}
			else
			{
				int spacing = Math.min(was.spacing, best.spacing);
				List<double[]> area = roomAt(shares, middle, guard, spacing, people, turn, clockwise);
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
	private static List<double[]> roomAt(List<double[]> shares, boolean middle, boolean guard, int spacing, int people, double turn,
		boolean clockwise)
	{
		List<int[]> spots = StackSpreader.crowdSpots(middle, spacing, people, null, turn, clockwise);
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
	private static boolean fitsAt(List<double[]> shares, boolean middle, boolean guard, Room room, int people, double turn,
		boolean clockwise)
	{
		double far = farAt(guard, room.spacing);
		if (Math.abs(room.x) > far + 1 || Math.abs(room.z) > far + 1)
		{
			return false;
		}
		for (int[] p : StackSpreader.crowdSpots(middle, room.spacing, people, null, turn, clockwise))
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
	 * @param turn     how far a ring is turned round, and {@code clockwise} which way it goes round (see
	 *                 {@link StackSpreader#crowdSpots})
	 */
	private static List<int[]> spotsWithRoom(boolean row, boolean straight, double angle, boolean middleTaken, int spacing,
		int capacity, int wanted, StackSpreader.SpotCheck check, int[] blocked, boolean[] squeezed, int arcRadius, boolean bow,
		double wrap, double turn, boolean clockwise, int[] used)
	{
		blocked[0] = 0;
		List<int[]> best = StackSpreader.spots(row, straight, angle, middleTaken, spacing, capacity, check, arcRadius, bow, wrap,
			turn, clockwise);
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
					List<int[]> ring = StackSpreader.crowdSpots(middleTaken, s, capacity, check, turn, clockwise);
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
		long youAlone = yourTileNow != Long.MIN_VALUE && byTile.containsKey(yourTileNow) && byTile.get(yourTileNow).size() == 1
			? yourTileNow : Long.MIN_VALUE;
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
							r.check, r.blocked, squeezed, StackSpreader.LOOK_AHEAD, bowRows, StackSpreader.WRAP_ARC, 0, false, used);
						if (!r.middleTaken && spots.size() < r.wanted)
						{
							// Someone will be left in the middle without a spot: don't give the middle away too.
							spots = spotsWithRoom(true, true, r.angle, true, r.spacing, spotsFor(r.tile, capacity), r.wanted, r.check, r.blocked, squeezed,
								StackSpreader.LOOK_AHEAD, bowRows, StackSpreader.WRAP_ARC, 0, false, used);
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
						first.centre() - lineEnd(byTile, loneId, first, -1, spacing, youAlone),
						last.centre() + lineEnd(byTile, loneId, last, 1, spacing, youAlone), roomToBow));
				}
				start = i;
			}
		}
		return shared;
	}

	/**
	 * How far a shared line may reach past its end: halfway to the next row along it, or three tiles.
	 * You standing alone next to it don't stop it: a line of fishers stopped short of you and left
	 * the bank beyond you empty, all of them squeezed into rows behind. Its spots keep a spacing
	 * clear of you anyway (see the terrain given to {@link LineBook}).
	 */
	private static double lineEnd(Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, Integer> loneId, Straight end,
		int direction, int spacing, long ignore)
	{
		for (int k = 1; k <= LINE_LOOKOUT; k++)
		{
			long key = end.keyAt(end.along + direction * k);
			List<StackSpreader.Entry> there = key == ignore && !loneId.containsKey(key) ? null : byTile.get(key);
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

	/** Whether people laid out round someone in the middle of their tile stand a body's width from them. */
	private static boolean roomRoundTheMiddle(boolean row, double angle, int spacing)
	{
		List<int[]> first = StackSpreader.spots(row, false, angle, true, spacing, 1, null);
		return !first.isEmpty() && Math.hypot(first.get(0)[0], first.get(0)[1]) >= NEIGHBOUR_GAP;
	}

	/**
	 * For a tile of two or three where one of them has just walked onto the others, the spot each of
	 * them still without one takes: whoever came keeps the side of the tile they came from, or as
	 * near it as its spots allow, and whoever was there steps to the other side. Nobody walks round
	 * or through anybody: walking in from the left, you used to be sent on to the right of whoever
	 * you stopped on, and they stepped out to the left behind you. Someone who stays in the middle
	 * (you, when your character stays put, or a caster) counts too: the others step away from them.
	 *
	 * <p>Only the spots the group fills anyway are used, or for an odd one out the spot across from
	 * theirs too, the same shape turned round: the third of three stands on whichever side they came
	 * from. Nobody already given a spot is moved. Left as it always was when it isn't known which
	 * way they came (a teleport, logging in), two came together, they came straight at the spots
	 * between the two sides, or the group is bigger, or round a fire, laid out for reasons of its own.
	 */
	private Map<Long, Map<Integer, Integer>> sidesFor(Map<Long, List<StackSpreader.Entry>> byTile, Map<Long, List<Integer>> movableByTile,
		Map<Long, List<int[]>> spotsByTile, Plan plan, IntPredicate shown, int capacity, int localId)
	{
		Map<Long, Map<Integer, Integer>> out = new HashMap<>();
		for (Map.Entry<Long, List<Integer>> e : movableByTile.entrySet())
		{
			long tile = e.getKey();
			List<int[]> spots = spotsByTile.get(tile);
			List<Integer> movable = e.getValue();
			if (spots == null || movable.isEmpty() || plan.fires.containsKey(tile))
			{
				continue;
			}
			// Whoever came last, if only one of them did.
			int came = cameLast(byTile.get(tile), shown);
			if (came < 0)
			{
				continue;
			}
			int[] from = cameFrom.apply(came);
			Map<Integer, Integer> placed = slots.spotsOn(tile);
			List<Integer> newcomers = new ArrayList<>();
			Set<Integer> kept = new HashSet<>();
			for (int id : movable)
			{
				Integer s = placed.get(id);
				if (s == null)
				{
					newcomers.add(id);
				}
				else
				{
					kept.add(s);
				}
			}
			int filled = Math.min(capacity, movable.size());
			List<Integer> free = new ArrayList<>();
			for (int s = 0; s < Math.min(spots.size(), filled + filled % 2); s++)
			{
				if (!kept.contains(s))
				{
					free.add(s);
				}
			}
			boolean moves = movable.contains(came);
			if (free.size() < 2 || newcomers.isEmpty() || moves && !newcomers.contains(came))
			{
				continue;
			}
			// Which way each free spot lies from the middle of them, so a crowd moved over to make
			// room for its neighbours is judged from where it stands.
			double midX = 0;
			double midZ = 0;
			for (int s : free)
			{
				midX += spots.get(s)[0] / (double) free.size();
				midZ += spots.get(s)[1] / (double) free.size();
			}
			double length = Math.hypot(from[0], from[1]);
			Map<Integer, Double> toward = new HashMap<>();
			for (int s : free)
			{
				double east = spots.get(s)[0] - midX;
				double north = spots.get(s)[1] - midZ;
				double d = Math.hypot(east, north);
				toward.put(s, d < 1 ? 0 : (east * from[0] + north * from[1]) / (d * length));
			}
			// The way they came first; a tie keeps the tile's own order.
			free.sort(Comparator.comparingDouble(s -> -toward.get(s)));
			if (toward.get(free.get(0)) - toward.get(free.get(1)) < SIDE_MARGIN)
			{
				continue;
			}
			Map<Integer, Integer> sides = new HashMap<>();
			if (moves)
			{
				sides.put(came, free.remove(0));
			}
			// Whoever was there steps the other way, you first, as SlotBook settles you first.
			newcomers.remove((Integer) came);
			if (newcomers.remove((Integer) localId))
			{
				newcomers.add(0, localId);
			}
			for (int id : newcomers)
			{
				if (!free.isEmpty())
				{
					sides.put(id, free.remove(free.size() - 1));
				}
			}
			out.put(tile, sides);
		}
		return out;
	}

	/**
	 * Whoever of these people, standing together on a tile, came onto it last, if only one of them
	 * did, it's known which way they came, and few enough of them are shown to keep sides
	 * ({@link #SIDED_GROUP}); else -1.
	 */
	private int cameLast(List<StackSpreader.Entry> people, IntPredicate shown)
	{
		int came = -1;
		int cameTick = 0;
		int together = 0;
		int here = 0;
		for (StackSpreader.Entry en : people)
		{
			if (!shown.test(en.id))
			{
				continue;
			}
			here++;
			int arrived = arrivedTick.applyAsInt(en.id);
			if (came < 0 || arrived > cameTick)
			{
				came = en.id;
				cameTick = arrived;
				together = 1;
			}
			else if (arrived == cameTick)
			{
				together++;
			}
		}
		return came < 0 || together > 1 || cameTick <= 0 || here > SIDED_GROUP || cameFrom.apply(came) == null ? -1 : came;
	}

	/**
	 * Which way a small group's spots lie across its tile as it forms, in degrees anticlockwise from
	 * east and west (see {@link StackSpreader#crowdSpots}): along the way whoever walked onto the
	 * others came, so they keep that side, front and back or a diagonal as much as left and right,
	 * and whoever was there steps straight out the other way ({@link #sidesFor}). Laid out east and
	 * west, someone walking in from the front or back had no side to keep and stood wherever the
	 * camera put them.
	 *
	 * <p>The way is one of the eight anyone walks: a pair stands square to the tiles or on a
	 * diagonal ({@link #DIAGONAL_WAY}), never at the odd slant of where someone was mid-stride the
	 * tick before. Where their end of it can't be stood on (a wall, an object, flames), the next way
	 * round towards it that can. 0, east and west as always, when nobody walked onto the others or
	 * none of those ways has room.
	 */
	private double turnFor(long tile, List<StackSpreader.Entry> group, IntPredicate shown, int spacing, Surroundings around)
	{
		int came = cameLast(group, shown);
		if (came < 0)
		{
			return 0;
		}
		int[] from = cameFrom.apply(came);
		double way = Math.toDegrees(Math.atan2(from[1], from[0]));
		double diagonal = Math.floor(way / 90) * 90 + 45;
		double nearest = Math.abs(way - diagonal) <= DIAGONAL_WAY ? diagonal : Math.round(way / 90) * 90.0;
		// Then the next way round, on the side of it they came from.
		double round = way >= nearest ? 45 : -45;
		for (double end : new double[]{nearest, nearest + round, nearest - round})
		{
			int[] spot = StackSpreader.crowdSpots(false, spacing, 1, null, end, false).get(0);
			if (around.canStand(tile, spot[0], spot[1]))
			{
				return (end % 180 + 180) % 180;
			}
		}
		return 0;
	}

	/**
	 * Whether the spots round a pair go round the other way for the third of three, who has just
	 * walked onto them (see {@link StackSpreader#crowdSpots}). The third has the choice of the spot
	 * after the pair's two or the one across from it ({@link #sidesFor}), and going round the other
	 * way puts those two either side of the pair's line instead: between the four, whichever way
	 * they came there's one on their side. With a pair laid out on a diagonal and a third coming
	 * square on, both spots of one way round could leave them well round from where they came.
	 * Only while the pair have the first two spots and nobody else has one, so nobody moves for it.
	 */
	private boolean clockwiseFor(long tile, List<StackSpreader.Entry> group, IntPredicate shown, double turn, int spacing,
		StackSpreader.SpotCheck check, boolean was)
	{
		int came = cameLast(group, shown);
		Map<Integer, Integer> placed = slots.spotsOn(tile);
		if (came < 0 || group.size() != 3 || placed.containsKey(came) || placed.size() != 2)
		{
			return was;
		}
		for (int s : placed.values())
		{
			if (s > 1)
			{
				return was;
			}
		}
		// Turning the rest round must leave the pair's own two spots first, where they are.
		for (int[] spot : StackSpreader.crowdSpots(false, spacing, 2, null, turn, false))
		{
			if (!check.canStand(spot[0], spot[1]))
			{
				return was;
			}
		}
		int[] from = cameFrom.apply(came);
		double way = Math.toDegrees(Math.atan2(from[1], from[0])) - turn;
		// Of the third's two spots each way round, 120 and 300 degrees on from the first spot or 240
		// and 60, the one nearer the way they came.
		double anticlockwise = Math.abs(Math.cos(Math.toRadians(way - 120)));
		double clockwise = Math.abs(Math.cos(Math.toRadians(way + 120)));
		return Math.abs(clockwise - anticlockwise) < 1e-6 ? was : clockwise > anticlockwise;
	}

	/**
	 * Who of two or three on a tile is drawn in its middle, where they really stand: someone casting
	 * a skilling spell. Their spell effect is drawn there by the game wherever they are drawn, so
	 * only there does it line up with them, and stood still they are never walked mid-cast. They
	 * keep it while they cast, through the pauses between casts ({@link #casting}). Of two casters,
	 * whoever has it keeps it; otherwise whoever is in the middle already, then whoever has been
	 * casting longest. Someone with a spot is given it only between casts, or as one ends
	 * ({@link #midAction}), so they walk straight in, and only once nobody else is in the middle, so
	 * nobody walks into someone stepping out of it. Until then they keep their spot.
	 *
	 * @return the caster; {@link #CASTER_WAITS} while one with a spot waits to step in where the
	 *         middle was kept for a caster last tick, so the others aren't laid out afresh meanwhile;
	 *         or {@link #NO_CASTER}
	 */
	private int casterFor(long tile, List<StackSpreader.Entry> group, int tick)
	{
		Integer had = castersWere.get(tile);
		int best = NO_CASTER;
		boolean bestInMiddle = false;
		boolean middleClear = true;
		for (StackSpreader.Entry e : group)
		{
			// Without a spot here last tick (just arrived, or left in the middle), they're in the middle.
			double[] was = drawnLastTick(e.id, tile);
			boolean inMiddle = was == null || was[0] == 0 && was[1] == 0;
			middleClear &= !inMiddle;
			if (!casting.test(e.id))
			{
				continue;
			}
			if (had != null && had == e.id)
			{
				return e.id;
			}
			int since = castingSince.getOrDefault(e.id, tick);
			int bestSince = castingSince.getOrDefault(best, tick);
			if (best == NO_CASTER || inMiddle && !bestInMiddle
				|| inMiddle == bestInMiddle && (since < bestSince || since == bestSince && e.id < best))
			{
				best = e.id;
				bestInMiddle = inMiddle;
			}
		}
		if (best == NO_CASTER || bestInMiddle || middleClear && !midAction.test(best))
		{
			return best;
		}
		return castersWere.containsKey(tile) ? CASTER_WAITS : NO_CASTER;
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
		lineBook.yourTile = yourTileNow;
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
		castersWere = castersNow;
		castersNow = new HashMap<>();
		turnsWere = turnsNow;
		turnsNow = new HashMap<>();
		clockwiseWere = clockwiseNow;
		clockwiseNow = new HashSet<>();
		Map<Integer, Integer> castingFrom = new HashMap<>();
		for (StackSpreader.Entry e : still)
		{
			if (casting.test(e.id))
			{
				castingFrom.put(e.id, castingSince.getOrDefault(e.id, tick));
			}
		}
		castingSince = castingFrom;
		Map<Long, List<Integer>> movableByTile = new HashMap<>();
		Map<Long, List<int[]>> spotsByTile = new HashMap<>();
		Map<Long, String> shapeByTile = new HashMap<>();
		Map<Long, Integer> spacingByTile = new HashMap<>();
		Map<Long, Integer> shownByTile = new HashMap<>();
		Map<Long, Integer> blockedByTile = new HashMap<>();
		List<Straight> pendingStraight = new ArrayList<>();
		Map<Long, List<Integer>> onFires = new HashMap<>();
		// Crowds round an object nobody can stand on: the tiles round each, and who on them may be moved.
		Map<Long, List<Long>> objectCrowds = new HashMap<>();
		Map<Long, List<Integer>> objectIds = new HashMap<>();
		objectAt.clear();
		previousObjectOf = currentObjectOf;
		currentObjectOf = new HashMap<>();
		firesNearNow.clear();
		roundFires.clear();
		fireCrowdsWas = fireCrowds;
		fireCrowds = new HashSet<>();
		anchorOfWas = anchorOf;
		anchorOf = new HashMap<>();
		fireGroups.clear();
		emberGroups.clear();
		previousFireOf = currentFireOf;
		currentFireOf = new HashMap<>();
		// Smart, at a busy fire: one crowd all round it, as at an anvil, with everyone standing on it.
		Map<Long, Long> fireOfTile = smart && arrangement == PersonalSpaceConfig.Arrangement.AUTO
			? findFireCrowds(byTile, shown, around, tick) : new HashMap<>();
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
			// The way a group's spots lie across its tile is kept for as long as the group is
			// together, whatever it is laid out as meanwhile.
			Double hadTurn = turnsWere.get(tile);
			if (hadTurn != null)
			{
				turnsNow.put(tile, hadTurn);
			}
			if (clockwiseWere.contains(tile))
			{
				clockwiseNow.add(tile);
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
			List<int[]> firesHere = firesNear(tile, around);
			if (fireUnderfoot(firesHere))
			{
				Long crowdFire = fireOfTile.get(tile);
				if (crowdFire != null && fireCrowds.contains(crowdFire))
				{
					// A busy fire, or one close beside a busy one (see findFireCrowds): everyone standing
					// on it is one crowd with everyone round them.
					joinFireCrowd(crowdFire, tile, movable, objectCrowds, objectIds);
					continue;
				}
				// Standing on a fire (a Forester's Campfire can be stood on): never made room for like
				// a crowd, nor lined up across it, but stood round the fire once everyone else's spots
				// are known, by layOutOnFires.
				onFires.put(tile, movable);
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
			boolean atFire = false;
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
				Long crowdFire = fireOfTile.get(tile);
				if (crowdFire != null && anchorOf.containsKey(crowdFire))
				{
					// At a busy fire: one crowd all round it with everyone else at it, laid out once
					// everyone else's spots are known (see layOutRoundObjects). Or at one just gone out
					// beside it, still: see findFireCrowds.
					joinFireCrowd(crowdFire, tile, movable, objectCrowds, objectIds);
					continue;
				}
				// Gathered round a fire, in a row or not: stay close, whatever the slider says, and
				// everyone faces the fire.
				int[] fire = fireFaced(group, firesHere);
				atFire = fire != null || around.facesFire(tile, rowAngle);
				if (fire != null || (row && around.facesFire(tile, rowAngle)))
				{
					tileSpacing = smallGroupsClose
						? Math.min(tileSpacing, group.size() <= PersonalSpaceConfig.SMALL_FIRE_GROUP
							? PersonalSpaceConfig.SMALL_FIRE_SPACING : PersonalSpaceConfig.FIRE_SPACING)
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
			// Smart, at something nobody can stand on, such as an anvil: one crowd all round it with
			// everyone at it from the other tiles round it, laid out once everyone else's spots are known.
			long object = !straight && !atFire && smart && arrangement == PersonalSpaceConfig.Arrangement.AUTO
				? objectFaced(tile, row, angle, group, around) : NO_OBJECT;
			if (object != NO_OBJECT)
			{
				plan.curvedRows.add(tile);
				objectCrowds.computeIfAbsent(object, k -> new ArrayList<>()).add(tile);
				objectIds.put(tile, movable);
				currentObjectOf.put(tile, object);
				continue;
			}
			// Two or three out in the open, one of them alching or the like: the caster stays where
			// they really stand and the others stand round them, as round anyone staying put. Not at
			// a counter or a fire, which lay people out their own way, nor so close together that
			// the others would stand inside the caster.
			int caster = straight || atFire || middleTaken || group.size() > CASTER_GROUP || !roomRoundTheMiddle(row, angle, tileSpacing)
				? NO_CASTER : casterFor(tile, group, tick);
			if (caster != NO_CASTER)
			{
				// Laid out round the middle while a caster has it, or is about to step into it.
				middleTaken = true;
				castersNow.put(tile, caster);
			}
			if (caster >= 0)
			{
				// Given no spot of their own: the others' spots are laid out round them.
				movable.remove((Integer) caster);
				kind += ", caster in the middle";
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
			Integer hadSpacing = spacingWas.get(tile);
			if (hadSpacing != null && Math.abs(tileSpacing - hadSpacing) <= SPACING_DEADBAND)
			{
				tileSpacing = hadSpacing;
			}
			spacingNow.put(tile, tileSpacing);
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
			// Two or three in the open who have only just met, one walking onto the others: their
			// spots lie along the way that one came (see turnFor), and stay that way while they're
			// together. A row, or a crowd round a fire, is laid out its own way.
			double turn = hadTurn != null ? hadTurn
				: row || atFire || !slots.spotsOn(tile).isEmpty() ? 0 : turnFor(tile, group, shown, tileSpacing, around);
			turnsNow.put(tile, turn);
			double ringTurn = row ? 0 : turn;
			boolean clockwise = !row && clockwiseFor(tile, group, shown, turn, tileSpacing, check, clockwiseWere.contains(tile));
			if (clockwise)
			{
				clockwiseNow.add(tile);
			}
			else if (!row)
			{
				clockwiseNow.remove(tile);
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
				room = roomFor(tile, shares, tileSpacing, middleTaken, guard, people, turn, clockwise);
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
				Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, used);
			if (!middleTaken && spots.size() < movable.size() && (!row || squeezed[0]))
			{
				// Walls leave too few spots for everyone: someone stays in the middle without a spot,
				// so don't also give the middle to someone else.
				boolean wasRow = row && squeezed[0];
				middleNow = true;
				spots = spotsWithRoom(false, false, angle, true, tileSpacing, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, used);
				squeezed[0] |= wasRow;
			}
			if (room != null && room.moved() && (used[0] < room.spacing || middleNow != middleTaken))
			{
				// A wall closed the ring up further than the room allowed for, around the same moved
				// middle, or left someone in the middle: move it over again for the ring it has now,
				// or its near side lands on whoever stands in the middle of the tile.
				boolean g = guard || middleNow;
				List<double[]> area = roomAt(shares, middleNow, g, used[0], people, ringTurn, clockwise);
				double far = farAt(g, used[0]);
				double[] c = area.isEmpty()
					? new double[]{Math.max(-far, Math.min(far, room.x)), Math.max(-far, Math.min(far, room.z))}
					: nearestTo(area, room.x, room.z);
				Room again = new Room(Math.round(c[0]), Math.round(c[1]), used[0], room.wanted, room.fits && !area.isEmpty());
				moveOver = new double[]{again.x, again.z};
				laidOut = movedOver(check, shares, again, g);
				int closer = used[0];
				spots = spotsWithRoom(false, false, angle, middleNow, closer, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), laidOut, blocked, squeezed, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, used);
			}
			if (room != null && !room.fits)
			{
				// No layout keeps a crowd's first spots all to their shares (a full tile boxed in by
				// its neighbours), but the ring can still have enough spots further round that do:
				// take those, rather than handing out spots that lean into the neighbours.
				Room within = new Room(moveOver[0], moveOver[1], used[0], room.wanted, true);
				List<int[]> kept = StackSpreader.crowdSpots(middleNow, used[0], spotsFor(tile, capacity),
					movedOver(check, shares, within, guard || middleNow), ringTurn, clockwise);
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
				// The ground the neighbours have may be leaned into a little, but never the people
				// standing on it: someone alone on the next tile keeps their gap.
				rowShares.removeIf(h -> !Double.isNaN(h[2]));
				boolean[] plain = {false};
				int[] plainUsed = {tileSpacing};
				int[] plainBlocked = {0};
				List<int[]> loose = spotsWithRoom(row, false, angle, middleTaken, tileSpacing, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), check, plainBlocked, plain, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, plainUsed);
				if (!plain[0] && loose.size() >= Math.min(spots.size(), movable.size()))
				{
					spots = loose;
					squeezed[0] = false;
					used[0] = plainUsed[0];
					blocked[0] = plainBlocked[0];
				}
				else
				{
					rowShares.clear();
					rowShares.addAll(kept);
				}
			}
			if (row && squeezed[0])
			{
				lastSqueezed.put(tile, tick);
			}
			else if (row && tick - lastSqueezed.getOrDefault(tile, Integer.MIN_VALUE / 2) < UNSQUEEZE_TICKS)
			{
				// Room for a row again, but only just now: stay a ring a while yet, as long as the ring
				// still has a spot for everyone the row does. Where the row was let lean into the
				// neighbours (above), this ring may lean a little too: holding it to its share there
				// instead made a campfire crowd flip between the two every few ticks, which looked worse.
				boolean[] ring = {false};
				int[] ringUsed = {tileSpacing};
				int[] ringBlocked = {0};
				List<int[]> asRing = spotsWithRoom(false, false, angle, middleNow, tileSpacing, spotsFor(tile, capacity),
					Math.min(capacity, movable.size()), laidOut, ringBlocked, ring, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, ringUsed);
				if (asRing.size() >= spots.size())
				{
					spots = asRing;
					squeezed[0] = true;
					used[0] = ringUsed[0];
					blocked[0] = ringBlocked[0];
				}
			}
			Integer hadUsed = usedWas.get(tile);
			if (hadUsed != null && used[0] > hadUsed && (room == null || room.fits))
			{
				int since = widerSince.computeIfAbsent(tile, k -> tick);
				if (tick - since < WIDEN_TICKS || used[0] - hadUsed <= SPACING_DEADBAND)
				{
					// Not yet: stay as close as before, as long as that still has a spot for everyone.
					boolean[] stillSqueezed = {false};
					int[] stillUsed = {hadUsed};
					int[] stillBlocked = {0};
					List<int[]> asBefore = spotsWithRoom(row && !squeezed[0], false, angle, middleNow, hadUsed, spotsFor(tile, capacity),
						Math.min(capacity, movable.size()), laidOut, stillBlocked, stillSqueezed, arcRadius, false, StackSpreader.WRAP_ARC, ringTurn, clockwise, stillUsed);
					if (asBefore.size() >= spots.size() && stillUsed[0] == hadUsed)
					{
						spots = asBefore;
						used[0] = hadUsed;
						blocked[0] = stillBlocked[0];
						squeezed[0] |= stillSqueezed[0];
					}
				}
			}
			else
			{
				widerSince.remove(tile);
			}
			usedNow.put(tile, used[0]);
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
		spacingWas = spacingNow;
		spacingNow = new HashMap<>();
		usedWas = usedNow;
		usedNow = new HashMap<>();
		widerSince.keySet().retainAll(usedWas.keySet());
		lastSqueezed.values().removeIf(t -> tick - t >= UNSQUEEZE_TICKS);
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
		// Beside a fire, the spot nearest it is yours, whoever else is there and wherever the camera
		// is: you were left further off than someone just standing about by it. Only when it's
		// really nearer: a row facing the fire is level with it, and the camera's side still counts.
		slots.fireSideForYou = new HashMap<>();
		int[] fireBesideYou = youTileNow == Long.MIN_VALUE ? null : plan.fires.get(youTileNow);
		if (fireBesideYou != null && (fireBesideYou[0] != 0 || fireBesideYou[1] != 0) && withYou != null && withYou.size() >= 2
			&& yourSpots != null && yourSpots.size() >= 2)
		{
			int picked = Math.min(slots.pairSideForYou.getOrDefault(youTileNow, 0), yourSpots.size() - 1);
			int nearest = picked;
			// Of the spots your group fills, so nobody is left with a gap beside them.
			for (int s = 0; s < Math.min(Math.min(capacity, withYou.size()), yourSpots.size()); s++)
			{
				nearest = fromFire(yourSpots.get(s), fireBesideYou) < fromFire(yourSpots.get(nearest), fireBesideYou) ? s : nearest;
			}
			if (fromFire(yourSpots.get(nearest), fireBesideYou) < fromFire(yourSpots.get(picked), fireBesideYou) - NEARER_THE_FIRE)
			{
				slots.fireSideForYou.put(youTileNow, nearest);
				slots.pairSideForYou.put(youTileNow, nearest);
			}
		}
		// Whoever has just walked onto someone keeps the side they came from.
		slots.sideFor = sidesFor(byTile, movableByTile, spotsByTile, plan, shown, capacity, localId);
		// Your personal space and line of sight are judged from where you'll stand. The tick you
		// are first given a spot you were in the middle of your tile a moment ago, and judged from
		// there, the spot beside you fell inside you: the other player went round you first and
		// came back beside you a tick later.
		long judgeTile = lastYouTile;
		int judgeSlot = lastYouSlot;
		if (lastYouSlot < 0 && yourSpots != null)
		{
			judgeTile = youTileNow;
			Integer side = slots.sideFor.getOrDefault(youTileNow, Collections.emptyMap()).get(localId);
			judgeSlot = Math.min(side != null ? side : slots.pairSideForYou.getOrDefault(youTileNow, 0), yourSpots.size() - 1);
		}
		if (youTileNow != Long.MIN_VALUE && Integer.valueOf(localId).equals(castersNow.get(youTileNow)))
		{
			// Casting in the middle of your tile, with no spot there: judged from where you were drawn
			// last tick.
			judgeTile = youTileNow;
			judgeSlot = -1;
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
		slots.keepPlaces = castersNow.keySet();
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
		// Whoever waits in the middle of a tile on a line without a spot isn't drawn there.
		plan.middleOutOfSight.addAll(lineBook.middleHidden);
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
			Integer caster = castersNow.get(tile);
			if (caster != null && caster >= 0)
			{
				// Placed where they really stand, so they are drawn there.
				plan.placements.add(new StackSpreader.Placement(caster, tile, 0, 0));
				plan.castersInTheMiddle.add(caster);
				placed.add(caster);
			}
			plan.tiles.put(tile, new TileReport(shapeByTile.get(tile), spacingByTile.get(tile), shownByTile.get(tile),
				e.getValue().size(), spots.size(), blockedByTile.get(tile)));
		}
		// A busy fire stood round by a crowd last tick stays one while anyone is left at it, even with
		// nobody left sharing a tile: whoever is alone on it or beside it keeps their place round it
		// (see layOutRoundObjects). Left out, the last one on the fire was laid out afresh and walked
		// straight through it to the first place by it.
		for (long fire : fireCrowds)
		{
			long anchor = anchorOf.getOrDefault(fire, fire);
			if (!objectCrowds.containsKey(anchor) && previousFireOf.containsValue(fire))
			{
				objectCrowds.put(anchor, new ArrayList<>());
			}
		}
		layOutRoundObjects(plan, byTile, objectCrowds, objectIds, onFires.keySet(), placed, shown, capacity, includeLocal, tick, around);
		layOutOnFires(plan, byTile, onFires, placed, shown, capacity, includeLocal, tick, around);
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
			// Last of all, whatever laid them out (a crowd, a shared row, a row leaning into its
			// neighbours): nobody is drawn inside you. Anyone who would be waits in the middle of
			// their tile instead. A row squeezed round a fire, leaning into the ground beside it,
			// stood people right where you stood, and one of you vanished into the other.
			// People on your own tile are left to its layout: their spacing is the one you chose.
			int yourPlaneNow = yourPlane(still);
			long yourTileHere = Long.MIN_VALUE;
			for (StackSpreader.Entry e : still)
			{
				yourTileHere = e.local ? e.tile : yourTileHere;
			}
			for (java.util.Iterator<StackSpreader.Placement> it = plan.placements.iterator(); it.hasNext(); )
			{
				StackSpreader.Placement p = it.next();
				if (p.id == localId || p.tile == yourTileHere || StackRegistry.plane(p.tile) != yourPlaneNow)
				{
					continue;
				}
				double px = StackRegistry.sceneX(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dx;
				double pz = StackRegistry.sceneY(p.tile) * 2.0 * HALF_TILE + HALF_TILE + p.dz;
				if (Math.hypot(px - youAt[0], pz - youAt[1]) < YOUR_SPACE)
				{
					it.remove();
					plan.unplaced.add(new StackSpreader.Placement(p.id, p.tile, 0, 0));
					insideYouHidden++;
				}
			}
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
		placedBefore = new ArrayList<>(plan.placements);
		drawnBefore = null;
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
		return slots.moves + lineBook.moves + fireMoves + objectMoves;
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
		spacingWas.clear();
		spacingNow.clear();
		usedWas.clear();
		usedNow.clear();
		widerSince.clear();
		lastSqueezed.clear();
		roomsNow.clear();
		presence.clear();
		waited.clear();
		busyLineUntil.clear();
		fireSpotOf.clear();
		fireSpotHeld.clear();
		objectAt.clear();
		previousObjectOf.clear();
		currentObjectOf.clear();
		objectSpotOf.clear();
		objectSpotHeld.clear();
		steppingBack.clear();
		grounds.clear();
		spotsNow.clear();
		spotsWas.clear();
		spotsKept.clear();
		fireFocusNow.clear();
		fireCrowds.clear();
		fireCrowdsWas.clear();
		anchorOf.clear();
		anchorOfWas.clear();
		fireGroups.clear();
		embers.clear();
		emberGroups.clear();
		previousFireOf.clear();
		currentFireOf.clear();
		firesNearNow.clear();
		roundFires.clear();
		waitingSince.clear();
		waitingSinceNow.clear();
		placedBefore.clear();
		drawnBefore = null;
		castersWere.clear();
		castersNow.clear();
		castingSince.clear();
		turnsWere.clear();
		turnsNow.clear();
		clockwiseWere.clear();
		clockwiseNow.clear();
	}
}
