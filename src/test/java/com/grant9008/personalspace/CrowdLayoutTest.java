package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CrowdLayoutTest
{
	private static final int SIZE = 20;
	private static final int TX = 10;
	private static final int TZ = 10;
	private static final long TILE = StackRegistry.key(0, TX, TZ);
	private static final int CX = TX * 128 + 64;
	private static final int CZ = TZ * 128 + 64;

	private static int[][][] openGround()
	{
		int[][][] flags = new int[4][][];
		flags[0] = new int[SIZE][SIZE];
		return flags;
	}

	private static List<StackSpreader.Placement> stack(int count, int spacing, StackSpreader.Layout layout, int orientation)
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			in.add(new StackSpreader.Entry(i + 1, TILE, false, orientation));
		}
		return StackSpreader.place(in, true, 5, spacing, layout);
	}

	private static double minGap(List<StackSpreader.Placement> ps)
	{
		double min = Double.MAX_VALUE;
		for (int i = 0; i < ps.size(); i++)
		{
			for (int j = i + 1; j < ps.size(); j++)
			{
				min = Math.min(min, Math.hypot(ps.get(i).dx - ps.get(j).dx, ps.get(i).dz - ps.get(j).dz));
			}
		}
		return min;
	}

	@Test
	public void openGroundKeepsPlayersRoughlyASpacingApart()
	{
		CollisionTerrain terrain = new CollisionTerrain(openGround());
		List<StackSpreader.Placement> out = CrowdLayout.settle(stack(4, 80, StackSpreader.Layout.RING, -1),
			Collections.emptyList(), terrain, 80);
		Assert.assertEquals(4, out.size());
		Assert.assertTrue("gap was " + minGap(out), minGap(out) >= 70);
	}

	@Test
	public void nobodyIsDrawnInsideABlockedTileOrAgainstIt()
	{
		// A bank booth on the tile to the north.
		int[][][] flags = openGround();
		flags[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);

		List<StackSpreader.Placement> out = CrowdLayout.settle(stack(3, 96, StackSpreader.Layout.RING, -1),
			Collections.emptyList(), terrain, 96);
		for (StackSpreader.Placement p : out)
		{
			int z = CZ + p.dz;
			Assert.assertTrue("player " + p.id + " is in or against the booth: z offset " + p.dz,
				z <= TZ * 128 + 127 - CollisionTerrain.EDGE_MARGIN);
		}
	}

	@Test
	public void wallsAreNotCrossed()
	{
		// Wall along the east edge of the tile.
		int[][][] flags = openGround();
		flags[0][TX][TZ] |= CollisionTerrain.BLOCK_EAST;
		flags[0][TX + 1][TZ] |= CollisionTerrain.BLOCK_WEST;
		CollisionTerrain terrain = new CollisionTerrain(flags);

		List<StackSpreader.Placement> out = CrowdLayout.settle(stack(2, 140, StackSpreader.Layout.RING, -1),
			Collections.emptyList(), terrain, 140);
		for (StackSpreader.Placement p : out)
		{
			Assert.assertTrue("player " + p.id + " crossed the wall: x offset " + p.dx, CX + p.dx < (TX + 1) * 128);
		}
	}

	@Test
	public void crowdSpreadsAwayFromANeighbour()
	{
		CollisionTerrain terrain = new CollisionTerrain(openGround());
		List<CrowdLayout.Obstacle> neighbour = new ArrayList<>();
		neighbour.add(new CrowdLayout.Obstacle(0, CX + 128, CZ)); // someone standing on the tile to the east

		List<StackSpreader.Placement> out = CrowdLayout.settle(stack(2, 96, StackSpreader.Layout.RING, -1), neighbour, terrain, 96);
		for (StackSpreader.Placement p : out)
		{
			Assert.assertTrue("player " + p.id + " too close to the neighbour",
				Math.hypot(CX + p.dx - (CX + 128), CZ + p.dz - CZ) >= 80);
		}
	}

	@Test
	public void sameCrowdAlwaysSettlesTheSameWay()
	{
		CollisionTerrain terrain = new CollisionTerrain(openGround());
		List<StackSpreader.Placement> a = CrowdLayout.settle(stack(5, 72, StackSpreader.Layout.AUTO, 1024), Collections.emptyList(), terrain, 72);
		List<StackSpreader.Placement> b = CrowdLayout.settle(stack(5, 72, StackSpreader.Layout.AUTO, 1024), Collections.emptyList(), terrain, 72);
		for (int i = 0; i < a.size(); i++)
		{
			Assert.assertEquals(a.get(i).dx, b.get(i).dx);
			Assert.assertEquals(a.get(i).dz, b.get(i).dz);
		}
	}

	@Test
	public void playersFacingABoothStayInARowBesideEachOther()
	{
		// Booth to the north, everyone facing it (1024): they should stay in an east-west row.
		int[][][] flags = openGround();
		flags[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		flags[0][TX - 1][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		flags[0][TX + 1][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);

		List<StackSpreader.Placement> out = CrowdLayout.settle(stack(3, 72, StackSpreader.Layout.AUTO, 1024),
			Collections.emptyList(), terrain, 72);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		for (StackSpreader.Placement p : out)
		{
			minX = Math.min(minX, p.dx);
			maxX = Math.max(maxX, p.dx);
			Assert.assertTrue("row drifted north/south: " + p.dz, Math.abs(p.dz) <= 40);
		}
		Assert.assertTrue("row should be spread east-west", maxX - minX >= 120);
	}

	@Test
	public void aRowAtABoothDoesNotTurnIntoAQueue()
	{
		// Five players facing a booth to the north, widest spacing, with still players standing
		// just behind them pushing forward: nobody is drawn more than a quarter tile back, nobody
		// goes past the booth, and nobody wanders more than a tile and a bit away.
		CollisionTerrain terrain = new CollisionTerrain(openGround()); // even with no collision data at all
		java.util.Set<Long> none = java.util.Collections.emptySet();
		java.util.Map<Long, Double> facing = new java.util.HashMap<>();
		List<StackSpreader.Entry> in = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			in.add(new StackSpreader.Entry(i + 1, TILE, false, 1024));
		}
		List<StackSpreader.Placement> seeds = StackSpreader.place(in, true, 5, PersonalSpaceConfig.MAX_SPACING,
			StackSpreader.Layout.AUTO, none, new java.util.HashSet<>(), facing);
		Assert.assertTrue("the tile is a row", facing.containsKey(TILE));

		List<CrowdLayout.Obstacle> queue = new ArrayList<>();
		queue.add(new CrowdLayout.Obstacle(0, CX, CZ - 128));
		queue.add(new CrowdLayout.Obstacle(0, CX - 128, CZ - 128));
		queue.add(new CrowdLayout.Obstacle(0, CX + 128, CZ - 128));

		List<StackSpreader.Placement> out = CrowdLayout.settle(seeds, queue, terrain, PersonalSpaceConfig.MAX_SPACING, facing);
		for (StackSpreader.Placement p : out)
		{
			Assert.assertTrue("player " + p.id + " pushed back into the queue: " + p.dz, p.dz >= -CrowdLayout.ROW_BACK - 1);
			Assert.assertTrue("player " + p.id + " went past the booth: " + p.dz, p.dz <= CrowdLayout.ROW_FORWARD + 1);
			Assert.assertTrue("player " + p.id + " wandered off", Math.hypot(p.dx, p.dz) <= CrowdLayout.ROW_REACH + 1);
		}
	}

	@Test
	public void circleStillKeepsPeopleOutOfBooths()
	{
		int[][][] flags = openGround();
		flags[0][TX][TZ + 1] = CollisionTerrain.BLOCK_FULL;
		CollisionTerrain terrain = new CollisionTerrain(flags);
		List<StackSpreader.Placement> ring = stack(4, 200, StackSpreader.Layout.RING, -1);
		for (StackSpreader.Placement p : CrowdLayout.keepStandable(ring, terrain))
		{
			Assert.assertTrue("player " + p.id + " inside the booth: " + p.dz,
				CZ + p.dz <= TZ * 128 + 127 - CollisionTerrain.EDGE_MARGIN);
		}
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

	@Test
	public void walkingFacesTheWayItMoves()
	{
		Assert.assertEquals(0, OffsetTable.facing(0, -1));     // south
		Assert.assertEquals(512, OffsetTable.facing(-1, 0));   // west
		Assert.assertEquals(1024, OffsetTable.facing(0, 1));   // north
		Assert.assertEquals(1536, OffsetTable.facing(1, 0));   // east
	}

	@Test
	public void walkMovesAtWalkingPaceAndStopsOnTheSpot()
	{
		OffsetTable t = new OffsetTable();
		t.setTarget(5, 128, 0);
		t.advance(0.3f);
		Assert.assertTrue(t.isWalking(5));
		Assert.assertEquals("half a tile in 0.3 s", 64, t.dx(5), 2);
		Assert.assertEquals(1536, t.walkOrientation(5));
		t.advance(0.4f);
		Assert.assertFalse("arrived", t.isWalking(5));
		Assert.assertEquals(128, t.dx(5));
	}

	@Test
	public void smallCorrectionsDriftWithoutWalking()
	{
		OffsetTable t = new OffsetTable();
		t.setTarget(5, 10, 0);
		t.advance(0.016f);
		Assert.assertFalse("a 10-unit nudge shouldn't start a walk", t.isWalking(5));
		t.setTarget(6, 90, 0);
		t.advance(0.016f);
		Assert.assertTrue(t.isWalking(6));
	}
}
