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

	/** How many players each tile's spacing is sized for, and the last tick the tile was that big. */
	private Map<Long, int[]> previousSizes = new HashMap<>();
	private Map<Long, int[]> currentSizes = new HashMap<>();

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

			// At a bank counter or row of booths, people stand close together in a straight line
			// along it: spread wide, a bank crowd reads as a queue. Around a fire they stay close
			// too, or it looks deserted.
			int tileSpacing = smallGroupsClose ? spacingFor(spacing, sizeFor(tile, group.size(), tick)) : spacing;
			double angle = shape.angle;
			boolean straight = false;
			String kind = row ? "curved row" : "crowd";
			if (row && around.isCounter(tile, shape.angle))
			{
				tileSpacing = Math.min(tileSpacing, PersonalSpaceConfig.COUNTER_SPACING);
				// Run exactly along the counter, even if the row was formed by someone facing it at a slant.
				angle = Math.round(shape.angle / (Math.PI / 2)) * (Math.PI / 2);
				straight = true;
				kind = "counter row";
			}
			else if (row && around.facesFire(tile, shape.angle))
			{
				tileSpacing = Math.min(tileSpacing, PersonalSpaceConfig.FIRE_SPACING);
				kind = "fire row";
			}
			if (row && !straight)
			{
				plan.curvedRows.add(tile);
			}
			int[] fire = fireFaced(group, around.firesNear(tile));
			if (fire != null)
			{
				plan.fires.put(tile, new int[]{fire[0] * 2 * HALF_TILE, fire[1] * 2 * HALF_TILE});
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

			int[] blocked = {0};
			StackSpreader.SpotCheck check =
				(dx, dz) ->
				{
					double along = dx * alongX + dz * alongZ;
					if (along > reachAhead || along < -reachBehind || !around.canStand(tile, dx, dz))
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
	}
}
