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
}
