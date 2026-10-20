package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class StillnessTrackerTest
{
	private static final long TILE_A = 10L;
	private static final long TILE_B = 11L;

	@Test
	public void standingInTheMiddleOfOneTileForTwoTicksCountsAsStill()
	{
		StillnessTracker t = new StillnessTracker();
		Assert.assertFalse("one tick is not enough", t.observe(7, TILE_A, true, 1));
		Assert.assertTrue(t.observe(7, TILE_A, true, 2));
		Assert.assertTrue("and stays still", t.observe(7, TILE_A, true, 3));
	}

	@Test
	public void beingBetweenTilesIsNeverStill()
	{
		StillnessTracker t = new StillnessTracker();
		Assert.assertFalse(t.observe(7, TILE_A, false, 1));
		Assert.assertFalse(t.observe(7, TILE_A, false, 2));
	}

	@Test
	public void walkingOntoANewTileResetsStillness()
	{
		StillnessTracker t = new StillnessTracker();
		t.observe(7, TILE_A, true, 1);
		Assert.assertTrue(t.observe(7, TILE_A, true, 2));
		Assert.assertFalse("arrived on a different tile", t.observe(7, TILE_B, true, 3));
		Assert.assertTrue("then waited a tick there", t.observe(7, TILE_B, true, 4));
	}

	@Test
	public void passingThroughTheCentreMidWalkDoesNotCount()
	{
		StillnessTracker t = new StillnessTracker();
		t.observe(7, TILE_A, true, 1);
		Assert.assertFalse(t.observe(7, TILE_A, false, 2));
		Assert.assertFalse("stillness must restart after moving", t.observe(7, TILE_A, true, 3));
	}

	@Test
	public void aMissedTickResetsStillness()
	{
		StillnessTracker t = new StillnessTracker();
		t.observe(7, TILE_A, true, 1);
		Assert.assertFalse("not seen on tick 2, so tick 3 starts over", t.observe(7, TILE_A, true, 3));
	}

	@Test
	public void clearForgetsEveryone()
	{
		StillnessTracker t = new StillnessTracker();
		t.observe(7, TILE_A, true, 1);
		t.clear();
		Assert.assertFalse(t.observe(7, TILE_A, true, 2));
	}

	@Test
	public void outOfRangeIdsAreIgnored()
	{
		StillnessTracker t = new StillnessTracker();
		Assert.assertFalse(t.observe(-1, TILE_A, true, 1));
		Assert.assertFalse(t.observe(OffsetTable.CAPACITY, TILE_A, true, 1));
	}

	private static final long TILE = StackRegistry.key(0, 50, 50);
	private static final int MIDDLE = 50 * 128 + 64;

	@Test
	public void itKnowsWhichWaySomeoneWalkedOrRanOntoTheirTile()
	{
		StillnessTracker t = new StillnessTracker();
		// Walking in from the west: between tiles on the tick before.
		t.observe(7, StackRegistry.key(0, 49, 50), false, 1, MIDDLE - 100, MIDDLE);
		t.observe(7, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertArrayEquals(new int[]{-100, 0}, t.cameFrom(7));
		Assert.assertTrue(t.observe(7, TILE, true, 3, MIDDLE, MIDDLE));
		Assert.assertArrayEquals("kept while they stand there", new int[]{-100, 0}, t.cameFrom(7));
		// Running in from two tiles north-east, in the middle of a tile on the tick before.
		t.observe(8, StackRegistry.key(0, 52, 52), true, 1, MIDDLE + 256, MIDDLE + 256);
		t.observe(8, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertArrayEquals(new int[]{256, 256}, t.cameFrom(8));
	}

	@Test
	public void appearingOnATileComesFromNowhere()
	{
		StillnessTracker t = new StillnessTracker();
		// A teleport.
		t.observe(7, StackRegistry.key(0, 40, 50), true, 1, MIDDLE - 1280, MIDDLE);
		t.observe(7, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertNull(t.cameFrom(7));
		// Logging in, or first seen there.
		t.observe(8, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertNull(t.cameFrom(8));
		// Up the stairs.
		t.observe(9, StackRegistry.key(1, 49, 50), false, 1, MIDDLE - 64, MIDDLE);
		t.observe(9, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertNull(t.cameFrom(9));
		// Not seen the tick before.
		t.observe(10, StackRegistry.key(0, 49, 50), false, 1, MIDDLE - 64, MIDDLE);
		t.observe(10, TILE, true, 3, MIDDLE, MIDDLE);
		Assert.assertNull(t.cameFrom(10));
		// Nor without being told where anyone is.
		t.observe(11, StackRegistry.key(0, 49, 50), false, 1);
		t.observe(11, TILE, true, 2);
		Assert.assertNull(t.cameFrom(11));
	}

	@Test
	public void lateArrivalsAreKnownBetweenTicksToo()
	{
		StillnessTracker t = new StillnessTracker();
		t.observe(7, StackRegistry.key(0, 51, 50), false, 1, MIDDLE + 70, MIDDLE);
		// Between ticks they reach the middle: still on their way, as far as ticks go.
		Assert.assertArrayEquals(new int[]{70, 0}, t.cameFromLately(7, TILE, MIDDLE, MIDDLE));
		t.observe(7, TILE, true, 2, MIDDLE, MIDDLE);
		Assert.assertArrayEquals(new int[]{70, 0}, t.cameFromLately(7, TILE, MIDDLE, MIDDLE));
		t.observe(7, TILE, true, 3, MIDDLE, MIDDLE);
		Assert.assertArrayEquals(new int[]{70, 0}, t.cameFromLately(7, TILE, MIDDLE, MIDDLE));
		t.observe(7, TILE, true, 4, MIDDLE, MIDDLE);
		Assert.assertNull("stood there a while now", t.cameFromLately(7, TILE, MIDDLE, MIDDLE));
		t.clear();
		Assert.assertNull(t.cameFrom(7));
	}

	@Test
	public void centreOfATileIsHalfATileIn()
	{
		Assert.assertTrue(StillnessTracker.isCentred(64, 64));
		Assert.assertTrue(StillnessTracker.isCentred(128 * 40 + 64, 128 * 7 + 64));
		Assert.assertFalse(StillnessTracker.isCentred(128 * 40 + 70, 128 * 7 + 64));
		Assert.assertFalse(StillnessTracker.isCentred(128 * 40, 128 * 7 + 64));
	}
}
