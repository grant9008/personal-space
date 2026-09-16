package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Smart" arrangement: takes the per-tile starting spots from {@link StackSpreader} and lets
 * the whole crowd settle, the way people naturally give each other room.
 *
 * <ul>
 * <li>Players push apart from anyone closer than the chosen spacing, including still players on
 * neighbouring tiles, so a crowd spreads into empty space instead of into itself.</li>
 * <li>Nobody is drawn somewhere a player couldn't stand: not in a booth, stall, anvil or wall, and
 * not pressed against one ({@link Terrain}).</li>
 * <li>A gentle pull back towards the starting spot keeps the shape (e.g. a side-by-side row at a
 * bank booth) and keeps everyone near their real tile.</li>
 * </ul>
 *
 * <p>Pure and deterministic: the same crowd always settles the same way, so nobody jitters from one
 * tick to the next. Knows nothing about RuneLite; unit tested.
 */
final class CrowdLayout
{
	/** Where a player may be drawn. */
	interface Terrain
	{
		/** True if a player whose real spot is (fromX, fromZ) could be drawn standing at (x, z). Local units. */
		boolean canStand(int plane, int fromX, int fromZ, int x, int z);
	}

	/** A still player who isn't being moved but still takes up room. */
	static final class Obstacle
	{
		final int plane;
		final int x;
		final int z;

		Obstacle(int plane, int x, int z)
		{
			this.plane = plane;
			this.x = x;
			this.z = z;
		}
	}

	static final int ITERATIONS = 16;
	/** Nobody is drawn further than this from their real spot, in local units (three tiles). */
	static final int MAX_REACH = 384;
	private static final double PULL_TO_START = 0.12;
	private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

	private CrowdLayout()
	{
	}

	/**
	 * @param placements starting spots from {@link StackSpreader}; tiles are {@link StackRegistry} keys
	 * @param obstacles  still players who stay put but should be given room
	 * @param terrain    where players may stand
	 * @param spacing    how far apart players should be, in local units
	 * @return the settled placements, in the same order
	 */
	static List<StackSpreader.Placement> settle(List<StackSpreader.Placement> placements, List<Obstacle> obstacles,
		Terrain terrain, int spacing)
	{
		int n = placements.size();
		if (n == 0 || spacing <= 0)
		{
			return placements;
		}

		int[] plane = new int[n];
		double[] ax = new double[n];
		double[] az = new double[n];
		double[] sx = new double[n];
		double[] sz = new double[n];
		double[] px = new double[n];
		double[] pz = new double[n];
		double[] maxR = new double[n];

		Map<Long, Integer> groupSize = new HashMap<>();
		for (StackSpreader.Placement p : placements)
		{
			groupSize.merge(p.tile, 1, Integer::sum);
		}
		for (Obstacle o : obstacles)
		{
			// Players staying in the middle of a stacked tile count towards its size too.
			groupSize.computeIfPresent(StackRegistry.key(o.plane, o.x >> 7, o.z >> 7), (k, v) -> v + 1);
		}

		for (int i = 0; i < n; i++)
		{
			StackSpreader.Placement p = placements.get(i);
			plane[i] = StackRegistry.plane(p.tile);
			ax[i] = StackRegistry.sceneX(p.tile) * 128 + 64;
			az[i] = StackRegistry.sceneY(p.tile) * 128 + 64;
			int size = groupSize.getOrDefault(p.tile, 1);
			maxR[i] = Math.min(MAX_REACH, spacing * Math.max(1.0, (size - 1) / 2.0) + 1);
			double[] start = pullInside(terrain, plane[i], ax[i], az[i], ax[i] + p.dx, az[i] + p.dz);
			sx[i] = start[0];
			sz[i] = start[1];
			px[i] = sx[i];
			pz[i] = sz[i];
		}

		double maxStep = spacing / 3.0;
		for (int iter = 0; iter < ITERATIONS; iter++)
		{
			for (int i = 0; i < n; i++)
			{
				double fx = 0;
				double fz = 0;

				for (int j = 0; j < n; j++)
				{
					if (j == i || plane[j] != plane[i])
					{
						continue;
					}
					double[] push = push(px[i] - px[j], pz[i] - pz[j], spacing, i, j);
					fx += push[0] * 0.5;
					fz += push[1] * 0.5;
				}
				for (Obstacle o : obstacles)
				{
					if (o.plane != plane[i])
					{
						continue;
					}
					double[] push = push(px[i] - o.x, pz[i] - o.z, spacing, i, -1);
					fx += push[0];
					fz += push[1];
				}
				fx += (sx[i] - px[i]) * PULL_TO_START;
				fz += (sz[i] - pz[i]) * PULL_TO_START;

				double step = Math.hypot(fx, fz);
				if (step < 0.25)
				{
					continue;
				}
				if (step > maxStep)
				{
					fx *= maxStep / step;
					fz *= maxStep / step;
				}

				double cx = px[i] + fx;
				double cz = pz[i] + fz;
				double rx = cx - ax[i];
				double rz = cz - az[i];
				double r = Math.hypot(rx, rz);
				if (r > maxR[i])
				{
					cx = ax[i] + rx * maxR[i] / r;
					cz = az[i] + rz * maxR[i] / r;
				}

				if (stand(terrain, plane[i], ax[i], az[i], cx, cz))
				{
					px[i] = cx;
					pz[i] = cz;
				}
				else if (stand(terrain, plane[i], ax[i], az[i], cx, pz[i]))
				{
					px[i] = cx; // slide along whatever is blocking
				}
				else if (stand(terrain, plane[i], ax[i], az[i], px[i], cz))
				{
					pz[i] = cz;
				}
			}
		}

		List<StackSpreader.Placement> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			StackSpreader.Placement p = placements.get(i);
			out.add(new StackSpreader.Placement(p.id, p.tile,
				(int) Math.round(px[i] - ax[i]), (int) Math.round(pz[i] - az[i])));
		}
		return out;
	}

	/** How hard two players push each other apart. Zero once they're at least {@code spacing} apart. */
	private static double[] push(double dx, double dz, int spacing, int i, int j)
	{
		if (Math.abs(dx) >= spacing || Math.abs(dz) >= spacing)
		{
			return new double[]{0, 0};
		}
		double d = Math.hypot(dx, dz);
		if (d >= spacing)
		{
			return new double[]{0, 0};
		}
		double ux;
		double uz;
		if (d < 0.5)
		{
			// Exactly on top of each other: pick a fixed direction from who they are, so it's repeatable.
			double angle = GOLDEN_ANGLE * (i * 31 + j + 7);
			ux = Math.cos(angle);
			uz = Math.sin(angle);
		}
		else
		{
			ux = dx / d;
			uz = dz / d;
		}
		double strength = spacing - d;
		return new double[]{ux * strength, uz * strength};
	}

	private static boolean stand(Terrain terrain, int plane, double ax, double az, double x, double z)
	{
		return terrain.canStand(plane, (int) ax, (int) az, (int) Math.round(x), (int) Math.round(z));
	}

	/** Move a starting spot towards the real tile until it's somewhere a player could stand. */
	private static double[] pullInside(Terrain terrain, int plane, double ax, double az, double x, double z)
	{
		for (int k = 0; k < 6; k++)
		{
			if (stand(terrain, plane, ax, az, x, z))
			{
				return new double[]{x, z};
			}
			x = ax + (x - ax) * 0.6;
			z = az + (z - az) * 0.6;
		}
		return new double[]{ax, az};
	}
}
