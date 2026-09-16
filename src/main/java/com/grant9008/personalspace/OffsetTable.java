package com.grant9008.personalspace;

/**
 * Per-player draw offsets in local units, indexed by player id.
 *
 * <p>Targets are set once per game tick and eased toward once per frame, and the published
 * {@code outX}/{@code outZ} values are read from the renderer's drawTemp call. All three happen on
 * the client thread (checked against the RuneLite 1.12.38 client), so plain arrays are enough.
 */
final class OffsetTable
{
	/** RuneScape never has more than this many players in the scene. */
	static final int CAPACITY = 2048;

	/** Ease-in time constant in seconds; about 95% of the way there after three of these. */
	private static final float TAU = 0.10f;

	private final float[] curX = new float[CAPACITY];
	private final float[] curZ = new float[CAPACITY];
	private final int[] tgtX = new int[CAPACITY];
	private final int[] tgtZ = new int[CAPACITY];
	private final int[] outX = new int[CAPACITY];
	private final int[] outZ = new int[CAPACITY];

	/** Ids that currently have a non-zero target or are still easing back to zero. */
	private final int[] active = new int[CAPACITY];
	private final boolean[] isActive = new boolean[CAPACITY];
	private int activeCount;
	private int frame;

	int dx(int id)
	{
		return id >= 0 && id < CAPACITY ? outX[id] : 0;
	}

	int dz(int id)
	{
		return id >= 0 && id < CAPACITY ? outZ[id] : 0;
	}

	/** True while this player is drawn away from their real spot, including while easing back. */
	boolean isOffset(int id)
	{
		return id >= 0 && id < CAPACITY && (outX[id] != 0 || outZ[id] != 0);
	}

	/** Counts frames; used to tell draws in the same frame apart. */
	int frame()
	{
		return frame;
	}

	/** Client thread. Zero every target before this tick's placements are applied. */
	void clearTargets()
	{
		for (int i = 0; i < activeCount; i++)
		{
			int id = active[i];
			tgtX[id] = 0;
			tgtZ[id] = 0;
		}
	}

	/** Client thread. */
	void setTarget(int id, int dx, int dz)
	{
		if (id < 0 || id >= CAPACITY)
		{
			return;
		}
		tgtX[id] = dx;
		tgtZ[id] = dz;
		if (!isActive[id] && (dx != 0 || dz != 0))
		{
			isActive[id] = true;
			active[activeCount++] = id;
		}
	}

	/** Client thread. Everyone back to their real spot immediately, no easing. Used for safety and shutdown. */
	void snapAllToZero()
	{
		for (int i = 0; i < activeCount; i++)
		{
			int id = active[i];
			curX[id] = 0f;
			curZ[id] = 0f;
			tgtX[id] = 0;
			tgtZ[id] = 0;
			outX[id] = 0;
			outZ[id] = 0;
			isActive[id] = false;
		}
		activeCount = 0;
	}

	/** Client thread, once per frame. Moves every active offset toward its target. */
	void advance(float dtSeconds, boolean smooth)
	{
		frame++;
		float k = smooth ? 1f - (float) Math.exp(-dtSeconds / TAU) : 1f;
		for (int i = activeCount - 1; i >= 0; i--)
		{
			int id = active[i];
			curX[id] = step(curX[id], tgtX[id], k);
			curZ[id] = step(curZ[id], tgtZ[id], k);
			outX[id] = Math.round(curX[id]);
			outZ[id] = Math.round(curZ[id]);
			if (tgtX[id] == 0 && tgtZ[id] == 0 && curX[id] == 0f && curZ[id] == 0f)
			{
				// Fully eased back home: drop it from the active list (swap-remove; the slot
				// we pull in from the end was already visited this pass).
				isActive[id] = false;
				active[i] = active[--activeCount];
			}
		}
	}

	private static float step(float cur, int target, float k)
	{
		float next = cur + (target - cur) * k;
		return Math.abs(next - target) < 0.5f ? target : next;
	}
}
