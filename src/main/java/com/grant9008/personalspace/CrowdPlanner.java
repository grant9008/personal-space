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

	/** The "Small groups stay close" setting: whether spacing grows with the size of the group. Set by the plugin each tick. */
	boolean smallGroupsClose = true;
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
	 * How far round a curved row on this tile may reach before it meets someone standing on another
	 * side of the same thing: {anticlockwise, clockwise}, in radians, each half the angle to the
	 * nearest occupied tile around what the row faces (or {@link Double#MAX_VALUE} if there's none).
	 */
	private static double[] arcLimits(Map<Long, List<StackSpreader.Entry>> byTile, int plane, int sceneX, int sceneY, double angle)
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
					|| !byTile.containsKey(StackRegistry.key(plane, nx, ny)))
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
		int sideX, int sideY, int spacing)
	{
		if (byTile.containsKey(StackRegistry.key(plane, sceneX + sideX, sceneY + sideY)))
		{
			return HALF_TILE - spacing / 2.0;
		}
		if (byTile.containsKey(StackRegistry.key(plane, sceneX + 2 * sideX, sceneY + 2 * sideY)))
		{
			return 2 * HALF_TILE - spacing / 2.0;
		}
		return Double.MAX_VALUE;
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

		Plan plan = new Plan();
		shapes.startTick();
		previousSizes = currentSizes;
		currentSizes = new HashMap<>();
		previousMostFaced = currentMostFaced;
		currentMostFaced = new HashMap<>();
		Map<Long, List<Integer>> movableByTile = new HashMap<>();
		Map<Long, List<int[]>> spotsByTile = new HashMap<>();
		Map<Long, String> shapeByTile = new HashMap<>();
		Map<Long, Integer> spacingByTile = new HashMap<>();
		Map<Long, Integer> shownByTile = new HashMap<>();
		Map<Long, Integer> blockedByTile = new HashMap<>();
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
				if (includeLocal || !en.local)
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

			// At a bank counter or row of booths, people stand close together in a straight line
			// along it: spread wide, a bank crowd reads as a queue. Around a fire they stay close
			// too, or it looks deserted.
			int tileSpacing = smallGroupsClose ? spacingFor(spacing, sizeFor(tile, group.size(), tick)) : spacing;
			double angle = rowAngle;
			boolean straight = false;
			String kind = row ? "curved row" : "crowd";
			if (row && around.isCounter(tile, rowAngle))
			{
				tileSpacing = Math.min(tileSpacing, PersonalSpaceConfig.COUNTER_SPACING);
				// Run exactly along the counter, even if the row was formed by someone facing it at a slant.
				angle = Math.round(rowAngle / (Math.PI / 2)) * (Math.PI / 2);
				straight = true;
				kind = "counter row";
			}
			else
			{
				// Gathered round a fire, in a row or not: stay close, whatever the slider says, and
				// everyone faces the fire.
				int[] fire = fireFaced(group, around.firesNear(tile));
				if (fire != null || (row && around.facesFire(tile, rowAngle)))
				{
					tileSpacing = Math.min(tileSpacing, PersonalSpaceConfig.FIRE_SPACING);
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
			double reachAhead = straight ? counterReach(byTile, plane, sceneX, sceneY, sideX, sideY, tileSpacing) : Double.MAX_VALUE;
			double reachBehind = straight ? counterReach(byTile, plane, sceneX, sceneY, -sideX, -sideY, tileSpacing) : Double.MAX_VALUE;

			// Around a tree, anvil or fire with people on more than one side, each tile's curve keeps
			// to its own share of the ring, squeezing its players closer rather than reaching round
			// behind the people on the next side.
			double[] arc = row && !straight
				? arcLimits(byTile, plane, sceneX, sceneY, angle)
				: new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
			double tightest = Math.min(arc[0], arc[1]);
			if (tightest < Double.MAX_VALUE)
			{
				tileSpacing = Math.min(tileSpacing,
					Math.max(MIN_SHARED_SPACING, (int) Math.floor(StackSpreader.LOOK_AHEAD * tightest / 2)));
			}
			final double margin = row && !straight ? StackSpreader.curvedTurn(tileSpacing) / 2 : 0;
			final double focusX = -Math.sin(angle) * StackSpreader.LOOK_AHEAD;
			final double focusZ = -Math.cos(angle) * StackSpreader.LOOK_AHEAD;

			int[] blocked = {0};
			StackSpreader.SpotCheck check =
				(dx, dz) ->
				{
					double along = dx * alongX + dz * alongZ;
					double round = arc[0] == Double.MAX_VALUE && arc[1] == Double.MAX_VALUE
						? 0 : signedAngle(-focusX, -focusZ, dx - focusX, dz - focusZ);
					boolean inner = Math.abs(round) <= margin + 1e-6;
					boolean outsideArc = !inner && (round > arc[0] - margin || round < -(arc[1] - margin));
					if (along > reachAhead || along < -reachBehind || outsideArc || !around.canStand(tile, dx, dz))
					{
						blocked[0]++;
						return false;
					}
					return true;
				};
			List<int[]> spots = StackSpreader.spots(row, straight, angle, middleTaken, tileSpacing, capacity, check);
			if (!row && !middleTaken && spots.size() < movable.size())
			{
				// Walls leave too few spots for everyone: someone stays in the middle without a spot,
				// so don't also give the middle to someone else.
				blocked[0] = 0;
				spots = StackSpreader.spots(false, false, angle, true, tileSpacing, capacity, check);
			}
			spotsByTile.put(tile, spots);
			movableByTile.put(tile, movable);
			shapeByTile.put(tile, kind);
			spacingByTile.put(tile, tileSpacing);
			shownByTile.put(tile, group.size());
			blockedByTile.put(tile, blocked[0]);
		}

		Map<Long, Map<Integer, Integer>> assigned = slots.update(movableByTile, tile -> spotsByTile.get(tile).size(), tick);

		Set<Integer> placed = new HashSet<>();
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
		return slots.moves;
	}

	void clear()
	{
		slots.clear();
		shapes.clear();
		previousSizes.clear();
		currentSizes.clear();
		previousMostFaced.clear();
		currentMostFaced.clear();
	}
}
