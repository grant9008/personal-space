package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers whether each crowded tile is laid out as a row (everyone facing the same thing) or a
 * crowd, and which way a row faces, so the layout doesn't change just because someone turns.
 *
 * <p>A player casting spells or skilling keeps turning towards what they're working on. If the tile's
 * shape were worked out from scratch every tick, each turn could flip the tile between a row and a
 * crowd, or swing the row round, and everyone on it would get new spots. So a row is kept for as
 * long as the same players are on the tile. A crowd can still become a row once everyone faces the
 * same way: people walking up to a bank booth only turn to face it after they arrive. That change
 * only goes one way, so it can happen at most once. Someone leaving (or briefly dropping out and
 * coming back) never changes the shape; only a genuinely new arrival does. Even then a row stays a
 * row, facing the same way, unless the facings have clearly changed.
 *
 * <p>Client thread only. Knows nothing about RuneLite; unit tested.
 */
final class ShapeMemory
{
	/** A row's direction is only updated if it has turned by more than this. */
	static final double KEEP_ANGLE = Math.toRadians(30);

	static final class Shape
	{
		final boolean row;
		/** Direction a row faces, radians in game convention; 0 for a crowd. */
		final double angle;

		Shape(boolean row, double angle)
		{
			this.row = row;
			this.angle = angle;
		}
	}

	private static final class Remembered
	{
		final List<Integer> ids;
		final boolean smart;
		final Shape shape;

		Remembered(List<Integer> ids, boolean smart, Shape shape)
		{
			this.ids = ids;
			this.smart = smart;
			this.shape = shape;
		}
	}

	private Map<Long, Remembered> previous = new HashMap<>();
	private Map<Long, Remembered> current = new HashMap<>();

	/** Diagnostics: how many times a tile's shape actually changed. */
	long changes;

	/** Call before deciding this tick's shapes. */
	void startTick()
	{
		previous = current;
		current = new HashMap<>();
	}

	/**
	 * The shape for a tile this tick.
	 *
	 * @param group everyone standing still on the tile, with their facings
	 * @param smart whether rows are allowed at all (the Smart arrangement)
	 */
	Shape decide(long tile, List<StackSpreader.Entry> group, boolean smart)
	{
		List<Integer> ids = new ArrayList<>(group.size());
		for (StackSpreader.Entry e : group)
		{
			ids.add(e.id);
		}
		Collections.sort(ids);

		Remembered old = previous.get(tile);
		if (old != null && old.smart == smart && old.ids.containsAll(ids))
		{
			// Same people, or some of them stepped away: keep the shape, and keep remembering
			// everyone so someone coming straight back doesn't count as a newcomer. The one change
			// allowed is a crowd that has since turned to face the same thing becoming a row.
			Double facing = smart && !old.shape.row && group.size() >= 2 ? StackSpreader.sharedFacing(group, StackSpreader.SAME_FACING) : null;
			if (facing == null)
			{
				current.put(tile, old);
				return old.shape;
			}
			Remembered promoted = new Remembered(old.ids, smart, new Shape(true, facing));
			changes++;
			current.put(tile, promoted);
			return promoted.shape;
		}
		Shape shape;
		if (!smart)
		{
			shape = new Shape(false, 0);
		}
		else
		{
			boolean wasRow = old != null && old.smart && old.shape.row;
			Double facing = StackSpreader.sharedFacing(group,
				wasRow ? StackSpreader.STILL_SAME_FACING : StackSpreader.SAME_FACING);
			if (facing == null)
			{
				shape = new Shape(false, 0);
			}
			else if (wasRow && angleBetween(facing, old.shape.angle) < KEEP_ANGLE)
			{
				shape = old.shape;
			}
			else
			{
				shape = new Shape(true, facing);
			}
		}
		if (old != null && (old.shape.row != shape.row || old.shape.angle != shape.angle))
		{
			changes++;
		}
		List<Integer> remembered = ids;
		if (old != null && old.smart == smart)
		{
			// Keep remembering people who stepped away, so their return isn't a new arrival.
			java.util.Set<Integer> union = new java.util.TreeSet<>(old.ids);
			union.addAll(ids);
			remembered = new ArrayList<>(union);
		}
		current.put(tile, new Remembered(remembered, smart, shape));
		return shape;
	}

	void clear()
	{
		previous.clear();
		current.clear();
	}

	static double angleBetween(double a, double b)
	{
		double d = Math.abs(a - b) % (2 * Math.PI);
		return d > Math.PI ? 2 * Math.PI - d : d;
	}
}
