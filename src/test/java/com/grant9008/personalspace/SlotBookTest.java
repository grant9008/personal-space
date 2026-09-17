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
	public void youGetTheFrontSpotAndOnlyOnePersonMakesRoom()
	{
		SlotBook book = new SlotBook();
		book.localId = 99;
		Map<Integer, Integer> before = step(book, 1, 10, 20, 30);
		Map<Integer, Integer> after = step(book, 2, 10, 20, 30, 99);
		Assert.assertEquals("you get the best spot", 0, (int) after.get(99));
		int moved = 0;
		for (Map.Entry<Integer, Integer> e : before.entrySet())
		{
			moved += e.getValue().equals(after.get(e.getKey())) ? 0 : 1;
		}
		Assert.assertEquals("only whoever had it moves", 1, moved);
		Assert.assertEquals("and then everyone stays put", after, step(book, 3, 10, 20, 30, 99));
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
