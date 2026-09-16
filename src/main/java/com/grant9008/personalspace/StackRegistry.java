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
	/** Tiles laid out as a curved row this tick. */
	private volatile java.util.Set<Long> curvedRows = Collections.emptySet();
	/** Players on a crowded tile who weren't given a spot this tick (past the limit, you staying put, or not shown yet). */
	private volatile java.util.Set<Integer> unplaced = Collections.emptySet();
	/** Tiles gathered round a fire, and where the fire is from the tile centre. */
	private volatile Map<Long, int[]> fires = Collections.emptyMap();

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
	void rebuild(List<StackSpreader.Placement> placements, java.util.Set<Long> curvedRowTiles)
	{
		rebuild(placements, curvedRowTiles, Collections.emptySet());
	}

	void rebuild(List<StackSpreader.Placement> members, java.util.Set<Long> curvedRowTiles, java.util.Set<Integer> unplacedIds)
	{
		rebuild(members, curvedRowTiles, unplacedIds, Collections.emptyMap());
	}

	void rebuild(List<StackSpreader.Placement> members, java.util.Set<Long> curvedRowTiles, java.util.Set<Integer> unplacedIds,
		Map<Long, int[]> fireByTile)
	{
		curvedRows = curvedRowTiles == null ? Collections.emptySet() : new java.util.HashSet<>(curvedRowTiles);
		unplaced = unplacedIds == null ? Collections.emptySet() : new java.util.HashSet<>(unplacedIds);
		fires = fireByTile == null ? Collections.emptyMap() : new HashMap<>(fireByTile);
		rebuild(members);
	}

	/** Where the fire this tile's crowd is gathered round is, from the tile centre, or null. */
	int[] fireAt(long tileKey)
	{
		return fires.get(tileKey);
	}

	/**
	 * Which way a player drawn at (dx, dz) from this tile's centre should face: towards the fire the
	 * crowd is gathered round, round towards what a curved row faces, or just their own facing.
	 */
	int drawOrientation(long tileKey, int orientation, int dx, int dz)
	{
		int[] fire = fires.get(tileKey);
		if (fire != null)
		{
			return StackSpreader.faceTowards(orientation, dx, dz, fire[0], fire[1]);
		}
		return curvedRows.contains(tileKey) ? StackSpreader.faceSameSpot(orientation, dx, dz) : orientation;
	}

	/** True if this player is on a crowded tile but wasn't given a spot this tick. */
	boolean isUnplaced(int id)
	{
		return unplaced.contains(id);
	}

	/** True if this tile is a curved row, whose players turn to face what the row is facing. */
	boolean isCurvedRow(long tileKey)
	{
		return curvedRows.contains(tileKey);
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
		curvedRows = Collections.emptySet();
		unplaced = Collections.emptySet();
		fires = Collections.emptyMap();
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
