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
		t.advance(0.3f, PersonalSpaceConfig.Movement.WALK);
		Assert.assertTrue(t.isWalking(5));
		Assert.assertEquals("half a tile in 0.3 s", 64, t.dx(5), 2);
		Assert.assertEquals(1536, t.walkOrientation(5));
		t.advance(0.4f, PersonalSpaceConfig.Movement.WALK);
		Assert.assertFalse("arrived", t.isWalking(5));
		Assert.assertEquals(128, t.dx(5));
	}

	@Test
	public void smallCorrectionsDriftWithoutWalking()
	{
		OffsetTable t = new OffsetTable();
		t.setTarget(5, 10, 0);
		t.advance(0.016f, PersonalSpaceConfig.Movement.WALK);
		Assert.assertFalse("a 10-unit nudge shouldn't start a walk", t.isWalking(5));
		t.setTarget(6, 90, 0);
		t.advance(0.016f, PersonalSpaceConfig.Movement.WALK);
		Assert.assertTrue(t.isWalking(6));
	}

	@Test
	public void slowerWalkSpeedMovesLessPerFrame()
	{
		OffsetTable full = new OffsetTable();
		OffsetTable slow = new OffsetTable();
		full.setTarget(5, 128, 0);
		slow.setTarget(5, 128, 0);
		full.advance(0.3f, PersonalSpaceConfig.Movement.WALK, 1f);
		slow.advance(0.3f, PersonalSpaceConfig.Movement.WALK, 0.5f);
		Assert.assertEquals(64, full.dx(5), 2);
		Assert.assertEquals(32, slow.dx(5), 2);
		Assert.assertEquals("animation slows down too", full.walkSeconds(5) / 2, slow.walkSeconds(5), 0.01);
	}

	@Test
	public void instantJumpsStraightThere()
	{
		OffsetTable t = new OffsetTable();
		t.setTarget(5, 100, -40);
		t.advance(0.016f, PersonalSpaceConfig.Movement.INSTANT);
		Assert.assertEquals(100, t.dx(5));
		Assert.assertEquals(-40, t.dz(5));
		Assert.assertFalse(t.isWalking(5));
	}
}
