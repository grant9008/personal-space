package com.grant9008.personalspace;

/**
 * Per-player draw offsets in local units, indexed by player id, and how players move into them.
 *
 * <p>Targets are set once per game tick and moved toward once per frame, and the published
 * {@code outX}/{@code outZ} values are read from the renderer's drawTemp call. All three happen on
 * the client thread (checked against the RuneLite 1.12.38 client), so plain arrays are enough.
 *
 * <p>Movement styles:
 * <ul>
 * <li>{@link PersonalSpaceConfig.Movement#WALK}: straight line at walking pace. While a player is
 * on the way, {@link #isWalking} is true and {@link #walkSeconds}/{@link #walkOrientation} tell
 * the draw shim which walk frame to show and which way to face.</li>
 * <li>{@link PersonalSpaceConfig.Movement#GLIDE}: eases in quickly, then settles.</li>
 * <li>{@link PersonalSpaceConfig.Movement#INSTANT}: jumps straight there.</li>
 * </ul>
 */
final class OffsetTable
{
	/** RuneScape never has more than this many players in the scene. */
	static final int CAPACITY = 2048;

	/** Glide time constant in seconds; about 95% of the way there after three of these. */
	private static final float TAU = 0.10f;
	/** Walking pace: the game's own, one tile (128 units) every 0.6 seconds. */
	static final float WALK_SPEED = 128f / 0.6f;

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
	void advance(float dtSeconds, PersonalSpaceConfig.Movement movement)
	{
		frame++;
		float k = movement == PersonalSpaceConfig.Movement.GLIDE ? 1f - (float) Math.exp(-dtSeconds / TAU) : 1f;
		for (int i = activeCount - 1; i >= 0; i--)
		{
			int id = active[i];
			if (movement == PersonalSpaceConfig.Movement.WALK)
			{
				walkTowardTarget(id, dtSeconds);
			}
			else
			{
				walking[id] = false;
				walkTime[id] = 0f;
				curX[id] = step(curX[id], tgtX[id], k);
				curZ[id] = step(curZ[id], tgtZ[id], k);
			}
			outX[id] = Math.round(curX[id]);
			outZ[id] = Math.round(curZ[id]);
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
	}

	private void walkTowardTarget(int id, float dt)
	{
		float ex = tgtX[id] - curX[id];
		float ez = tgtZ[id] - curZ[id];
		float dist = (float) Math.hypot(ex, ez);
		float stride = WALK_SPEED * dt;
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

	private static float step(float cur, int target, float k)
	{
		float next = cur + (target - cur) * k;
		return Math.abs(next - target) < 0.5f ? target : next;
	}
}
