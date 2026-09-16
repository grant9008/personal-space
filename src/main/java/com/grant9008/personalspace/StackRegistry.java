package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which players share each stacked tile, as worked out on the last game tick.
 *
 * <p>Why this exists: when several players stand still in the exact middle of one tile, the game
 * only draws the first of them each frame and skips the rest before any renderer sees them. So
 * when the renderer is asked to draw a player on a stacked tile, the draw shim looks the tile up
 * here and draws the hidden stackmates itself.
 *
 * <p>Tiles are keyed by scene coordinates (plane, scene x, scene y), because that is what the draw
 * call can see cheaply. The table is rebuilt once per tick on the client thread and swapped in as
 * a whole, so a reader never sees it half-built. Knows nothing about RuneLite; unit tested.
 */
final class StackRegistry
{
	private static final int[] NONE = new int[0];

	private volatile Map<Long, int[]> byTile = Collections.emptyMap();
	/** Tiles laid out as a side-by-side row this tick. */
	private volatile java.util.Set<Long> rows = Collections.emptySet();

	static long key(int plane, int sceneX, int sceneY)
	{
		return ((long) (plane & 0xFF) << 40) | ((long) (sceneX & 0xFFFFF) << 20) | (sceneY & 0xFFFFF);
	}

	static int plane(long key)
	{
		return (int) ((key >> 40) & 0xFF);
	}

	static int sceneX(long key)
	{
		return (int) ((key >> 20) & 0xFFFFF);
	}

	static int sceneY(long key)
	{
		return (int) (key & 0xFFFFF);
	}

	/** Replace the table with the tiles and members in these placements (placement order is kept). */
	void rebuild(List<StackSpreader.Placement> placements, java.util.Set<Long> rowTiles)
	{
		rows = rowTiles == null ? Collections.emptySet() : new java.util.HashSet<>(rowTiles);
		rebuild(placements);
	}

	/** True if this tile is a side-by-side row, where everyone faces the same thing. */
	boolean isRow(long tileKey)
	{
		return rows.contains(tileKey);
	}

	void rebuild(List<StackSpreader.Placement> placements)
	{
		if (placements.isEmpty())
		{
			byTile = Collections.emptyMap();
			return;
		}
		Map<Long, List<Integer>> grouped = new LinkedHashMap<>();
		for (StackSpreader.Placement p : placements)
		{
			grouped.computeIfAbsent(p.tile, k -> new ArrayList<>(5)).add(p.id);
		}
		Map<Long, int[]> table = new HashMap<>(grouped.size() * 2);
		for (Map.Entry<Long, List<Integer>> e : grouped.entrySet())
		{
			List<Integer> ids = e.getValue();
			int[] arr = new int[ids.size()];
			for (int i = 0; i < arr.length; i++)
			{
				arr[i] = ids.get(i);
			}
			table.put(e.getKey(), arr);
		}
		byTile = table;
	}

	void clear()
	{
		rows = Collections.emptySet();
		byTile = Collections.emptyMap();
	}

	boolean isEmpty()
	{
		return byTile.isEmpty();
	}

	/** Ids of the players placed on this tile, or an empty array. Never null. */
	int[] membersAt(long tileKey)
	{
		int[] ids = byTile.get(tileKey);
		return ids == null ? NONE : ids;
	}
}
