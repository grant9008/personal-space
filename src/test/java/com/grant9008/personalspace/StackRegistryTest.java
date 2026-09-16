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
	public void spreaderPlacementsCarryTheirTileIntoTheRegistry()
	{
		long tile = StackRegistry.key(0, 12, 34);
		List<StackSpreader.Entry> entries = new ArrayList<>();
		entries.add(new StackSpreader.Entry(1, tile, true));
		entries.add(new StackSpreader.Entry(2, tile, false));
		entries.add(new StackSpreader.Entry(3, tile, false));

		StackRegistry r = new StackRegistry();
		r.rebuild(StackSpreader.place(entries, false, 5, 32));
		Assert.assertArrayEquals("local player excluded, the other two are on the tile", new int[]{2, 3}, r.membersAt(tile));
	}
}
