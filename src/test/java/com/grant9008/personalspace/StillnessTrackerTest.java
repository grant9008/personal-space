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

	@Test
	public void centreOfATileIsHalfATileIn()
	{
		Assert.assertTrue(StillnessTracker.isCentred(64, 64));
		Assert.assertTrue(StillnessTracker.isCentred(128 * 40 + 64, 128 * 7 + 64));
		Assert.assertFalse(StillnessTracker.isCentred(128 * 40 + 70, 128 * 7 + 64));
		Assert.assertFalse(StillnessTracker.isCentred(128 * 40, 128 * 7 + 64));
	}
}
