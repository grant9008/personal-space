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
	/**
	 * Moves between two spots shorter than this just drift into place, with no walk animation, so
	 * small corrections don't look like shuffling. At 28, a quarter of a tile, someone moving back
	 * a spot in a packed ring slid there instead of walking.
	 */
	static final float MIN_WALK_DISTANCE = 16f;
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
	/**
	 * Set by the plugin for players the game has walking for real: their offset eases back at this
	 * share of walking pace. At full pace their body moved half as fast again as their legs, or
	 * sideways, as they walked out of a crowd: a slide. A gentle ease keeps body and legs together.
	 */
	static final float WALKING_OFF_PACE = 0.35f;
	private final boolean[] gentle = new boolean[CAPACITY];
	private final float[] walkTime = new float[CAPACITY];
	private final int[] walkFacing = new int[CAPACITY];

	/** Ids that currently have a non-zero target or are still moving back to zero. */
	private final int[] active = new int[CAPACITY];
	private final boolean[] isActive = new boolean[CAPACITY];
	private int activeCount;
	private int frame;
	/** Diagnostics: frames someone was moving without walking because the move was short. */
	long driftFrames;

	/** The farthest anyone is drawn from their real spot right now, in local units. */
	private float maxOffset;

	/**
	 * What {@link #keepClear} needs to know about the ground, in scene local units: whether a body
	 * can be carried in a straight line from one point to another without going through a wall or
	 * anything that can't be stood on, and whether there's room for a body standing at a point.
	 */
	interface Walls
	{
		boolean clear(int fromX, int fromZ, int toX, int toZ);

		boolean roomAt(int x, int z);
	}

	/**
	 * Someone following the way they really went round a wall (see {@link #keepClear}) goes at this
	 * much over their own pace to catch up: as fast as an offset otherwise runs out as they walk.
	 */
	static final float CATCH_UP = WALK_SPEED * WALKING_OFF_PACE;
	/** Further than this in one frame isn't a walk: a teleport, or the scene moving under them. */
	static final int JUMP = 3 * 128;
	/** Corners of the way someone went that are kept while they follow it. */
	static final int TRAIL_POINTS = 32;
	/** Half a body's width: a shortcut to a later corner of the way is only taken with this much room either side. */
	static final int HALF_BODY = 16;

	/** For {@link #keepClear}: the frame each player was last checked, where they really were and where they were drawn. */
	private final int[] clearFrame = longAgo();
	private final int[] seenX = new int[CAPACITY];
	private final int[] seenZ = new int[CAPACITY];
	private final float[] drawnX = new float[CAPACITY];
	private final float[] drawnZ = new float[CAPACITY];
	/** Following the way they really went: its corners still ahead of them, as (x, z) pairs, from {@code trailFrom} to {@code trailTo}. */
	private final boolean[] following = new boolean[CAPACITY];
	private final float[][] trail = new float[CAPACITY][];
	private final int[] trailFrom = new int[CAPACITY];
	private final int[] trailTo = new int[CAPACITY];
	/**
	 * Diagnostics, in frames, for {@link #keepClear}: someone's offset run out faster at a wall, someone
	 * waiting at one, following their way round one, their offset cut back at once (nothing to follow
	 * from), and offsets dropped for a teleport.
	 */
	long wallHurries;
	long wallHolds;
	long wallFollows;
	long wallShrinks;
	long wallJumps;

	private static int[] longAgo()
	{
		int[] a = new int[CAPACITY];
		java.util.Arrays.fill(a, Integer.MIN_VALUE / 2);
		return a;
	}

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

	/** Whether this player is on their way back to the middle of their tile. */
	boolean targetIsMiddle(int id)
	{
		return id >= 0 && id < CAPACITY && tgtX[id] == 0 && tgtZ[id] == 0;
	}

	/** Client thread. Whether this player is walking for real, so eases back gently (see {@link #WALKING_OFF_PACE}). */
	void setWalkingOff(int id, boolean on)
	{
		if (id >= 0 && id < CAPACITY)
		{
			gentle[id] = on;
		}
	}

	/** Client thread. */
	void setTarget(int id, int dx, int dz)
	{
		if (id < 0 || id >= CAPACITY)
		{
			return;
		}
		// Someone standing in their spot stays exactly there when it only shifts a little, as a crowd
		// tightens or loosens round them: they used to glide the few units over, and in a busy place
		// people slid about all the time. Once it has shifted far enough to be worth a step, they walk.
		boolean resting = !walking[id] && (curX[id] != 0f || curZ[id] != 0f) && (dx != 0 || dz != 0);
		if (resting && Math.hypot(dx - curX[id], dz - curZ[id]) < MIN_WALK_DISTANCE)
		{
			dx = Math.round(curX[id]);
			dz = Math.round(curZ[id]);
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
			following[id] = false;
		}
		activeCount = 0;
	}

	/** Whether anyone is drawn away from their real spot, or on their way back to it. */
	boolean anyActive()
	{
		return activeCount > 0;
	}

	/** Client thread, once per frame. Moves every active offset toward its target. */
	void advance(float dtSeconds)
	{
		frame++;
		float farthest = 0f;
		for (int i = activeCount - 1; i >= 0; i--)
		{
			int id = active[i];
			if (following[id] && clearFrame[id] != frame - 1)
			{
				following[id] = false; // not checked last frame (gone from view): their offset just runs out
			}
			if (!following[id])
			{
				walkTowardTarget(id, dtSeconds);
			}
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
		float stride = WALK_SPEED * dt * (gentle[id] ? WALKING_OFF_PACE : 1f);
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
				driftFrames++;
				return; // a small correction: drift, don't start a walk
			}
			walking[id] = true;
			walkTime[id] = 0f;
		}
		walkTime[id] += dt;
		walkFacing[id] = facing(ex, ez);
	}

	/**
	 * Client thread, once a frame after {@link #advance}, for each player with an offset: keeps
	 * someone whose offset is running out from being drawn through a wall or in anything that can't
	 * be stood on. A spot is only ever checked from the middle of its tile; walking off, someone is
	 * drawn where they really are plus what is left of their offset, and running out of a crowd of
	 * smiths, which reaches nearly two tiles out every way, that was across the room's wall from
	 * where they really ran, and through it by the door.
	 *
	 * <p>While it's clear (in the open, nearly always) nothing changes: their offset runs out as it
	 * did. When it isn't, they go as far as the wall lets them and wait there for their real self to
	 * come by; and if that has gone round the wall, through a door, they follow the way it really
	 * went, corner to corner, a little faster than it to catch up. Never a jump, and their legs keep
	 * pace with how far they're drawn moving (see SpreadingDrawCallbacks#movingLegs, and
	 * {@link #isWalking} for someone who has stopped).
	 *
	 * @param realX where they really are, in local units
	 * @param moving whether the game has them walking or running for real
	 * @param runningOut whether their offset is running out: on their way off their tile, or back into its middle
	 */
	void keepClear(int id, int realX, int realZ, boolean moving, float dtSeconds, boolean runningOut, Walls walls)
	{
		if (id < 0 || id >= CAPACITY)
		{
			return;
		}
		boolean known = clearFrame[id] == frame - 1;
		float lastX = drawnX[id];
		float lastZ = drawnZ[id];
		int lastRealX = seenX[id];
		int lastRealZ = seenZ[id];
		clearFrame[id] = frame;
		seenX[id] = realX;
		seenZ[id] = realZ;
		if (known && Math.abs(realX - lastRealX) + Math.abs(realZ - lastRealZ) > JUMP)
		{
			// Teleported, or the scene moved under them: there's no way to follow. Where they are.
			wallJumps += curX[id] != 0f || curZ[id] != 0f ? 1 : 0;
			following[id] = false;
			place(id, 0f, 0f, realX, realZ);
			return;
		}
		float realStep = known ? (float) Math.hypot(realX - lastRealX, realZ - lastRealZ) : 0f;
		if (following[id])
		{
			follow(id, realX, realZ, moving, realStep, dtSeconds, walls, lastX, lastZ);
			return;
		}
		float x = realX + curX[id];
		float z = realZ + curZ[id];
		// Passing through the middle of a tile on their way counts too: they're centred for a frame.
		boolean off = runningOut || known && realStep > 0f;
		if (!off || curX[id] == 0f && curZ[id] == 0f || fits(walls, realX, realZ, known, lastX, lastZ, x, z))
		{
			drawnX[id] = x;
			drawnZ[id] = z;
			return;
		}
		// However they're moved, no faster than following would take them.
		float most = (moving ? realStep : WALK_SPEED * dtSeconds) + CATCH_UP * dtSeconds + 1f;
		if (known)
		{
			// Running into a wall (or the anvil): what is left of their offset runs out faster, just
			// enough to keep them clear of it, so they turn along it towards where they really are.
			float keep = -1f;
			for (int k = 7; k >= 0 && keep < 0f; k--)
			{
				float f = k / 8f;
				if (fits(walls, realX, realZ, true, lastX, lastZ, realX + curX[id] * f, realZ + curZ[id] * f))
				{
					keep = f;
				}
			}
			if (keep >= 0f)
			{
				float lo = keep;
				float hi = keep + 1f / 8f;
				for (int i = 0; i < 4; i++)
				{
					float f = (lo + hi) / 2f;
					if (fits(walls, realX, realZ, true, lastX, lastZ, realX + curX[id] * f, realZ + curZ[id] * f))
					{
						lo = f;
					}
					else
					{
						hi = f;
					}
				}
				float hx = realX + curX[id] * lo;
				float hz = realZ + curZ[id] * lo;
				if (Math.hypot(hx - lastX, hz - lastZ) <= most)
				{
					wallHurries++;
					place(id, curX[id] * lo, curZ[id] * lo, realX, realZ);
					return;
				}
			}
		}
		if (known && fits(walls, realX, realZ, false, 0f, 0f, lastX, lastZ))
		{
			// Up against a wall (or the anvil): as far towards where their offset would have them as
			// it lets them, and wait there for their real self to come by.
			float lo = 0f;
			float hi = 1f;
			for (int i = 0; i < 5; i++)
			{
				float t = (lo + hi) / 2f;
				if (fits(walls, realX, realZ, true, lastX, lastZ, lastX + (x - lastX) * t, lastZ + (z - lastZ) * t))
				{
					lo = t;
				}
				else
				{
					hi = t;
				}
			}
			float heldX = lastX + (x - lastX) * lo;
			float heldZ = lastZ + (z - lastZ) * lo;
			// Only while their real self comes their way, or passes by: held while it went off, they
			// were left standing at the wall, further behind with every frame.
			if (Math.hypot(heldX - realX, heldZ - realZ) <= Math.hypot(lastX - lastRealX, lastZ - lastRealZ))
			{
				wallHolds++;
				place(id, heldX - realX, heldZ - realZ, realX, realZ);
				return;
			}
		}
		if (!known || !walls.clear(lastRealX, lastRealZ, Math.round(lastX), Math.round(lastZ)))
		{
			// Not drawn last frame, or not in plain view of where they really were then, so nothing
			// to follow from: what is left of their offset is cut back until it is on their side of the wall.
			float lo = 0f;
			float hi = 1f;
			for (int i = 0; i < 6; i++)
			{
				float t = (lo + hi) / 2f;
				if (fits(walls, realX, realZ, false, 0f, 0f, realX + curX[id] * t, realZ + curZ[id] * t))
				{
					lo = t;
				}
				else
				{
					hi = t;
				}
			}
			wallShrinks++;
			float keptX = curX[id] * lo;
			float keptZ = curZ[id] * lo;
			if (known)
			{
				// Drawn last frame where they can't be followed back from (stood in a spot on the far side
				// of a fence from their tile, say): cut back at once, they jumped most of a tile. They go
				// there no faster than following would take them instead.
				float ex = realX + keptX - lastX;
				float ez = realZ + keptZ - lastZ;
				float d = (float) Math.hypot(ex, ez);
				if (d > most)
				{
					keptX = lastX + ex / d * most - realX;
					keptZ = lastZ + ez / d * most - realZ;
				}
			}
			place(id, keptX, keptZ, realX, realZ);
			return;
		}
		// Their real self has gone round the wall, through a door say: follow the way it went, from
		// where it was a moment ago (in plain view of where they were drawn then).
		if (trail[id] == null)
		{
			trail[id] = new float[TRAIL_POINTS * 2];
		}
		trail[id][0] = lastRealX;
		trail[id][1] = lastRealZ;
		trailFrom[id] = 0;
		trailTo[id] = 1;
		following[id] = true;
		follow(id, realX, realZ, moving, realStep, dtSeconds, walls, lastX, lastZ);
	}

	/** Whether this player following the way they really went round a wall (see {@link #keepClear}). */
	boolean isFollowing(int id)
	{
		return id >= 0 && id < CAPACITY && following[id];
	}

	/** Where they'd be drawn fits: room for them there, in plain view of where they really are, and reached from where they were drawn without going through anything. */
	private static boolean fits(Walls walls, int realX, int realZ, boolean fromLast, float lastX, float lastZ, float x, float z)
	{
		int ix = Math.round(x);
		int iz = Math.round(z);
		return walls.roomAt(ix, iz) && walls.clear(realX, realZ, ix, iz)
			&& (!fromLast || walls.clear(Math.round(lastX), Math.round(lastZ), ix, iz));
	}

	/** Whether a body could go straight from one point to another with room either side. */
	private static boolean roomy(Walls walls, float fromX, float fromZ, float x, float z)
	{
		float ex = x - fromX;
		float ez = z - fromZ;
		float d = (float) Math.hypot(ex, ez);
		if (!walls.clear(Math.round(fromX), Math.round(fromZ), Math.round(x), Math.round(z)))
		{
			return false;
		}
		if (d < 1f)
		{
			return true;
		}
		float nx = -ez / d * HALF_BODY;
		float nz = ex / d * HALF_BODY;
		return walls.clear(Math.round(fromX + nx), Math.round(fromZ + nz), Math.round(x + nx), Math.round(z + nz))
			&& walls.clear(Math.round(fromX - nx), Math.round(fromZ - nz), Math.round(x - nx), Math.round(z - nz));
	}

	/** One frame of following the way they really went (see {@link #keepClear}), from where they were drawn last frame. */
	private void follow(int id, int realX, int realZ, boolean moving, float realStep, float dt, Walls walls, float x, float z)
	{
		wallFollows++;
		if (!addCorner(id, realX, realZ))
		{
			// More corners than can be kept: they've been going round in circles. Where they are.
			following[id] = false;
			place(id, 0f, 0f, realX, realZ);
			return;
		}
		float[] t = trail[id];
		int last = trailTo[id] - 1;
		// A shortcut to the latest corner they can see, with room for a body all the way.
		for (int k = last; k > trailFrom[id]; k--)
		{
			if (roomy(walls, x, z, t[2 * k], t[2 * k + 1]))
			{
				trailFrom[id] = k;
				break;
			}
		}
		// As far as their real self went this frame and a little more, or at a walk once it has stopped.
		float left = moving ? realStep + CATCH_UP * dt : WALK_SPEED * dt;
		float fromX = x;
		float fromZ = z;
		int at = trailFrom[id];
		while (left > 0f)
		{
			float ex = t[2 * at] - x;
			float ez = t[2 * at + 1] - z;
			float d = (float) Math.hypot(ex, ez);
			if (d <= left)
			{
				x = t[2 * at];
				z = t[2 * at + 1];
				left -= d;
				if (at == last)
				{
					break;
				}
				at++;
			}
			else
			{
				x += ex / d * left;
				z += ez / d * left;
				left = 0f;
			}
		}
		trailFrom[id] = at;
		float moved = (float) Math.hypot(x - fromX, z - fromZ);
		if (at == last && x == t[2 * last] && z == t[2 * last + 1])
		{
			// Caught up with where they really are.
			following[id] = false;
			walking[id] = false;
			walkTime[id] = 0f;
			place(id, 0f, 0f, realX, realZ);
			return;
		}
		if (!moving && moved > 0f)
		{
			// Their real self has stopped: drawn walking by this table, as anyone walking to a spot is.
			walking[id] = true;
			walkTime[id] += moved / WALK_SPEED;
			walkFacing[id] = facing(x - fromX, z - fromZ);
		}
		place(id, x - realX, z - realZ, realX, realZ);
	}

	/** Adds where they really are now to the way they went; false if there's no room left for it. */
	private boolean addCorner(int id, int x, int z)
	{
		float[] t = trail[id];
		int n = trailTo[id];
		if (t[2 * n - 2] == x && t[2 * n - 1] == z)
		{
			return true;
		}
		if (n >= 2 && trailFrom[id] < n - 1)
		{
			// Still going the same way as along the last stretch, which they haven't started on yet: it just gets longer.
			float ax = t[2 * n - 2] - t[2 * n - 4];
			float az = t[2 * n - 1] - t[2 * n - 3];
			float bx = x - t[2 * n - 2];
			float bz = z - t[2 * n - 1];
			if (Math.abs(ax * bz - az * bx) <= 2f * (Math.abs(ax) + Math.abs(az)) && ax * bx + az * bz > 0f)
			{
				t[2 * n - 2] = x;
				t[2 * n - 1] = z;
				return true;
			}
		}
		if (n == TRAIL_POINTS)
		{
			int from = trailFrom[id];
			if (from == 0)
			{
				return false;
			}
			System.arraycopy(t, 2 * from, t, 0, 2 * (n - from));
			n -= from;
			trailFrom[id] = 0;
		}
		t[2 * n] = x;
		t[2 * n + 1] = z;
		trailTo[id] = n + 1;
		return true;
	}

	/** Sets where this player is drawn from where they really are, as {@link #keepClear} decided. */
	private void place(int id, float dx, float dz, int realX, int realZ)
	{
		curX[id] = dx;
		curZ[id] = dz;
		outX[id] = Math.round(dx);
		outZ[id] = Math.round(dz);
		drawnX[id] = realX + dx;
		drawnZ[id] = realZ + dz;
		maxOffset = Math.max(maxOffset, (float) Math.hypot(outX[id], outZ[id]));
	}

	/** Game orientation for moving in direction (east, north): 0 south, 512 west, 1024 north, 1536 east. */
	static int facing(double east, double north)
	{
		double angle = Math.atan2(-east, -north);
		int o = (int) Math.round(angle * 1024 / Math.PI);
		return ((o % 2048) + 2048) % 2048;
	}
}
