package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Someone walking or running off a spot is drawn where they really are plus what is left of their
 * offset. Out of a crowd round a smithy's anvil, that was across the room's wall from where they
 * really ran, and through it by the door. Frame by frame, as the plugin runs it: the game moves them
 * along their path, the offsets move ({@link OffsetTable#advance}) and are kept clear of walls
 * ({@link OffsetTable#keepClear}). What is drawn is checked here without the plugin's own wall code.
 */
public class WalkingOffWallsTest
{
	private static final int SIZE = 40;
	private static final int ID = 7;
	private static final float DT = 0.02f;

	private int[][][] flags;

	private void room(int x0, int z0, int x1, int z1, int doorX)
	{
		flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		for (int x = x0; x <= x1; x++)
		{
			if (x != doorX)
			{
				wallNorth(x, z1);
			}
			wallNorth(x, z0 - 1);
		}
		for (int z = z0; z <= z1; z++)
		{
			wallEast(x1, z);
			wallEast(x0 - 1, z);
		}
	}

	private void wallNorth(int x, int z)
	{
		flags[0][x][z] |= CollisionTerrain.BLOCK_NORTH;
		flags[0][x][z + 1] |= CollisionTerrain.BLOCK_SOUTH;
	}

	private void wallEast(int x, int z)
	{
		flags[0][x][z] |= CollisionTerrain.BLOCK_EAST;
		flags[0][x + 1][z] |= CollisionTerrain.BLOCK_WEST;
	}

	private boolean blocked(int x, int z)
	{
		return x < 0 || z < 0 || x >= SIZE || z >= SIZE || (flags[0][x][z] & CollisionTerrain.BLOCK_FULL) != 0;
	}

	/** Whether a straight move between two drawn points goes through a wall or a blocked tile, sampled a unit at a time. */
	private boolean throughSomething(double ax, double az, double bx, double bz)
	{
		int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az)));
		int px = (int) Math.floor(ax / 128);
		int pz = (int) Math.floor(az / 128);
		if (blocked(px, pz))
		{
			return true;
		}
		for (int k = 1; k <= n; k++)
		{
			int tx = (int) Math.floor((ax + (bx - ax) * k / n) / 128);
			int tz = (int) Math.floor((az + (bz - az) * k / n) / 128);
			if (tx == px && tz == pz)
			{
				continue;
			}
			if (blocked(tx, tz))
			{
				return true;
			}
			int sx = tx - px;
			int sz = tz - pz;
			boolean ok = sx != 0 && sz != 0
				? CollisionTerrain.canStep(flags[0], px, pz, sx, 0) && CollisionTerrain.canStep(flags[0], px + sx, pz, 0, sz)
					|| CollisionTerrain.canStep(flags[0], px, pz, 0, sz) && CollisionTerrain.canStep(flags[0], px, pz + sz, sx, 0)
				: CollisionTerrain.canStep(flags[0], px, pz, sx, sz);
			if (!ok)
			{
				return true;
			}
			px = tx;
			pz = tz;
		}
		return false;
	}

	/** What happened over one walk off a spot. */
	private static final class Run
	{
		int framesThrough;
		int framesOnBlocked;
		double biggestStep;
		/** The most anyone was drawn moving in a frame beyond their real step and the pace an offset runs out at. */
		double biggestExcess;
		int framesUntilGone = -1;
		final List<double[]> drawn = new ArrayList<>();
		boolean walkingWhileDrawnMovingStill = true;
	}

	/**
	 * Someone standing on tile (x, z) with this offset walks (or runs) the path, the way the client
	 * moves people: each axis by up to its speed a cycle, stopping on each tile it's heading for.
	 * Then they stand where they got to until their offset has run out.
	 */
	private Run walkOff(int x, int z, int offsetX, int offsetZ, int[][] path, boolean running, boolean guarded)
	{
		OffsetTable t = new OffsetTable();
		OffsetTable.Walls walls = new CollisionTerrain(flags).walls(0);
		t.setTarget(ID, offsetX, offsetZ);
		for (int i = 0; i < 200; i++)
		{
			t.advance(DT);
			if (guarded)
			{
				t.keepClear(ID, x * 128 + 64, z * 128 + 64, false, DT, false, walls);
			}
		}
		Assert.assertEquals("in their spot to start with", offsetX, t.dx(ID));
		// They set off: next tick their target is the middle and they ease back gently (they're off it).
		t.clearTargets();
		t.setWalkingOff(ID, true);
		Run run = new Run();
		int px = x * 128 + 64;
		int pz = z * 128 + 64;
		int next = 0;
		int speed = running ? 8 : 4;
		double lastX = px + t.dx(ID);
		double lastZ = pz + t.dz(ID);
		boolean walkedLastFrame = false;
		for (int frame = 0; frame < 2000; frame++)
		{
			int fromX = px;
			int fromZ = pz;
			boolean moving = next < path.length;
			if (moving)
			{
				int tx = path[next][0] * 128 + 64;
				int tz = path[next][1] * 128 + 64;
				px += Math.max(-speed, Math.min(speed, tx - px));
				pz += Math.max(-speed, Math.min(speed, tz - pz));
				if (px == tx && pz == tz)
				{
					next++;
				}
			}
			if (frame % 30 == 29)
			{
				// A game tick: whether they're off the middle of a tile is looked at once a tick.
				t.setWalkingOff(ID, !StillnessTracker.isCentred(px, pz));
			}
			t.advance(DT);
			if (guarded && t.isOffset(ID))
			{
				t.keepClear(ID, px, pz, moving, DT, true, walls);
			}
			double dx = px + t.dx(ID);
			double dz = pz + t.dz(ID);
			if (throughSomething(px, pz, dx, dz) && !t.isFollowing(ID) || throughSomething(lastX, lastZ, dx, dz))
			{
				run.framesThrough++;
			}
			if (blocked((int) Math.floor(dx / 128), (int) Math.floor(dz / 128)))
			{
				run.framesOnBlocked++;
			}
			double step = Math.hypot(dx - lastX, dz - lastZ);
			run.biggestStep = Math.max(run.biggestStep, step);
			run.biggestExcess = Math.max(run.biggestExcess, step - Math.hypot(px - fromX, pz - fromZ)
				- (moving ? OffsetTable.CATCH_UP : OffsetTable.WALK_SPEED) * DT);
			// (The last stride of a walk lands them where they are, as the walk ends: not a slide.)
			if (!moving && step >= 2 && !t.isWalking(ID) && !walkedLastFrame)
			{
				run.walkingWhileDrawnMovingStill = false;
			}
			walkedLastFrame = t.isWalking(ID);
			run.drawn.add(new double[]{dx, dz});
			lastX = dx;
			lastZ = dz;
			if (!t.isOffset(ID) && !moving)
			{
				run.framesUntilGone = frame;
				break;
			}
		}
		return run;
	}

	/** West along the inside of the north wall to the door in the room's north-west corner, out through it, and on. */
	private static final int[][] OUT_OF_THE_DOOR = {{13, 14}, {12, 14}, {11, 14}, {10, 14}, {10, 15}, {10, 16}, {10, 17}, {10, 18}, {11, 19}, {12, 20}};

	@Test
	public void runningOutOfTheDoorNeverCrossesTheWall()
	{
		// A room 10..20 by 8..14, the door in the north wall's west end; they stood at (14, 14) with
		// their spot most of two tiles east along the wall, and run west and out.
		room(10, 8, 20, 14, 10);
		Run base = walkOff(14, 14, 230, -20, OUT_OF_THE_DOOR, true, false);
		Assert.assertTrue("unguarded, they're drawn through the wall by the door", base.framesThrough > 0);

		Run run = walkOff(14, 14, 230, -20, OUT_OF_THE_DOOR, true, true);
		Assert.assertEquals("never drawn through the wall", 0, run.framesThrough);
		Assert.assertEquals("never on anything that can't be stood on", 0, run.framesOnBlocked);
		Assert.assertTrue("their offset runs out", run.framesUntilGone > 0);
	}

	@Test
	public void walkingOutOfTheDoorNeverCrossesTheWallEither()
	{
		// From a tile beside the door, every way their spot might be.
		room(10, 8, 20, 14, 10);
		int[][] path = {{10, 14}, {10, 15}, {10, 16}, {10, 17}, {11, 18}};
		Assert.assertTrue("unguarded, through the wall", walkOff(11, 14, 230, -20, path, false, false).framesThrough > 0);
		for (int[] spot : new int[][]{{230, -20}, {180, -120}, {-40, -150}, {150, 0}, {60, -200}})
		{
			Run run = walkOff(11, 14, spot[0], spot[1], path, false, true);
			Assert.assertEquals("spot " + spot[0] + "," + spot[1], 0, run.framesThrough);
			Assert.assertEquals(0, run.framesOnBlocked);
			Assert.assertTrue(run.framesUntilGone > 0);
		}
	}

	@Test
	public void runningAtAWallWithTheirSpotAheadStopsThemShortOfIt()
	{
		// Their spot is most of two tiles west of their tile, and they run west to the room's west wall
		// (tile 10), then north along it: unguarded they were drawn out through the wall.
		room(10, 8, 20, 14, 10);
		int[][] path = {{12, 11}, {11, 11}, {10, 11}, {10, 12}, {10, 13}, {10, 14}};
		Run base = walkOff(13, 11, -230, 30, path, true, false);
		Assert.assertTrue(base.framesOnBlocked > 0 || base.framesThrough > 0);

		Run run = walkOff(13, 11, -230, 30, path, true, true);
		Assert.assertEquals(0, run.framesThrough);
		Assert.assertEquals(0, run.framesOnBlocked);
		for (double[] d : run.drawn)
		{
			Assert.assertTrue("kept clear of the west wall", d[0] >= 10 * 128 + CollisionTerrain.EDGE_MARGIN - 1);
		}
	}

	@Test
	public void nobodyJumpsAndLegsKeepPace()
	{
		room(10, 8, 20, 14, 10);
		for (boolean running : new boolean[]{false, true})
		{
			Run run = walkOff(14, 14, 230, -20, OUT_OF_THE_DOOR, running, true);
			// No faster than their real self and the pace an offset runs out at: a walk, never a jump.
			Assert.assertTrue("faster by " + run.biggestExcess, run.biggestExcess <= 1.5);
			Assert.assertTrue("drawn walking whenever they move while their real self stands", run.walkingWhileDrawnMovingStill);
		}
	}

	@Test
	public void stoppingJustOutsideTheDoorTheyWalkTheRestOfTheWayRound()
	{
		// Out of the door and stop on the next tile: their real self stands outside while they are
		// still drawn inside; they walk round through the door rather than through the wall.
		room(10, 8, 20, 14, 10);
		int[][] path = {{13, 14}, {12, 14}, {11, 14}, {10, 14}, {10, 15}};
		Run run = walkOff(14, 14, 230, -20, path, true, true);
		Assert.assertEquals(0, run.framesThrough);
		Assert.assertEquals(0, run.framesOnBlocked);
		Assert.assertTrue(run.walkingWhileDrawnMovingStill);
		Assert.assertTrue(run.framesUntilGone > 0);
	}

	@Test
	public void standingOnTheFarSideOfAFenceTheyComeBackWithoutAJump()
	{
		// A fence along the east edge of column 15. Their spot is across it (it shouldn't be, but a
		// crowd's spot has been): there's no way round to follow, and they used to jump back over it.
		flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		for (int z = 10; z <= 20; z++)
		{
			wallEast(15, z);
		}
		int[][] path = {{14, 15}, {13, 15}, {12, 15}, {11, 15}, {10, 15}};
		for (boolean running : new boolean[]{false, true})
		{
			Run run = walkOff(15, 15, 120, 10, path, running, true);
			// No faster than following would take them (give or take a unit's rounding either frame), not most of a tile at once.
			Assert.assertTrue("faster by " + run.biggestExcess, run.biggestExcess <= 2.5);
			Assert.assertEquals(0, run.framesOnBlocked);
			Assert.assertTrue("their offset runs out", run.framesUntilGone > 0);
			double[] last = run.drawn.get(run.drawn.size() - 1);
			Assert.assertTrue("back on their side of it", last[0] < 16 * 128);
		}
	}

	@Test
	public void inTheOpenTheirOffsetRunsOutJustAsBefore()
	{
		flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		int[][] path = {{14, 15}, {13, 16}, {12, 17}, {12, 18}, {12, 19}};
		Run base = walkOff(15, 15, 200, -90, path, true, false);
		Run run = walkOff(15, 15, 200, -90, path, true, true);
		Assert.assertEquals(base.drawn.size(), run.drawn.size());
		for (int i = 0; i < base.drawn.size(); i++)
		{
			Assert.assertEquals(base.drawn.get(i)[0], run.drawn.get(i)[0], 0.0);
			Assert.assertEquals(base.drawn.get(i)[1], run.drawn.get(i)[1], 0.0);
		}
	}

	@Test
	public void aTeleportTakesTheOffsetAway()
	{
		flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		OffsetTable t = new OffsetTable();
		OffsetTable.Walls walls = new CollisionTerrain(flags).walls(0);
		t.setTarget(ID, 150, 0);
		for (int i = 0; i < 100; i++)
		{
			t.advance(DT);
			t.keepClear(ID, 1000, 1000, false, DT, false, walls);
		}
		t.clearTargets();
		t.advance(DT);
		t.keepClear(ID, 3000, 3000, false, DT, true, walls);
		Assert.assertFalse(t.isOffset(ID));
	}
}
