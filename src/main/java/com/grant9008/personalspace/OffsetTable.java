package com.grant9008.personalspace;

/**
 * Per-player draw offsets in local units, indexed by player id, and how players move into them.
 *
 * <p>Targets are set once per game tick and moved toward once per frame, and the published
 * {@code outX}/{@code outZ} values are read from the renderer's drawTemp call. All three happen on
 * the client thread (checked against the RuneLite 1.12.38 client), so plain arrays are enough.
 *
 * <p>Players walk to their spot in a straight line at the game's walking pace. While a player is on
 * the way, {@link #isWalking} is true and {@link #walkSeconds}/{@link #walkOrientation} tell the draw
 * shim which walk frame to show and which way to face.
 */
final class OffsetTable
{
	/** RuneScape never has more than this many players in the scene. */
	static final int CAPACITY = 2048;

	/** Walking pace: the game's own, one tile (128 units) every 0.6 seconds. */
	static final float WALK_SPEED = 128f / 0.6f;
	/** Moves between two spots shorter than this just drift into place, with no walk animation, so small corrections don't look like shuffling. */
	static final float MIN_WALK_DISTANCE = 28f;
	/**
	 * Stepping out of the middle of a tile, or back into it, walks from this far. That is a real
	 * step even when it's short: two at a bank stand half a body's width either side of the middle,
	 * 24 each, and used to slide apart (and the one left behind slide back) while three walked.
	 */
	static final float MIN_STEP_DISTANCE = 12f;

	private final float[] curX = new float[CAPACITY];
	private final float[] curZ = new float[CAPACITY];
	private final int[] tgtX = new int[CAPACITY];
	private final int[] tgtZ = new int[CAPACITY];
	private final int[] outX = new int[CAPACITY];
	private final int[] outZ = new int[CAPACITY];

	private final boolean[] walking = new boolean[CAPACITY];
	private final float[] walkTime = new float[CAPACITY];
	private final int[] walkFacing = new int[CAPACITY];

	/** Ids that currently have a non-zero target or are still moving back to zero. */
	private final int[] active = new int[CAPACITY];
	private final boolean[] isActive = new boolean[CAPACITY];
	private int activeCount;
	private int frame;
	/** The farthest anyone is drawn from their real spot right now, in local units. */
	private float maxOffset;

	float maxOffset()
	{
		return maxOffset;
	}

	int dx(int id)
	{
		return id >= 0 && id < CAPACITY ? outX[id] : 0;
	}

	int dz(int id)
	{
		return id >= 0 && id < CAPACITY ? outZ[id] : 0;
	}

	/** True while this player is drawn away from their real spot, including while moving back. */
	boolean isOffset(int id)
	{
		return id >= 0 && id < CAPACITY && (outX[id] != 0 || outZ[id] != 0);
	}

	/** True while this player is walking to (or back from) their spot. */
	boolean isWalking(int id)
	{
		return id >= 0 && id < CAPACITY && walking[id];
	}

	/** Seconds this player has been walking, to pick the walk animation frame. */
	float walkSeconds(int id)
	{
		return id >= 0 && id < CAPACITY ? walkTime[id] : 0f;
	}

	/** Which way a walking player faces, in game orientation units (0 south, 512 west, 1024 north, 1536 east). */
	int walkOrientation(int id)
	{
		return id >= 0 && id < CAPACITY ? walkFacing[id] : 0;
	}

	/** True if this player currently has somewhere other than their real spot to be. */
	boolean hasTarget(int id)
	{
		return id >= 0 && id < CAPACITY && isActive[id] && (tgtX[id] != 0 || tgtZ[id] != 0);
	}

	int targetX(int id)
	{
		return id >= 0 && id < CAPACITY ? tgtX[id] : 0;
	}

	int targetZ(int id)
	{
		return id >= 0 && id < CAPACITY ? tgtZ[id] : 0;
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

	/** Client thread. Everyone back to their real spot immediately, no movement. Used for safety and shutdown. */
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
			walking[id] = false;
			walkTime[id] = 0f;
			isActive[id] = false;
		}
		activeCount = 0;
	}

	/** Client thread, once per frame. Moves every active offset toward its target. */
	void advance(float dtSeconds)
	{
		frame++;
		float farthest = 0f;
		for (int i = activeCount - 1; i >= 0; i--)
		{
			int id = active[i];
			walkTowardTarget(id, dtSeconds);
			outX[id] = Math.round(curX[id]);
			outZ[id] = Math.round(curZ[id]);
			farthest = Math.max(farthest, (float) Math.hypot(outX[id], outZ[id]));
			if (tgtX[id] == 0 && tgtZ[id] == 0 && curX[id] == 0f && curZ[id] == 0f)
			{
				// Fully back home: drop it from the active list (swap-remove; the slot we pull in
				// from the end was already visited this pass).
				walking[id] = false;
				walkTime[id] = 0f;
				isActive[id] = false;
				active[i] = active[--activeCount];
			}
		}
		maxOffset = farthest;
	}

	private void walkTowardTarget(int id, float dt)
	{
		float ex = tgtX[id] - curX[id];
		float ez = tgtZ[id] - curZ[id];
		float dist = (float) Math.hypot(ex, ez);
		float stride = WALK_SPEED * dt;
		// Out of the middle of the tile or back into it, rather than from one spot to another.
		boolean stepInOrOut = (tgtX[id] == 0 && tgtZ[id] == 0) || (curX[id] == 0f && curZ[id] == 0f);
		if (dist <= Math.max(stride, 0.5f))
		{
			curX[id] = tgtX[id];
			curZ[id] = tgtZ[id];
			walking[id] = false;
			walkTime[id] = 0f;
			return;
		}
		curX[id] += ex / dist * stride;
		curZ[id] += ez / dist * stride;
		if (!walking[id])
		{
			if (dist < (stepInOrOut ? MIN_STEP_DISTANCE : MIN_WALK_DISTANCE))
			{
				return; // a small correction: drift, don't start a walk
			}
			walking[id] = true;
			walkTime[id] = 0f;
		}
		walkTime[id] += dt;
		walkFacing[id] = facing(ex, ez);
	}

	/** Game orientation for moving in direction (east, north): 0 south, 512 west, 1024 north, 1536 east. */
	static int facing(double east, double north)
	{
		double angle = Math.atan2(-east, -north);
		int o = (int) Math.round(angle * 1024 / Math.PI);
		return ((o % 2048) + 2048) % 2048;
	}
}
