package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure layout logic: given who is standing on which tile, decide who gets nudged and by how much.
 * Knows nothing about RuneLite so it can be unit tested on its own.
 *
 * <p>Players on the same tile are sorted by id, so every client that runs this sees the same
 * player in the same slot, and a slot never jitters between ticks while the group is unchanged.
 */
final class StackSpreader
{
	/** One idle player standing on a tile. */
	static final class Entry
	{
		final int id;
		final long tile;
		final boolean local;

		Entry(int id, long tile, boolean local)
		{
			this.id = id;
			this.tile = tile;
			this.local = local;
		}
	}

	/** Where one player should be drawn relative to their real spot, in local units (x east, z north). */
	static final class Placement
	{
		final int id;
		final int dx;
		final int dz;

		Placement(int id, int dx, int dz)
		{
			this.id = id;
			this.dx = dx;
			this.dz = dz;
		}
	}

	private StackSpreader()
	{
	}

	/**
	 * @param entries      every idle player and the tile they stand on
	 * @param includeLocal whether the local player takes a slot too, or stays put in the middle
	 * @param maxStack     spread at most this many players per tile; the rest stay centred
	 * @param radius       ring radius in local units
	 * @return one placement per player that should move; anyone not listed stays where they are
	 */
	static List<Placement> place(List<Entry> entries, boolean includeLocal, int maxStack, int radius)
	{
		Map<Long, List<Entry>> byTile = new LinkedHashMap<>();
		for (Entry e : entries)
		{
			byTile.computeIfAbsent(e.tile, k -> new ArrayList<>(4)).add(e);
		}

		List<Placement> out = new ArrayList<>();
		for (List<Entry> group : byTile.values())
		{
			if (group.size() < 2)
			{
				continue; // nobody to be stacked with
			}
			group.sort(Comparator.comparingInt(e -> e.id));

			List<Entry> movable = new ArrayList<>(group.size());
			for (Entry e : group)
			{
				if (includeLocal || !e.local)
				{
					movable.add(e);
				}
			}

			int n = Math.min(movable.size(), maxStack);
			for (int i = 0; i < n; i++)
			{
				int[] off = ringOffset(i, n, radius);
				out.add(new Placement(movable.get(i).id, off[0], off[1]));
			}
		}
		return out;
	}

	/**
	 * Slot {@code slot} of {@code count} on a ring of the given radius.
	 * Slot 0 is due east and the rest go round anticlockwise, so two players end up
	 * side by side (east/west), which reads best from the default north-facing camera.
	 */
	static int[] ringOffset(int slot, int count, int radius)
	{
		if (count <= 0)
		{
			return new int[]{0, 0};
		}
		double angle = 2 * Math.PI * slot / count;
		return new int[]{
			(int) Math.round(radius * Math.cos(angle)),
			(int) Math.round(radius * Math.sin(angle))
		};
	}
}
