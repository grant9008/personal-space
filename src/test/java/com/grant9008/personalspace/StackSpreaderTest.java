package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class StackSpreaderTest
{
	private static final long TILE_A = 1L;
	private static final long TILE_B = 2L;

	private static Map<Integer, int[]> byId(List<StackSpreader.Placement> placements)
	{
		Map<Integer, int[]> m = new HashMap<>();
		for (StackSpreader.Placement p : placements)
		{
			Assert.assertNull("a player must only be placed once", m.put(p.id, new int[]{p.dx, p.dz}));
		}
		return m;
	}

	@Test
	public void aLonePlayerIsNeverMoved()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(5, TILE_A, false));
		in.add(new StackSpreader.Entry(9, TILE_B, false));
		Assert.assertTrue(StackSpreader.place(in, true, 5, 32).isEmpty());
	}

	@Test
	public void twoPlayersGoEastAndWest()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(7, TILE_A, false));
		in.add(new StackSpreader.Entry(3, TILE_A, false));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, true, 5, 32));
		// Spacing is the distance between the two, so each sits half of it from the middle.
		Assert.assertArrayEquals(new int[]{16, 0}, out.get(3));   // lower id takes slot 0 (east)
		Assert.assertArrayEquals(new int[]{-16, 0}, out.get(7));
	}

	@Test
	public void localPlayerStaysPutWhenExcluded()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, true));
		in.add(new StackSpreader.Entry(2, TILE_A, false));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, false, 5, 32));
		Assert.assertFalse("local player must not be placed", out.containsKey(1));
		Assert.assertArrayEquals("the other player steps aside", new int[]{32, 0}, out.get(2));
	}

	@Test
	public void localPlayerTakesASlotWhenIncluded()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, true));
		in.add(new StackSpreader.Entry(2, TILE_A, false));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, true, 5, 32));
		Assert.assertEquals(2, out.size());
		Assert.assertTrue(out.containsKey(1));
	}

	@Test
	public void placementDoesNotDependOnInputOrder()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		for (int id : new int[]{40, 12, 99, 7})
		{
			in.add(new StackSpreader.Entry(id, TILE_A, false));
		}
		Map<Integer, int[]> first = byId(StackSpreader.place(in, true, 5, 44));
		for (int i = 0; i < 20; i++)
		{
			Collections.shuffle(in);
			Map<Integer, int[]> again = byId(StackSpreader.place(in, true, 5, 44));
			for (int id : first.keySet())
			{
				Assert.assertArrayEquals("slot for " + id + " must be stable", first.get(id), again.get(id));
			}
		}
	}

	@Test
	public void maxStackCapsHowManyMove()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		for (int id = 0; id < 8; id++)
		{
			in.add(new StackSpreader.Entry(id, TILE_A, false));
		}
		Assert.assertEquals(5, StackSpreader.place(in, true, 5, 32).size());
		Assert.assertEquals(2, StackSpreader.place(in, true, 2, 32).size());
	}

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
	public void playersFacingTheSameWayStandSideBySideAcrossTheirFacing()
	{
		// Both face north (1024), e.g. at a bank booth to the north: the line must run east-west.
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false, 1024));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 1030));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, true, 5, 32, StackSpreader.Layout.AUTO));
		Assert.assertTrue("barely any north-south offset", Math.abs(out.get(1)[1]) <= 3);
		Assert.assertTrue(Math.abs(out.get(2)[1]) <= 3);
		Assert.assertEquals("neighbours are about one spacing apart", 32, Math.abs(out.get(1)[0] - out.get(2)[0]), 2);
	}

	@Test
	public void facingWestLinesUpNorthSouth()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false, 512));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 512));
		in.add(new StackSpreader.Entry(3, TILE_A, false, 512));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, true, 5, 32, StackSpreader.Layout.AUTO));
		for (int[] o : out.values())
		{
			Assert.assertTrue("hardly any east-west offset when facing west: " + o[0], Math.abs(o[0]) <= 5);
		}
		Assert.assertEquals("middle player stays centred", 0, out.get(2)[1]);
		Assert.assertEquals(0, out.get(2)[0]);
	}

	@Test
	public void mixedFacingsFormACircle()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false, 0));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 1024));
		Assert.assertNull(StackSpreader.sharedFacing(in));
		Map<Integer, int[]> auto = byId(StackSpreader.place(in, true, 5, 32, StackSpreader.Layout.AUTO));
		Map<Integer, int[]> ring = byId(StackSpreader.place(in, true, 5, 32, StackSpreader.Layout.RING));
		Assert.assertArrayEquals(ring.get(1), auto.get(1));
		Assert.assertArrayEquals(ring.get(2), auto.get(2));
	}

	@Test
	public void unknownFacingFallsBackToACircleInAuto()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false));
		in.add(new StackSpreader.Entry(2, TILE_A, false));
		Assert.assertNull(StackSpreader.sharedFacing(in));
	}

	@Test
	public void lineWithYouInTheMiddlePutsOthersEitherSide()
	{
		// You (id 1, excluded) face south with two others: they stand east and west of you.
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, true, 0));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 0));
		in.add(new StackSpreader.Entry(3, TILE_A, false, 0));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, false, 5, 32, StackSpreader.Layout.AUTO));
		Assert.assertFalse(out.containsKey(1));
		Assert.assertEquals(32, Math.abs(out.get(2)[0]));
		Assert.assertEquals(32, Math.abs(out.get(3)[0]));
		Assert.assertEquals("on opposite sides", -out.get(2)[0], out.get(3)[0]);
	}

	@Test
	public void aRowStaysARowWhenOneFacingWobbles()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false, 1024));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 1024));
		in.add(new StackSpreader.Entry(3, TILE_A, false, 1024 + 380)); // one player turned about 65 degrees
		java.util.Set<Long> none = java.util.Collections.emptySet();
		java.util.Set<Long> rows = new java.util.HashSet<>();
		StackSpreader.place(in, true, 5, 60, StackSpreader.Layout.AUTO, none, rows);
		Assert.assertFalse("a fresh tile with that much disagreement is a circle", rows.contains(TILE_A));

		java.util.Set<Long> wasRow = new java.util.HashSet<>();
		wasRow.add(TILE_A);
		rows.clear();
		StackSpreader.place(in, true, 5, 60, StackSpreader.Layout.AUTO, wasRow, rows);
		Assert.assertTrue("a tile that was already a row stays one", rows.contains(TILE_A));
	}

	@Test
	public void currentSpotsAreKeptUnlessTheNewOneIsClearlyDifferent()
	{
		List<StackSpreader.Placement> fresh = new ArrayList<>();
		fresh.add(new StackSpreader.Placement(1, TILE_A, 50, 0));
		fresh.add(new StackSpreader.Placement(2, TILE_A, -50, 0));
		fresh.add(new StackSpreader.Placement(3, TILE_A, 0, 60));
		StackSpreader.Targets current = new StackSpreader.Targets()
		{
			@Override
			public boolean has(int id)
			{
				return id != 3;
			}

			@Override
			public int dx(int id)
			{
				return id == 1 ? 44 : 20;
			}

			@Override
			public int dz(int id)
			{
				return 0;
			}
		};
		Map<Integer, int[]> out = byId(StackSpreader.keepCurrentSpots(fresh, current, 16));
		Assert.assertArrayEquals("6 units off: keep the old spot", new int[]{44, 0}, out.get(1));
		Assert.assertArrayEquals("70 units off: move", new int[]{-50, 0}, out.get(2));
		Assert.assertArrayEquals("new player: take the new spot", new int[]{0, 60}, out.get(3));
	}

	@Test
	public void aWideRowCurvesRoundTheThingInsteadOfRunningPastIt()
	{
		// Five players facing north at an anvil, widest spacing: nobody drifts further than a
		// tile and a bit from the anvil spot, and the ends don't go past its sides.
		double focusX = 0;
		double focusZ = StackSpreader.LOOK_AHEAD;
		for (int i = 0; i < 5; i++)
		{
			int[] o = StackSpreader.lineOffset(i, 5, false, PersonalSpaceConfig.MAX_SPACING, Math.PI);
			double fromAnvil = Math.hypot(o[0] - focusX, o[1] - focusZ);
			Assert.assertEquals("everyone is the same distance from the anvil", StackSpreader.LOOK_AHEAD, fromAnvil, 2);
			Assert.assertTrue("slot " + i + " went past the anvil: z " + o[1], o[1] < focusZ - 30);
		}
		// Everyone faces the anvil.
		for (int i = 0; i < 5; i++)
		{
			int[] o = StackSpreader.lineOffset(i, 5, false, 112, Math.PI);
			int facing = StackSpreader.faceSameSpot(1024, o[0], o[1]);
			double lookX = -Math.sin(StackSpreader.toRadians(facing));
			double lookZ = -Math.cos(StackSpreader.toRadians(facing));
			double toAnvilX = focusX - o[0];
			double toAnvilZ = focusZ - o[1];
			double cos = (lookX * toAnvilX + lookZ * toAnvilZ) / Math.hypot(toAnvilX, toAnvilZ);
			Assert.assertTrue("slot " + i + " isn't facing the anvil", cos > 0.99);
		}
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
		Assert.assertTrue(r.isRow(TILE_A));
		Assert.assertFalse(r.isRow(TILE_B));
		r.clear();
		Assert.assertFalse(r.isRow(TILE_A));
	}

	@Test
	public void twoPlayersInACrowdStandEitherSideOfTheMiddle()
	{
		List<int[]> spots = StackSpreader.spots(false, 0, false, 60, 5, null);
		Assert.assertEquals(5, spots.size());
		Assert.assertEquals("first spot east", 0, spots.get(0)[1]);
		Assert.assertTrue(spots.get(0)[0] > 0);
		Assert.assertEquals("second spot straight across", -spots.get(0)[0], spots.get(1)[0]);
		Assert.assertEquals(0, spots.get(1)[1]);
	}

	@Test
	public void theSpotPatternDoesNotDependOnHowManyPlayersThereAre()
	{
		List<int[]> small = StackSpreader.spots(true, Math.PI, false, 80, 3, null);
		List<int[]> big = StackSpreader.spots(true, Math.PI, false, 80, 10, null);
		for (int i = 0; i < small.size(); i++)
		{
			Assert.assertArrayEquals("spot " + i + " must not move when the tile gets bigger", small.get(i), big.get(i));
		}
	}

	@Test
	public void theMiddleIsLeftForWhoeverStaysPut()
	{
		for (boolean row : new boolean[]{false, true})
		{
			List<int[]> spots = StackSpreader.spots(row, Math.PI, true, 80, 10, null);
			for (int[] s : spots)
			{
				Assert.assertFalse("row=" + row + ": nobody may be put in the middle", s[0] == 0 && s[1] == 0);
			}
		}
	}

	@Test
	public void blockedSpotsAreSkippedAndTheNextOneIsUsed()
	{
		// Anything to the north (into a booth) is off limits.
		List<int[]> spots = StackSpreader.spots(false, 0, false, 80, 6, (dx, dz) -> dz <= 20);
		Assert.assertEquals(6, spots.size());
		for (int[] s : spots)
		{
			Assert.assertTrue("spot in the booth: " + s[1], s[1] <= 20);
		}
	}

	@Test
	public void wideSpacingDoesNotFlingRowsFarBack()
	{
		int[] third = StackSpreader.spotOffset(10, true, Math.PI, PersonalSpaceConfig.MAX_SPACING);
		Assert.assertTrue("third row is at most two row-depths back: " + third[1], third[1] >= -2 * StackSpreader.ROW_DEPTH - 2);
	}

	@Test
	public void backRowsStandBehindTheFrontRow()
	{
		// Facing north: the front row is nearer the thing, row two is a spacing further back.
		int[] front = StackSpreader.spotOffset(0, true, Math.PI, 80);
		int[] back = StackSpreader.spotOffset(5, true, Math.PI, 80);
		Assert.assertTrue("back row is further south: " + front[1] + " vs " + back[1], back[1] < front[1] - 40);
	}

	@Test
	public void ringNeighboursAreOneSpacingApart()
	{
		for (int n = 3; n <= 5; n++)
		{
			int r = StackSpreader.ringRadius(n, false, 60);
			int[] a = StackSpreader.ringOffset(0, n, r);
			int[] b = StackSpreader.ringOffset(1, n, r);
			Assert.assertEquals("n=" + n, 60.0, Math.hypot(a[0] - b[0], a[1] - b[1]), 2.0);
		}
		Assert.assertEquals("someone in the middle keeps a full spacing away", 60, StackSpreader.ringRadius(5, true, 60));
	}

	@Test
	public void longLinesAreCapped()
	{
		for (int n = 2; n <= 5; n++)
		{
			for (boolean centreTaken : new boolean[]{false, true})
			{
				for (int i = 0; i < n; i++)
				{
					int[] o = StackSpreader.lineOffset(i, n, centreTaken, 44, 0.7);
					Assert.assertTrue("slot " + i + "/" + n + " too far out", Math.hypot(o[0], o[1]) <= StackSpreader.MAX_LINE_EXTENT + 1);
				}
			}
		}
	}

	@Test
	public void forcedLineIgnoresFacing()
	{
		List<StackSpreader.Entry> in = new ArrayList<>();
		in.add(new StackSpreader.Entry(1, TILE_A, false, 0));
		in.add(new StackSpreader.Entry(2, TILE_A, false, 1024));
		Map<Integer, int[]> out = byId(StackSpreader.place(in, true, 5, 32, StackSpreader.Layout.LINE));
		Assert.assertEquals(32, Math.abs(out.get(1)[0] - out.get(2)[0]) + Math.abs(out.get(1)[1] - out.get(2)[1]));
	}

	@Test
	public void ringSlotsSitOnTheCircleAndSpreadEvenly()
	{
		for (int n = 1; n <= 5; n++)
		{
			for (int i = 0; i < n; i++)
			{
				int[] o = StackSpreader.ringOffset(i, n, 40);
				double r = Math.hypot(o[0], o[1]);
				Assert.assertEquals("slot " + i + " of " + n + " should be on the ring", 40.0, r, 1.0);
			}
		}
		// Opposite slots of a pair cancel out, so the pair is centred on the tile.
		int[] a = StackSpreader.ringOffset(0, 2, 30);
		int[] b = StackSpreader.ringOffset(1, 2, 30);
		Assert.assertEquals(0, a[0] + b[0]);
		Assert.assertEquals(0, a[1] + b[1]);
	}
}
