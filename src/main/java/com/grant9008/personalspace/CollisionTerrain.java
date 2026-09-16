package com.grant9008.personalspace;

/**
 * Answers "could a player be drawn standing here?" from the game's own walkability map, so
 * spread-out players never appear inside a bank booth, market stall, anvil, table or wall.
 *
 * <p>A spot counts as standable when its tile can be walked on, it can be reached from the
 * player's real tile by ordinary steps without passing through a wall, and it isn't pressed right
 * up against a blocked neighbour (models of booths and stalls hang over the tile edge).
 *
 * <p>Coordinates are scene local units: 128 per tile, x east, z north. Flags use the game's
 * CollisionDataFlag bits, copied here so this class stays free of RuneLite and unit testable.
 */
final class CollisionTerrain
{
	static final int BLOCK_NORTH_WEST = 1;
	static final int BLOCK_NORTH = 2;
	static final int BLOCK_NORTH_EAST = 4;
	static final int BLOCK_EAST = 8;
	static final int BLOCK_SOUTH_EAST = 16;
	static final int BLOCK_SOUTH = 32;
	static final int BLOCK_SOUTH_WEST = 64;
	static final int BLOCK_WEST = 128;
	/** Object, floor decoration or blocked floor: the tile can't be stood on at all. */
	static final int BLOCK_FULL = 256 | 262144 | 2097152;

	/** How close to a blocked edge a player may be drawn, in local units. */
	static final int EDGE_MARGIN = 26;

	private static final int TILE = 128;

	/** flags[plane][sceneX][sceneY]; a plane may be null if unknown. */
	private final int[][][] flags;

	CollisionTerrain(int[][][] flagsByPlane)
	{
		this.flags = flagsByPlane;
	}

	/** Whether a player standing at (fromX, fromZ) could be drawn at (x, z). */
	boolean canStand(int plane, int fromX, int fromZ, int x, int z)
	{
		int[][] f = plane >= 0 && plane < flags.length ? flags[plane] : null;
		if (f == null)
		{
			return true; // no map: don't restrict
		}
		int tx = floorDiv(x);
		int tz = floorDiv(z);
		if (!inside(f, tx, tz) || (f[tx][tz] & BLOCK_FULL) != 0)
		{
			return false;
		}

		// Walk from the real tile to the target tile one step at a time, like the game's pathing.
		int cx = floorDiv(fromX);
		int cz = floorDiv(fromZ);
		if (!inside(f, cx, cz))
		{
			return false;
		}
		int guard = 0;
		while ((cx != tx || cz != tz) && guard++ < 8)
		{
			int sx = Integer.signum(tx - cx);
			int sz = Integer.signum(tz - cz);
			if (!canStep(f, cx, cz, sx, sz))
			{
				return false;
			}
			cx += sx;
			cz += sz;
		}
		if (cx != tx || cz != tz)
		{
			return false;
		}

		// Keep clear of blocked edges so overhanging models don't swallow the player.
		int ox = x - tx * TILE;
		int oz = z - tz * TILE;
		if (ox < EDGE_MARGIN && !canStep(f, tx, tz, -1, 0))
		{
			return false;
		}
		if (ox > TILE - 1 - EDGE_MARGIN && !canStep(f, tx, tz, 1, 0))
		{
			return false;
		}
		if (oz < EDGE_MARGIN && !canStep(f, tx, tz, 0, -1))
		{
			return false;
		}
		return oz <= TILE - 1 - EDGE_MARGIN || canStep(f, tx, tz, 0, 1);
	}

	/**
	 * True if a player on this tile, facing {@code angle}, is facing something: the next tile that
	 * way can't be walked onto (an anvil, tree, booth, range or water) or there's a wall in between.
	 */
	boolean facesObstacle(int plane, int tileX, int tileY, double angle)
	{
		int[][] f = plane >= 0 && plane < flags.length ? flags[plane] : null;
		if (f == null || !inside(f, tileX, tileY))
		{
			return false;
		}
		int aheadX = (int) Math.round(-Math.sin(angle));
		int aheadZ = (int) Math.round(-Math.cos(angle));
		return (aheadX != 0 || aheadZ != 0) && !canStep(f, tileX, tileY, aheadX, aheadZ);
	}

	/**
	 * True if a player on this tile, facing {@code angle}, is up against something long: the way
	 * ahead is blocked (by a booth, counter or wall) for their tile and for the tiles either side.
	 * That's a bank counter or a row of booths, as opposed to a single anvil or range.
	 */
	boolean isCounter(int plane, int tileX, int tileY, double angle)
	{
		int[][] f = plane >= 0 && plane < flags.length ? flags[plane] : null;
		if (f == null)
		{
			return false;
		}
		int aheadX = (int) Math.round(-Math.sin(angle));
		int aheadZ = (int) Math.round(-Math.cos(angle));
		if ((aheadX != 0) == (aheadZ != 0))
		{
			return false; // facing diagonally: counters run along tile edges
		}
		int sideX = aheadZ != 0 ? 1 : 0;
		int sideZ = aheadX != 0 ? 1 : 0;
		return !canStep(f, tileX, tileY, aheadX, aheadZ)
			&& blockedAheadOf(f, tileX + sideX, tileY + sideZ, aheadX, aheadZ)
			&& blockedAheadOf(f, tileX - sideX, tileY - sideZ, aheadX, aheadZ);
	}

	/** The way ahead from this tile is blocked, or this tile itself can't be stood on. */
	private static boolean blockedAheadOf(int[][] f, int x, int z, int aheadX, int aheadZ)
	{
		if (!inside(f, x, z) || (f[x][z] & BLOCK_FULL) != 0)
		{
			return true;
		}
		return !canStep(f, x, z, aheadX, aheadZ);
	}

	/** Whether the game would let a player take one step from (x, z) by (sx, sz), each -1, 0 or 1. */
	static boolean canStep(int[][] f, int x, int z, int sx, int sz)
	{
		int nx = x + sx;
		int nz = z + sz;
		if (!inside(f, nx, nz) || (f[nx][nz] & BLOCK_FULL) != 0)
		{
			return false;
		}
		int from = f[x][z];
		int to = f[nx][nz];
		if (sx != 0 && sz != 0)
		{
			// Diagonal: the corner itself and both orthogonal routes must be clear.
			int outCorner = sx > 0 ? (sz > 0 ? BLOCK_NORTH_EAST : BLOCK_SOUTH_EAST) : (sz > 0 ? BLOCK_NORTH_WEST : BLOCK_SOUTH_WEST);
			int inCorner = sx > 0 ? (sz > 0 ? BLOCK_SOUTH_WEST : BLOCK_NORTH_WEST) : (sz > 0 ? BLOCK_SOUTH_EAST : BLOCK_NORTH_EAST);
			return (from & outCorner) == 0 && (to & inCorner) == 0
				&& canStep(f, x, z, sx, 0) && canStep(f, x, z, 0, sz)
				&& canStep(f, x + sx, z, 0, sz) && canStep(f, x, z + sz, sx, 0);
		}
		if (sx > 0)
		{
			return (from & BLOCK_EAST) == 0 && (to & BLOCK_WEST) == 0;
		}
		if (sx < 0)
		{
			return (from & BLOCK_WEST) == 0 && (to & BLOCK_EAST) == 0;
		}
		if (sz > 0)
		{
			return (from & BLOCK_NORTH) == 0 && (to & BLOCK_SOUTH) == 0;
		}
		if (sz < 0)
		{
			return (from & BLOCK_SOUTH) == 0 && (to & BLOCK_NORTH) == 0;
		}
		return true;
	}

	private static boolean inside(int[][] f, int x, int z)
	{
		return x >= 0 && z >= 0 && x < f.length && f[x] != null && z < f[x].length;
	}

	private static int floorDiv(int v)
	{
		return Math.floorDiv(v, TILE);
	}
}
