package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class CollisionTerrainTest
{
	private static final int SIZE = 20;
	private static final int TX = 10;
	private static final int TZ = 10;
	private static final int CX = TX * 128 + 64;
	private static final int CZ = TZ * 128 + 64;

	private static int[][][] openGround()
	{
		int[][][] flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		return flags;
	}

	@Test
	public void nobodyIsDrawnInsideABlockedTileOrAgainstIt()
	{
		// A bank booth on the tile to the north.
		int[][][] flags = openGround();
		flags[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		Assert.assertFalse("inside the booth", terrain.canStand(0, CX, CZ, CX, CZ + 128));
		Assert.assertFalse("pressed against the booth", terrain.canStand(0, CX, CZ, CX, CZ + 60));
		Assert.assertTrue(terrain.canStand(0, CX, CZ, CX, CZ + 20));
		Assert.assertTrue("open to the south", terrain.canStand(0, CX, CZ, CX, CZ - 100));
	}

	@Test
	public void wallsAreNotCrossed()
	{
		// Wall along the east edge of the tile.
		int[][][] flags = openGround();
		flags[0][TX][TZ] |= CollisionTerrain.BLOCK_EAST;
		flags[0][TX + 1][TZ] |= CollisionTerrain.BLOCK_WEST;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		Assert.assertFalse("across the wall", terrain.canStand(0, CX, CZ, CX + 100, CZ));
		Assert.assertFalse("against the wall", terrain.canStand(0, CX, CZ, CX + 50, CZ));
		Assert.assertTrue(terrain.canStand(0, CX, CZ, CX - 100, CZ));
	}

	@Test
	public void aStraightLineStopsAtWallsAndBlockedTilesButGoesThroughADoor()
	{
		// A wall along the north edge of row TZ, with a door in it at column TX.
		int[][][] flags = openGround();
		for (int x = TX - 3; x <= TX + 3; x++)
		{
			if (x != TX)
			{
				flags[0][x][TZ] |= CollisionTerrain.BLOCK_NORTH;
				flags[0][x][TZ + 1] |= CollisionTerrain.BLOCK_SOUTH;
			}
		}
		flags[0][TX + 5][TZ] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		Assert.assertTrue("through the door", terrain.clearLine(0, CX, CZ, CX + 20, CZ + 128));
		Assert.assertFalse("through the wall beside it", terrain.clearLine(0, CX + 128, CZ, CX + 128, CZ + 128));
		Assert.assertFalse("slanting out through the wall", terrain.clearLine(0, CX + 200, CZ, CX + 100, CZ + 256));
		Assert.assertTrue("along the inside of the wall", terrain.clearLine(0, CX - 300, CZ + 40, CX + 300, CZ + 40));
		Assert.assertFalse("through the booth", terrain.clearLine(0, CX + 4 * 128, CZ, CX + 6 * 128, CZ));
		Assert.assertFalse("from inside it", terrain.clearLine(0, CX + 5 * 128, CZ, CX + 6 * 128, CZ));
		Assert.assertTrue("the same spot", terrain.clearLine(0, CX, CZ, CX, CZ));
		// Exactly through the corner where the wall starts beside the door: it takes both ways round it.
		Assert.assertFalse("diagonally past the end of the wall", terrain.clearLine(0, CX, CZ, CX + 128, CZ + 128));
		Assert.assertTrue("straight out of the door", terrain.clearLine(0, CX, CZ, CX, CZ + 256));
	}

	@Test
	public void roomForABodyKeepsClearOfBlockedEdges()
	{
		int[][][] flags = openGround();
		flags[0][TX][TZ] |= CollisionTerrain.BLOCK_EAST;
		flags[0][TX + 1][TZ] |= CollisionTerrain.BLOCK_WEST;
		flags[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		Assert.assertTrue(terrain.roomAt(0, CX, CZ));
		Assert.assertFalse("against the wall", terrain.roomAt(0, CX + 50, CZ));
		Assert.assertFalse("against the booth", terrain.roomAt(0, CX, CZ + 50));
		Assert.assertFalse("in the booth", terrain.roomAt(0, CX, CZ + 128));
		Assert.assertTrue(new CollisionTerrain(new int[4][][]).roomAt(0, CX, CZ));
	}

	@Test
	public void noMapMeansNoLimits()
	{
		Assert.assertTrue(new CollisionTerrain(new int[4][][]).canStand(0, CX, CZ, CX + 300, CZ));
	}

	@Test
	public void facingAnAnvilOrAWallCountsButOpenFloorDoesNot()
	{
		int[][][] anvil = openGround();
		anvil[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		Assert.assertTrue(new CollisionTerrain(anvil).facesObstacle(0, TX, TZ, Math.PI));
		Assert.assertFalse("facing away from it", new CollisionTerrain(anvil).facesObstacle(0, TX, TZ, 0));

		int[][][] wall = openGround();
		wall[0][TX][TZ] |= CollisionTerrain.BLOCK_EAST;
		Assert.assertTrue(new CollisionTerrain(wall).facesObstacle(0, TX, TZ, 3 * Math.PI / 2));

		Assert.assertFalse(new CollisionTerrain(openGround()).facesObstacle(0, TX, TZ, Math.PI));
	}

	@Test
	public void aRowOfBoothsIsACounterButASingleAnvilIsNot()
	{
		// Facing north (pi) from the tile at (TX, TZ).
		int[][][] booths = openGround();
		for (int x = TX - 2; x <= TX + 2; x++)
		{
			booths[0][x][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		}
		Assert.assertTrue(new CollisionTerrain(booths).isCounter(0, TX, TZ, Math.PI));

		int[][][] anvil = openGround();
		anvil[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		Assert.assertFalse(new CollisionTerrain(anvil).isCounter(0, TX, TZ, Math.PI));

		Assert.assertFalse("open floor", new CollisionTerrain(openGround()).isCounter(0, TX, TZ, Math.PI));
		Assert.assertFalse("facing along the counter, not at it", new CollisionTerrain(booths).isCounter(0, TX, TZ, Math.PI / 2));
	}

	@Test
	public void aCounterWallCountsToo()
	{
		// A wall along the north edge of three tiles in a row.
		int[][][] flags = openGround();
		for (int x = TX - 1; x <= TX + 1; x++)
		{
			flags[0][x][TZ] |= CollisionTerrain.BLOCK_NORTH;
			flags[0][x][TZ + 1] |= CollisionTerrain.BLOCK_SOUTH;
		}
		Assert.assertTrue(new CollisionTerrain(flags).isCounter(0, TX, TZ, Math.PI));
	}

	@Test
	public void terrainStepRules()
	{
		int[][] f = new int[3][3];
		Assert.assertTrue(CollisionTerrain.canStep(f, 1, 1, 1, 0));
		f[2][1] = CollisionTerrain.BLOCK_FULL;
		Assert.assertFalse("can't step onto a blocked tile", CollisionTerrain.canStep(f, 1, 1, 1, 0));
		f[2][1] = 0;
		f[1][1] = CollisionTerrain.BLOCK_NORTH;
		Assert.assertFalse("wall on the north edge", CollisionTerrain.canStep(f, 1, 1, 0, 1));
		Assert.assertFalse("diagonal past that wall", CollisionTerrain.canStep(f, 1, 1, 1, 1));
		Assert.assertTrue(CollisionTerrain.canStep(f, 1, 1, 0, -1));
		Assert.assertFalse("off the map", CollisionTerrain.canStep(f, 2, 2, 1, 0));
	}
}
