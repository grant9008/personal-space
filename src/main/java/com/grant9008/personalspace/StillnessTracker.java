package com.grant9008.personalspace;

import java.util.Arrays;

/**
 * Decides whether a player is standing still, without relying on animation ids.
 *
 * <p>A player counts as standing still when, on two game ticks in a row, they were exactly in the
 * middle of the same tile. Someone walking or running is between tiles, or on a different tile,
 * at least one of those ticks, so they never qualify and never get pulled into a group mid-stride.
 *
 * <p>Client thread only. Knows nothing about RuneLite so it can be unit tested.
 */
final class StillnessTracker
{
	private final long[] tile = new long[OffsetTable.CAPACITY];
	/** Tick on which the player was last seen centred on {@link #tile}; 0 means "not centred". */
	private final int[] centredTick = new int[OffsetTable.CAPACITY];

	/**
	 * Record where a player is this tick.
	 *
	 * @param id       player id
	 * @param tileKey  the tile they are on
	 * @param centred  whether they are exactly in the middle of that tile right now
	 * @param tick     this tick's number; must go up by one each game tick and start at 1
	 * @return true if they were also centred on this same tile on the previous tick
	 */
	boolean observe(int id, long tileKey, boolean centred, int tick)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return false;
		}
		if (!centred)
		{
			centredTick[id] = 0;
			return false;
		}
		boolean still = centredTick[id] != 0 && centredTick[id] == tick - 1 && tile[id] == tileKey;
		tile[id] = tileKey;
		centredTick[id] = tick;
		return still;
	}

	void clear()
	{
		Arrays.fill(centredTick, 0);
	}

	/** Local coordinates of a size-1 actor standing in the middle of a tile end in exactly half a tile. */
	static boolean isCentred(int localX, int localY)
	{
		return (localX & 127) == 64 && (localY & 127) == 64;
	}
}
