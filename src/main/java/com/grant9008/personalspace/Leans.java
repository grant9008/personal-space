package com.grant9008.personalspace;

import java.util.Arrays;

/**
 * Where each busy player's body is, averaged over the animation they're playing.
 *
 * <p>A rod's cast or a hammer's swing moves the model every frame. Sorted by each frame's own
 * lean, two fishers side by side swapped which of them was drawn in front back and forth, and
 * flickered. Averaged over the animation, the lean settles within a moment and then holds still
 * for as long as they keep at it; it starts again when they change what they're doing.
 * Averages are kept in the model's own frame, before it's turned, so turning round doesn't
 * disturb them. Client thread only.
 */
final class Leans
{
	/** Frames averaged over at most; after that each new frame counts this little. */
	static final int FRAMES = 256;

	private final int[] animation = new int[OffsetTable.CAPACITY];
	private final double[] sumX = new double[OffsetTable.CAPACITY];
	private final double[] sumZ = new double[OffsetTable.CAPACITY];
	private final int[] count = new int[OffsetTable.CAPACITY];

	Leans()
	{
		Arrays.fill(animation, -1);
	}

	/** The average so far for a player playing this animation, or null if there's none yet. */
	double[] average(int id, int anim)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY || animation[id] != anim || count[id] == 0)
		{
			return null;
		}
		return new double[]{sumX[id] / count[id], sumZ[id] / count[id]};
	}

	/**
	 * Adds this frame's middle of the model, (x, z) in the model's own frame, for a player playing
	 * this animation, and returns their average so far. Null if the id is out of range.
	 */
	double[] settle(int id, int anim, double x, double z)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return null;
		}
		if (animation[id] != anim)
		{
			animation[id] = anim;
			sumX[id] = 0;
			sumZ[id] = 0;
			count[id] = 0;
		}
		if (count[id] < FRAMES)
		{
			count[id]++;
			sumX[id] += x;
			sumZ[id] += z;
		}
		else
		{
			sumX[id] += x - sumX[id] / FRAMES;
			sumZ[id] += z - sumZ[id] / FRAMES;
		}
		return new double[]{sumX[id] / count[id], sumZ[id] / count[id]};
	}
}
