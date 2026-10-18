package com.grant9008.personalspace;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ChatRangeOverlayTest
{
	@Test
	public void theAreaIsTheSquareOfTilesTheChatFilterKeeps()
	{
		// Within 2 tiles of tile (50, 50): tiles 48..52 each way, so corners from 48 to 53 tiles in.
		List<int[]> corners = ChatRangeOverlay.boundary(50, 50, 2);
		Assert.assertEquals("a corner a tile apart all round, and back to the start", 4 * 5 + 1, corners.size());
		Assert.assertArrayEquals(corners.get(0), corners.get(corners.size() - 1));
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int[] c : corners)
		{
			min = Math.min(min, Math.min(c[0], c[1]));
			max = Math.max(max, Math.max(c[0], c[1]));
		}
		Assert.assertEquals(48 * 128, min);
		Assert.assertEquals(53 * 128, max);
		// Every tile inside it is one the chat filter counts as within 2: the far corner tile included.
		Assert.assertEquals(2, ChatHider.tilesApart(50, 50, 52, 52));
		Assert.assertEquals(3, ChatHider.tilesApart(50, 50, 53, 50));
	}
}
