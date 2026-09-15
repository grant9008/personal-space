package com.grant9008.personalspace;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * A thin shim installed in front of whatever renderer is active (the GPU plugin or 117 HD).
 *
 * <p>Every call is forwarded untouched, except the one the client makes to draw a player:
 * there the x/z it is about to draw at get this player's current offset added, and the
 * height is re-sampled from the ground at the new spot so feet stay planted on slopes.
 * The player object itself, its tile, its overhead text and its chat bubble are never touched.
 *
 * <p>Every method of {@link DrawCallbacks} is overridden, including the ones with default
 * bodies, because a default body would silently swallow the call instead of forwarding it.
 */
final class SpreadingDrawCallbacks implements DrawCallbacks
{
	private final Client client;
	private final OffsetTable offsets;
	private final DrawCallbacks delegate;

	SpreadingDrawCallbacks(Client client, OffsetTable offsets, DrawCallbacks delegate)
	{
		this.client = client;
		this.offsets = offsets;
		this.delegate = delegate;
	}

	DrawCallbacks getDelegate()
	{
		return delegate;
	}

	// ---- the one call we care about ----------------------------------------------------

	@Override
	public void drawDynamic(int thread, Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		if (renderable instanceof Player && scene == offsets.scene())
		{
			Player player = (Player) renderable;
			int id = player.getId();
			int dx = offsets.dx(id);
			int dz = offsets.dz(id);
			if (dx != 0 || dz != 0)
			{
				int nx = x + dx;
				int nz = z + dz;
				y += groundDelta(player, x, z, nx, nz);
				x = nx;
				z = nz;
			}
		}
		delegate.drawDynamic(thread, projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	/** Height difference between the ground at the real spot and at the drawn spot. */
	private int groundDelta(Player player, int x, int z, int nx, int nz)
	{
		try
		{
			WorldView wv = player.getWorldView();
			if (wv == null)
			{
				return 0;
			}
			int plane = wv.getPlane();
			int before = Perspective.getTileHeight(client, new LocalPoint(x, z, wv), plane);
			int after = Perspective.getTileHeight(client, new LocalPoint(nx, nz, wv), plane);
			return after - before;
		}
		catch (RuntimeException e)
		{
			// Never let a bad lookup take the whole frame down; a flat offset is fine for one frame.
			return 0;
		}
	}

	// ---- everything below is a straight pass-through -----------------------------------

	@Override
	public void drawDynamic(Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		delegate.drawDynamic(projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void drawTemp(Projection projection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		delegate.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
	{
		delegate.drawScenePaint(scene, paint, plane, tileX, tileY);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY)
	{
		delegate.drawSceneTileModel(scene, model, tileX, tileY);
	}

	@Override
	public void draw(int overlayColor)
	{
		delegate.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		delegate.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		delegate.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		delegate.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		delegate.loadScene(scene);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		delegate.loadScene(worldView, scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		delegate.swapScene(scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		delegate.despawnWorldView(worldView);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		return delegate.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int a, int b, int c, int d)
	{
		return delegate.zoneInFrustum(a, b, c, d);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection projection, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw, int plane, int minLevel, int maxLevel, Set<Integer> zones)
	{
		delegate.preSceneDraw(scene, projection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw, int plane, int minLevel, int maxLevel, Set<Integer> zones)
	{
		delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		delegate.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		delegate.drawPass(projection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection projection, Scene scene, int zx, int zy)
	{
		delegate.drawZoneOpaque(projection, scene, zx, zy);
	}

	@Override
	public void drawZoneAlpha(Projection projection, Scene scene, int zx, int zy, int level)
	{
		delegate.drawZoneAlpha(projection, scene, zx, zy, level);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zy)
	{
		delegate.invalidateZone(scene, zx, zy);
	}
}
