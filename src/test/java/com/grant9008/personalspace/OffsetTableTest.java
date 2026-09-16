package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class OffsetTableTest
{
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
