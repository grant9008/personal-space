package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class DrawOrderTest
{
	@Test
	public void swingingTheCameraDoesntSwapPeopleStandingSideBySide()
	{
		// Last frame: 0 drawn first (farther), then 1. The camera has swung a little and 1 is now a
		// hair farther: they stay as they were.
		int[] order = {0, 1};
		SpreadingDrawCallbacks.settle(order, new double[]{1000, 1000 + SpreadingDrawCallbacks.SORT_TOLERANCE - 4}, 2, SpreadingDrawCallbacks.SORT_TOLERANCE);
		Assert.assertArrayEquals(new int[]{0, 1}, order);
		// Swung further, 1 is clearly farther now: they swap.
		order = new int[]{0, 1};
		SpreadingDrawCallbacks.settle(order, new double[]{1000, 1000 + SpreadingDrawCallbacks.SORT_TOLERANCE + 8}, 2, SpreadingDrawCallbacks.SORT_TOLERANCE);
		Assert.assertArrayEquals(new int[]{1, 0}, order);
	}

	@Test
	public void someoneNewIsPutWhereTheyBelong()
	{
		// Three already in order, far to near; someone new (last in the list) farther than all of them.
		int[] order = {0, 1, 2, 3};
		SpreadingDrawCallbacks.settle(order, new double[]{1200, 1100, 1000, 1300}, 4, SpreadingDrawCallbacks.SORT_TOLERANCE);
		Assert.assertArrayEquals(new int[]{3, 0, 1, 2}, order);
	}

	@Test
	public void peopleApartOnScreenAreSortedFreelyButOverlappingOnesHold()
	{
		// 0 was drawn first, 1 is now a little farther. Apart on screen: they swap, as nobody can see
		// it. Overlapping: they hold until 1 is clearly farther.
		double[] apart = {0, 1, 0, 1, 1, 5, 6, 0, 1, 1};
		double[] overlapping = {0, 1, 0, 1, 1, 0.5, 1.5, 0, 1, 1};
		double[] distance = {1000, 1010};
		int[] order = {0, 1};
		SpreadingDrawCallbacks.settle(order, distance, 2, SpreadingDrawCallbacks.SORT_TOLERANCE, null, apart);
		Assert.assertArrayEquals(new int[]{1, 0}, order);
		order = new int[]{0, 1};
		SpreadingDrawCallbacks.settle(order, distance, 2, SpreadingDrawCallbacks.SORT_TOLERANCE, null, overlapping);
		Assert.assertArrayEquals(new int[]{0, 1}, order);
		order = new int[]{0, 1};
		SpreadingDrawCallbacks.settle(order, new double[]{1000, 1000 + SpreadingDrawCallbacks.SORT_TOLERANCE + 1}, 2,
			SpreadingDrawCallbacks.SORT_TOLERANCE, null, overlapping);
		Assert.assertArrayEquals("clearly farther now", new int[]{1, 0}, order);
	}

	@Test
	public void someoneNewIsPlacedWhereTheyBelongAtOnce()
	{
		int[] order = {0, 1, 2};
		boolean[] fresh = {false, false, true};
		SpreadingDrawCallbacks.settle(order, new double[]{1000, 990, 1005}, 3, SpreadingDrawCallbacks.SORT_TOLERANCE, fresh, null);
		Assert.assertArrayEquals(new int[]{2, 0, 1}, order);
	}

	@Test
	public void someoneWalkingOffTurnsGraduallyTheShortWayRound()
	{
		Assert.assertEquals(48, SpreadingDrawCallbacks.turnTowards(0, 512, 48));
		Assert.assertEquals(2000, SpreadingDrawCallbacks.turnTowards(0, 1536, 48));
		Assert.assertEquals(510, SpreadingDrawCallbacks.turnTowards(500, 510, 48));
	}

	@Test
	public void aMovingPlayerIsFiledUnderTheTileTheyAreSteppingOnto()
	{
		// Tile (10, 10)'s middle is (1344, 1344).
		Assert.assertArrayEquals(new int[]{10, 10, 10, 11}, SpreadingDrawCallbacks.movingSpan(1344, 1344, 1024)); // north
		Assert.assertArrayEquals(new int[]{10, 9, 10, 10}, SpreadingDrawCallbacks.movingSpan(1344, 1344, 0)); // south
		Assert.assertArrayEquals(new int[]{9, 10, 10, 10}, SpreadingDrawCallbacks.movingSpan(1344, 1344, 512)); // west
		// Half-way east across the edge of tile 10 into 11, stepping on into 12.
		Assert.assertArrayEquals(new int[]{10, 10, 12, 10}, SpreadingDrawCallbacks.movingSpan(1408, 1344, 1536));
		// North-east: both ways.
		Assert.assertArrayEquals(new int[]{10, 10, 11, 11}, SpreadingDrawCallbacks.movingSpan(1344, 1344, 1280));
	}

	@Test
	public void someoneDealingWithAClerkFacesHimFromWhereTheyreDrawn()
	{
		// The game's orientations: 0 faces south, 512 west, 1024 north, 1536 east.
		Assert.assertEquals("clerk due south", 0, SpreadingDrawCallbacks.facingToward(0, 0, 0, -256, -1));
		Assert.assertEquals("clerk due west", 512, SpreadingDrawCallbacks.facingToward(0, 0, -256, 0, -1));
		Assert.assertEquals("clerk due north", 1024, SpreadingDrawCallbacks.facingToward(0, 0, 0, 256, -1));
		Assert.assertEquals("clerk due east", 1536, SpreadingDrawCallbacks.facingToward(0, 0, 256, 0, -1));
		// Drawn a third of a tile east of where they stand, they turn a little west, towards him.
		int turned = SpreadingDrawCallbacks.facingToward(42, 0, 0, 256, -1);
		Assert.assertTrue("turned towards the clerk: " + turned, turned < 1024 && turned > 1024 - 128);
		Assert.assertEquals("on top of them: their own rule", -1, SpreadingDrawCallbacks.facingToward(5, 5, 5, 5, -1));
		Assert.assertEquals("too far: their own rule", -1, SpreadingDrawCallbacks.facingToward(0, 0, 0, 20 * 128, -1));
	}

	@Test
	public void onlySomeoneDrawnAwayFromWhereTheyStandStepsAsideForTheClick()
	{
		Assert.assertTrue("drawn beside the fire", PersonalSpacePlugin.movedAway(7, 85, 0));
		Assert.assertTrue(PersonalSpacePlugin.movedAway(7, 0, -40));
		Assert.assertFalse("standing where they are", PersonalSpacePlugin.movedAway(7, 0, 0));
		Assert.assertFalse("no such player", PersonalSpacePlugin.movedAway(-1, 85, 0));
	}
}
