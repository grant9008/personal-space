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
import net.runelite.client.callback.RenderCallbackManager;

/**
 * A thin shim installed in front of whatever renderer is active (the GPU plugin or 117 HD).
 *
 * <p>How the game draws players, checked against the RuneLite 1.12.38 client:
 * <ul>
 * <li>Players, NPCs and projectiles are "temporary" scene entities re-added every frame and drawn
 * through {@link #drawTemp}. {@code drawDynamic} only ever carries animated scenery and ground
 * items. (Version 0.1.0 hooked {@code drawDynamic} by mistake, so nothing moved.)</li>
 * <li>When several players stand still in the exact middle of one tile, the game adds only the
 * first of them to the scene each frame and skips the rest. That is why a stack looks like one
 * person, and why nudging draw calls alone can never reveal anyone.</li>
 * </ul>
 *
 * <p>So for a player draw on a stacked tile this shim does two things: it draws that player at its
 * own ring slot (if it has one), and then draws each hidden stackmate's model at that mate's slot,
 * reusing the same renderer call. Every other call is forwarded untouched. The real players, their
 * tiles, clickboxes, overhead text and chat bubbles are never modified. Hidden stackmates drawn
 * this way have no clickbox of their own, exactly as when the game hides them.
 *
 * <p>Every method of {@link DrawCallbacks} is overridden, including the ones with default bodies,
 * because a default body would silently swallow the call instead of forwarding it.
 *
 * <p>The counters are plain fields read by the plugin for the sidebar diagnostics.
 */
final class SpreadingDrawCallbacks implements DrawCallbacks
{
	private final Client client;
	private final OffsetTable offsets;
	private final StackRegistry stacks;
	private final RenderCallbackManager renderCallbacks;
	private final DrawCallbacks delegate;

	/** Players the renderer was asked to draw. */
	long playerDraws;
	/** Of those, how many we drew at a ring slot instead of their real spot. */
	long nudgedDraws;
	/** Hidden stackmates we drew ourselves. */
	long revealedDraws;
	/** Player draws for a scene other than the main one (e.g. on a boat); left alone. */
	long sceneMismatches;
	/** Players arriving through drawDynamic or the legacy draw call. Expected to stay 0. */
	long playersInOtherCalls;
	/** drawTemp calls made off the client thread. Expected to stay 0; such calls are passed through untouched. */
	long offThreadDraws;
	/** Errors while drawing a hidden stackmate (that mate is skipped for the frame). */
	long revealErrors;

	/** Frame each player id was last drawn by the game itself, and by us, so nobody is drawn twice in a frame. */
	private final int[] nativeFrame = new int[OffsetTable.CAPACITY];
	private final int[] revealedFrame = new int[OffsetTable.CAPACITY];

	SpreadingDrawCallbacks(Client client, OffsetTable offsets, StackRegistry stacks, RenderCallbackManager renderCallbacks, DrawCallbacks delegate)
	{
		this.client = client;
		this.offsets = offsets;
		this.stacks = stacks;
		this.renderCallbacks = renderCallbacks;
		this.delegate = delegate;
	}

	DrawCallbacks getDelegate()
	{
		return delegate;
	}

	// ---- the call players are drawn through --------------------------------------------

	@Override
	public void drawTemp(Projection projection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		Renderable renderable = gameObject == null ? null : gameObject.getRenderable();
		if (!(renderable instanceof Player))
		{
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
			return;
		}
		playerDraws++;
		if (!client.isClientThread())
		{
			offThreadDraws++;
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || scene != wv.getScene())
		{
			sceneMismatches++;
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
			return;
		}

		Player drawn = (Player) renderable;
		int drawnId = drawn.getId();
		if (drawnId >= 0 && drawnId < OffsetTable.CAPACITY)
		{
			nativeFrame[drawnId] = offsets.frame();
		}
		int plane = gameObject.getPlane();
		int dx = offsets.dx(drawn.getId());
		int dz = offsets.dz(drawn.getId());
		if (dx != 0 || dz != 0)
		{
			delegate.drawTemp(projection, scene, gameObject, model, orientation,
				x + dx, y + groundDelta(wv, plane, x, z, x + dx, z + dz), z + dz);
			nudgedDraws++;
		}
		else
		{
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
		}

		// Only a player standing exactly in the middle of its tile hides others there.
		if (!stacks.isEmpty() && StillnessTracker.isCentred(x, z))
		{
			drawHiddenStackmates(projection, scene, gameObject, drawn, wv, plane, x, y, z);
		}
	}

	/**
	 * Draw the players the game skipped on the drawn player's tile.
	 *
	 * <p>A mate still standing exactly where the drawn player is was certainly skipped, because the
	 * game draws only one centred player per tile. A mate that has since stepped off-centre is
	 * drawn by the game itself, so it is left alone to avoid drawing anyone twice.
	 */
	private void drawHiddenStackmates(Projection projection, Scene scene, GameObject gameObject, Player drawn, WorldView wv, int plane, int x, int y, int z)
	{
		int[] mates = stacks.membersAt(StackRegistry.key(plane, x >> 7, z >> 7));
		if (mates.length == 0)
		{
			return;
		}

		// The y the game passed is ground height minus the drawn player's own animation lift.
		int ground = y + drawn.getAnimationHeightOffset();
		int frame = offsets.frame();
		boolean touchedSharedModel = false;
		for (int id : mates)
		{
			if (id == drawn.getId() || id < 0 || id >= OffsetTable.CAPACITY
				|| nativeFrame[id] == frame || revealedFrame[id] == frame)
			{
				continue; // already drawn this frame, by the game or by us
			}
			try
			{
				Player mate = wv.players().byIndex(id);
				if (mate == null)
				{
					continue;
				}
				LocalPoint lp = mate.getLocalLocation();
				if (lp == null || lp.getX() != x || lp.getY() != z)
				{
					continue;
				}
				if (renderCallbacks != null && !renderCallbacks.addEntity(mate, false))
				{
					continue; // hidden by another plugin, e.g. Entity Hider: respect that
				}
				touchedSharedModel = true;
				Model mateModel = mate.getModel();
				if (mateModel == null)
				{
					continue;
				}
				int mdx = offsets.dx(id);
				int mdz = offsets.dz(id);
				int mateY = ground - mate.getAnimationHeightOffset() + groundDelta(wv, plane, x, z, x + mdx, z + mdz);
				delegate.drawTemp(projection, scene, gameObject, mateModel, mate.getCurrentOrientation(), x + mdx, mateY, z + mdz);
				revealedFrame[id] = frame;
				revealedDraws++;
			}
			catch (RuntimeException e)
			{
				revealErrors++;
			}
		}

		if (touchedSharedModel)
		{
			// Animated models share one buffer inside the game. Building the mates' models may have
			// overwritten the drawn player's, which the game uses for its click test right after this
			// call returns, so rebuild the drawn player's model to put it back.
			try
			{
				drawn.getModel();
			}
			catch (RuntimeException e)
			{
				revealErrors++;
			}
		}
	}

	/** Height difference between the ground at the real spot and at the drawn spot, or 0 if unknown. */
	private int groundDelta(WorldView wv, int plane, int x, int z, int nx, int nz)
	{
		if (nx == x && nz == z)
		{
			return 0;
		}
		int maxX = wv.getSizeX() * Perspective.LOCAL_TILE_SIZE;
		int maxZ = wv.getSizeY() * Perspective.LOCAL_TILE_SIZE;
		if (x < 0 || z < 0 || nx < 0 || nz < 0 || x >= maxX || nx >= maxX || z >= maxZ || nz >= maxZ)
		{
			return 0;
		}
		try
		{
			int before = Perspective.getTileHeight(client, new LocalPoint(x, z, wv), plane);
			int after = Perspective.getTileHeight(client, new LocalPoint(nx, nz, wv), plane);
			return after - before;
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	// ---- everything below is a straight pass-through -----------------------------------

	@Override
	public void drawDynamic(int thread, Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		if (renderable instanceof Player)
		{
			playersInOtherCalls++;
		}
		delegate.drawDynamic(thread, projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void drawDynamic(Projection projection, Scene scene, TileObject tileObject, Renderable renderable, Model model, int orientation, int x, int y, int z)
	{
		if (renderable instanceof Player)
		{
			playersInOtherCalls++;
		}
		delegate.drawDynamic(projection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (renderable instanceof Player)
		{
			playersInOtherCalls++;
		}
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
