package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class StackSpreaderTest
{
	private static final long TILE_A = 1L;
	private static final long TILE_B = 2L;

	@Test
	public void stackedTilesCountsOnlyTilesWithTwoOrMore()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		Assert.assertEquals(0, StackSpreader.stackedTiles(in));
		in.add(new StackSpreader.Entry(1, TILE_A, false));
		in.add(new StackSpreader.Entry(2, TILE_B, false));
		Assert.assertEquals(0, StackSpreader.stackedTiles(in));
		in.add(new StackSpreader.Entry(3, TILE_A, false));
		Assert.assertEquals(1, StackSpreader.stackedTiles(in));
		in.add(new StackSpreader.Entry(4, TILE_A, false));
		Assert.assertEquals("a third player on the same tile is still one stacked tile", 1, StackSpreader.stackedTiles(in));
		in.add(new StackSpreader.Entry(5, TILE_B, false));
		Assert.assertEquals(2, StackSpreader.stackedTiles(in));
	}

	@Test
	public void aWideRowCurvesRoundTheThingInsteadOfRunningPastIt()
	{
		// Players facing north at an anvil, widest spacing: the front row stays a tile from the anvil
		// spot, doesn't go past its sides, and everyone turned to face the same spot faces the anvil.
		double focusX = 0;
		double focusZ = StackSpreader.LOOK_AHEAD;
		for (int[] o : StackSpreader.spots(true, false, Math.PI, false, PersonalSpaceConfig.MAX_SPACING, StackSpreader.ROW_WIDTH, null))
		{
			Assert.assertEquals("everyone is the same distance from the anvil", StackSpreader.LOOK_AHEAD,
				Math.hypot(o[0] - focusX, o[1] - focusZ), 2);
			Assert.assertTrue("went past the anvil: z " + o[1], o[1] < focusZ - 30);

			int facing = StackSpreader.faceSameSpot(1024, o[0], o[1]);
			double lookX = -Math.sin(StackSpreader.toRadians(facing));
			double lookZ = -Math.cos(StackSpreader.toRadians(facing));
			double toAnvilX = focusX - o[0];
			double toAnvilZ = focusZ - o[1];
			double cos = (lookX * toAnvilX + lookZ * toAnvilZ) / Math.hypot(toAnvilX, toAnvilZ);
			Assert.assertTrue("isn't facing the anvil from " + o[0] + "," + o[1], cos > 0.99);
		}
	}

	@Test
	public void curvedRowsBehindKeepTheFrontRowsAngles()
	{
		// Facing north: row two's whole steps sit exactly between row one's half steps.
		List<int[]> spots = StackSpreader.spots(true, false, Math.PI, false, 72, StackSpreader.CURVED_ROW_WIDTH + 2, null);
		double front = Math.atan2(spots.get(0)[0], StackSpreader.LOOK_AHEAD - spots.get(0)[1]);
		double back = Math.atan2(spots.get(StackSpreader.CURVED_ROW_WIDTH + 1)[0], StackSpreader.LOOK_AHEAD - spots.get(StackSpreader.CURVED_ROW_WIDTH + 1)[1]);
		Assert.assertEquals("row two's first step out is twice row one's half step", 2 * front, back, 0.02);
	}

	@Test
	public void aGroupFacingTheSameWayHasASharedFacing()
	{
		List<StackSpreader.Entry> group = new ArrayList<>();
		group.add(new StackSpreader.Entry(1, TILE_A, false, 1024));
		group.add(new StackSpreader.Entry(2, TILE_A, false, 1000));
		Double facing = StackSpreader.sharedFacing(group, StackSpreader.SAME_FACING);
		Assert.assertNotNull(facing);
		Assert.assertEquals(Math.PI, Math.abs(facing), 0.1);

		group.add(new StackSpreader.Entry(3, TILE_A, false, 0));
		Assert.assertNull("one facing the other way: no shared facing", StackSpreader.sharedFacing(group, StackSpreader.SAME_FACING));

		List<StackSpreader.Entry> unknown = new ArrayList<>();
		unknown.add(new StackSpreader.Entry(1, TILE_A, false));
		unknown.add(new StackSpreader.Entry(2, TILE_A, false));
		Assert.assertNull(StackSpreader.sharedFacing(unknown, StackSpreader.SAME_FACING));
	}

	@Test
	public void endsOfARowTurnInTowardsWhatEveryoneIsFacing()
	{
		// Facing north (1024) at an anvil. Pushed east, you turn to the north-west to still face it.
		int east = StackSpreader.faceSameSpot(1024, 88, 0);
		Assert.assertTrue("turned towards the west: " + east, east > 512 && east < 1024);
		// Pushed west, you turn to the north-east.
		int west = StackSpreader.faceSameSpot(1024, -88, 0);
		Assert.assertTrue("turned towards the east: " + west, west > 1024 && west < 1536);
		// The further out, the more you turn.
		Assert.assertTrue(StackSpreader.faceSameSpot(1024, 120, 0) < StackSpreader.faceSameSpot(1024, 40, 0));
		// Not moved sideways: keep facing straight ahead.
		Assert.assertEquals(1024, StackSpreader.faceSameSpot(1024, 0, 0));
		Assert.assertEquals(1024, StackSpreader.faceSameSpot(1024, 0, -30));
	}

	@Test
	public void rowTilesAreRememberedForDrawing()
	{
		StackRegistry r = new StackRegistry();
		java.util.Set<Long> rows = new java.util.HashSet<>();
		rows.add(TILE_A);
		r.rebuild(new ArrayList<>(), rows);
		Assert.assertTrue(r.isCurvedRow(TILE_A));
		Assert.assertFalse(r.isCurvedRow(TILE_B));
		r.clear();
		Assert.assertFalse(r.isCurvedRow(TILE_A));
	}

	@Test
	public void twoPlayersInACrowdStandEitherSideOfTheMiddle()
	{
		List<int[]> spots = StackSpreader.spots(false, false, 0, false, 60, 5, null);
		Assert.assertEquals(5, spots.size());
		Assert.assertEquals("first spot east", 0, spots.get(0)[1]);
		Assert.assertTrue(spots.get(0)[0] > 0);
		Assert.assertEquals("second spot straight across", -spots.get(0)[0], spots.get(1)[0]);
		Assert.assertEquals(0, spots.get(1)[1]);
	}

	@Test
	public void theSpotPatternDoesNotDependOnHowManyPlayersThereAre()
	{
		for (boolean straight : new boolean[]{false, true})
		{
			for (boolean middleTaken : new boolean[]{false, true})
			{
				List<int[]> small = StackSpreader.spots(true, straight, Math.PI, middleTaken, 80, 3, null);
				List<int[]> big = StackSpreader.spots(true, straight, Math.PI, middleTaken, 80, 10, null);
				for (int i = 0; i < small.size(); i++)
				{
					Assert.assertArrayEquals("spot " + i + " must not move when the tile gets bigger", small.get(i), big.get(i));
				}
			}
		}
	}

	@Test
	public void theMiddleIsLeftForWhoeverStaysPut()
	{
		for (boolean row : new boolean[]{false, true})
		{
			for (boolean straight : new boolean[]{false, true})
			{
				List<int[]> spots = StackSpreader.spots(row, straight, Math.PI, true, 80, 10, null);
				Assert.assertEquals(10, spots.size());
				for (int[] s : spots)
				{
					Assert.assertFalse("row=" + row + ": nobody may be put in the middle", s[0] == 0 && s[1] == 0);
				}
			}
		}
	}

	@Test
	public void twoPlayersInARowShareTheSpaceEvenly()
	{
		// Facing north, at an anvil (curved row) and at a counter (straight row).
		for (boolean straight : new boolean[]{false, true})
		{
			List<int[]> spots = StackSpreader.spots(true, straight, Math.PI, false, 42, 2, null);
			Assert.assertEquals(2, spots.size());
			Assert.assertEquals("either side of the middle", -spots.get(0)[0], spots.get(1)[0]);
			Assert.assertEquals(spots.get(0)[1], spots.get(1)[1]);
			Assert.assertEquals("one spacing apart", 42.0, spots.get(0)[0] - spots.get(1)[0], 2.0);
		}
	}

	@Test
	public void aFourthPlayerAtACounterStillJoinsTheFrontRowWhenOneSideIsBlocked()
	{
		// Facing north at a counter with a rope barrier just east of the tile.
		List<int[]> spots = StackSpreader.spots(true, true, Math.PI, false, 42, 10, (dx, dz) -> dx <= 38);
		for (int i = 0; i < 4; i++)
		{
			Assert.assertEquals("spot " + i + " is in the front row", 0, spots.get(i)[1]);
			Assert.assertTrue(spots.get(i)[0] <= 38);
		}
		Assert.assertEquals("the first two still share the middle", -spots.get(0)[0], spots.get(1)[0]);
	}

	@Test
	public void aCurvedRowWrapsRoundTheThingBeforeAnyoneStandsBehind()
	{
		// Facing north: what everyone faces is 128 north of the tile centre.
		List<int[]> spots = StackSpreader.spots(true, false, Math.PI, false, 60, StackSpreader.CURVED_ROW_WIDTH + 1, null);
		for (int i = 0; i < StackSpreader.CURVED_ROW_WIDTH; i++)
		{
			Assert.assertEquals("spot " + i + " is in the front ring", 128, Math.hypot(spots.get(i)[0], 128 - spots.get(i)[1]), 1.5);
		}
		Assert.assertTrue("the next one is in the row behind",
			Math.hypot(spots.get(StackSpreader.CURVED_ROW_WIDTH)[0], 128 - spots.get(StackSpreader.CURVED_ROW_WIDTH)[1]) > 150);
	}

	@Test
	public void aRowBehindStandsInTheGaps()
	{
		List<int[]> spots = StackSpreader.spots(true, true, Math.PI, false, 42, 7, null);
		Assert.assertEquals("the second row starts straight behind the middle", 0, spots.get(6)[0]);
		Assert.assertTrue(spots.get(6)[1] < 0);
	}

	@Test
	public void aCurvedRowNeverWrapsPastTheWrapLimit()
	{
		for (int spacing : new int[]{PersonalSpaceConfig.MIN_SPACING, 72, 160, PersonalSpaceConfig.MAX_SPACING})
		{
			for (boolean middleTaken : new boolean[]{false, true})
			{
				for (int[] s : StackSpreader.spots(true, false, Math.PI, middleTaken, spacing, 17, null))
				{
					double round = Math.atan2(Math.abs(s[0]), 128 - s[1]);
					Assert.assertTrue("spacing " + spacing + ": " + Math.toDegrees(round) + " degrees round",
						round <= StackSpreader.WRAP_ARC + 0.02);
				}
			}
		}
	}

	@Test
	public void blockedSpotsAreSkippedAndTheNextOneIsUsed()
	{
		// Anything to the north (into a booth) is off limits.
		List<int[]> spots = StackSpreader.spots(false, false, 0, false, 80, 6, (dx, dz) -> dz <= 20);
		Assert.assertEquals(6, spots.size());
		for (int[] s : spots)
		{
			Assert.assertTrue("spot in the booth: " + s[1], s[1] <= 20);
		}
	}

	@Test
	public void wideSpacingDoesNotFlingRowsFarBack()
	{
		for (boolean straight : new boolean[]{false, true})
		{
			for (int[] s : StackSpreader.spots(true, straight, Math.PI, false, PersonalSpaceConfig.MAX_SPACING, 17, null))
			{
				Assert.assertTrue("at most two row-depths back: " + s[1], s[1] >= -2 * StackSpreader.ROW_DEPTH - 2);
			}
		}
	}

	@Test
	public void backRowsStandBehindTheFrontRow()
	{
		// Facing north: the front row is nearer the thing, row two is a spacing further back.
		int back = StackSpreader.CURVED_ROW_WIDTH;
		List<int[]> spots = StackSpreader.spots(true, false, Math.PI, false, 80, back + 1, null);
		Assert.assertTrue("back row is further south: " + spots.get(0)[1] + " vs " + spots.get(back)[1], spots.get(back)[1] < spots.get(0)[1] - 40);
	}

	@Test
	public void firesAreRecognisedByName()
	{
		Assert.assertTrue(StackSpreader.isFireName("Fire"));
		Assert.assertTrue(StackSpreader.isFireName("Forester's Campfire"));
		Assert.assertTrue(StackSpreader.isFireName("Fire pit"));
		Assert.assertFalse(StackSpreader.isFireName("Fireplace"));
		Assert.assertFalse(StackSpreader.isFireName("Anvil"));
		Assert.assertFalse(StackSpreader.isFireName(null));
	}
}
