package com.grant9008.personalspace;

import java.util.Arrays;

/**
 * Decides whether a player is standing still, without relying on animation ids.
 *
 * <p>A player counts as standing still when, on two game ticks in a row, they were exactly in the
 * middle of the same tile. Someone walking or running is between tiles, or on a different tile,
 * at least one of those ticks, so they never qualify and never get pulled into a group mid-stride.
 *
 * <p>Given where they are each tick, it also knows which way they came onto the tile they stand
 * on: from where they were the tick before they got there.
 *
 * <p>Client thread only. Knows nothing about RuneLite so it can be unit tested.
 */
final class StillnessTracker
{
	/** A position that isn't known. */
	static final int NOWHERE = Integer.MIN_VALUE;

	private final long[] tile = new long[OffsetTable.CAPACITY];
	/** Tick on which the player was last seen centred on {@link #tile}; 0 means "not centred". */
	private final int[] centredTick = new int[OffsetTable.CAPACITY];
	/** Tick on which the player was first seen centred on {@link #tile}, this time round. */
	private final int[] arrivedTick = new int[OffsetTable.CAPACITY];
	/** Where each player was on the tick they were last seen, in local units, on which layer, and that tick; 0 if not known. */
	private final int[] seenX = new int[OffsetTable.CAPACITY];
	private final int[] seenZ = new int[OffsetTable.CAPACITY];
	private final int[] seenLayer = new int[OffsetTable.CAPACITY];
	private final int[] seenTick = new int[OffsetTable.CAPACITY];
	/**
	 * Which way each player came onto {@link #tile}, this time round: where they were the tick before
	 * they first stood there, east and north of the middle of it. Both 0 when that isn't known (they
	 * appeared there, or came further than anyone walks or runs in a tick).
	 */
	private final int[] fromX = new int[OffsetTable.CAPACITY];
	private final int[] fromZ = new int[OffsetTable.CAPACITY];
	/** The last tick anyone was seen. */
	private int latest;

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
		return observe(id, tileKey, centred, tick, NOWHERE, NOWHERE);
	}

	/** As above, with where they are in local units, so which way they came onto a tile is known too. */
	boolean observe(int id, long tileKey, boolean centred, int tick, int x, int z)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return false;
		}
		// Where they were last tick, if they were seen then and on the same level.
		boolean seen = x != NOWHERE && seenTick[id] != 0 && seenTick[id] == tick - 1 && seenLayer[id] == StackRegistry.plane(tileKey);
		long east = seen ? (long) seenX[id] - x : 0;
		long north = seen ? (long) seenZ[id] - z : 0;
		seenX[id] = x;
		seenZ[id] = z;
		seenLayer[id] = StackRegistry.plane(tileKey);
		seenTick[id] = x == NOWHERE ? 0 : tick;
		latest = tick;
		if (!centred)
		{
			centredTick[id] = 0;
			return false;
		}
		boolean still = centredTick[id] != 0 && centredTick[id] == tick - 1 && tile[id] == tileKey;
		if (!still)
		{
			arrivedTick[id] = tick;
			boolean step = isStep(east, north);
			fromX[id] = step ? (int) east : 0;
			fromZ[id] = step ? (int) north : 0;
		}
		tile[id] = tileKey;
		centredTick[id] = tick;
		return still;
	}

	/** Whether this far from one tick to the next is a walk or a run, not a teleport. */
	private static boolean isStep(long east, long north)
	{
		long d = east * east + north * north;
		return d > 0 && d <= (long) OffsetTable.JUMP * OffsetTable.JUMP;
	}

	/** The tick this player came to stand where they are now; 0 if unknown. */
	int arrivedTick(int id)
	{
		return id >= 0 && id < OffsetTable.CAPACITY ? arrivedTick[id] : 0;
	}

	/**
	 * Which way this player came onto the tile they stand on, this time round, as {east, north}
	 * from its middle to where they were the tick before they got there; null if not known.
	 */
	int[] cameFrom(int id)
	{
		return id < 0 || id >= OffsetTable.CAPACITY || fromX[id] == 0 && fromZ[id] == 0 ? null : new int[]{fromX[id], fromZ[id]};
	}

	/**
	 * Which way this player, now standing at local (x, z) on this tile, came onto it, if that was
	 * lately: on their way there as of the last tick, or first standing there that tick or the one
	 * before. As {east, north} from (x, z); null if they have stood there longer, or it isn't known.
	 * Between ticks, someone who has only just stopped is still on their way, as far as ticks go.
	 */
	int[] cameFromLately(int id, long tileKey, int x, int z)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY || seenTick[id] == 0 || seenTick[id] != latest)
		{
			return null;
		}
		if (centredTick[id] == latest && tile[id] == tileKey)
		{
			return latest - arrivedTick[id] <= 1 ? cameFrom(id) : null;
		}
		long east = (long) seenX[id] - x;
		long north = (long) seenZ[id] - z;
		return seenLayer[id] == StackRegistry.plane(tileKey) && isStep(east, north) ? new int[]{(int) east, (int) north} : null;
	}

	void clear()
	{
		Arrays.fill(centredTick, 0);
		Arrays.fill(arrivedTick, 0);
		Arrays.fill(seenTick, 0);
		Arrays.fill(fromX, 0);
		Arrays.fill(fromZ, 0);
		latest = 0;
	}

	/** Local coordinates of a size-1 actor standing in the middle of a tile end in exactly half a tile. */
	static boolean isCentred(int localX, int localY)
	{
		return (localX & 127) == 64 && (localY & 127) == 64;
	}
}
