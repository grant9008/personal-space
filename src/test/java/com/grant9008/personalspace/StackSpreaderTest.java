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
		Assert.assertEquals("no north-south offset", 0, out.get(1)[1]);
		Assert.assertEquals(0, out.get(2)[1]);
		Assert.assertEquals("neighbours are one spacing apart", 32, Math.abs(out.get(1)[0] - out.get(2)[0]));
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
			Assert.assertEquals("no east-west offset when facing west", 0, o[0]);
		}
		Assert.assertEquals("middle player stays centred", 0, out.get(2)[1]);
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
