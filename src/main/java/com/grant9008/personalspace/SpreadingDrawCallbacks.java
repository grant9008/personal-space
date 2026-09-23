package com.grant9008.personalspace;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
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
 * <p>Who is drawn over whom: the GPU plugin draws every player and NPC of a frame in one batch,
 * in the order the game hands them over, and (as they are RENDERMODE_SORTED_NO_DEPTH) without
 * any depth test between them. Whoever is drawn later is on top, wherever they stand. The game
 * hands them over tile by tile, back to front, which is right until people are drawn a tile from
 * where they stand: then someone behind you, handed over a moment later, was drawn over you. So
 * while a crowd is being spread, players and NPCs aren't drawn as they come. They are kept until
 * the renderer asks for its opaque pass, and drawn then, farthest from the camera first, so
 * whoever is between you and the camera covers you and whoever is behind you doesn't, as in the
 * real world. (With "Draw me in front" on you're drawn after everyone in your crowd, so nobody
 * near you is drawn over you.) Each kept model is built again at that point
 * (the game's animated models share one
 * buffer), so to save that work anyone farther from the camera than every crowd, and than
 * anywhere anyone is drawn, is drawn straight away as before: they belong under the kept set
 * anyway. Nobody nearer is, whichever tile they are on, so the order is right all the way out.
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
	private final StackProbe probe;
	private final RenderCallbackManager renderCallbacks;
	private final DrawCallbacks delegate;

	/** Players the renderer was asked to draw. */
	long playerDraws;
	/** Of those, how many we drew at a ring slot instead of their real spot. */
	long nudgedDraws;
	/** Hidden stackmates we drew ourselves. */
	long revealedDraws;
	/** Player draws for a scene no world view owns; left alone. */
	long sceneMismatches;
	/** Players arriving through drawDynamic or the legacy draw call. Expected to stay 0. */
	long playersInOtherCalls;
	/** drawTemp calls made off the client thread. Expected to stay 0; such calls are passed through untouched. */
	long offThreadDraws;
	/** Errors while drawing a hidden stackmate (that mate is skipped for the frame). */
	long revealErrors;
	/** Players waiting in the middle of a tile between you and the camera, left undrawn. */
	long hiddenInYourWay;
	/** Player models drawn mid-step with their walk animation. */
	long walkDraws;
	/** Players and NPCs drawn farthest first, once the renderer asked for its opaque pass. */
	long orderedDraws;
	/** Frames where draws were kept back but the renderer never asked for its opaque pass. Expected to stay 0. */
	long passMissedFrames;
	/** Moving players drawn without walk frames because they were busy with an emote or action. */
	long walkSkippedBusy;
	/** Moving players drawn without walk frames because the animation couldn't be loaded (yet). */
	long walkSkippedNoAnimation;

	/** Walk animation timing by animation id, loaded once. */
	private final Map<Integer, WalkTiming> walkTimings = new HashMap<>();

	/** Frame each player id was last drawn by the game itself, and by us, so nobody is drawn twice in a frame. */
	private final int[] nativeFrame = new int[OffsetTable.CAPACITY];
	private final int[] revealedFrame = new int[OffsetTable.CAPACITY];

	/** Scratch list for the players held back on one tile this frame. */
	private final int[] held = new int[64];

	SpreadingDrawCallbacks(Client client, OffsetTable offsets, StackRegistry stacks, StackProbe probe, RenderCallbackManager renderCallbacks, DrawCallbacks delegate)
	{
		this.client = client;
		this.offsets = offsets;
		this.stacks = stacks;
		this.probe = probe;
		this.renderCallbacks = renderCallbacks;
		this.delegate = delegate;
	}

	DrawCallbacks getDelegate()
	{
		return delegate;
	}

	// ---- the call players are drawn through --------------------------------------------

	/** A player or NPC kept back to be drawn in depth order: where, facing which way, and how. */
	private static final class Kept
	{
		Projection projection;
		Scene scene;
		GameObject gameObject;
		Renderable actor;
		int orientation;
		int walkOrientation;
		int x;
		int y;
		int z;
		boolean walk;
		double distance;
	}

	private Kept[] kept = new Kept[128];
	private int keptCount;
	/**
	 * The renderer asks for its opaque pass once the game has handed over every player and NPC of
	 * the frame; only after seeing that once can draws be kept back until then. If they were kept
	 * back and that pass never came, this renderer draws in its own order, and they're never kept
	 * back again.
	 */
	private boolean opaquePassSeen;
	private boolean passMissed;

	/**
	 * With "Draw me in front" on, you're drawn over everyone this close to you (a tile and a
	 * half, your crowd), whichever way the camera faces: seeing yourself comes from the order
	 * people are drawn in, not from anyone moving out of your way.
	 */
	static final int NEAR_YOU = 192;
	/** You're drawn where you are, and only win a dead heat: a hair ahead. */
	static final int YOU_FIRST_ON_TIES = 4;
	/** The "Draw me in front" setting; kept up to date by the plugin. */
	volatile boolean drawMeInFront;

	/**
	 * Actors are kept back when they are drawn no farther from the camera than the farthest
	 * stacked tile, plus the farthest anyone is drawn from their tile, plus this to spare: a tile
	 * and a half. Everyone drawn farther is drawn as they come, and so ends up under the kept set,
	 * which is right: every kept actor is drawn nearer than them.
	 */
	static final int KEEP_SLACK = 192;

	/** The frame {@link #keepWithin} was worked out for, and the distance itself (-1: keep nobody). */
	private int keepFrame = -1;
	private double keepWithin;

	/** How far from the camera actors are kept back this frame. Worked out once per frame. */
	private double keepWithin(WorldView wv)
	{
		int frame = offsets.frame();
		if (keepFrame != frame)
		{
			keepFrame = frame;
			double cameraX = client.getCameraX();
			double cameraHeight = client.getCameraZ();
			double cameraZ = client.getCameraY();
			double farthest = -1;
			for (long tile : stacks.stackedTiles())
			{
				if (StackRegistry.worldViewOf(StackRegistry.plane(tile)) != WorldView.TOPLEVEL)
				{
					continue; // a boat's deck has coordinates of its own
				}
				int x = StackRegistry.sceneX(tile) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_HALF_TILE_SIZE;
				int z = StackRegistry.sceneY(tile) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_HALF_TILE_SIZE;
				double east = x - cameraX;
				double up = tileHeight(wv, StackRegistry.planeOf(StackRegistry.plane(tile)), x, z) - cameraHeight;
				double north = z - cameraZ;
				farthest = Math.max(farthest, Math.sqrt(east * east + up * up + north * north));
			}
			keepWithin = farthest < 0 ? -1 : farthest + offsets.maxOffset() + KEEP_SLACK;
		}
		return keepWithin;
	}

	/** Whether an actor drawn at (x, y, z) is near enough the camera to be kept back. */
	private boolean withinKeep(WorldView wv, int x, int y, int z)
	{
		double within = keepWithin(wv);
		if (within < 0)
		{
			return false;
		}
		double east = x - client.getCameraX();
		double up = y - client.getCameraZ();
		double north = z - client.getCameraY();
		return east * east + up * up + north * north <= within * within;
	}

	/** Whether an NPC standing at (x, y, z) on this scene is kept back for the opaque pass. */
	private boolean keeping(Scene scene, int x, int y, int z)
	{
		if (!opaquePassSeen || passMissed || stacks.isEmpty() || !client.isClientThread())
		{
			return false;
		}
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && scene == wv.getScene() && withinKeep(wv, x, y, z);
	}

	/** Ground height at a spot, or 0 if it can't be read. */
	private int tileHeight(WorldView wv, int plane, int x, int z)
	{
		int maxX = wv.getSizeX() * Perspective.LOCAL_TILE_SIZE;
		int maxZ = wv.getSizeY() * Perspective.LOCAL_TILE_SIZE;
		if (x < 0 || z < 0 || x >= maxX || z >= maxZ)
		{
			return 0;
		}
		try
		{
			return Perspective.getTileHeight(client, new LocalPoint(x, z, wv), plane);
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private void keep(Projection projection, Scene scene, GameObject gameObject, Renderable actor, int orientation, int walkOrientation, int x, int y, int z, boolean walk)
	{
		if (keptCount == kept.length)
		{
			kept = Arrays.copyOf(kept, kept.length * 2);
		}
		Kept k = kept[keptCount];
		if (k == null)
		{
			k = kept[keptCount] = new Kept();
		}
		k.projection = projection;
		k.scene = scene;
		k.gameObject = gameObject;
		k.actor = actor;
		k.orientation = orientation;
		k.walkOrientation = walkOrientation;
		k.x = x;
		k.y = y;
		k.z = z;
		k.walk = walk;
		keptCount++;
	}

	/**
	 * Draw everyone kept back this frame, farthest from the camera first. Each model is built
	 * afresh here: animated models share one buffer inside the game, so the one handed over
	 * with the draw call is long gone.
	 */
	private void drawKept()
	{
		if (keptCount == 0)
		{
			return;
		}
		double cameraX = client.getCameraX();
		double cameraHeight = client.getCameraZ();
		double cameraZ = client.getCameraY();
		Player local = client.getLocalPlayer();
		Kept you = null;
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			double east = k.x - cameraX;
			double up = k.y - cameraHeight;
			double north = k.z - cameraZ;
			k.distance = Math.sqrt(east * east + up * up + north * north) - (k.actor == local ? YOU_FIRST_ON_TIES : 0);
			if (k.actor == local)
			{
				you = k;
			}
		}
		if (drawMeInFront && you != null)
		{
			// Nearer the camera than anyone in your crowd, so drawn after them all, on top.
			for (int i = 0; i < keptCount; i++)
			{
				Kept k = kept[i];
				if (k != you && Math.abs(k.x - you.x) <= NEAR_YOU && Math.abs(k.z - you.z) <= NEAR_YOU)
				{
					you.distance = Math.min(you.distance, k.distance - 1);
				}
			}
		}
		// The game hands actors over roughly back to front already, so a plain insertion sort is
		// quick here and, unlike Arrays.sort, allocates nothing.
		for (int i = 1; i < keptCount; i++)
		{
			Kept k = kept[i];
			int j = i - 1;
			while (j >= 0 && kept[j].distance < k.distance)
			{
				kept[j + 1] = kept[j];
				j--;
			}
			kept[j + 1] = k;
		}
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			try
			{
				int orientation = k.orientation;
				Model model = null;
				if (k.walk && k.actor instanceof Player)
				{
					Player player = (Player) k.actor;
					model = walkModel(player, player.getId());
					if (model != null)
					{
						orientation = k.walkOrientation;
						walkDraws++;
					}
				}
				if (model == null)
				{
					model = k.actor.getModel();
				}
				if (model != null)
				{
					delegate.drawTemp(k.projection, k.scene, k.gameObject, model, orientation, k.x, k.y, k.z);
					orderedDraws++;
				}
			}
			catch (RuntimeException e)
			{
				revealErrors++;
			}
			k.projection = null;
			k.scene = null;
			k.gameObject = null;
			k.actor = null;
		}
		keptCount = 0;
	}

	/** Forget everyone kept back, drawing nobody: the frame they belonged to is over. */
	private void dropKept()
	{
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			k.projection = null;
			k.scene = null;
			k.gameObject = null;
			k.actor = null;
		}
		keptCount = 0;
	}

	/** Draws were kept back for an opaque pass that never came: this renderer draws in its own order. */
	private void passMissed()
	{
		if (keptCount > 0)
		{
			passMissedFrames++;
			passMissed = true;
			dropKept();
		}
	}

	private boolean isMainScene(Scene scene)
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && scene == wv.getScene();
	}

	@Override
	public void drawTemp(Projection projection, Scene scene, GameObject gameObject, Model model, int orientation, int x, int y, int z)
	{
		Renderable renderable = gameObject == null ? null : gameObject.getRenderable();
		if (!(renderable instanceof Player))
		{
			if (renderable instanceof NPC && keeping(scene, x, y, z))
			{
				keep(projection, scene, gameObject, renderable, orientation, orientation, x, y, z, false);
			}
			else
			{
				delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
			}
			return;
		}
		playerDraws++;
		if (!client.isClientThread())
		{
			offThreadDraws++;
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
			return;
		}
		// The main world, or a boat: each is a world view with a scene of its own.
		WorldView wv = client.getWorldView(scene.getWorldViewId());
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
		int layer = StackRegistry.layer(wv.getId(), plane);
		// This shim already knows it's on the client thread here. Whether someone is kept back goes
		// by where they are drawn, not where they stand: someone easing back from a spot is drawn
		// past where they stand, and it's the drawn spot that has to be inside the cut-off for the
		// order across it to be right. Only on the main scene: a boat's deck is drawn as it comes.
		boolean mayKeep = wv.getId() == WorldView.TOPLEVEL && opaquePassSeen && !passMissed && !stacks.isEmpty();
		int dx = offsets.dx(drawnId);
		int dz = offsets.dz(drawnId);
		boolean touchedSharedModel = false;
		if (dx != 0 || dz != 0)
		{
			// Someone really walking off (from the bank back to the anvil, say) keeps the game's own
			// walk and facing while their offset runs out. Drawn with ours instead, they faced back
			// towards the tile they had left, the way the offset was shrinking, while their body was
			// carried the other way: moonwalking off until the offset had gone.
			boolean reallyMoving = reallyMoving(drawn);
			int drawOrientation = reallyMoving ? orientation : stacks.drawOrientation(StackRegistry.key(layer, x >> 7, z >> 7), orientation, dx, dz);
			int drawY = y + groundDelta(wv, plane, x, z, x + dx, z + dz);
			boolean walking = offsets.isWalking(drawnId) && !reallyMoving;
			if (mayKeep && withinKeep(wv, x + dx, drawY, z + dz))
			{
				keep(projection, scene, gameObject, drawn, drawOrientation, offsets.walkOrientation(drawnId), x + dx, drawY, z + dz, walking);
			}
			else
			{
				Model drawModel = model;
				if (walking)
				{
					Model walk = walkModel(drawn, drawnId);
					touchedSharedModel = true;
					if (walk != null)
					{
						drawModel = walk;
						drawOrientation = offsets.walkOrientation(drawnId);
						walkDraws++;
					}
				}
				delegate.drawTemp(projection, scene, gameObject, drawModel, drawOrientation, x + dx, drawY, z + dz);
			}
			nudgedDraws++;
		}
		else if (StillnessTracker.isCentred(x, z) && stacks.isUnplaced(drawnId) && !isLocal(drawn)
			&& stacks.middleOutOfSight(StackRegistry.key(layer, x >> 7, z >> 7)))
		{
			// Waiting in the middle of a tile that is between you and the camera: left undrawn, like
			// the others the game hides there, rather than standing in front of you.
			hiddenInYourWay++;
		}
		else if (mayKeep && withinKeep(wv, x, y, z))
		{
			keep(projection, scene, gameObject, drawn, orientation, orientation, x, y, z, false);
		}
		else
		{
			delegate.drawTemp(projection, scene, gameObject, model, orientation, x, y, z);
		}

		// Only a player standing exactly in the middle of its tile hides others there.
		if (!stacks.isEmpty() && StillnessTracker.isCentred(x, z))
		{
			touchedSharedModel |= drawHiddenStackmates(projection, scene, gameObject, drawn, wv, plane, x, y, z, mayKeep);
		}

		if (touchedSharedModel)
		{
			// Animated models share one buffer inside the game. Building other models (or this
			// player's walking model) overwrote the one the game uses for its click test right
			// after this call returns, so rebuild it to put it back.
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

	/** Whether the game has this player walking or running for real, rather than standing. */
	private static boolean reallyMoving(Player player)
	{
		int pose = player.getPoseAnimation();
		return pose != -1 && pose != player.getIdlePoseAnimation()
			&& (pose == player.getWalkAnimation() || pose == player.getRunAnimation() || pose == player.getWalkRotate180()
			|| pose == player.getWalkRotateLeft() || pose == player.getWalkRotateRight());
	}

	/**
	 * This player's model mid-step: their own walk animation at the frame matching how long they've
	 * been walking. Returns null (draw them as the game did) if they're busy with an action such as
	 * sitting or smithing, or the animation can't be read. Their real animation state is restored
	 * before returning; the model stays valid until the next model is built.
	 */
	private Model walkModel(Player player, int id)
	{
		if (player.getAnimation() != -1)
		{
			walkSkippedBusy++;
			return null;
		}
		int walk = player.getWalkAnimation();
		if (walk < 0)
		{
			return null;
		}
		int frame = walkTiming(walk).frameAt(offsets.walkSeconds(id));
		if (frame < 0)
		{
			walkSkippedNoAnimation++;
			return null;
		}
		int pose = player.getPoseAnimation();
		int poseFrame = player.getPoseAnimationFrame();
		try
		{
			player.setPoseAnimation(walk);
			player.setPoseAnimationFrame(frame);
			return player.getModel();
		}
		finally
		{
			player.setPoseAnimation(pose);
			player.setPoseAnimationFrame(poseFrame);
		}
	}

	private WalkTiming walkTiming(int animationId)
	{
		WalkTiming timing = walkTimings.get(animationId);
		if (timing == null || (timing.missing() && offsets.frame() - timing.loadedFrame > RETRY_FRAMES))
		{
			// A walk animation that couldn't be read is tried again a little later rather than
			// given up on for good, so a player doesn't glide for the rest of the session.
			timing = WalkTiming.load(client, animationId);
			timing.loadedFrame = offsets.frame();
			walkTimings.put(animationId, timing);
		}
		return timing;
	}

	private boolean isLocal(Player player)
	{
		return player == client.getLocalPlayer();
	}

	/** How many frames to wait before trying to read a missing walk animation again (about a second). */
	private static final int RETRY_FRAMES = 50;

	/** How long each frame of a walk animation lasts, in game cycles (20 ms). */
	static final class WalkTiming
	{

		private final int[] frameLengths;
		private final int duration;
		/** Frame this was read on, for retrying a missing animation. */
		int loadedFrame;

		WalkTiming(int[] frameLengths, int duration)
		{
			this.frameLengths = frameLengths;
			this.duration = duration;
		}

		static WalkTiming load(Client client, int animationId)
		{
			try
			{
				Animation animation = client.loadAnimation(animationId);
				if (animation == null)
				{
					return new WalkTiming(null, 0);
				}
				if (animation.isMayaAnim())
				{
					return new WalkTiming(null, animation.getDuration());
				}
				int[] lengths = animation.getFrameLengths();
				return lengths == null || lengths.length == 0 ? new WalkTiming(null, 0) : new WalkTiming(lengths, 0);
			}
			catch (RuntimeException e)
			{
				return new WalkTiming(null, 0);
			}
		}

		boolean missing()
		{
			return frameLengths == null && duration <= 0;
		}

		/** Frame to show after walking this many seconds, looping; -1 if unknown. */
		int frameAt(float seconds)
		{
			int cycles = (int) (seconds / 0.02f);
			if (frameLengths == null)
			{
				return duration > 0 ? cycles % duration : -1;
			}
			int total = 0;
			for (int length : frameLengths)
			{
				total += Math.max(1, length);
			}
			int t = cycles % total;
			for (int i = 0; i < frameLengths.length; i++)
			{
				t -= Math.max(1, frameLengths[i]);
				if (t < 0)
				{
					return i;
				}
			}
			return frameLengths.length - 1;
		}
	}

	/**
	 * Draw the players the game skipped on the drawn player's tile.
	 *
	 * <p>A mate still standing exactly where the drawn player is was certainly skipped, because the
	 * game draws only one centred player per tile. A mate that has since stepped off-centre is
	 * drawn by the game itself, so it is left alone to avoid drawing anyone twice.
	 */
	private boolean drawHiddenStackmates(Projection projection, Scene scene, GameObject gameObject, Player drawn, WorldView wv, int plane, int x, int y, int z, boolean mayKeep)
	{
		long tileKey = StackRegistry.key(StackRegistry.layer(wv.getId(), plane), x >> 7, z >> 7);
		int[] mates = stacks.membersAt(tileKey);
		int frame = offsets.frame();
		int heldCount = probe.heldOn(tileKey, frame, held);
		if (mates.length == 0 && heldCount == 0)
		{
			return false;
		}

		// The y the game passed is ground height minus the drawn player's own animation lift.
		int ground = y + drawn.getAnimationHeightOffset();
		int cycle = client.getGameCycle();
		boolean touchedSharedModel = false;
		// As in the game, only one player is drawn in the middle of a tile: anyone left without a spot
		// (past the "players per tile" limit, or standing in the middle with you) stays hidden there.
		// Players with a spot are always drawn, and so are you.
		boolean middleDrawn = offsets.dx(drawn.getId()) == 0 && offsets.dz(drawn.getId()) == 0
			|| stacks.middleOutOfSight(tileKey);
		Player local = client.getLocalPlayer();
		int localId = local == null ? -1 : local.getId();
		int total = mates.length + heldCount;
		for (int i = 0; i < total; i++)
		{
			int id = i < mates.length ? mates[i] : held[i - mates.length];
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
				int mdx = offsets.dx(id);
				int mdz = offsets.dz(id);
				boolean inMiddle = mdx == 0 && mdz == 0 && !offsets.isWalking(id);
				if (inMiddle && middleDrawn && id != localId && stacks.isUnplaced(id))
				{
					continue;
				}
				if (!probe.isConfirmed(id, mate, cycle))
				{
					continue; // the game hasn't shown this player recently, e.g. hidden by the server: never draw them
				}
				if (!probe.othersAllow(renderCallbacks, mate))
				{
					continue; // hidden by another plugin, e.g. Entity Hider: respect that
				}
				int mateOrientation = stacks.drawOrientation(tileKey, mate.getCurrentOrientation(), mdx, mdz);
				int mateY = ground - mate.getAnimationHeightOffset() + groundDelta(wv, plane, x, z, x + mdx, z + mdz);
				boolean walking = offsets.isWalking(id);
				if (mayKeep && withinKeep(wv, x + mdx, mateY, z + mdz))
				{
					keep(projection, scene, gameObject, mate, mateOrientation, offsets.walkOrientation(id), x + mdx, mateY, z + mdz, walking);
				}
				else
				{
					touchedSharedModel = true;
					Model mateModel = null;
					if (walking)
					{
						mateModel = walkModel(mate, id);
						if (mateModel != null)
						{
							mateOrientation = offsets.walkOrientation(id);
							walkDraws++;
						}
					}
					if (mateModel == null)
					{
						mateModel = mate.getModel();
					}
					if (mateModel == null)
					{
						continue;
					}
					delegate.drawTemp(projection, scene, gameObject, mateModel, mateOrientation, x + mdx, mateY, z + mdz);
				}
				revealedFrame[id] = frame;
				revealedDraws++;
				middleDrawn |= inMiddle;
			}
			catch (RuntimeException e)
			{
				revealErrors++;
			}
		}

		return touchedSharedModel;
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
		dropKept();
		delegate.loadScene(scene);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		dropKept();
		delegate.loadScene(worldView, scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		dropKept();
		delegate.swapScene(scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		dropKept();
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
		if (isMainScene(scene))
		{
			passMissed();
		}
		delegate.preSceneDraw(scene, projection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw, int plane, int minLevel, int maxLevel, Set<Integer> zones)
	{
		if (isMainScene(scene))
		{
			passMissed();
		}
		delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane, minLevel, maxLevel, zones);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (isMainScene(scene))
		{
			passMissed();
		}
		delegate.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		if (pass == PASS_OPAQUE && isMainScene(scene))
		{
			// The game has handed over everyone for this frame: now they're drawn, farthest first.
			opaquePassSeen = true;
			drawKept();
		}
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
