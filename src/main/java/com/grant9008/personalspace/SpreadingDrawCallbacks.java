package com.grant9008.personalspace;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import net.runelite.api.Actor;
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
	/** Moving players drawn without walk frames because they were busy and busy walks were off. */
	long walkSkippedBusy;
	/** Moving players drawn walking although busy with an emote or action. */
	long walkBusy;
	/** Diagnostics: which actions people were shown walking out of (animation id to frames), for the report. */
	final Map<Integer, Long> busyAnimations = new HashMap<>();
	/**
	 * Whether walk models may swap a player's action ({@link #modelStepping}, and busy walks in
	 * {@link #modelMidStep}). The plugin switches it off for the session if the game ever reports an
	 * animation change from it: busy players then glide, and the rest get the walk as their pose only.
	 */
	boolean busyWalks = true;
	/** The player whose action is swapped for a walk in their model right now, or null. */
	private Actor busySwap;

	/** Client thread. Whether an AnimationChanged for this actor came from swapping their action for a walk. */
	boolean fromBusySwap(Actor actor)
	{
		return actor != null && actor == busySwap;
	}
	/** Moving players drawn without walk frames because the animation couldn't be loaded (yet). */
	long walkSkippedNoAnimation;

	/** Walk animation timing by animation id, loaded once. */
	private final Map<Integer, WalkTiming> walkTimings = new HashMap<>();

	/** Frame each player id was last drawn by the game itself, and by us, so nobody is drawn twice in a frame. */
	private final int[] nativeFrame = new int[OffsetTable.CAPACITY];
	private final int[] revealedFrame = new int[OffsetTable.CAPACITY];
	/** The frame each player was last really drawn (or kept back to be drawn) by this shim. */
	private final int[] drawnFrame = longAgo(OffsetTable.CAPACITY);
	/**
	 * A player the game handed over this frame, in the main world, and what with: lent to anyone the
	 * game had no room for (see {@link #drawRefused}), as people on a tile are lent the drawn one's.
	 */
	private int lentFrame = Integer.MIN_VALUE;
	private Projection lentProjection;
	private Scene lentScene;
	private GameObject lentObject;
	/** Diagnostics: players drawn at their spot although the game had no room for them on their tile; frames. */
	long refusedDrawn;

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
		this.depthKnown = delegate.getClass().getSimpleName().equals("GpuPlugin") || delegate.getClass().getName().startsWith("rs117.");
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
		/** For someone walking for real: the walk to draw them with and its frame (see {@link #movingLegs}); -1 for the game's own. */
		int legAnim = -1;
		int legFrame;
		/** Drawn with another player's object lent (see {@link #drawRefused}), on this plane. */
		boolean lent;
		int plane;
		double distance;
		/** Who this is, to carry last frame's order over: a player's id, or past those, an NPC's index. */
		int who;
	}

	/**
	 * Two people only swap which of them is drawn in front once one is this much nearer the camera
	 * than the other: about a body's width. (Half that still let a packed crowd swap pair after pair
	 * as the camera was rocked across it.) Each frame starts from the order of the last.
	 * Sorted by distance alone, two people side by side swapped every time the camera swung across
	 * the angle at which they're equally far away, and a kite shield, cape or shoulder pad that
	 * reaches across into the other popped in front of them and behind, over and over, with nobody
	 * moving but the camera. People really in front of or behind one another are further apart than
	 * this, so they're still drawn the right way round.
	 */
	static final int SORT_TOLERANCE = 32;

	/** Who wasn't in last frame's order: they're put where they belong at once. */
	private boolean[] fresh = new boolean[128];
	/**
	 * Where each one is on screen this frame, roughly: a box round their body and gear, as
	 * (left, right, top, bottom, valid) in the camera's view, 5 to a person. Swapping two people who
	 * don't overlap on screen can't be seen, so they're sorted freely; only two who overlap keep
	 * their order until one is clearly in front ({@link #SORT_TOLERANCE}). Sorted with that
	 * tolerance for everyone, a crowd flickered as the camera turned; held still while it turned,
	 * people were drawn the wrong way round. This way swaps mostly happen while people are apart.
	 */
	private double[] boxes = new double[128 * 5];
	private final float[] projected = new float[3];
	private final int[] lastX = new int[WHO_SLOTS];
	private final int[] lastZ = new int[WHO_SLOTS];
	private final int[] stateFrame = new int[WHO_SLOTS];
	private boolean[] movedNow = new boolean[128];
	private boolean[] walkingNow = new boolean[128];
	/** When each pair of people who overlap last swapped, to catch them swapping straight back: flicker. */
	private final Map<Long, Integer> lastSwap = new HashMap<>();
	/** Diagnostics: visible swaps with the camera still caused by someone walking, by someone standing whose drawn spot shifted; and swaps straight back within half a second. */
	long stillSwapsWalking;
	long stillSwapsStanding;
	long swapsBack;
	static final int SWAP_BACK_FRAMES = 25;
	/** Diagnostics: visible swaps with the camera still, by why: someone moved, or not. */
	long stillSwapsMoved;
	long stillSwapsOther;

	/** How far out from someone's feet their box reaches, and how tall it is, in local units. */
	static final int BOX_REACH = 48;
	static final int BOX_HEIGHT = 230;

	/** Diagnostics: two people who overlap on screen swapping which is in front, with the camera moving or still. */
	long swapsCameraMoving;
	long swapsCameraStill;
	private int lastCamX;
	private int lastCamY;
	private int lastCamZ;
	private int lastCamYaw;
	private int lastCamPitch;
	private static final int WHO_SLOTS = OffsetTable.CAPACITY + 65536;
	private final int[] rankOf = new int[WHO_SLOTS];
	private final int[] rankFrame = new int[WHO_SLOTS];
	private int[] order = new int[128];
	private int[] prior = new int[128];
	private double[] far = new double[128];
	private Kept[] sorted = new Kept[128];

	{
		Arrays.fill(rankFrame, Integer.MIN_VALUE / 2);
		Arrays.fill(stateFrame, Integer.MIN_VALUE / 2);
	}

	/**
	 * Indices {@code order[0..n)}, given in last frame's order, sorted farthest first, where one only
	 * moves ahead of another that it's farther from the camera than by more than {@code tolerance}.
	 * In place, allocating nothing.
	 */
	static void settle(int[] order, double[] distance, int n, double tolerance)
	{
		settle(order, distance, n, tolerance, null, null);
	}

	/**
	 * As above, with the tolerance only between two who overlap on screen ({@code box}: 5 to a
	 * person, see {@link #boxes}; null means everyone overlaps), and none for anyone marked
	 * {@code fresh} (new this frame).
	 */
	static void settle(int[] order, double[] distance, int n, double tolerance, boolean[] fresh, double[] box)
	{
		// One step at a time: someone only passes the one just ahead of them. Letting them jump ahead
		// past a near-tie to anyone they overlap and are clearly farther than (to stop a tight row
		// staying drawn back to front after a slow swing of the camera) carried them past others
		// they were nearer than, and crowds flashed and were drawn the wrong way round.
		for (int i = 1; i < n; i++)
		{
			int k = order[i];
			int j = i - 1;
			while (j >= 0)
			{
				int o = order[j];
				double t = fresh != null && fresh[k] || !overlaps(box, k, o) ? 0 : tolerance;
				if (!(distance[k] > distance[o] + t))
				{
					break;
				}
				order[j + 1] = o;
				j--;
			}
			order[j + 1] = k;
		}
	}

	/** Whether two people's boxes overlap on screen; with no boxes, yes. */
	static boolean overlaps(double[] box, int a, int b)
	{
		if (box == null)
		{
			return true;
		}
		int i = a * 5;
		int j = b * 5;
		if (box[i + 4] == 0 || box[j + 4] == 0)
		{
			return false;
		}
		return box[i] <= box[j + 1] && box[j] <= box[i + 1] && box[i + 2] <= box[j + 3] && box[j + 2] <= box[i + 3];
	}

	/** Fill in someone's box (see {@link #boxes}) from where they're drawn this frame. */
	private void boxFor(Kept k, int index)
	{
		int at = index * 5;
		boxes[at + 4] = 0;
		if (k.projection == null)
		{
			return;
		}
		double left = Double.MAX_VALUE;
		double right = -Double.MAX_VALUE;
		double top = Double.MAX_VALUE;
		double bottom = -Double.MAX_VALUE;
		try
		{
			for (int dx = -BOX_REACH; dx <= BOX_REACH; dx += 2 * BOX_REACH)
			{
				for (int dz = -BOX_REACH; dz <= BOX_REACH; dz += 2 * BOX_REACH)
				{
					for (int dy = 0; dy >= -BOX_HEIGHT; dy -= BOX_HEIGHT)
					{
						k.projection.project(k.x + dx, k.y + dy, k.z + dz, projected);
						if (projected[2] < 50)
						{
							return; // by or behind the camera: treated as overlapping nobody
						}
						double sx = projected[0] / projected[2];
						double sy = projected[1] / projected[2];
						left = Math.min(left, sx);
						right = Math.max(right, sx);
						top = Math.min(top, sy);
						bottom = Math.max(bottom, sy);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			return;
		}
		boxes[at] = left;
		boxes[at + 1] = right;
		boxes[at + 2] = top;
		boxes[at + 3] = bottom;
		boxes[at + 4] = 1;
	}

	/**
	 * Diagnostics, for a crowd that vanishes for a moment: frames where under half as many people
	 * were held back to be drawn in order as the frame before; frames where the stack probe held
	 * a tile's people back and the game then drew nobody on it, so none of them were drawn; and
	 * people held back but thrown away undrawn when the scene changed.
	 */
	long keptDips;
	long probeBlackouts;
	long droppedUndrawn;
	private int lastKeptCount;
	/**
	 * Diagnostics for one tile's crowd blinking out for a frame: its stackmates drawn this frame and
	 * the last, and why any were left out. Tiles whose crowd drew 3 or more one frame and under half
	 * that the next count as a blink, put down to what left most of them out: the game drawing
	 * nobody in the middle of the tile that frame (so there was nobody to draw them with), their
	 * showing not being confirmed, another plugin hiding them, them having moved, or no model.
	 */
	private static final class TileDraws
	{
		int frame = -1;
		int count;
		int lastFrame = -1;
		int lastCount;
		int unconfirmed;
		int othersHid;
		int moved;
	}

	private final Map<Long, TileDraws> tileDraws = new HashMap<>();
	long blinkNoMiddle;
	long blinkUnconfirmed;
	long blinkOthersHid;
	long blinkMoved;
	long blinkOther;
	/** Held back, then no model to draw them with. */
	long keptWithoutModel;
	/** Diagnostics: stackmates left undrawn, by why, one count per person per frame. */
	long skippedUnconfirmed;
	long skippedOthersHid;
	long skippedMoved;
	long fireBlackouts;
	long fireUnconfirmed;

	private TileDraws tileDraws(long tileKey, int frame)
	{
		TileDraws td = tileDraws.computeIfAbsent(tileKey, k -> new TileDraws());
		if (td.frame != frame)
		{
			td.lastFrame = td.frame;
			td.lastCount = td.count;
			td.frame = frame;
			td.count = 0;
			td.unconfirmed = 0;
			td.othersHid = 0;
			td.moved = 0;
		}
		return td;
	}

	/** Once a frame, before the held-back crowd is drawn: see {@link TileDraws}. */
	private void countBlinks(int frame)
	{
		for (java.util.Iterator<TileDraws> it = tileDraws.values().iterator(); it.hasNext(); )
		{
			TileDraws td = it.next();
			if (td.frame < frame - 50)
			{
				it.remove();
			}
			else if (td.frame == frame - 1 && td.count >= 3)
			{
				// Drawn last frame, and this frame the game drew nobody in the middle of the tile.
				blinkNoMiddle++;
			}
			else if (td.frame == frame && td.lastFrame == frame - 1 && td.lastCount >= 3 && td.count * 2 < td.lastCount)
			{
				int most = Math.max(td.unconfirmed, Math.max(td.othersHid, td.moved));
				if (most == 0)
				{
					blinkOther++;
				}
				else if (most == td.unconfirmed)
				{
					blinkUnconfirmed++;
				}
				else if (most == td.othersHid)
				{
					blinkOthersHid++;
				}
				else
				{
					blinkMoved++;
				}
			}
		}
	}

	/** Stacked tiles whose middle player the game drew this frame, so their stackmates were drawn too. */
	private final long[] tilesDrawnThisFrame = new long[256];
	private int tilesDrawnCount;
	private int tilesDrawnFrame = -1;

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
	 * The "Depth between players" setting; kept up to date by the plugin. The game draws its people
	 * with no depth between them, each a whole layer over the one drawn before, so no order is right
	 * from every side: a shield reaching into a neighbour popped in front and behind as the camera
	 * turned, and someone's hat vanished behind the head in front. Drawn with depth (see
	 * {@link DepthTestedObject}), each part is hidden only where someone really is in front of it.
	 */
	volatile boolean depthBetweenPlayers = true;
	private final DepthTestedObject depthTested = new DepthTestedObject();
	/** Renderers this is known to work with: RuneLite's GPU plugin, and 117 HD. */
	private final boolean depthKnown;

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

	/**
	 * How far from the camera actors are kept back this frame, along the ground. Worked out once
	 * per frame. Distances here and in the sort are along the ground, heights left out: of two
	 * people standing upright, whoever is nearer along the ground covers the other wherever they
	 * overlap, uphill or down. Counted with heights, someone standing up a slope behind another
	 * came out nearer, and was drawn over them.
	 */
	private double keepWithin(WorldView wv)
	{
		int frame = offsets.frame();
		if (keepFrame != frame)
		{
			keepFrame = frame;
			double cameraX = client.getCameraX();
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
				double north = z - cameraZ;
				farthest = Math.max(farthest, Math.sqrt(east * east + north * north));
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
		double north = z - client.getCameraY();
		return east * east + north * north <= within * within;
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
		k.legAnim = -1;
		k.lent = false;
		keptCount++;
		markDrawn(actor);
	}

	/** As {@link #keep}, for someone walking for real who is to be drawn with these legs. */
	private void keepWithLegs(Projection projection, Scene scene, GameObject gameObject, Player actor, int orientation, int x, int y, int z, int[] legs)
	{
		keep(projection, scene, gameObject, actor, orientation, orientation, x, y, z, false);
		Kept k = kept[keptCount - 1];
		k.legAnim = legs[0];
		k.legFrame = legs[1];
	}

	/** Whether people doing something as they walk are drawn walking (Walk while busy). */
	volatile boolean walkWhileBusy = true;
	/** Diagnostics: frames drawn with legs keeping pace with the body, and busy people drawn walking on the move. */
	long legsMatched;
	long movingBusyWalks;
	/** For each player walking off a spot: where they were drawn last frame, and how far along their walk their legs are. */
	private final int[] legFrame = longAgo(OffsetTable.CAPACITY);
	private final int[] legX = new int[OffsetTable.CAPACITY];
	private final int[] legZ = new int[OffsetTable.CAPACITY];
	private final float[] legSeconds = new float[OffsetTable.CAPACITY];

	/**
	 * The legs to draw someone walking for real with, as {walk animation, frame}, or null for the
	 * game's own model.
	 *
	 * <p>Walking off a spot ({@code drawnAway}), their drawn spot eases back onto their real path as
	 * they go, so their body crosses the ground faster or slower than their legs carry them: they
	 * skated. Their walk is played instead at the pace their body really moves on screen.
	 *
	 * <p>Doing something as they walk (alching on the run), the game draws the action over the walk,
	 * legs and all, and they glide along in its pose; with Walk while busy on, the action is left
	 * out while they move (see {@link #modelMidStep}).
	 */
	private int[] movingLegs(Player player, int id, int drawnX, int drawnZ, boolean drawnAway)
	{
		boolean busy = player.getAnimation() != -1;
		if (busy && !(walkWhileBusy && busyWalks) || id < 0 || id >= OffsetTable.CAPACITY)
		{
			return null;
		}
		int pose = player.getPoseAnimation();
		boolean running = pose == player.getRunAnimation();
		if (!drawnAway || !running && pose != player.getWalkAnimation())
		{
			// Busy, but where they really are, or turning on the spot: just the action left out.
			return busy ? new int[]{pose, player.getPoseAnimationFrame()} : null;
		}
		int frame = offsets.frame();
		if (legFrame[id] == frame - 1)
		{
			double moved = Math.hypot(drawnX - legX[id], drawnZ - legZ[id]);
			legSeconds[id] += (float) (moved / (running ? 2 * OffsetTable.WALK_SPEED : OffsetTable.WALK_SPEED));
		}
		else if (legFrame[id] != frame)
		{
			legSeconds[id] = 0f;
		}
		legX[id] = drawnX;
		legZ[id] = drawnZ;
		legFrame[id] = frame;
		int at = walkTiming(pose).frameAt(legSeconds[id]);
		return at < 0 ? (busy ? new int[]{pose, player.getPoseAnimationFrame()} : null) : new int[]{pose, at};
	}

	/**
	 * Diagnostics: walks that didn't show. Each walk drawn is checked against the last: a walk frame
	 * lasts a few screen frames, but a body drawn walking that kept exactly the same shape for longer
	 * than any walk frame lasts wasn't walking at all, whatever this code thought it drew.
	 */
	long walksLegsStill;
	long walksLegsStillBusy;
	/** ...by how they were moving: to a spot, to a spot while busy, walking off, walking off while busy. */
	final long[] legsStillBy = new long[4];
	/** ...by the walk animation they were drawn with, and whether it is a skeletal one. */
	final Map<Integer, Long> legsStillAnims = new HashMap<>();
	private final int[] walkShapeX = new int[OffsetTable.CAPACITY];
	private final int[] walkShapeZ = new int[OffsetTable.CAPACITY];
	private final int[] walkShape = new int[OffsetTable.CAPACITY];
	private final int[] walkShapeFrame = longAgo(OffsetTable.CAPACITY);
	private final int[] walkShapeSince = new int[OffsetTable.CAPACITY];
	private final boolean[] walkShapeNoted = new boolean[OffsetTable.CAPACITY];
	/** Longer than any walk frame lasts, in game cycles: 0.3 s. */
	private static final int LEGS_STILL_CYCLES = 15;
	private final int[] lastLegsX = new int[OffsetTable.CAPACITY];
	private final int[] lastLegsZ = new int[OffsetTable.CAPACITY];

	static final int WALK_TO_SPOT = 0;
	static final int WALK_OFF = 2;

	private void checkWalkShown(Player player, int id, Model model, boolean busy, int how, int animation)
	{
		LocalPoint here = player.getLocalLocation();
		if (model == null || id < 0 || id >= OffsetTable.CAPACITY || here == null)
		{
			return;
		}
		int drawnX = here.getX() + offsets.dx(id);
		int drawnZ = here.getY() + offsets.dz(id);
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		float[] zs = model.getVerticesZ();
		int n = Math.min(model.getVerticesCount(), xs == null ? 0 : Math.min(xs.length, Math.min(ys.length, zs.length)));
		if (n == 0)
		{
			return;
		}
		int shape = 17;
		for (int i = 0, step = Math.max(1, n / 160); i < n; i += step)
		{
			shape = shape * 31 + Float.floatToIntBits(xs[i]) * 7 + Float.floatToIntBits(ys[i]) * 3 + Float.floatToIntBits(zs[i]);
		}
		int frame = offsets.frame();
		int cycle = client.getGameCycle();
		if (walkShapeFrame[id] < frame - 2 || walkShape[id] != shape)
		{
			walkShape[id] = shape;
			walkShapeSince[id] = cycle;
			walkShapeNoted[id] = false;
			walkShapeX[id] = drawnX;
			walkShapeZ[id] = drawnZ;
		}
		else if (!walkShapeNoted[id] && cycle - walkShapeSince[id] > LEGS_STILL_CYCLES
			&& Math.hypot(drawnX - walkShapeX[id], drawnZ - walkShapeZ[id]) >= 24)
		{
			// Moved a fifth of a tile and more with the same shape: a slide, whatever was meant.
			walkShapeNoted[id] = true;
			walksLegsStill++;
			if (busy)
			{
				walksLegsStillBusy++;
			}
			legsStillBy[how + (busy ? 1 : 0)]++;
			if (legsStillAnims.size() < 32 || legsStillAnims.containsKey(animation))
			{
				legsStillAnims.merge(animation, 1L, Long::sum);
			}
		}
		if (walkShapeNoted[id] && walkShapeFrame[id] != frame)
		{
			hides.noteSlide(nameOf(player), HideLog.SLIDE_LEGS_STILL, cycle,
				Math.hypot(drawnX - lastLegsX[id], drawnZ - lastLegsZ[id]), false);
		}
		lastLegsX[id] = drawnX;
		lastLegsZ[id] = drawnZ;
		walkShapeFrame[id] = frame;
	}

	/** {@link #modelMidStep} with these legs, counted; null (draw them as the game did) if it fails. */
	private Model modelWithLegs(Player player, int[] legs)
	{
		try
		{
			Model model = modelMidStep(player, legs[0], legs[1]);
			checkWalkShown(player, player.getId(), model, player.getAnimation() != -1, WALK_OFF, legs[0]);
			if (player.getAnimation() != -1)
			{
				movingBusyWalks++;
			}
			else
			{
				legsMatched++;
			}
			return model;
		}
		catch (RuntimeException e)
		{
			revealErrors++;
			return null;
		}
	}

	private void markDrawn(Renderable actor)
	{
		if (actor instanceof Player)
		{
			int id = ((Player) actor).getId();
			if (id >= 0 && id < OffsetTable.CAPACITY)
			{
				drawnFrame[id] = offsets.frame();
			}
		}
	}

	/**
	 * Once a frame, after the game has handed everyone over: anyone drawn away from their tile, on
	 * screen, whom nothing has drawn yet. The game adds at most five things to a tile; a busy
	 * Forester's Campfire is the fire, the flames of every log put on it and the people tending it,
	 * so whenever another log went on, the one standing on it wasn't added, and nobody on it was
	 * drawn: the whole group round the fire vanished for a moment, again and again. Those the game
	 * had no room for are drawn here instead, at their spots, with a player object lent from this
	 * frame. Anyone left undrawn for another reason goes in the hide log.
	 */
	private void drawRefused(int frame)
	{
		WorldView wv = client.getTopLevelWorldView();
		// Someone alone on a full tile (a lone campfire tender) blinked whenever no stack was in view.
		if (wv == null || stacks.isEmpty() && !probe.enabled)
		{
			return;
		}
		int left = client.getViewportXOffset();
		int top = client.getViewportYOffset();
		int right = left + client.getViewportWidth();
		int bottom = top + client.getViewportHeight();
		int cycle = client.getGameCycle();
		drawRefusedMoving(wv, frame, cycle, left, top, right, bottom);
		for (long tile : stacks.stackedTiles())
		{
			int layer = StackRegistry.plane(tile);
			int plane = StackRegistry.planeOf(layer);
			if (StackRegistry.layer(wv.getId(), plane) != layer)
			{
				continue;
			}
			int sceneX = StackRegistry.sceneX(tile);
			int sceneY = StackRegistry.sceneY(tile);
			// Only those the game had no room for, or the probe held back, are drawn here. Others the
			// game left out on purpose: past your draw distance, say, or hidden by the server.
			int heldCount = probe.heldOn(tile, frame, refusedHeld);
			Boolean full = null;
			boolean rescued = false;
			for (int id : stacks.membersAt(tile))
			{
				if (id < 0 || id >= OffsetTable.CAPACITY || drawnFrame[id] == frame || hides.notedThisFrame(id, frame))
				{
					continue;
				}
				int dx = offsets.dx(id);
				int dz = offsets.dz(id);
				Player p = (dx != 0 || dz != 0) ? wv.players().byIndex(id) : null;
				LocalPoint lp = p == null ? null : p.getLocalLocation();
				if (lp == null || !onScreen(wv, lp.getX() + dx, lp.getY() + dz, plane, left, top, right, bottom))
				{
					continue;
				}
				if (nativeFrame[id] == frame)
				{
					// Handed over, then left out on a path none of the other reasons name.
					noteHidden(p, id, HideLog.SKIPPED_OTHER, -1, wv, tile);
					continue;
				}
				int x = lp.getX();
				int z = lp.getY();
				if (full == null)
				{
					full = tileFull(wv, plane, sceneX, sceneY);
				}
				// The game skips a tile it can't see, so someone drawn on screen from a tile just off
				// it vanished whenever the camera put their real tile past the edge.
				boolean tileOffScreen = !onScreen(wv, x, z, plane, left, top, right, bottom);
				if (lentFrame != frame || lentObject == null || !(full || tileOffScreen || zoneDrawn(x, z, frame) || held(refusedHeld, heldCount, id))
					|| !StillnessTracker.isCentred(x, z) || lp.getSceneX() != sceneX || lp.getSceneY() != sceneY
					|| !probe.mayDraw(id, p, cycle) || !probe.othersAllow(renderCallbacks, p))
				{
					// Walking off, gone, left out on purpose, or someone who may not be drawn.
					noteHidden(p, id, tileOffScreen ? HideLog.TILE_OFF_SCREEN : HideLog.NOT_HANDED_OVER, -1, wv, tile);
					continue;
				}
				keepLent(p, id, tile, plane, x, z, dx, dz, wv);
				rescued = true;
			}
			// Whoever is shown in the middle of a tile nobody on it was drawn on blinked with the rest.
			int cx = sceneX * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
			int cz = sceneY * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
			if (lentFrame == frame && lentObject != null && !tileDrawnThisFrame(tile, frame) && !stacks.middleOutOfSight(tile))
			{
				int chosen = middleOf(tile, wv, cx, cz);
				Player p = chosen >= 0 && drawnFrame[chosen] != frame && nativeFrame[chosen] != frame ? wv.players().byIndex(chosen) : null;
				if (p != null && onScreen(wv, cx, cz, plane, left, top, right, bottom))
				{
					if (full == null)
					{
						full = tileFull(wv, plane, sceneX, sceneY);
					}
					// middleOf gives you the middle without asking other plugins: Entity Hider may hide you.
					if ((full || zoneDrawn(cx, cz, frame) || held(refusedHeld, heldCount, chosen)) && probe.othersAllow(renderCallbacks, p)
						&& (p == client.getLocalPlayer() || probe.mayDraw(chosen, p, cycle)))
					{
						keepLent(p, chosen, tile, plane, cx, cz, offsets.dx(chosen), offsets.dz(chosen), wv);
						rescued = true;
					}
				}
			}
			if (rescued)
			{
				noteTileDrawn(tile);
			}
		}
		drawRefusedAlone(wv, frame, cycle, left, top, right, bottom);
	}

	/**
	 * Someone alone on their tile, standing where they are, whom the game had no room for this
	 * frame (a tile full of other things: a campfire's flames, say) while drawing the zone round
	 * them: they flashed in and out though nothing here ever moved them. Drawn where they stand.
	 */
	private void drawRefusedAlone(WorldView wv, int frame, int cycle, int left, int top, int right, int bottom)
	{
		if (!probe.enabled || lentFrame != frame || lentObject == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		int yourPlane = local == null || local.getWorldLocation() == null ? -1 : local.getWorldLocation().getPlane();
		for (Player p : wv.players())
		{
			int id = p == null ? -1 : p.getId();
			if (id < 0 || id >= OffsetTable.CAPACITY || drawnFrame[id] == frame || nativeFrame[id] == frame
				|| offsets.dx(id) != 0 || offsets.dz(id) != 0)
			{
				continue;
			}
			LocalPoint lp = p.getLocalLocation();
			net.runelite.api.coords.WorldPoint wp = p.getWorldLocation();
			if (lp == null || wp == null || wp.getPlane() != yourPlane || !StillnessTracker.isCentred(lp.getX(), lp.getY()))
			{
				continue;
			}
			int plane = wp.getPlane();
			long tile = StackRegistry.key(StackRegistry.layer(wv.getId(), plane), lp.getSceneX(), lp.getSceneY());
			if (stacks.membersAt(tile).length > 0 || !zoneDrawn(lp.getX(), lp.getY(), frame)
				|| !onScreen(wv, lp.getX(), lp.getY(), plane, left, top, right, bottom)
				|| !probe.mayDraw(id, p, cycle) || !probe.othersAllow(renderCallbacks, p))
			{
				continue;
			}
			keepLent(p, id, tile, plane, lp.getX(), lp.getY(), 0, 0, wv);
			aloneRefusedDrawn++;
		}
	}

	/** Diagnostics: frames someone alone on their tile was drawn though the game had no room for them. */
	long aloneRefusedDrawn;

	/**
	 * Someone walking or running across a tile the game has no room on (a campfire's, crowded with
	 * flames and the people standing in it). The game won't add a moving player to the scene at all
	 * if any tile they're crossing, or the one they're stepping onto, is full: they vanished as they
	 * walked over the fire, from a step before it until they were off it. Drawn where they are, as
	 * the game would have drawn them. So is someone walking through the middle of a crowd, whom the
	 * game left out there because it draws one player in the middle of a tile.
	 */
	private void drawRefusedMoving(WorldView wv, int frame, int cycle, int left, int top, int right, int bottom)
	{
		if (!probe.enabled || lentFrame != frame || lentObject == null)
		{
			return;
		}
		int plane = wv.getPlane();
		for (Player p : wv.players())
		{
			int id = p == null ? -1 : p.getId();
			if (id < 0 || id >= OffsetTable.CAPACITY || drawnFrame[id] == frame || nativeFrame[id] == frame
				|| hides.notedThisFrame(id, frame))
			{
				continue;
			}
			LocalPoint lp = p.getLocalLocation();
			if (lp == null)
			{
				continue;
			}
			int x = lp.getX();
			int z = lp.getY();
			boolean centred = StillnessTracker.isCentred(x, z);
			long tile = StackRegistry.key(StackRegistry.layer(wv.getId(), plane), lp.getSceneX(), lp.getSceneY());
			// Standing on a tile: see drawRefused and drawRefusedAlone.
			if (centred && (!reallyMoving(p) || memberOf(tile, id)))
			{
				continue;
			}
			int dx = offsets.dx(id);
			int dz = offsets.dz(id);
			if (!zoneDrawn(x, z, frame) || !onScreen(wv, x + dx, z + dz, plane, left, top, right, bottom)
				|| !(crossingFullTile(wv, plane, x, z, p.getCurrentOrientation()) || centred && tileDrawnThisFrame(tile, frame))
				|| !probe.mayDraw(id, p, cycle) || !probe.othersAllow(renderCallbacks, p))
			{
				continue;
			}
			int y = Perspective.getTileHeight(client, new LocalPoint(x, z, wv), plane) - p.getAnimationHeightOffset()
				+ groundDelta(wv, plane, x, z, x + dx, z + dz);
			int facing = p.getCurrentOrientation();
			keep(lentProjection, lentScene, lentObject, p, facing, facing, x + dx, y, z + dz, false);
			Kept k = kept[keptCount - 1];
			k.lent = true;
			k.plane = plane;
			movingRefusedDrawn++;
		}
	}

	/** Diagnostics: frames someone walking was drawn though the game had no room for them on their way. */
	long movingRefusedDrawn;

	/** How far a player reaches each way when the game adds them to the scene. */
	private static final int PLAYER_REACH = 60;

	/**
	 * The tiles the game files a moving player under, as {first x, first y, last x, last y}: as far
	 * as they reach each way, and a tile further the way they face (orientation 0 is south, 512 west,
	 * 1024 north, 1536 east). If any of them is full, the game leaves the player out.
	 */
	static int[] movingSpan(int x, int z, int orientation)
	{
		int minX = x - PLAYER_REACH;
		int minZ = z - PLAYER_REACH;
		int maxX = x + PLAYER_REACH;
		int maxZ = z + PLAYER_REACH;
		if (orientation > 640 && orientation < 1408)
		{
			maxZ += Perspective.LOCAL_TILE_SIZE;
		}
		if (orientation > 1152 && orientation < 1920)
		{
			maxX += Perspective.LOCAL_TILE_SIZE;
		}
		if (orientation > 1664 || orientation < 384)
		{
			minZ -= Perspective.LOCAL_TILE_SIZE;
		}
		if (orientation > 128 && orientation < 896)
		{
			minX -= Perspective.LOCAL_TILE_SIZE;
		}
		return new int[]{minX >> 7, minZ >> 7, maxX >> 7, maxZ >> 7};
	}

	private static boolean crossingFullTile(WorldView wv, int plane, int x, int z, int orientation)
	{
		int[] span = movingSpan(x, z, orientation);
		for (int sx = span[0]; sx <= span[2]; sx++)
		{
			for (int sz = span[1]; sz <= span[3]; sz++)
			{
				if (tileFull(wv, plane, sx, sz))
				{
					return true;
				}
			}
		}
		return false;
	}

	private final int[] refusedHeld = new int[256];

	/**
	 * The 8-by-8-tile zones the game handed anyone over in this frame: it draws a zone or not, so
	 * someone it skipped in one it drew had no room on their tile, rather than being past your draw
	 * distance. (Counting a tile's objects missed some of those.)
	 */
	private final java.util.Set<Integer> zonesDrawn = new java.util.HashSet<>();
	private int zonesFrame = Integer.MIN_VALUE;

	private void noteZoneDrawn(int x, int z)
	{
		noteZone(x >> 10, z >> 10);
	}

	/** {@link #zonesDrawn}, also as the game draws each zone's ground: a zone nobody in was handed over. */
	private void noteZone(int zoneX, int zoneZ)
	{
		int frame = offsets.frame();
		if (zonesFrame != frame)
		{
			zonesFrame = frame;
			zonesDrawn.clear();
		}
		zonesDrawn.add((zoneX << 16) | (zoneZ & 0xFFFF));
	}

	/** How far the main scene's zone numbers run ahead of its tiles' (40 tiles of extended scene). */
	private static final int MAIN_ZONE_OFFSET = 5;

	private boolean zoneDrawn(int x, int z, int frame)
	{
		if (zonesFrame == frame && zonesDrawn.contains(((x >> 10) << 16) | ((z >> 10) & 0xFFFF)))
		{
			return true;
		}
		// Your own zone is always within your draw distance.
		Player local = client.getLocalPlayer();
		WorldView yours = local == null ? null : local.getWorldView();
		LocalPoint you = yours == null || yours.getId() != WorldView.TOPLEVEL ? null : local.getLocalLocation();
		return you != null && (you.getX() >> 10) == (x >> 10) && (you.getY() >> 10) == (z >> 10);
	}

	private static boolean held(int[] ids, int count, int id)
	{
		for (int i = 0; i < count; i++)
		{
			if (ids[i] == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Draw this player, whom the game had no room for, at their spot with an object lent from this frame. */
	private void keepLent(Player p, int id, long tile, int plane, int x, int z, int dx, int dz, WorldView wv)
	{
		int y = Perspective.getTileHeight(client, new LocalPoint(x, z, wv), plane) - p.getAnimationHeightOffset() + groundDelta(wv, plane, x, z, x + dx, z + dz);
		int facing = drawnFacing(p, tile, p.getCurrentOrientation(), dx, dz, x, z, wv);
		keep(lentProjection, lentScene, lentObject, p, facing, offsets.walkOrientation(id), x + dx, y, z + dz, offsets.isWalking(id) && !reallyMoving(p));
		Kept k = kept[keptCount - 1];
		k.lent = true;
		k.plane = plane;
		refusedDrawn++;
		// Each one drawn counts towards the tile's blink check, as stackmates drawn there do.
		tileDraws(tile, offsets.frame()).count++;
	}

	private boolean onScreen(WorldView wv, int x, int z, int plane, int left, int top, int right, int bottom)
	{
		net.runelite.api.Point at = Perspective.localToCanvas(client, new LocalPoint(x, z, wv), plane);
		return at != null && at.getX() >= left && at.getX() <= right && at.getY() >= top && at.getY() <= bottom;
	}

	/** Whether a player standing in the middle of this tile was handed over and drawn this frame. */
	private boolean tileDrawnThisFrame(long tile, int frame)
	{
		for (int i = 0; tilesDrawnFrame == frame && i < tilesDrawnCount; i++)
		{
			if (tilesDrawnThisFrame[i] == tile)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the game has no room left on this tile: it adds at most five things to one, and
	 * refuses anything more. Its own plane and the one below (a bridge's).
	 */
	private static boolean tileFull(WorldView wv, int plane, int sceneX, int sceneY)
	{
		Scene scene = wv.getScene();
		net.runelite.api.Tile[][][] tiles = scene == null ? null : scene.getTiles();
		for (int z = Math.max(0, plane - 1); tiles != null && z <= plane && z < tiles.length; z++)
		{
			if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[z].length || sceneY >= tiles[z][sceneX].length)
			{
				continue;
			}
			net.runelite.api.Tile t = tiles[z][sceneX][sceneY];
			GameObject[] objects = t == null ? null : t.getGameObjects();
			int n = 0;
			for (int i = 0; objects != null && i < objects.length; i++)
			{
				n += objects[i] != null ? 1 : 0;
			}
			if (n >= 5)
			{
				return true;
			}
		}
		return false;
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
		double cameraZ = client.getCameraY();
		Player local = client.getLocalPlayer();
		int frame = offsets.frame();
		int cycle = client.getGameCycle();
		if (movedNow.length < keptCount)
		{
			movedNow = new boolean[kept.length];
			walkingNow = new boolean[kept.length];
		}
		Kept you = null;
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			double east = k.x - cameraX;
			double north = k.z - cameraZ;
			k.who = k.actor instanceof Player ? ((Player) k.actor).getId()
				: k.actor instanceof NPC ? OffsetTable.CAPACITY + ((NPC) k.actor).getIndex()
				: -1;
			// Sorted by where they stand, never by how their body leans: someone who bent to tend a
			// fire or cast a spell swapped with the person beside them the moment they started, and
			// again when they stopped. Whether they moved, for the report.
			movedNow[i] = false;
			walkingNow[i] = k.walk || k.actor instanceof Actor
				&& ((Actor) k.actor).getPoseAnimation() != ((Actor) k.actor).getIdlePoseAnimation();
			int w = k.who;
			if (w >= 0 && w < WHO_SLOTS)
			{
				if (stateFrame[w] >= frame - 2 && stateFrame[w] < frame)
				{
					movedNow[i] = Math.abs(k.x - lastX[w]) + Math.abs(k.z - lastZ[w]) > 1;
				}
				lastX[w] = k.x;
				lastZ[w] = k.z;
				stateFrame[w] = frame;
			}
			k.distance = Math.sqrt(east * east + north * north) - (k.actor == local ? YOU_FIRST_ON_TIES : 0);
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
					you.distance = Math.min(you.distance, k.distance - 1 - 2 * SORT_TOLERANCE);
				}
			}
		}
		// Last frame's order first, anyone new after them; then farthest first, but nobody passes
		// anyone they're not clearly farther from the camera than (see SORT_TOLERANCE). Insertion
		// sorts: quick on an order that's nearly right already, and they allocate nothing.
		if (order.length < keptCount)
		{
			order = new int[kept.length];
			prior = new int[kept.length];
			far = new double[kept.length];
			sorted = new Kept[kept.length];
			fresh = new boolean[kept.length];
			boxes = new double[kept.length * 5];
		}
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			order[i] = i;
			far[i] = k.distance;
			boolean seen = k.who >= 0 && k.who < WHO_SLOTS && rankFrame[k.who] >= frame - 2 && rankFrame[k.who] <= frame;
			prior[i] = seen ? rankOf[k.who] : Integer.MAX_VALUE;
			fresh[i] = !seen;
			boxFor(k, i);
		}
		for (int i = 1; i < keptCount; i++)
		{
			int k = order[i];
			int j = i - 1;
			while (j >= 0 && prior[order[j]] > prior[k])
			{
				order[j + 1] = order[j];
				j--;
			}
			order[j + 1] = k;
		}
		int camX = client.getCameraX();
		int camY = client.getCameraY();
		int camZ = client.getCameraZ();
		int camYaw = client.getCameraYaw();
		int camPitch = client.getCameraPitch();
		boolean cameraStill = camX == lastCamX && camY == lastCamY && camZ == lastCamZ && camYaw == lastCamYaw && camPitch == lastCamPitch;
		settle(order, far, keptCount, SORT_TOLERANCE, fresh, boxes);
		lastCamX = camX;
		lastCamY = camY;
		lastCamZ = camZ;
		lastCamYaw = camYaw;
		lastCamPitch = camPitch;
		for (int i = 1; i < keptCount; i++)
		{
			int a = prior[order[i - 1]];
			int b = prior[order[i]];
			// Only swaps that can be seen: two who overlap on screen.
			if (a != Integer.MAX_VALUE && b != Integer.MAX_VALUE && a > b && overlaps(boxes, order[i - 1], order[i]))
			{
				int wa = kept[order[i - 1]].who;
				int wb = kept[order[i]].who;
				if (wa >= 0 && wb >= 0)
				{
					long pair = (long) Math.min(wa, wb) * WHO_SLOTS + Math.max(wa, wb);
					Integer when = lastSwap.put(pair, frame);
					if (when != null && frame - when <= SWAP_BACK_FRAMES)
					{
						swapsBack++;
					}
					if (lastSwap.size() > 4096)
					{
						lastSwap.values().removeIf(f -> frame - f > SWAP_BACK_FRAMES);
					}
				}
				if (cameraStill)
				{
					swapsCameraStill++;
					int x = order[i - 1];
					int y = order[i];
					if (movedNow[x] || movedNow[y])
					{
						stillSwapsMoved++;
						if (movedNow[x] && walkingNow[x] || movedNow[y] && walkingNow[y])
						{
							stillSwapsWalking++;
						}
						else
						{
							stillSwapsStanding++;
						}
					}
					else
					{
						stillSwapsOther++;
					}
				}
				else
				{
					swapsCameraMoving++;
				}
			}
		}
		for (int i = 0; i < keptCount; i++)
		{
			sorted[i] = kept[order[i]];
		}
		for (int i = 0; i < keptCount; i++)
		{
			kept[i] = sorted[i];
			sorted[i] = null;
			if (kept[i].who >= 0 && kept[i].who < WHO_SLOTS)
			{
				rankOf[kept[i].who] = i;
				rankFrame[kept[i].who] = frame;
			}
		}
		for (int i = 0; i < keptCount; i++)
		{
			Kept k = kept[i];
			try
			{
				int orientation = k.orientation;
				Model model = null;
				if (k.legAnim >= 0 && k.actor instanceof Player)
				{
					model = modelWithLegs((Player) k.actor, new int[]{k.legAnim, k.legFrame});
				}
				else if (k.walk && k.actor instanceof Player)
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
					GameObject object = k.lent ? depthTested.lend(k.gameObject, k.actor, k.x, k.z, k.plane, depthBetweenPlayers && depthKnown)
						: depthBetweenPlayers && depthKnown ? depthTested.wrap(k.gameObject) : k.gameObject;
					delegate.drawTemp(k.projection, k.scene, object, model, orientation, k.x, k.y, k.z);
					orderedDraws++;
				}
				else
				{
					keptWithoutModel++;
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
		lentProjection = null;
		lentScene = null;
		lentObject = null;
	}

	/** Forget everyone kept back, drawing nobody: the frame they belonged to is over. */
	private void dropKept()
	{
		droppedUndrawn += keptCount;
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

	private void noteTileDrawn(long key)
	{
		int frame = offsets.frame();
		if (tilesDrawnFrame != frame)
		{
			tilesDrawnFrame = frame;
			tilesDrawnCount = 0;
		}
		if (tilesDrawnCount < tilesDrawnThisFrame.length)
		{
			tilesDrawnThisFrame[tilesDrawnCount++] = key;
		}
	}

	/** See {@link #keptDips}. Once a frame, as the held-back crowd is about to be drawn. */
	private void countDrops()
	{
		if (lastKeptCount >= 6 && keptCount * 2 < lastKeptCount)
		{
			keptDips++;
		}
		lastKeptCount = keptCount;
		int frame = offsets.frame();
		long[] heldTiles = probe.heldTiles(frame);
		for (long tile : heldTiles)
		{
			boolean drawn = false;
			for (int i = 0; tilesDrawnFrame == frame && i < tilesDrawnCount && !drawn; i++)
			{
				drawn = tilesDrawnThisFrame[i] == tile;
			}
			if (!drawn)
			{
				probeBlackouts++;
				if (stacks.fireAt(tile) != null)
				{
					fireBlackouts++;
				}
				probe.backOff(tile, frame);
			}
		}
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
		if (wv.getId() == WorldView.TOPLEVEL)
		{
			lentFrame = offsets.frame();
			lentProjection = projection;
			lentScene = scene;
			lentObject = gameObject;
			noteZoneDrawn(x, z);
		}
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
			int drawOrientation = reallyMoving ? headingOf(drawnId, x + dx, z + dz, orientation)
				: drawnFacing(drawn, StackRegistry.key(layer, x >> 7, z >> 7), orientation, dx, dz, x, z, wv);
			int drawY = y + groundDelta(wv, plane, x, z, x + dx, z + dz);
			boolean walking = offsets.isWalking(drawnId) && !reallyMoving;
			int[] legs = reallyMoving ? movingLegs(drawn, drawnId, x + dx, z + dz, true) : null;
			noteMotion(drawn, drawnId, dx, dz, walking && (busyWalks || drawn.getAnimation() == -1) || legs != null, reallyMoving);
			if (legs != null && mayKeep && withinKeep(wv, x + dx, drawY, z + dz))
			{
				keepWithLegs(projection, scene, gameObject, drawn, drawOrientation, x + dx, drawY, z + dz, legs);
			}
			else if (mayKeep && withinKeep(wv, x + dx, drawY, z + dz))
			{
				keep(projection, scene, gameObject, drawn, drawOrientation, offsets.walkOrientation(drawnId), x + dx, drawY, z + dz, walking);
			}
			else
			{
				Model drawModel = model;
				if (legs != null)
				{
					Model withLegs = modelWithLegs(drawn, legs);
					touchedSharedModel = true;
					drawModel = withLegs != null ? withLegs : model;
				}
				else if (walking)
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
				markDrawn(drawn);
			}
			nudgedDraws++;
		}
		else if (StillnessTracker.isCentred(x, z) && stacks.isUnplaced(drawnId) && !isLocal(drawn)
			&& stacks.middleOutOfSight(StackRegistry.key(layer, x >> 7, z >> 7)))
		{
			// Waiting in the middle of a tile that is between you and the camera: left undrawn, like
			// the others the game hides there, rather than standing in front of you.
			hiddenInYourWay++;
			long key = StackRegistry.key(layer, x >> 7, z >> 7);
			noteHidden(drawn, drawnId, stacks.isWaiting(drawnId) ? HideLog.WAITING_OUT_OF_SIGHT : HideLog.NO_SPOT_OUT_OF_SIGHT, -1, wv, key);
		}
		else if (StillnessTracker.isCentred(x, z) && !isLocal(drawn)
			&& (stacks.isUnplaced(drawnId) || !reallyMoving(drawn) && (!offsets.targetIsMiddle(drawnId) && !offsets.isWalking(drawnId)
				|| !memberOf(StackRegistry.key(layer, x >> 7, z >> 7), drawnId)))
			&& otherInMiddle(StackRegistry.key(layer, x >> 7, z >> 7), wv, x, z, drawnId))
		{
			// Someone else waiting here is the one shown in the middle (see middleOf): drawn below,
			// with the others on the tile. So is anyone else still standing in it who doesn't hold it:
			// given a spot elsewhere and not yet setting off, or just arrived and not one of the stack
			// yet. The game handed them over first, and drawn there they made the one shown blink out.
			// Not someone walking through, though: they're only passing the middle.
			middleKeptSteady++;
			long key = StackRegistry.key(layer, x >> 7, z >> 7);
			noteHidden(drawn, drawnId, stacks.isWaiting(drawnId) ? HideLog.WAITING_MIDDLE_TAKEN : HideLog.NO_SPOT_MIDDLE_TAKEN,
				middleOf(key, wv, x, z), wv, key);
		}
		else
		{
			int[] legs = drawn.getAnimation() != -1 && reallyMoving(drawn) ? movingLegs(drawn, drawnId, x, z, false) : null;
			if (mayKeep && withinKeep(wv, x, y, z))
			{
				if (legs != null)
				{
					keepWithLegs(projection, scene, gameObject, drawn, orientation, x, y, z, legs);
				}
				else
				{
					keep(projection, scene, gameObject, drawn, orientation, orientation, x, y, z, false);
				}
			}
			else
			{
				Model drawModel = model;
				if (legs != null)
				{
					Model withLegs = modelWithLegs(drawn, legs);
					touchedSharedModel = true;
					drawModel = withLegs != null ? withLegs : model;
				}
				delegate.drawTemp(projection, scene, gameObject, drawModel, orientation, x, y, z);
				markDrawn(drawn);
			}
		}

		// Only a player standing exactly in the middle of its tile hides others there.
		if (!stacks.isEmpty() && StillnessTracker.isCentred(x, z))
		{
			noteTileDrawn(StackRegistry.key(layer, x >> 7, z >> 7));
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

	/**
	 * Someone walking off while their offset eases back faces the way their body is really moving on
	 * screen: their own walk plus the easing. Facing the way the game walks them, their legs went one
	 * way while their body drifted sideways back to their real path, and they seemed to slide out of
	 * a crowd. Turned gradually, at most {@link #TURN_PER_FRAME} a frame, as the game turns people.
	 */
	private int headingOf(int id, int drawnX, int drawnZ, int gameOrientation)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return gameOrientation;
		}
		int frame = offsets.frame();
		int heading = gameOrientation;
		if (headingFrame[id] >= frame - 2 && headingFrame[id] < frame)
		{
			int east = drawnX - lastDrawnX[id];
			int north = drawnZ - lastDrawnZ[id];
			int want = east * east + north * north >= 4 ? OffsetTable.facing(east, north) : headingNow[id];
			heading = turnTowards(headingNow[id], want, TURN_PER_FRAME);
		}
		else if (headingFrame[id] == frame)
		{
			return headingNow[id];
		}
		lastDrawnX[id] = drawnX;
		lastDrawnZ[id] = drawnZ;
		headingNow[id] = heading;
		headingFrame[id] = frame;
		return heading;
	}

	/** {@code from} turned towards {@code to} the short way round, by at most {@code step}. */
	static int turnTowards(int from, int to, int step)
	{
		int diff = ((to - from) % 2048 + 3072) % 2048 - 1024;
		if (Math.abs(diff) <= step)
		{
			return to & 2047;
		}
		return (from + Integer.signum(diff) * step) & 2047;
	}

	static final int TURN_PER_FRAME = 48;

	/**
	 * Diagnostics: frames someone was drawn moving across the ground with no walk to match, by why:
	 * busy with an action, walking off for real while their spot eases back to where they are, or
	 * anything else.
	 */
	long slideBusy;
	long slideWalkingOff;
	long slideOther;
	/** Busy slides by kind: out of the middle to a spot, from one spot to another, back into the middle. */
	long slideBusyArriving;
	long slideBusyBetween;
	long slideBusyToMiddle;
	private final int[] motionOX = new int[OffsetTable.CAPACITY];
	private final int[] motionOZ = new int[OffsetTable.CAPACITY];
	private final int[] motionFrame = longAgo(OffsetTable.CAPACITY);
	/** The frame each player was last drawn with a walk to match their moving. */
	private final int[] walkShownFrame = longAgo(OffsetTable.CAPACITY);

	private void noteMotion(Player player, int id, int dx, int dz, boolean walkShown, boolean reallyMoving)
	{
		if (id < 0 || id >= OffsetTable.CAPACITY)
		{
			return;
		}
		int frame = offsets.frame();
		if (motionFrame[id] == frame)
		{
			return;
		}
		if (motionFrame[id] == frame - 1)
		{
			int ex = dx - motionOX[id];
			int ez = dz - motionOZ[id];
			// The last stride of a walk lands them on their spot as the walk ends: not a slide.
			boolean landing = walkShownFrame[id] == frame - 1;
			if (ex * ex + ez * ez >= 4 && !walkShown && !landing)
			{
				int kind = reallyMoving ? HideLog.SLIDE_WALKING_OFF : player.getAnimation() != -1 ? HideLog.SLIDE_BUSY : HideLog.SLIDE_OTHER;
				LocalPoint at = player.getLocalLocation();
				WorldView pwv = player.getWorldView();
				net.runelite.api.coords.WorldPoint wp = player.getWorldLocation();
				boolean atFire = at != null && pwv != null && wp != null && stacks.fireAt(StackRegistry.key(
					StackRegistry.layer(pwv.getId(), wp.getPlane()), at.getSceneX(), at.getSceneY())) != null;
				hides.noteSlide(nameOf(player), kind, client.getGameCycle(), Math.sqrt(ex * ex + ez * ez), atFire);
				if (reallyMoving)
				{
					slideWalkingOff++;
				}
				else if (player.getAnimation() != -1)
				{
					slideBusy++;
					int wasFromMiddle = motionOX[id] * motionOX[id] + motionOZ[id] * motionOZ[id];
					int nowFromMiddle = dx * dx + dz * dz;
					if (nowFromMiddle < wasFromMiddle && offsets.targetIsMiddle(id))
					{
						slideBusyToMiddle++;
					}
					else if (wasFromMiddle < 24 * 24 && nowFromMiddle > wasFromMiddle)
					{
						slideBusyArriving++;
					}
					else
					{
						slideBusyBetween++;
					}
				}
				else
				{
					slideOther++;
				}
			}
		}
		motionOX[id] = dx;
		motionOZ[id] = dz;
		motionFrame[id] = frame;
		if (walkShown)
		{
			walkShownFrame[id] = frame;
		}
	}
	private final int[] lastDrawnX = new int[OffsetTable.CAPACITY];
	private final int[] lastDrawnZ = new int[OffsetTable.CAPACITY];
	private final int[] headingNow = new int[OffsetTable.CAPACITY];
	private final int[] headingFrame = longAgo(OffsetTable.CAPACITY);

	private static int[] longAgo(int n)
	{
		int[] a = new int[n];
		Arrays.fill(a, Integer.MIN_VALUE / 2);
		return a;
	}

	/** Diagnostics: frames the game drew someone in the middle of a tile and someone else was shown there instead. */
	long middleKeptSteady;

	/** Diagnostics: who goes undrawn longest, and why. The plugin hands over its own, which outlives map loads. */
	HideLog hides = new HideLog();

	/** Client thread. This player is left undrawn this frame, for this reason. */
	private void noteHidden(Player player, int id, int why, int givenTo, WorldView wv, long tileKey)
	{
		int frame = offsets.frame();
		if (hides.notedThisFrame(id, frame))
		{
			return;
		}
		int cycle = client.getGameCycle();
		if (hides.continues(id, frame, why))
		{
			hides.extend(id, frame, cycle);
			if (givenTo != hides.toId(id))
			{
				hides.setTo(id, givenTo, holderName(givenTo, wv));
			}
			return;
		}
		hides.start(id, frame, cycle, why, nameOf(player), givenTo, holderName(givenTo, wv), stacks.fireAt(tileKey) != null);
	}

	/** Who the middle went to, for the report: "you", their name, or null if nobody. */
	private String holderName(int id, WorldView wv)
	{
		if (id < 0)
		{
			return null;
		}
		Player local = client.getLocalPlayer();
		if (local != null && local.getId() == id)
		{
			return "you";
		}
		Player p = wv.players().byIndex(id);
		return p == null ? "someone" : nameOf(p);
	}

	private static String nameOf(Player player)
	{
		String name = player.getName();
		return name == null ? "someone" : name.replace('\u00A0', ' ');
	}

	private final Map<Long, int[]> middleChoice = new HashMap<>();

	/**
	 * Who is shown in the middle of a tile where people wait without a spot: you, if you're standing
	 * there, or someone whose spot is the middle itself, or else always the same one of them: whoever has stood there longest (then the lowest
	 * id) among them that may be drawn. Going by id alone, a tender alone at a campfire vanished
	 * whenever someone with a lower id came to wait beside him. The game draws one of a stack, and which one
	 * changes from frame to frame (more so while the stack probe is at work), so two people
	 * waiting there took turns, each flashing in and out. -1 if nobody is waiting there.
	 */
	private int middleOf(long tileKey, WorldView wv, int x, int z)
	{
		int frame = offsets.frame();
		int[] cached = middleChoice.get(tileKey);
		if (cached != null && cached[0] == frame)
		{
			return cached[1];
		}
		if (middleChoice.size() > 512)
		{
			middleChoice.values().removeIf(v -> v[0] < frame - 2);
		}
		int best = -1;
		int bestArrived = Integer.MAX_VALUE;
		int cycle = client.getGameCycle();
		Player local = client.getLocalPlayer();
		int localId = local == null ? -1 : local.getId();
		LocalPoint you = local == null ? null : local.getLocalLocation();
		if (you != null && you.getX() == x && you.getY() == z && localId >= 0 && localId < OffsetTable.CAPACITY
			&& local.getWorldView() == wv && offsets.dx(localId) == 0 && offsets.dz(localId) == 0 && !offsets.isWalking(localId))
		{
			// You're standing in the middle of this tile, and you're always drawn: the middle is yours.
			middleChoice.put(tileKey, new int[]{frame, localId});
			return localId;
		}
		for (int id : stacks.membersAt(tileKey))
		{
			// Someone with a spot is always drawn, and the planner can give out the middle itself as
			// a spot: then it's theirs, and nobody waiting is drawn inside them. Only if it really is
			// their spot: given one elsewhere, they stand in the middle a frame or two before setting
			// off, and taking the middle then made the one shown there blink out.
			if (id < 0 || id >= OffsetTable.CAPACITY || id == localId || stacks.isUnplaced(id)
				|| offsets.dx(id) != 0 || offsets.dz(id) != 0 || offsets.isWalking(id) || !offsets.targetIsMiddle(id))
			{
				continue;
			}
			Player p = wv.players().byIndex(id);
			LocalPoint lp = p == null ? null : p.getLocalLocation();
			if (lp == null || lp.getX() != x || lp.getY() != z || !probe.mayDraw(id, p, cycle) || !probe.othersAllow(renderCallbacks, p))
			{
				continue;
			}
			middleChoice.put(tileKey, new int[]{frame, id});
			return id;
		}
		for (int id : stacks.membersAt(tileKey))
		{
			if (id < 0 || id >= OffsetTable.CAPACITY || !stacks.isUnplaced(id)
				|| offsets.dx(id) != 0 || offsets.dz(id) != 0 || offsets.isWalking(id))
			{
				continue;
			}
			int arrived = stacks.arrivedTick(id);
			if (best >= 0 && (arrived > bestArrived || arrived == bestArrived && id >= best))
			{
				continue;
			}
			Player p = wv.players().byIndex(id);
			if (p == null || p == local)
			{
				continue;
			}
			LocalPoint lp = p.getLocalLocation();
			if (lp == null || lp.getX() != x || lp.getY() != z || !probe.mayDraw(id, p, cycle) || !probe.othersAllow(renderCallbacks, p))
			{
				continue;
			}
			best = id;
			bestArrived = arrived;
		}
		middleChoice.put(tileKey, new int[]{frame, best});
		return best;
	}

	private boolean memberOf(long tileKey, int id)
	{
		for (int m : stacks.membersAt(tileKey))
		{
			if (m == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether someone other than {@code id} is the one shown in the middle of this tile. */
	private boolean otherInMiddle(long tileKey, WorldView wv, int x, int z, int id)
	{
		int chosen = middleOf(tileKey, wv, x, z);
		return chosen >= 0 && chosen != id;
	}

	/** How far away someone they're dealing with may be for a player drawn at a spot to be turned to face them: 10 tiles. */
	private static final int FACE_TARGET_REACH = 10 * 128;

	/**
	 * Which way a player drawn at a spot faces. Whoever they're dealing with (a Grand Exchange clerk,
	 * a banker, someone they're trading with) they face from where they're drawn, as the game turns
	 * them to face them from where they stand. Turned instead to a point a tile ahead of their tile,
	 * like the rest of a row, people at a clerk two tiles away behind the counter were angled well
	 * past him, unlike you beside them. Otherwise the tile's own rule: the fire, what a row faces, a
	 * pose. {@code x} and {@code z} are where they really stand.
	 */
	private int drawnFacing(Player player, long tileKey, int orientation, int dx, int dz, int x, int z, WorldView wv)
	{
		Actor target = player.getInteracting();
		if (target != null && target.getWorldView() == wv)
		{
			LocalPoint at = target.getLocalLocation();
			if (at != null)
			{
				int toX = at.getX();
				int toZ = at.getY();
				if (target instanceof Player)
				{
					int id = ((Player) target).getId();
					if (id >= 0 && id < OffsetTable.CAPACITY)
					{
						// Someone else drawn at a spot: face them where they're drawn.
						toX += offsets.dx(id);
						toZ += offsets.dz(id);
					}
				}
				int facing = facingToward(x + dx, z + dz, toX, toZ, -1);
				if (facing >= 0)
				{
					return facing;
				}
			}
		}
		return stacks.drawOrientation(tileKey, orientation, dx, dz);
	}

	/** The orientation that looks from one point to another, or {@code otherwise} if they're on top of each other or too far apart. */
	static int facingToward(int fromX, int fromZ, int toX, int toZ, int otherwise)
	{
		double east = toX - fromX;
		double north = toZ - fromZ;
		double d = Math.hypot(east, north);
		return d < 1 || d > FACE_TARGET_REACH ? otherwise : OffsetTable.facing(east, north);
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
	 * been walking. Someone busy with an action (alching, tending a campfire) is shown walking too,
	 * the action left out for the step (see {@link #modelMidStep}): with it in, it was drawn over the
	 * walk, legs and all, and they glided. Returns null (draw them as the game did) if the animation
	 * can't be read, or busy walks have been switched off. The model stays valid until the next model
	 * is built.
	 *
	 * <p>The walk goes in as their action as well as their pose ({@link #modelStepping}): in a crowd
	 * the client stops animating the pose of anyone standing still, and the walk didn't show. Should
	 * the game ever report the swap, it goes back to the pose alone ({@link #modelMidStep}). Animation
	 * smoothing is held off for the step: it blends by how far into its frame the player's own
	 * animation had got, which has nothing to do with this walk, and can throw the legs past the next frame.
	 */
	private Model walkModel(Player player, int id)
	{
		int action = player.getAnimation();
		if (action != -1 && !busyWalks)
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
		busySwap = busyWalks ? player : null;
		IntPredicate smoothing = client.getAnimationInterpolationFilter();
		try
		{
			if (smoothing != null)
			{
				client.setAnimationInterpolationFilter(null);
			}
			Model model = busyWalks ? modelStepping(player, walk, frame) : modelMidStep(player, walk, frame);
			checkWalkShown(player, id, model, action != -1, WALK_TO_SPOT, walk);
			if (action != -1)
			{
				walkBusy++;
				if (busyAnimations.size() < 64 || busyAnimations.containsKey(action))
				{
					busyAnimations.merge(action, 1L, Long::sum);
				}
			}
			return model;
		}
		catch (RuntimeException e)
		{
			revealErrors++;
			return null;
		}
		finally
		{
			busySwap = null;
			if (smoothing != null)
			{
				client.setAnimationInterpolationFilter(smoothing);
			}
		}
	}

	/**
	 * Client thread only. This player's model taking a step where they stand: their walk, at this
	 * frame, as both their action and their pose. Everything is put back before returning, as in
	 * {@link #modelMidStep}, and nobody is told: setAnimation unhooks the actor while it writes.
	 *
	 * <p>The pose alone doesn't do it in a crowd. With more than 200 players about (50 in low-memory
	 * mode), every frame before the scene is drawn the client marks each other player whose pose is
	 * their idle pose as not animated, and then builds them with no pose at all. Someone being moved
	 * to a spot is standing still, so a walk put only in their pose was dropped and the idle's
	 * leftover frame drawn instead: legs still while the body slid. The action is never dropped that
	 * way. The pose gets the walk too for an action still waiting out its start delay, which the
	 * client leaves out instead. With the same walk at the same frame in both, the model is just
	 * that walk. A walk can still freeze when the server has frozen the player's animation (it then
	 * draws copies the API can't reach); {@link #checkWalkShown} still counts that.
	 */
	static Model modelStepping(Player player, int walk, int walkFrame)
	{
		int action = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int pose = player.getPoseAnimation();
		int poseFrame = player.getPoseAnimationFrame();
		try
		{
			player.setAnimation(walk);
			player.setAnimationFrame(walkFrame);
			player.setPoseAnimation(walk);
			player.setPoseAnimationFrame(walkFrame);
			return player.getModel();
		}
		finally
		{
			try
			{
				player.setPoseAnimation(pose);
				player.setPoseAnimationFrame(poseFrame);
			}
			finally
			{
				restoreAction(player, action, actionFrame);
			}
		}
	}

	/**
	 * Client thread only. This player's model with their walk, at this frame, in place of their pose,
	 * and any action they're busy with left out of it. Everything is put back before returning, the
	 * action at the frame it had reached, so the game's own animation update never sees a change.
	 * Nobody is told about it either: the client's setAnimation unhooks the actor from the animation
	 * while it writes, so no AnimationChanged is posted or queued (checked in clients 1.12.38 to
	 * 1.13.2), and the pose has no hook at all. If one ever is, the plugin switches busy walks off
	 * ({@link #fromBusySwap}).
	 */
	static Model modelMidStep(Player player, int walk, int walkFrame)
	{
		int action = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int pose = player.getPoseAnimation();
		int poseFrame = player.getPoseAnimationFrame();
		try
		{
			if (action != -1)
			{
				player.setAnimation(-1);
			}
			player.setPoseAnimation(walk);
			player.setPoseAnimationFrame(walkFrame);
			return player.getModel();
		}
		finally
		{
			try
			{
				player.setPoseAnimation(pose);
				player.setPoseAnimationFrame(poseFrame);
			}
			finally
			{
				if (action != -1)
				{
					restoreAction(player, action, actionFrame);
				}
			}
		}
	}

	/** The action back, then its frame: choosing an action starts it from its first frame. */
	private static void restoreAction(Player player, int action, int frame)
	{
		try
		{
			player.setAnimation(action);
			player.setAnimationFrame(frame);
		}
		catch (RuntimeException e)
		{
			// Left half-chosen, the actor would stop telling any plugin about its animation changes
			// until the next one. Cleared instead (that can't fail), the action is just lost until
			// the server sends the next one. Never seen to happen.
			player.setAnimation(-1);
			throw e;
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
		// The middle is taken by whoever the game drew, unless they're waiting without a spot and
		// someone else waiting is the one shown there (see middleOf).
		boolean drawnStepsAside = stacks.isUnplaced(drawn.getId()) && !isLocal(drawn)
			&& otherInMiddle(tileKey, wv, x, z, drawn.getId());
		// ...and someone with a spot elsewhere, still in the middle before setting off, doesn't take it.
		boolean drawnHoldsMiddle = stacks.isUnplaced(drawn.getId()) || isLocal(drawn)
			|| offsets.targetIsMiddle(drawn.getId()) && memberOf(tileKey, drawn.getId());
		boolean middleDrawn = offsets.dx(drawn.getId()) == 0 && offsets.dz(drawn.getId()) == 0 && !drawnStepsAside && drawnHoldsMiddle
			|| stacks.middleOutOfSight(tileKey);
		int chosenMiddle = middleOf(tileKey, wv, x, z);
		Player local = client.getLocalPlayer();
		int localId = local == null ? -1 : local.getId();
		int total = mates.length + heldCount;
		TileDraws td = tileDraws(tileKey, frame);
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
					td.moved++;
					skippedMoved++;
					continue;
				}
				int mdx = offsets.dx(id);
				int mdz = offsets.dz(id);
				boolean inMiddle = mdx == 0 && mdz == 0 && !offsets.isWalking(id);
				// Still in the middle without holding it (given a spot elsewhere, not yet setting off,
				// or held back and not one of the stack yet): hidden there while someone else is shown.
				boolean sitter = inMiddle && id != localId && !stacks.isUnplaced(id) && !reallyMoving(mate)
					&& (!offsets.targetIsMiddle(id) || i >= mates.length && !memberOf(tileKey, id));
				if (sitter && chosenMiddle >= 0 && chosenMiddle != id)
				{
					noteHidden(mate, id, HideLog.NO_SPOT_MIDDLE_TAKEN, chosenMiddle, wv, tileKey);
					continue;
				}
				if (inMiddle && id != localId && stacks.isUnplaced(id) && (middleDrawn || chosenMiddle >= 0 && id != chosenMiddle))
				{
					boolean waiting = stacks.isWaiting(id);
					if (stacks.middleOutOfSight(tileKey))
					{
						noteHidden(mate, id, waiting ? HideLog.WAITING_OUT_OF_SIGHT : HideLog.NO_SPOT_OUT_OF_SIGHT, -1, wv, tileKey);
					}
					else
					{
						boolean drawnHasIt = offsets.dx(drawn.getId()) == 0 && offsets.dz(drawn.getId()) == 0 && !drawnStepsAside && drawnHoldsMiddle;
						noteHidden(mate, id, waiting ? HideLog.WAITING_MIDDLE_TAKEN : HideLog.NO_SPOT_MIDDLE_TAKEN,
							drawnHasIt ? drawn.getId() : chosenMiddle, wv, tileKey);
					}
					continue;
				}
				if (!probe.mayDraw(id, mate, cycle))
				{
					td.unconfirmed++;
					skippedUnconfirmed++;
					if (stacks.fireAt(tileKey) != null)
					{
						fireUnconfirmed++;
					}
					noteHidden(mate, id, HideLog.UNCONFIRMED, -1, wv, tileKey);
					continue; // the game hasn't shown this player recently, e.g. hidden by the server: never draw them
				}
				if (!probe.othersAllow(renderCallbacks, mate))
				{
					td.othersHid++;
					skippedOthersHid++;
					noteHidden(mate, id, HideLog.OTHER_PLUGIN, -1, wv, tileKey);
					continue; // hidden by another plugin, e.g. Entity Hider: respect that
				}
				int mateOrientation = drawnFacing(mate, tileKey, mate.getCurrentOrientation(), mdx, mdz, x, z, wv);
				int mateY = ground - mate.getAnimationHeightOffset() + groundDelta(wv, plane, x, z, x + mdx, z + mdz);
				boolean walking = offsets.isWalking(id);
				noteMotion(mate, id, mdx, mdz, walking && (busyWalks || mate.getAnimation() == -1), false);
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
					markDrawn(mate);
				}
				revealedFrame[id] = frame;
				revealedDraws++;
				td.count++;
				// Only someone who holds the middle takes it: one drawn there on their way out doesn't.
				middleDrawn |= inMiddle && (id == localId || stacks.isUnplaced(id) || offsets.targetIsMiddle(id));
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
			try
			{
				drawRefused(offsets.frame());
			}
			catch (RuntimeException e)
			{
				revealErrors++;
			}
			countDrops();
			countBlinks(offsets.frame());
			drawKept();
		}
		delegate.drawPass(projection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection projection, Scene scene, int zx, int zy)
	{
		if (isMainScene(scene))
		{
			// The main scene's zones are counted from the edge of its extended area, five zones out.
			noteZone(zx - MAIN_ZONE_OFFSET, zy - MAIN_ZONE_OFFSET);
		}
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
