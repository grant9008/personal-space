package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class StackRegistryTest
{
	@Test
	public void keysAreDistinctAcrossPlaneAndBothAxes()
	{
		Set<Long> keys = new HashSet<>();
		for (int plane = 0; plane < 4; plane++)
		{
			for (int x = 0; x < 200; x += 7)
			{
				for (int y = 0; y < 200; y += 7)
				{
					Assert.assertTrue("duplicate key for " + plane + "," + x + "," + y, keys.add(StackRegistry.key(plane, x, y)));
				}
			}
		}
		Assert.assertNotEquals(StackRegistry.key(0, 1, 2), StackRegistry.key(0, 2, 1));
	}

	@Test
	public void aBoatsDeckHasLayersOfItsOwn()
	{
		Assert.assertEquals("the main world's layers are its planes", 2, StackRegistry.layer(net.runelite.api.WorldView.TOPLEVEL, 2));
		int deck = StackRegistry.layer(7, 1);
		Assert.assertEquals(1, StackRegistry.planeOf(deck));
		Assert.assertEquals(7, StackRegistry.worldViewOf(deck));
		long onDeck = StackRegistry.key(deck, 50, 50);
		Assert.assertNotEquals("the same tile on a boat is not the world's", StackRegistry.key(1, 50, 50), onDeck);
		Assert.assertEquals(deck, StackRegistry.plane(onDeck));
		Assert.assertEquals(50, StackRegistry.sceneX(onDeck));
		Assert.assertEquals(50, StackRegistry.sceneY(onDeck));
		Assert.assertEquals(0, StackRegistry.worldViewOf(StackRegistry.plane(StackRegistry.key(3, 9, 9))));
	}

	@Test
	public void emptyUntilRebuiltAndNeverReturnsNull()
	{
		StackRegistry r = new StackRegistry();
		Assert.assertTrue(r.isEmpty());
		Assert.assertEquals(0, r.membersAt(StackRegistry.key(0, 10, 10)).length);
	}

	@Test
	public void rebuildGroupsPlacementsByTile()
	{
		long a = StackRegistry.key(0, 40, 50);
		long b = StackRegistry.key(0, 41, 50);
		List<StackSpreader.Placement> placements = new ArrayList<>();
		placements.add(new StackSpreader.Placement(3, a, 32, 0));
		placements.add(new StackSpreader.Placement(9, a, -32, 0));
		placements.add(new StackSpreader.Placement(5, b, 32, 0));

		StackRegistry r = new StackRegistry();
		r.rebuild(placements);
		Assert.assertFalse(r.isEmpty());
		Assert.assertArrayEquals(new int[]{3, 9}, r.membersAt(a));
		Assert.assertArrayEquals(new int[]{5}, r.membersAt(b));
		Assert.assertEquals(0, r.membersAt(StackRegistry.key(1, 40, 50)).length);
	}

	@Test
	public void rebuildReplacesTheOldTable()
	{
		long a = StackRegistry.key(0, 40, 50);
		long b = StackRegistry.key(0, 60, 60);
		StackRegistry r = new StackRegistry();
		List<StackSpreader.Placement> first = new ArrayList<>();
		first.add(new StackSpreader.Placement(3, a, 32, 0));
		r.rebuild(first);
		List<StackSpreader.Placement> second = new ArrayList<>();
		second.add(new StackSpreader.Placement(4, b, 32, 0));
		r.rebuild(second);
		Assert.assertEquals("old tile gone", 0, r.membersAt(a).length);
		Assert.assertArrayEquals(new int[]{4}, r.membersAt(b));

		r.rebuild(new ArrayList<>());
		Assert.assertTrue(r.isEmpty());
	}

	@Test
	public void clearEmptiesTheTable()
	{
		long a = StackRegistry.key(0, 40, 50);
		List<StackSpreader.Placement> placements = new ArrayList<>();
		placements.add(new StackSpreader.Placement(3, a, 32, 0));
		StackRegistry r = new StackRegistry();
		r.rebuild(placements);
		r.clear();
		Assert.assertTrue(r.isEmpty());
		Assert.assertEquals(0, r.membersAt(a).length);
	}

	@Test
	public void playersRoundAFireFaceItAndCurvedRowsTurnIn()
	{
		long fireTile = StackRegistry.key(0, 2, 2);
		long rowTile = StackRegistry.key(0, 3, 3);
		StackRegistry r = new StackRegistry();
		r.rebuild(new ArrayList<>(), java.util.Collections.singleton(rowTile), java.util.Collections.emptySet(),
			java.util.Collections.singletonMap(fireTile, new int[]{128, 0}));
		Assert.assertEquals("west of the fire, facing east", 1536, r.drawOrientation(fireTile, 0, -80, 0));
		Assert.assertEquals(StackSpreader.faceSameSpot(1024, 60, 0), r.drawOrientation(rowTile, 1024, 60, 0));
		Assert.assertEquals("anywhere else, their own facing", 700, r.drawOrientation(StackRegistry.key(0, 9, 9), 700, 60, 0));
	}

	@Test
	public void posedPairsFaceOrAngleTowardsEachOther()
	{
		long facing = StackRegistry.key(0, 4, 4);
		long angled = StackRegistry.key(0, 5, 5);
		java.util.Map<Long, PersonalSpaceConfig.Pose> poses = new java.util.HashMap<>();
		poses.put(facing, PersonalSpaceConfig.Pose.FACING);
		poses.put(angled, PersonalSpaceConfig.Pose.ANGLED);
		StackRegistry r = new StackRegistry();
		r.rebuild(new ArrayList<>(), java.util.Collections.emptySet(), java.util.Collections.emptySet(), java.util.Collections.emptyMap(), poses);
		// Drawn east of the middle: facing each other means facing west.
		Assert.assertEquals(512, r.drawOrientation(facing, 0, 80, 0));
		Assert.assertEquals(1536, r.drawOrientation(facing, 0, -80, 0));
		// Both really facing south (the camera): angled turns each halfway in.
		Assert.assertEquals(256, r.drawOrientation(angled, 0, 80, 0));
		Assert.assertEquals(1792, r.drawOrientation(angled, 0, -80, 0));
		Assert.assertEquals("someone in the middle keeps their facing", 300, r.drawOrientation(facing, 300, 0, 0));
	}

	@Test
	public void playersWithoutASpotAreRemembered()
	{
		long tile = StackRegistry.key(0, 1, 1);
		List<StackSpreader.Placement> members = new ArrayList<>();
		members.add(new StackSpreader.Placement(1, tile, 40, 0));
		members.add(new StackSpreader.Placement(2, tile, 0, 0));
		StackRegistry r = new StackRegistry();
		r.rebuild(members, java.util.Collections.emptySet(), java.util.Collections.singleton(2));
		Assert.assertTrue(r.isUnplaced(2));
		Assert.assertFalse(r.isUnplaced(1));
		r.clear();
		Assert.assertFalse(r.isUnplaced(2));
	}

	@Test
	public void plannedPlayersCarryTheirTileIntoTheRegistry()
	{
		long tile = StackRegistry.key(0, 12, 34);
		List<StackSpreader.Entry> entries = new ArrayList<>();
		entries.add(new StackSpreader.Entry(1, tile, true, 1024));
		entries.add(new StackSpreader.Entry(2, tile, false, 0));
		entries.add(new StackSpreader.Entry(3, tile, false, 512));

		CrowdPlanner.Surroundings open = new CrowdPlanner.Surroundings()
		{
			@Override
			public boolean canStand(long t, int dx, int dz)
			{
				return true;
			}

			@Override
			public boolean facesObstacle(long t, double angle)
			{
				return false;
			}

			@Override
			public boolean isCounter(long t, double angle)
			{
				return false;
			}

			@Override
			public boolean facesFire(long t, double angle)
			{
				return false;
			}

			@Override
			public List<int[]> firesNear(long t)
			{
				return new ArrayList<>();
			}
		};
		CrowdPlanner.Plan plan = new CrowdPlanner().plan(entries, id -> true, 32, 5, true, false, 1, open);
		StackRegistry r = new StackRegistry();
		r.rebuild(plan.placements);
		Assert.assertArrayEquals("local player stays put, the other two are placed on the tile", new int[]{2, 3}, r.membersAt(tile));
	}
}
