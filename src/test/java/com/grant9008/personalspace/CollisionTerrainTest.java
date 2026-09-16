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
