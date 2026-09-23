package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class LeanTest
{
	private static final int[] CX = new int[1024];
	private static final int[] CZ = new int[1024];

	private static float[] repeat(float v, int n)
	{
		float[] out = new float[n];
		java.util.Arrays.fill(out, v);
		return out;
	}

	private static double[] middle(float[] xs, float[] zs, int n)
	{
		return SpreadingDrawCallbacks.middle(xs, zs, n, CX, CZ);
	}

	@Test
	public void someoneStandingUprightIsSortedWhereTheyStand()
	{
		// Gear hangs a little off-centre; that's no lean.
		Assert.assertNull(SpreadingDrawCallbacks.turn(6, -8, 0));
	}

	@Test
	public void aBodyReachingForwardIsSortedWhereItIs()
	{
		double[] m = middle(repeat(0, 10), repeat(40, 10), 10);
		Assert.assertArrayEquals(new int[]{0, 40}, SpreadingDrawCallbacks.turn(m[0], m[1], 0));
	}

	@Test
	public void aRodHeldOutHardlyMovesTheMiddle()
	{
		// An upright fisher: 90 points of body round their feet, 10 points of rod far out in front.
		float[] zs = new float[100];
		for (int i = 0; i < 90; i++)
		{
			zs[i] = (i % 9) - 4;
		}
		for (int i = 90; i < 100; i++)
		{
			zs[i] = 150 + i;
		}
		double[] m = middle(new float[100], zs, 100);
		Assert.assertNull("no lean for a rod", SpreadingDrawCallbacks.turn(m[0], m[1], 0));
	}

	@Test
	public void aKneelingBodyStillLeans()
	{
		// Most of the body forward, the feet folded back behind.
		float[] zs = new float[100];
		for (int i = 0; i < 70; i++)
		{
			zs[i] = 30 + (i % 20);
		}
		for (int i = 70; i < 100; i++)
		{
			zs[i] = -30;
		}
		double[] m = middle(new float[100], zs, 100);
		int[] lean = SpreadingDrawCallbacks.turn(m[0], m[1], 0);
		Assert.assertNotNull(lean);
		Assert.assertTrue("leans forward: " + lean[1], lean[1] > SpreadingDrawCallbacks.LEAN_DEAD_ZONE);
	}

	@Test
	public void theLeanTurnsWithThem()
	{
		// Turned a quarter, the same reach points along the other axis, as the renderer turns it:
		// east = x cos + z sin, north = z cos - x sin.
		Assert.assertArrayEquals(new int[]{40, 0}, SpreadingDrawCallbacks.turn(0, 40, 512));
		Assert.assertArrayEquals(new int[]{0, -40}, SpreadingDrawCallbacks.turn(0, 40, 1024));
	}

	@Test
	public void aHugeReachCountsNoFartherThanTheLimit()
	{
		int[] lean = SpreadingDrawCallbacks.turn(0, 500, 0);
		Assert.assertEquals(0, lean[0]);
		Assert.assertEquals(SpreadingDrawCallbacks.MAX_LEAN, lean[1]);
	}

	@Test
	public void onlyTheVerticesInUseCount()
	{
		// The buffer can be longer than the model in it.
		float[] zs = new float[20];
		java.util.Arrays.fill(zs, 0, 5, 40);
		java.util.Arrays.fill(zs, 5, 20, -300);
		Assert.assertEquals(40, middle(new float[20], zs, 5)[1], 1e-9);
	}

	@Test
	public void aCastSettlesInsteadOfSwingingBackAndForth()
	{
		// Each frame of a cast the body swings 0 to 60 forward. Averaged, it holds near 30 rather
		// than jumping, so neighbours stop swapping places every frame.
		Leans leans = new Leans();
		double[] settled = null;
		for (int frame = 0; frame < 400; frame++)
		{
			settled = leans.settle(7, 623, 0, frame % 2 == 0 ? 0 : 60);
		}
		Assert.assertEquals(30, settled[1], 1);
		double[] next = leans.settle(7, 623, 0, 60);
		Assert.assertEquals("one more frame barely moves it", settled[1], next[1], 0.5);
	}

	@Test
	public void somethingElseStartsAFreshAverage()
	{
		Leans leans = new Leans();
		for (int frame = 0; frame < 50; frame++)
		{
			leans.settle(7, 623, 0, 60);
		}
		Assert.assertEquals(0, leans.settle(7, 897, 0, 0)[1], 1e-9);
	}
}
