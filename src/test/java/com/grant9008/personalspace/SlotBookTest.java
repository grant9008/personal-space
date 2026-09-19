package com.grant9008.personalspace;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SlotBookTest
{
	private static final long TILE = 42L;

	private static Map<Integer, Integer> step(SlotBook book, int tick, Integer... ids)
	{
		Map<Long, List<Integer>> present = new HashMap<>();
		if (ids.length > 0)
		{
			present.put(TILE, Arrays.asList(ids));
		}
		Map<Long, Map<Integer, Integer>> out = book.update(present, tile -> 5, tick);
		return out.getOrDefault(TILE, Collections.emptyMap());
	}

	@Test
	public void newcomersTakeTheNextSpotAndNobodyElseMoves()
	{
		SlotBook book = new SlotBook();
		Map<Integer, Integer> a = step(book, 1, 10, 20);
		Assert.assertEquals(0, (int) a.get(10));
		Assert.assertEquals(1, (int) a.get(20));

		Map<Integer, Integer> b = step(book, 2, 10, 20, 5); // a lower id arrives
		Assert.assertEquals("existing players keep their spots", 0, (int) b.get(10));
		Assert.assertEquals(1, (int) b.get(20));
		Assert.assertEquals("newcomer goes to the next spot", 2, (int) b.get(5));
	}

	@Test
	public void aSpotIsHeldForSomeoneWhoStepsAwayBriefly()
	{
		SlotBook book = new SlotBook();
		step(book, 1, 1, 2, 3);
		Map<Integer, Integer> gone = step(book, 2, 1, 3); // 2 steps away
		Assert.assertEquals("3 stays put while 2's spot is held", 2, (int) gone.get(3));
		Map<Integer, Integer> back = step(book, 4, 1, 2, 3);
		Assert.assertEquals("2 gets its spot back", 1, (int) back.get(2));
		Assert.assertEquals(2, (int) back.get(3));
	}

	@Test
	public void aNewcomerDoesNotTakeAHeldSpot()
	{
		SlotBook book = new SlotBook();
		step(book, 1, 1, 2, 3);
		step(book, 2, 1, 3);
		Map<Integer, Integer> m = step(book, 3, 1, 3, 9);
		Assert.assertEquals("9 goes past the held spot", 3, (int) m.get(9));
	}

	@Test
	public void whenAHoldRunsOutOnlyTheBackPlayerMovesForward()
	{
		SlotBook book = new SlotBook();
		step(book, 1, 1, 2, 3, 4);
		step(book, 2, 1, 3, 4); // 2 leaves for good
		Map<Integer, Integer> m = step(book, 2 + SlotBook.HOLD_TICKS + 1, 1, 3, 4);
		Assert.assertEquals("front stays", 0, (int) m.get(1));
		Assert.assertEquals("middle stays", 2, (int) m.get(3));
		Assert.assertEquals("the back player fills the gap", 1, (int) m.get(4));
	}

	@Test
	public void aFullTileLeavesExtrasWithoutASpot()
	{
		SlotBook book = new SlotBook();
		Map<Integer, Integer> m = step(book, 1, 1, 2, 3, 4, 5, 6, 7);
		Assert.assertEquals(5, m.size());
		Assert.assertFalse(m.containsKey(6));
		Assert.assertFalse(m.containsKey(7));
	}

	@Test
	public void holdsKeepATileRememberedAfterEveryoneLeaves()
	{
		SlotBook book = new SlotBook();
		step(book, 1, 1, 2);
		step(book, 2);
		Map<Integer, Integer> back = step(book, 5, 2, 1);
		Assert.assertEquals("both get their own spots back", 0, (int) back.get(1));
		Assert.assertEquals(1, (int) back.get(2));
	}

	@Test
	public void youGoStraightToTheFrontSpotAndOnlyOnePersonMakesRoom()
	{
		SlotBook book = new SlotBook();
		book.localId = 99;
		Map<Integer, Integer> before = step(book, 1, 10, 20, 30);
		Map<Integer, Integer> after = step(book, 2, 10, 20, 30, 99);
		Assert.assertEquals("you get the best spot as soon as you arrive, not off to the side first", 0, (int) after.get(99));
		int moved = 0;
		for (Map.Entry<Integer, Integer> e : before.entrySet())
		{
			moved += e.getValue().equals(after.get(e.getKey())) ? 0 : 1;
		}
		Assert.assertEquals("only whoever had it moves", 1, moved);
		Assert.assertEquals("and then everyone stays put", after, step(book, 20, 10, 20, 30, 99));
	}

	@Test
	public void stoppingNowAndThenMovesOnePersonAtATime()
	{
		// (Walking past never reaches here: nobody is given a spot until they've stood still.)
		SlotBook book = new SlotBook();
		book.localId = 99;
		Map<Integer, Integer> was = step(book, 1, 10, 20, 30);
		int moves = 0;
		for (int t = 2; t <= 40; t++)
		{
			boolean stopped = t % 12 == 0 || t % 12 == 1;
			Map<Integer, Integer> now = stopped ? step(book, t, 10, 20, 30, 99) : step(book, t, 10, 20, 30);
			int movedNow = 0;
			for (int id : new int[]{10, 20, 30})
			{
				movedNow += was.get(id).equals(now.get(id)) ? 0 : 1;
			}
			Assert.assertTrue("tick " + t + ": " + movedNow + " people moved at once", movedNow <= 1);
			moves += movedNow;
			was = now;
		}
		// Three stops: each time one person makes room, and gets their spot back once yours has
		// been held a while after you left.
		Assert.assertTrue(moves + " moves", moves <= 6);
	}

	@Test
	public void youDontSwapWhileABetterSpotIsHeldForSomeone()
	{
		// The front player steps away; you arrive; their hold runs out. Nobody else moves at all:
		// you step into the freed front spot.
		SlotBook book = new SlotBook();
		book.localId = 99;
		Map<Integer, Integer> before = step(book, 1, 10, 20, 30, 40);
		for (int t = 2; t <= 30; t++)
		{
			Map<Integer, Integer> now = t < 5 ? step(book, t, 10, 20, 30, 40) : t < 8 ? step(book, t, 20, 30, 40) : step(book, t, 20, 30, 40, 99);
			for (int id : new int[]{20, 30, 40})
			{
				Assert.assertEquals("tick " + t + ": player " + id + " was moved", before.get(id), now.get(id));
			}
		}
		Assert.assertEquals(0, (int) step(book, 31, 20, 30, 40, 99).get(99));
	}

	@Test
	public void youWalkOnceToTheFrontEvenWhenAMiddleSpotIsFree()
	{
		SlotBook book = new SlotBook();
		book.localId = 99;
		step(book, 1, 10, 20, 30, 40, 99);
		// 20 and 30 leave: their spots are held for a while, then free.
		Map<Integer, Integer> before = null;
		for (int t = 2; t <= 1 + SlotBook.HOLD_TICKS + 2; t++)
		{
			before = step(book, t, 10, 40, 99);
		}
		Assert.assertEquals("you end up at the front", 0, (int) before.get(99));
		for (int t = 1; t <= 4; t++)
		{
			Assert.assertEquals("and then nobody moves", before, step(book, 20 + t, 10, 40, 99));
		}
	}

	@Test
	public void walkingInBesideSomeoneSettlesInOneStep()
	{
		// The other player has the lower id, so without first pick they would take the front spot and
		// you would swap with them a few ticks later: the pair shuffling about once for no reason.
		SlotBook book = new SlotBook();
		book.localId = 99;
		// A tile with one person on it isn't laid out, so the pair's spots are both handed out the
		// tick you arrive: the other player has the lower id and would otherwise take the front one.
		Map<Integer, Integer> pair = step(book, 1, 7, 99);
		Assert.assertEquals("you take the front spot as you arrive", 0, (int) pair.get(99));
		for (int t = 2; t <= 2 + SlotBook.LOCAL_SWAP_DELAY + 2; t++)
		{
			Assert.assertEquals("and then nobody shuffles", pair, step(book, t, 7, 99));
		}
	}

	@Test
	public void clearForgetsEverything()
	{
		SlotBook book = new SlotBook();
		step(book, 1, 1, 2);
		book.clear();
		Map<Integer, Integer> m = step(book, 2, 2);
		Assert.assertEquals(0, (int) m.get(2));
	}
}
