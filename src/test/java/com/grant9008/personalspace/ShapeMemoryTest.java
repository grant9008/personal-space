package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ShapeMemoryTest
{
	private static final long TILE = 7L;

	private static List<StackSpreader.Entry> group(int... idAndOrientation)
	{
		List<StackSpreader.Entry> g = new ArrayList<>();
		for (int i = 0; i < idAndOrientation.length; i += 2)
		{
			g.add(new StackSpreader.Entry(idAndOrientation[i], TILE, false, idAndOrientation[i + 1]));
		}
		return g;
	}

	private static ShapeMemory.Shape tick(ShapeMemory memory, List<StackSpreader.Entry> group)
	{
		memory.startTick();
		return memory.decide(TILE, group, true);
	}

	@Test
	public void aPlayerTurningDoesNotChangeTheShape()
	{
		ShapeMemory memory = new ShapeMemory();
		ShapeMemory.Shape first = tick(memory, group(1, 1024, 2, 1024));
		Assert.assertTrue("both face north: a row", first.row);

		// Player 2 starts casting and spins round to face south, then east.
		ShapeMemory.Shape turned = tick(memory, group(1, 1024, 2, 0));
		Assert.assertTrue("same people: still a row", turned.row);
		Assert.assertEquals(first.angle, turned.angle, 1e-9);
		ShapeMemory.Shape turnedAgain = tick(memory, group(1, 1024, 2, 1536));
		Assert.assertEquals(first.angle, turnedAgain.angle, 1e-9);
	}

	@Test
	public void aCrowdStaysACrowdWhileTheSamePeopleAreThere()
	{
		ShapeMemory memory = new ShapeMemory();
		Assert.assertFalse(tick(memory, group(1, 0, 2, 1024)).row);
		Assert.assertFalse("they happen to face the same way now, but nobody came or went",
			tick(memory, group(1, 1024, 2, 1024)).row);
	}

	@Test
	public void someoneArrivingLetsTheShapeBeReconsidered()
	{
		ShapeMemory memory = new ShapeMemory();
		Assert.assertFalse(tick(memory, group(1, 0, 2, 1024)).row);
		Assert.assertTrue(tick(memory, group(1, 1024, 2, 1024, 3, 1024)).row);
	}

	@Test
	public void aRowKeepsItsDirectionThroughSmallChangesWhenSomeoneArrives()
	{
		ShapeMemory memory = new ShapeMemory();
		ShapeMemory.Shape first = tick(memory, group(1, 1024, 2, 1024));
		// A newcomer facing a little off to one side: the row doesn't swing round for them.
		ShapeMemory.Shape after = tick(memory, group(1, 1024, 2, 1024, 3, 1150));
		Assert.assertTrue(after.row);
		Assert.assertEquals(first.angle, after.angle, 1e-9);
	}

	@Test
	public void circleArrangementNeverMakesRows()
	{
		ShapeMemory memory = new ShapeMemory();
		memory.startTick();
		Assert.assertFalse(memory.decide(TILE, group(1, 1024, 2, 1024), false).row);
	}

	@Test
	public void angleBetweenWrapsRound()
	{
		Assert.assertEquals(Math.toRadians(20), ShapeMemory.angleBetween(Math.toRadians(350), Math.toRadians(10)), 1e-9);
		Assert.assertEquals(Math.PI, ShapeMemory.angleBetween(0, Math.PI), 1e-9);
	}
}
