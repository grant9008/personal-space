package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * A pet standing in the middle of a tile someone stands on or crowds, frame after frame, with the
 * game adding people to the scene by its own rules (read from the injected client, 1.13.2): each
 * frame it adds you, then every other player in turn, then NPCs of the default draw priority, which
 * every pet in the cache has. Only the first one standing in the middle of a tile gets in; anyone
 * after them there is skipped before any plugin hears of it, unless a render callback turns the
 * first down, which lets the next one in. And a tile holds five things at most. Whoever gets in is
 * handed to the shim, and then the renderer asks for its opaque pass. Where everyone is drawn, and
 * with which model (a pet's walk is a model of its own here), is kept for each frame.
 */
public class FollowerOnCrowdedTileTest
{
	private static final int TILE = 50;
	private static final int MIDDLE = TILE * 128 + 64;
	private static final int IDLE = 808;
	private static final int WALK = 819;
	private static final int PET_IDLE = 6609;
	private static final int PET_WALK = 6610;
	/** The game's walking pace, a frame at a time: a tile every 32 frames. */
	private static final int PACE = 4;
	/** The furthest anyone walking is drawn from one frame to the next: a stride at walking pace, rounded to whole units each way. */
	private static final int STRIDE = PACE + 2;
	/** The camera, 1500 south of the crowd, as the shim's sort and boxes see it. */
	private static final Projection CAMERA = new Projection()
	{
		@Override
		public float[] project(float x, float y, float z)
		{
			return project(x, y, z, new float[3]);
		}

		@Override
		public float[] project(float x, float y, float z, float[] out)
		{
			out[0] = x - MIDDLE;
			out[1] = y;
			out[2] = z - MIDDLE + 1500;
			return out;
		}
	};

	/** Someone in the game, where they really stand, and what they're doing. */
	private static final class Body
	{
		final int id;
		int x;
		int z;
		Renderable actor;
		Model model;
		/** A pet's model mid-step: what it's built as while its pose or action is its walk. */
		Model walkModel;
		GameObject object;
		int pose;
		int poseFrame;
		int animation = -1;
		int animationFrame;
		int orientation;
		Renderable interacting;

		Body(int id, int x, int z)
		{
			this.id = id;
			this.x = x;
			this.z = z;
		}

		boolean is(Model m)
		{
			return m == model || m != null && m == walkModel;
		}
	}

	private static final class Game
	{
		final List<Body> players = new ArrayList<>();
		final List<Body> npcs = new ArrayList<>();
		Body you;
		int cycle;
		/** Things on each tile other than people, such as a campfire and its flames. */
		final int[][] scenery = new int[104][104];
		/** What's on each tile this frame, and the frame each tile last let someone into its middle. */
		final int[][] count = new int[104][104];
		final int[][] lastDrawnActor = new int[104][104];
		final Tile[][][] tiles = new Tile[4][104][104];
		/** The game's collision flags for each tile, and the tiles with a fire on them, as the plugin's planner reads them. */
		final int[][] flags = new int[104][104];
		final Set<Long> fires = new HashSet<>();
		final StackRegistry stacks = new StackRegistry();
		final OffsetTable offsets = new OffsetTable();
		final RenderCallbackManager callbacks = new RenderCallbackManager();
		final Client client;
		final WorldView wv;
		final Scene scene;
		final StackProbe probe;
		final SpreadingDrawCallbacks shim;
		final CrowdPlanner.Surroundings ground;
		/** The models the renderer was handed this frame (each body has one of its own), and where: {x, z, orientation}. */
		final List<Model> drawn = new ArrayList<>();
		final List<int[]> where = new ArrayList<>();
		/** With the plugin's own game ticks running (see {@link #tick}), every 30th frame: 0.6 s at a frame each 0.02 s. */
		boolean ticking;
		final CrowdPlanner planner = new CrowdPlanner();
		final StillnessTracker stillness = new StillnessTracker();
		int tick;

		Game()
		{
			scene = FakeMeshes.fake(Scene.class, Map.of("getTiles", tiles));
			Map<String, Object> w = new HashMap<>();
			w.put("getScene", scene);
			w.put("getSizeX", 104);
			w.put("getSizeY", 104);
			w.put("getTileHeights", new int[4][105][105]);
			w.put("getTileSettings", new byte[4][104][104]);
			w.put("players", set(players));
			w.put("npcs", set(npcs));
			wv = FakeMeshes.fake(WorldView.class, w);
			Animation walk = FakeMeshes.fake(Animation.class, Map.of("getFrameLengths", new int[]{3, 3, 3, 3, 3, 3, 3, 3}));
			Map<String, Object> c = new HashMap<>();
			c.put("isClientThread", true);
			c.put("getTopLevelWorldView", wv);
			c.put("getWorldView", wv);
			c.put("getLocalPlayer", (Function<Object[], Object>) args -> you == null ? null : you.actor);
			c.put("getGameCycle", (Function<Object[], Object>) args -> cycle);
			c.put("getCameraX", MIDDLE);
			c.put("getCameraY", MIDDLE - 1500);
			c.put("getViewportWidth", 800);
			c.put("getViewportHeight", 600);
			c.put("getScale", 512);
			c.put("loadAnimation", walk);
			client = FakeMeshes.fake(Client.class, c);
			probe = new StackProbe(client, offsets, stacks);
			probe.enabled = true;
			callbacks.register(probe);
			Map<String, Object> d = new HashMap<>();
			d.put("drawTemp", (Function<Object[], Object>) args ->
			{
				drawn.add((Model) args[3]);
				where.add(new int[]{(Integer) args[5], (Integer) args[7], (Integer) args[4]});
				return null;
			});
			shim = new SpreadingDrawCallbacks(client, offsets, stacks, probe, callbacks, FakeMeshes.fake(DrawCallbacks.class, d));
			ground = FakeMeshes.fake(CrowdPlanner.Surroundings.class, Map.of("canStand",
				(Function<Object[], Object>) args -> canStand((Long) args[0], (Integer) args[1], (Integer) args[2])));
			shim.ground = ground;
		}

		/** As the plugin's planner has it: ground a body could stand on, reached from the middle of the tile, clear of fires. */
		private boolean canStand(long tile, int dx, int dz)
		{
			int x = StackRegistry.sceneX(tile) * 128 + 64;
			int z = StackRegistry.sceneY(tile) * 128 + 64;
			if (!new CollisionTerrain(new int[][][]{flags}).canStand(0, x, z, x + dx, z + dz))
			{
				return false;
			}
			for (int cx : new int[]{x + dx - PersonalSpacePlugin.FIRE_CLEARANCE, x + dx + PersonalSpacePlugin.FIRE_CLEARANCE})
			{
				for (int cz : new int[]{z + dz - PersonalSpacePlugin.FIRE_CLEARANCE, z + dz + PersonalSpacePlugin.FIRE_CLEARANCE})
				{
					if (fires.contains(StackRegistry.key(0, cx >> 7, cz >> 7)))
					{
						return false;
					}
				}
			}
			return true;
		}

		private static <T> IndexedObjectSet<T> set(List<Body> bodies)
		{
			Map<String, Object> a = new HashMap<>();
			a.put("byIndex", (Function<Object[], Object>) args ->
			{
				for (Body b : bodies)
				{
					if (b.id == (Integer) args[0])
					{
						return b.actor;
					}
				}
				return null;
			});
			a.put("iterator", (Function<Object[], Object>) args ->
			{
				List<Object> actors = new ArrayList<>();
				bodies.forEach(b -> actors.add(b.actor));
				return actors.iterator();
			});
			@SuppressWarnings("unchecked")
			IndexedObjectSet<T> s = FakeMeshes.fake(IndexedObjectSet.class, a);
			return s;
		}

		private Map<String, Object> body(Body b)
		{
			b.model = FakeMeshes.model(FakeMeshes.strip(4), 1000 + b.id);
			Map<String, Object> a = new HashMap<>();
			a.put("getLocalLocation", (Function<Object[], Object>) args -> new LocalPoint(b.x, b.z, 0));
			a.put("getWorldLocation", new WorldPoint(3200, 3200, 0));
			a.put("getWorldView", wv);
			a.put("getModel", (Function<Object[], Object>) args -> b.walkModel != null && (b.pose == PET_WALK || b.animation == PET_WALK) ? b.walkModel : b.model);
			a.put("getModelHeight", 200);
			a.put("getRenderMode", Renderable.RENDERMODE_SORTED_NO_DEPTH);
			a.put("getAnimation", (Function<Object[], Object>) args -> b.animation);
			a.put("setAnimation", (Function<Object[], Object>) args -> b.animation = (Integer) args[0]);
			a.put("getAnimationFrame", (Function<Object[], Object>) args -> b.animationFrame);
			a.put("setAnimationFrame", (Function<Object[], Object>) args -> b.animationFrame = (Integer) args[0]);
			a.put("getPoseAnimation", (Function<Object[], Object>) args -> b.pose);
			a.put("setPoseAnimation", (Function<Object[], Object>) args -> b.pose = (Integer) args[0]);
			a.put("getPoseAnimationFrame", (Function<Object[], Object>) args -> b.poseFrame);
			a.put("setPoseAnimationFrame", (Function<Object[], Object>) args -> b.poseFrame = (Integer) args[0]);
			a.put("getCurrentOrientation", (Function<Object[], Object>) args -> b.orientation);
			a.put("getInteracting", (Function<Object[], Object>) args -> b.interacting);
			return a;
		}

		private void file(Body b)
		{
			Map<String, Object> o = new HashMap<>();
			o.put("getRenderable", b.actor);
			o.put("getX", (Function<Object[], Object>) args -> b.x);
			o.put("getY", (Function<Object[], Object>) args -> b.z);
			b.object = FakeMeshes.fake(GameObject.class, o);
			tile(b.x >> 7, b.z >> 7);
		}

		/** The tile's objects as the scene has them: as many as are on it this frame. */
		private void tile(int x, int z)
		{
			if (tiles[0][x][z] == null)
			{
				GameObject thing = FakeMeshes.fake(GameObject.class, new HashMap<>());
				tiles[0][x][z] = FakeMeshes.fake(Tile.class, Map.of("getGameObjects", (Function<Object[], Object>) args ->
				{
					GameObject[] objects = new GameObject[5];
					for (int i = 0; i < Math.min(5, count[x][z]); i++)
					{
						objects[i] = thing;
					}
					return objects;
				}));
			}
		}

		Body player(int id, int tileX, int tileZ)
		{
			Body b = new Body(id, tileX * 128 + 64, tileZ * 128 + 64);
			b.pose = IDLE;
			Map<String, Object> a = body(b);
			a.put("getId", id);
			a.put("getName", "Player " + id);
			a.put("getIdlePoseAnimation", IDLE);
			a.put("getWalkAnimation", WALK);
			a.put("getRunAnimation", 824);
			b.actor = FakeMeshes.fake(Player.class, a);
			file(b);
			players.add(b);
			return b;
		}

		Body npc(int index, int tileX, int tileZ, boolean follower)
		{
			Body b = new Body(index, tileX * 128 + 64, tileZ * 128 + 64);
			b.pose = PET_IDLE;
			Map<String, Object> a = body(b);
			b.walkModel = FakeMeshes.model(FakeMeshes.strip(4), 3000 + index);
			NPCComposition composition = FakeMeshes.fake(NPCComposition.class, Map.of("isFollower", follower, "getSize", 1));
			a.put("getIndex", index);
			a.put("getId", 11277);
			a.put("getName", "Nexling");
			a.put("getComposition", composition);
			a.put("getTransformedComposition", composition);
			a.put("getIdlePoseAnimation", PET_IDLE);
			a.put("getWalkAnimation", PET_WALK);
			b.actor = FakeMeshes.fake(NPC.class, a);
			file(b);
			npcs.add(b);
			return b;
		}

		/** These players stand in the middle of the tile, spread out to spots round it as the planner would have them. */
		void crowd(int tileX, int tileZ, Body... people)
		{
			crowd(tileX, tileZ, null, false, people);
		}

		/**
		 * As above, with {@code middle} (if not null) one of them too, whose spot is the middle itself, or
		 * who waits there without a spot ({@code waiting}): the one shown in the middle.
		 */
		void crowd(int tileX, int tileZ, Body middle, boolean waiting, Body... people)
		{
			List<StackSpreader.Placement> placements = new ArrayList<>();
			Set<Integer> unplaced = new HashSet<>();
			long key = StackRegistry.key(0, tileX, tileZ);
			if (middle != null)
			{
				middle.x = tileX * 128 + 64;
				middle.z = tileZ * 128 + 64;
				placements.add(new StackSpreader.Placement(middle.id, key, 0, 0));
				offsets.setTarget(middle.id, 0, 0);
				if (waiting)
				{
					unplaced.add(middle.id);
				}
			}
			for (int i = 0; i < people.length; i++)
			{
				Body p = people[i];
				p.x = tileX * 128 + 64;
				p.z = tileZ * 128 + 64;
				tile(tileX, tileZ);
				int dx = i % 2 == 0 ? 96 : -96;
				int dz = i / 2 * 64;
				placements.add(new StackSpreader.Placement(p.id, key, dx, dz));
				offsets.setTarget(p.id, dx, dz);
			}
			stacks.rebuild(placements, new HashSet<>(), unplaced);
		}

		/** The crowd breaks up: nobody is spread out any more. */
		void noCrowd()
		{
			offsets.clearTargets();
			stacks.rebuild(new ArrayList<>());
		}

		/** The effect switched off, or a PvP area: as the plugin's reset, everyone straight back and nothing known. */
		void effectOff()
		{
			probe.enabled = false;
			offsets.snapAllToZero();
			stacks.clear();
			shim.ground = null;
		}

		void effectOn()
		{
			probe.enabled = true;
			shim.ground = ground;
		}

		/** The ground as the plugin builds it each tick, for its planner and the shim: the game's collision flags, and fires. */
		CrowdPlanner.Surroundings surroundings()
		{
			CollisionTerrain terrain = new CollisionTerrain(new int[][][]{flags});
			return new CrowdPlanner.Surroundings()
			{
				@Override
				public boolean canStand(long tile, int dx, int dz)
				{
					return Game.this.canStand(tile, dx, dz);
				}

				@Override
				public boolean facesObstacle(long tile, double angle)
				{
					return terrain.facesObstacle(0, StackRegistry.sceneX(tile), StackRegistry.sceneY(tile), angle);
				}

				@Override
				public boolean isCounter(long tile, double angle)
				{
					return terrain.isCounter(0, StackRegistry.sceneX(tile), StackRegistry.sceneY(tile), angle);
				}

				@Override
				public boolean facesFire(long tile, double angle)
				{
					return false;
				}

				@Override
				public List<int[]> firesNear(long tile)
				{
					List<int[]> out = new ArrayList<>();
					for (int east = -1; east <= 1; east++)
					{
						for (int north = -1; north <= 1; north++)
						{
							if (fires.contains(StackRegistry.key(0, StackRegistry.sceneX(tile) + east, StackRegistry.sceneY(tile) + north)))
							{
								out.add(new int[]{east, north});
							}
						}
					}
					return out;
				}
			};
		}

		/**
		 * A game tick as the plugin has it (see PersonalSpacePlugin#onGameTick): everyone in the middle
		 * of the same tile two ticks running, you too, goes to its planner (Smart, spacing 256, 30 a
		 * tile, a spot for you), and the plan is handed over as the plugin hands it: spots, who is on
		 * each crowded tile, the middles out of sight, and the ground.
		 */
		void tick()
		{
			tick++;
			List<StackSpreader.Entry> still = new ArrayList<>();
			for (Body p : players)
			{
				long key = StackRegistry.key(0, p.x >> 7, p.z >> 7);
				boolean centred = StillnessTracker.isCentred(p.x, p.z);
				offsets.setWalkingOff(p.id, !centred);
				if (stillness.observe(p.id, key, centred, tick))
				{
					still.add(new StackSpreader.Entry(p.id, key, p == you, p.orientation));
				}
			}
			planner.cameraX = MIDDLE;
			planner.cameraY = MIDDLE - 1500;
			CrowdPlanner.Surroundings around = surroundings();
			CrowdPlanner.Plan plan = planner.plan(still, id -> true, 256, 30, true, true, tick, around);
			shim.ground = around;
			offsets.clearTargets();
			for (StackSpreader.Placement pl : plan.placements)
			{
				offsets.setTarget(pl.id, pl.dx, pl.dz);
			}
			List<StackSpreader.Placement> members = new ArrayList<>(plan.placements);
			members.addAll(plan.unplaced);
			Set<Integer> unplaced = new HashSet<>();
			plan.unplaced.forEach(pl -> unplaced.add(pl.id));
			Set<Long> turnIn = new HashSet<>(plan.curvedRows);
			turnIn.addAll(plan.facingIn);
			stacks.rebuild(members, turnIn, unplaced, plan.fires, plan.poses, plan.middleOutOfSight);
			stacks.setArrivals(stillness::arrivedTick);
		}

		/** One frame: who the game adds to the scene, handed to the shim, then the renderer's opaque pass. */
		List<Model> frame()
		{
			cycle++;
			if (ticking && cycle % 30 == 0)
			{
				tick();
			}
			offsets.advance(0.02f);
			drawn.clear();
			where.clear();
			for (int x = 0; x < 104; x++)
			{
				count[x] = scenery[x].clone();
			}
			List<Body> added = new ArrayList<>();
			add(you, added);
			for (Body p : players)
			{
				if (p != you)
				{
					add(p, added);
				}
			}
			for (Body n : npcs)
			{
				add(n, added);
			}
			for (Body b : added)
			{
				shim.drawTemp(CAMERA, scene, b.object, b.actor.getModel(), b.orientation, b.x, 0, b.z);
			}
			shim.drawPass(CAMERA, scene, DrawCallbacks.PASS_OPAQUE);
			return new ArrayList<>(drawn);
		}

		/**
		 * The game adding someone to the scene: the client's player and NPC adders (one actor in the
		 * middle of a tile a frame), then the scene's own add, where RuneLite asks the render
		 * callbacks first and, if they turn someone in the middle of a tile down, frees the middle.
		 */
		private void add(Body b, List<Body> added)
		{
			int tx = b.x >> 7;
			int tz = b.z >> 7;
			boolean middle = StillnessTracker.isCentred(b.x, b.z);
			if (middle)
			{
				if (lastDrawnActor[tx][tz] == cycle)
				{
					return;
				}
				lastDrawnActor[tx][tz] = cycle;
			}
			if (!callbacks.addEntity(b.actor, false))
			{
				if (middle)
				{
					lastDrawnActor[tx][tz] = -1;
				}
				return;
			}
			if (count[tx][tz] >= 5)
			{
				return;
			}
			count[tx][tz]++;
			added.add(b);
		}

		/** How many times {@code who} was handed to the renderer in each of the next {@code frames} frames, as a string of digits. */
		String run(Body who, int frames)
		{
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < frames; i++)
			{
				b.append(frame().stream().filter(who::is).count());
			}
			return b.toString();
		}

		/** Where {@code who} was drawn in the last frame, as {x, z, orientation, walking (1) or not}, or null unless it was drawn just once. */
		int[] at(Body who)
		{
			int[] found = null;
			for (int i = 0; i < drawn.size(); i++)
			{
				if (who.is(drawn.get(i)))
				{
					if (found != null)
					{
						return null;
					}
					int[] w = where.get(i);
					found = new int[]{w[0], w[1], w[2], drawn.get(i) == who.walkModel ? 1 : 0};
				}
			}
			return found;
		}

		/** The next {@code frames} frames, {@code before} each: where {@code who} was drawn in each (see {@link #at}). */
		List<int[]> trace(Body who, int frames, Runnable before)
		{
			List<int[]> out = new ArrayList<>();
			for (int i = 0; i < frames; i++)
			{
				before.run();
				frame();
				out.add(at(who));
			}
			return out;
		}

		List<int[]> trace(Body who, int frames)
		{
			return trace(who, frames, () ->
			{
			});
		}

		/** {@code who} a frame's walk nearer (x, z), walking for real (in their walk pose) until there; standing once there. */
		void stepTowards(Body who, int x, int z)
		{
			int walk = who.walkModel != null ? PET_WALK : WALK;
			int idle = who.walkModel != null ? PET_IDLE : IDLE;
			if (who.x == x && who.z == z)
			{
				who.pose = idle;
				return;
			}
			who.pose = walk;
			who.x += Integer.signum(x - who.x) * Math.min(PACE, Math.abs(x - who.x));
			who.z += Integer.signum(z - who.z) * Math.min(PACE, Math.abs(z - who.z));
		}

		/** Turn down anyone this matches, as another plugin (Entity Hider) would. */
		void hide(Predicate<Renderable> hidden)
		{
			callbacks.register(new RenderCallback()
			{
				@Override
				public boolean addEntity(Renderable renderable, boolean ui)
				{
					return !hidden.test(renderable);
				}
			});
		}
	}

	private static String times(String digit, int n)
	{
		return digit.repeat(n);
	}

	/** How many times it was drawn in each frame of a trace, as a string of digits (2 standing for more than once). */
	private static String seen(List<int[]> trace)
	{
		StringBuilder b = new StringBuilder();
		trace.forEach(at -> b.append(at == null ? "0" : "1"));
		return b.toString();
	}

	/** The furthest it was drawn from one frame to the next, along a trace drawn in every frame. */
	private static double largestStep(List<int[]> trace)
	{
		double most = 0;
		for (int i = 1; i < trace.size(); i++)
		{
			int[] a = trace.get(i - 1);
			int[] b = trace.get(i);
			most = Math.max(most, Math.hypot(b[0] - a[0], b[1] - a[1]));
		}
		return most;
	}

	private static double apart(int[] a, int x, int z)
	{
		return Math.hypot(a[0] - x, a[1] - z);
	}

	private static double apart(int[] a, int[] b)
	{
		return Math.hypot(a[0] - b[0], a[1] - b[1]);
	}

	private static int[] last(List<int[]> trace)
	{
		return trace.get(trace.size() - 1);
	}

	private static List<int[]> joined(List<int[]> a, List<int[]> b)
	{
		List<int[]> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}

	private static long walkingFrames(List<int[]> trace)
	{
		return trace.stream().filter(at -> at != null && at[3] == 1).count();
	}

	/** You walk two tiles west onto a pet whose owner stands three tiles west of it, and stand there. */
	private static List<int[]> stepOntoPet(Game g, Body pet)
	{
		List<int[]> walk = g.trace(pet, 70, () -> g.stepTowards(g.you, MIDDLE, MIDDLE));
		return joined(walk, g.trace(pet, 150, () -> g.stepTowards(g.you, MIDDLE, MIDDLE)));
	}

	@Test
	public void aPetInTheMiddleOfASpreadCrowdIsDrawnEveryFrame()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, a, b);
		g.run(pet, 100);
		// The game lets one of the crowd into the middle of the tile and skips the pet after them,
		// so it was never drawn, though the crowd was drawn at spots round it and the middle was empty.
		Assert.assertEquals(times("1", 300), g.run(pet, 300));
		Assert.assertEquals("the crowd is still all drawn", times("1", 50), g.run(a, 50));
		Assert.assertEquals(times("1", 50), g.run(b, 50));
	}

	@Test
	public void aPetDoesNotComeAndGoAsPeopleStepOnAndOffItsTile()
	{
		// The video: a pet standing on a campfire was there, then gone while people stood on the
		// fire (drawn round it, so nothing was seen to cover it), then back as they left.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE + 1, TILE + 1);
		Body b = g.player(2, TILE - 1, TILE + 1);
		Body pet = g.npc(7, TILE, TILE, true);
		g.run(pet, 20);
		String visible = g.run(pet, 50);
		g.crowd(TILE, TILE, a, b);
		String crowded = g.run(pet, 150);
		g.noCrowd();
		a.x += 128;
		b.x -= 128;
		String left = g.run(pet, 50);
		Assert.assertEquals(times("1", 50) + " " + times("1", 150) + " " + times("1", 50), visible + " " + crowded + " " + left);
	}

	@Test
	public void aPetDoesNotFlashInWhileTheStackProbeHoldsTheCrowdBack()
	{
		// The stack probe holds the crowd back for a frame or three to let one it hasn't seen drawn
		// into the middle. Here that one is hidden by another plugin, so for those frames nobody took
		// the middle and the game let the pet in: it flashed in for three frames, and out again.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body hidden = g.player(1, TILE, TILE);
		Body shown = g.player(2, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.callbacks.unregister(g.probe);
		g.hide(r -> r == hidden.actor);
		g.callbacks.register(g.probe);
		g.crowd(TILE, TILE, hidden, shown);
		Assert.assertEquals(times("1", 200), g.run(pet, 200));
		Assert.assertEquals("the one hidden stays hidden", times("0", 20), g.run(hidden, 20));
	}

	@Test
	public void aPetTheGameHandsOverItselfIsDrawnOnce()
	{
		// With everyone in the middle turned down (here by another plugin; the stack probe does it
		// too, a frame at a time), the game lets the pet in after them: drawn as it comes, once.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, a, b);
		g.hide(r -> r == a.actor || r == b.actor);
		g.run(pet, 50);
		Assert.assertEquals(times("1", 100), g.run(pet, 100));
		Assert.assertEquals("those hidden stay hidden", times("0", 20), g.run(a, 20));
	}

	@Test
	public void aPetAnotherPluginHidesStaysHidden()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, a, b);
		g.hide(r -> r == pet.actor);
		Assert.assertEquals(times("0", 150), g.run(pet, 150));
	}

	@Test
	public void steppingOntoAPetWalksItOutBesideYou()
	{
		// The game shows you in the middle of the tile and leaves the pet under you: it vanished the
		// moment you stood on it. Now it walks out from under you to your side, the way its owner is.
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body owner = g.player(1, TILE - 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 512;
		g.run(pet, 20);
		List<int[]> trace = stepOntoPet(g, pet);
		Assert.assertEquals("drawn every frame", times("1", trace.size()), seen(trace));
		Assert.assertTrue("never jumps: " + largestStep(trace), largestStep(trace) <= STRIDE);
		int[] spot = last(trace);
		double out = apart(spot, MIDDLE, MIDDLE);
		Assert.assertTrue("just beside you: " + out, out >= CrowdPlanner.OBJECT_YOUR_GAP && out <= 70);
		Assert.assertTrue("on its owner's side of you", spot[0] < MIDDLE - 50);
		Assert.assertTrue("walked there with its own walk", walkingFrames(trace) >= 10);
		Assert.assertEquals("standing once there, facing its own way", 0, spot[3]);
		Assert.assertEquals(512, spot[2]);
		for (int[] at : trace.subList(trace.size() - 100, trace.size()))
		{
			Assert.assertArrayEquals("stays put while you do", spot, at);
		}
		Assert.assertEquals("you're drawn all along", times("1", 20), g.run(g.you, 20));
	}

	@Test
	public void yourPetStaysBesideYouWhileYouStandNextToACrowd()
	{
		// The video, at the Grand Exchange, with the plugin's own ticks: you stop a tile from a crowd,
		// and your pet, following, walks onto your tile. It stepped out beside you, then a moment
		// later was gone under you until you walked off. Once you had stood there two ticks running,
		// the planner counted the middle of your tile out of sight (nobody waiting there is drawn
		// inside you), and a pet under a middle out of sight was kept under it, though the one shown
		// there was you.
		Game g = new Game();
		g.ticking = true;
		g.you = g.player(0, TILE + 2, TILE);
		Body[] crowd = new Body[4];
		for (int i = 0; i < crowd.length; i++)
		{
			crowd[i] = g.player(1 + i, TILE - 1, TILE + 1);
		}
		Body pet = g.npc(7, TILE + 3, TILE, true);
		pet.interacting = g.you.actor;
		pet.orientation = 512;
		g.run(pet, 120);
		Assert.assertTrue("the crowd is spread out", g.offsets.isOffset(crowd[0].id));
		List<int[]> walk = g.trace(pet, 100, () ->
		{
			g.stepTowards(g.you, MIDDLE, MIDDLE);
			g.stepTowards(pet, MIDDLE, MIDDLE);
		});
		List<int[]> stand = g.trace(pet, 300);
		List<int[]> on = joined(walk, stand);
		Assert.assertEquals("drawn every frame", times("1", on.size()), seen(on));
		Assert.assertTrue("never jumps: " + largestStep(on), largestStep(on) <= STRIDE);
		int[] spot = last(stand);
		double out = apart(spot, MIDDLE, MIDDLE);
		Assert.assertTrue("just beside you: " + out, out >= CrowdPlanner.OBJECT_YOUR_GAP && out <= 70);
		for (int[] at : stand.subList(stand.size() - 200, stand.size()))
		{
			Assert.assertArrayEquals("stays put while you do", new int[]{spot[0], spot[1]}, new int[]{at[0], at[1]});
		}
		for (Body p : crowd)
		{
			int[] them = g.at(p);
			Assert.assertTrue("clear of the crowd", them != null && apart(spot, them) >= SpreadingDrawCallbacks.PET_KEEP);
		}
		Assert.assertEquals("you're drawn all along", times("1", 20), g.run(g.you, 20));
		// You walk off east, and it follows a moment later, as a follower does.
		List<int[]> off = g.trace(pet, 200, () ->
		{
			g.stepTowards(g.you, MIDDLE + 256, MIDDLE);
			if (g.you.x >= MIDDLE + 32)
			{
				pet.orientation = 1536;
				g.stepTowards(pet, MIDDLE + 128, MIDDLE);
			}
		});
		List<int[]> all = joined(on, off);
		Assert.assertEquals("drawn every frame", times("1", all.size()), seen(all));
		Assert.assertTrue("never jumps: " + largestStep(all), largestStep(all) <= STRIDE + 2);
		Assert.assertArrayEquals("where it really is in the end", new int[]{MIDDLE + 128, MIDDLE}, new int[]{last(off)[0], last(off)[1]});
	}

	@Test
	public void aPetStaysBesideYouOnceYouCountAsStandingStill()
	{
		// With the plugin's own ticks, and nobody else about: the middle of the tile you stand in
		// counts as out of sight for anyone waiting there, so the pet went back under you as soon
		// as you had stood on it two ticks running.
		Game g = new Game();
		g.ticking = true;
		g.you = g.player(0, TILE + 2, TILE);
		Body owner = g.player(1, TILE - 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 512;
		g.run(pet, 20);
		List<int[]> on = joined(stepOntoPet(g, pet), g.trace(pet, 300));
		Assert.assertEquals("drawn every frame", times("1", on.size()), seen(on));
		Assert.assertTrue("never jumps: " + largestStep(on), largestStep(on) <= STRIDE);
		int[] spot = last(on);
		Assert.assertTrue("on its owner's side of you", spot[0] < MIDDLE - 50);
		for (int[] at : on.subList(on.size() - 300, on.size()))
		{
			Assert.assertArrayEquals("stays put while you do", spot, at);
		}
	}

	@Test
	public void steppingOffAPetHandsItBackToTheGameWithoutAJump()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body owner = g.player(1, TILE - 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 512;
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		// You walk off east. The game hands the pet over again the moment you leave the middle of
		// its tile, where it stands: it walks back there from your side rather than jumping.
		List<int[]> off = g.trace(pet, 100, () -> g.stepTowards(g.you, MIDDLE + 256, MIDDLE));
		List<int[]> all = joined(on, off);
		Assert.assertEquals("drawn every frame", times("1", all.size()), seen(all));
		Assert.assertTrue("never jumps: " + largestStep(all), largestStep(all) <= STRIDE);
		Assert.assertTrue("walked back with its own walk", walkingFrames(off) >= 10);
		int[] home = last(off);
		Assert.assertArrayEquals("back where it stands, facing its own way, as the game draws it", new int[]{MIDDLE, MIDDLE, 512, 0}, home);
		Assert.assertEquals("and left to the game", times("1", 20), g.run(pet, 20));
	}

	@Test
	public void yourOwnPetStepsOutTheWayItFacesAndWalksOffWithoutAJump()
	{
		// With its owner the one standing on it, it steps out ahead of itself; then it walks off for
		// real a tile north, and what's left of its step is eased away as it goes.
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = g.you.actor;
		pet.orientation = 1024;
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		int[] spot = last(on);
		Assert.assertTrue("ahead of it, north", spot[1] > MIDDLE + 50 && Math.abs(spot[0] - MIDDLE) < 10);
		List<int[]> off = g.trace(pet, 120, () -> g.stepTowards(pet, MIDDLE, MIDDLE + 128));
		List<int[]> all = joined(on, off);
		Assert.assertEquals("drawn every frame", times("1", all.size()), seen(all));
		Assert.assertTrue("never jumps: " + largestStep(all), largestStep(all) <= STRIDE + 2);
		Assert.assertArrayEquals("where it really is in the end", new int[]{MIDDLE, MIDDLE + 128, 1024, 0}, last(off));
	}

	@Test
	public void aPetUnderSomeoneStandingOnItAloneIsDrawnBesideThem()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 5, TILE);
		Body stander = g.player(1, TILE, TILE);
		Body owner = g.player(2, TILE, TILE + 3);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		g.run(pet, 50);
		List<int[]> trace = g.trace(pet, 200);
		Assert.assertEquals(times("1", 200), seen(trace));
		int[] spot = last(trace);
		Assert.assertTrue("beside them, their owner's way", spot[1] > MIDDLE + 50 && apart(spot, MIDDLE, MIDDLE) <= 70);
		Assert.assertEquals("the one on it is drawn all along", times("1", 40), g.run(stander, 40));
	}

	@Test
	public void someoneWalkingAcrossAPetDoesNotMakeItBlink()
	{
		// Passing through the middle of its tile, they're the one the game puts there for a frame.
		Game g = new Game();
		g.you = g.player(0, TILE + 5, TILE);
		Body walker = g.player(1, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.run(pet, 20);
		List<int[]> trace = g.trace(pet, 130, () -> g.stepTowards(walker, MIDDLE - 256, MIDDLE));
		Assert.assertEquals(times("1", 130), seen(trace));
		for (int[] at : trace)
		{
			Assert.assertArrayEquals("where it stands", new int[]{MIDDLE, MIDDLE}, new int[]{at[0], at[1]});
		}
	}

	@Test
	public void aPetOnYourCrowdedTileIsDrawnBesideYou()
	{
		// You're added first and always drawn where you stand: the middle is yours, as in the game,
		// and the pet steps out from under you, clear of the crowd round you.
		Game g = new Game();
		g.you = g.player(0, TILE, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, a, b);
		g.run(pet, 50);
		List<int[]> trace = g.trace(pet, 150);
		Assert.assertEquals(times("1", 150), seen(trace));
		Assert.assertTrue(apart(last(trace), MIDDLE, MIDDLE) >= CrowdPlanner.OBJECT_YOUR_GAP);
		Assert.assertEquals(times("1", 20), g.run(g.you, 20));
	}

	@Test
	public void aPetUnderSomeoneWaitingInTheMiddleIsDrawnBesideThemClearOfTheCrowd()
	{
		// Someone waiting in the middle without a spot is the one shown there (see middleOf). Its
		// owner is east, where one of the crowd stands: it takes the nearest side clear of them.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE + 3);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body waiting = g.player(3, TILE, TILE);
		Body owner = g.player(4, TILE + 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		g.crowd(TILE, TILE, waiting, true, a, b);
		g.run(pet, 100);
		List<int[]> trace = g.trace(pet, 200);
		Assert.assertEquals(times("1", 200), seen(trace));
		int[] spot = last(trace);
		Assert.assertTrue("on its owner's side", spot[0] > MIDDLE + 30);
		Assert.assertTrue("clear of the one beside it there", apart(spot, MIDDLE + 96, MIDDLE) >= SpreadingDrawCallbacks.PET_GAP);
		Assert.assertEquals("the one waiting is drawn all along", times("1", 50), g.run(waiting, 50));
		Assert.assertEquals(times("1", 50), g.run(a, 50));
	}

	@Test
	public void aPetUnderTheOneWhoseSpotIsTheMiddleIsDrawnBesideThem()
	{
		// A crowd of seven or more is given the middle as a spot too: whoever has it stands over the pet.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body middle = g.player(3, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, middle, false, a, b);
		g.run(pet, 100);
		List<int[]> trace = g.trace(pet, 200);
		Assert.assertEquals(times("1", 200), seen(trace));
		Assert.assertTrue(apart(last(trace), MIDDLE, MIDDLE) >= SpreadingDrawCallbacks.PET_GAP);
		Assert.assertEquals("the one in the middle is drawn all along", times("1", 50), g.run(middle, 50));
	}

	@Test
	public void aPetUnderAMiddleOutOfSightStaysUnderIt()
	{
		// A middle between you and the camera: whoever waits there isn't drawn, and nor is the pet beside them.
		Game g = new Game();
		g.you = g.player(0, TILE, TILE + 2);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body waiting = g.player(3, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, waiting, true, a, b);
		long key = StackRegistry.key(0, TILE, TILE);
		List<StackSpreader.Placement> members = new ArrayList<>();
		members.add(new StackSpreader.Placement(waiting.id, key, 0, 0));
		members.add(new StackSpreader.Placement(a.id, key, 96, 0));
		members.add(new StackSpreader.Placement(b.id, key, -96, 0));
		g.stacks.rebuild(members, new HashSet<>(), Set.of(waiting.id), new HashMap<>(), new HashMap<>(), Set.of(key));
		g.run(pet, 100);
		Assert.assertEquals(times("0", 100), g.run(pet, 100));
	}

	@Test
	public void aPetUnderTheOneWhoseSpotIsAMiddleOutOfSightIsDrawnBesideThem()
	{
		// Only those waiting in a middle out of sight are left undrawn there: whoever has it for a
		// spot is drawn in it all the same, and so is the pet beside them.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body middle = g.player(3, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, middle, false, a, b);
		long key = StackRegistry.key(0, TILE, TILE);
		List<StackSpreader.Placement> members = new ArrayList<>();
		members.add(new StackSpreader.Placement(middle.id, key, 0, 0));
		members.add(new StackSpreader.Placement(a.id, key, 96, 0));
		members.add(new StackSpreader.Placement(b.id, key, -96, 0));
		g.stacks.rebuild(members, new HashSet<>(), new HashSet<>(), new HashMap<>(), new HashMap<>(), Set.of(key));
		g.run(pet, 100);
		List<int[]> trace = g.trace(pet, 200);
		Assert.assertEquals(times("1", 200), seen(trace));
		Assert.assertTrue(apart(last(trace), MIDDLE, MIDDLE) >= SpreadingDrawCallbacks.PET_GAP);
		Assert.assertEquals("the one in the middle is drawn all along", times("1", 50), g.run(middle, 50));
	}

	@Test
	public void aWallOnOneSideKeepsThePetOffIt()
	{
		// Its owner is east, through a wall along that side of the tile: it stands clear of the wall.
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE + 3);
		Body stander = g.player(1, TILE, TILE);
		Body owner = g.player(2, TILE + 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		g.flags[TILE][TILE] |= CollisionTerrain.BLOCK_EAST;
		g.flags[TILE + 1][TILE] |= CollisionTerrain.BLOCK_WEST;
		g.run(pet, 50);
		List<int[]> trace = g.trace(pet, 200);
		Assert.assertEquals(times("1", 200), seen(trace));
		for (int[] at : trace)
		{
			Assert.assertTrue("never at the wall: " + at[0], at[0] - TILE * 128 <= 127 - CollisionTerrain.EDGE_MARGIN);
		}
		int[] spot = last(trace);
		Assert.assertTrue("still beside them", apart(spot, MIDDLE, MIDDLE) >= SpreadingDrawCallbacks.PET_GAP);
		Assert.assertTrue("as near its owner's side as the wall lets it", spot[0] > MIDDLE);
		Assert.assertTrue(new CollisionTerrain(new int[][][]{g.flags}).canStand(0, MIDDLE, MIDDLE, spot[0], spot[1]));
	}

	@Test
	public void aPetUnderSomeoneAloneOnACampfireStaysUnderThem()
	{
		// A campfire with three things on it, and a fifth now and then as logs go on: the one the
		// game shows there is the player standing on it, as without the plugin, and there's no
		// room beside them out of the flames, so the pet stays under them.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body tender = g.player(1, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.fires.add(StackRegistry.key(0, TILE, TILE));
		StringBuilder seen = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			g.scenery[TILE][TILE] = i % 40 < 30 ? 3 : 5;
			seen.append(g.run(pet, 1));
		}
		Assert.assertEquals(times("0", 200), seen.toString());
		Assert.assertEquals("the tender is drawn all along", times("1", 40), g.run(tender, 40));
	}

	@Test
	public void aPetAloneOnAFullCampfireIsDrawnEveryFrame()
	{
		// A campfire with four things on it, and a fifth now and then as another log goes on: the
		// game had no room for a pet standing in it then, so it blinked whenever a log went on.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		StringBuilder seen = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			g.scenery[TILE][TILE] = i % 40 < 30 ? 4 : 5;
			seen.append(g.run(pet, 1));
		}
		Assert.assertEquals(times("1", 200), seen.toString());
	}

	@Test
	public void twoPetsUnderYouTakeASpotEach()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body owner = g.player(1, TILE - 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		Body other = g.npc(8, TILE, TILE, true);
		pet.interacting = owner.actor;
		other.interacting = owner.actor;
		g.run(pet, 20);
		List<int[]> mine = new ArrayList<>();
		List<int[]> theirs = new ArrayList<>();
		for (int i = 0; i < 220; i++)
		{
			g.stepTowards(g.you, MIDDLE, MIDDLE);
			g.frame();
			mine.add(g.at(pet));
			theirs.add(g.at(other));
		}
		List<int[]> settled = mine.subList(120, 220);
		Assert.assertEquals(times("1", 100), seen(settled));
		Assert.assertEquals(times("1", 100), seen(theirs.subList(120, 220)));
		int[] a = last(mine);
		int[] b = last(theirs);
		Assert.assertTrue("not inside each other: " + apart(a, b), apart(a, b) >= SpreadingDrawCallbacks.PET_GAP);
		Assert.assertTrue(apart(a, MIDDLE, MIDDLE) >= CrowdPlanner.OBJECT_YOUR_GAP);
		Assert.assertTrue(apart(b, MIDDLE, MIDDLE) >= CrowdPlanner.OBJECT_YOUR_GAP);
	}

	@Test
	public void nobodyIsDrawnInsideAPetWhenTheCrowdMovesOntoItsSpot()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE + 3);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body waiting = g.player(3, TILE, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, waiting, true, a, b);
		g.run(pet, 150);
		int[] spot = g.at(pet);
		Assert.assertNotNull(spot);
		// The crowd is laid out afresh, and one of them is given a spot where the pet stands.
		g.offsets.setTarget(a.id, spot[0] - MIDDLE, spot[1] - MIDDLE);
		List<int[]> trace = new ArrayList<>();
		for (int i = 0; i < 150; i++)
		{
			g.frame();
			int[] at = g.at(pet);
			trace.add(at);
			if (i >= 60 && at != null)
			{
				for (Body p : g.players)
				{
					int[] them = g.at(p);
					Assert.assertTrue("nobody inside it", them == null || apart(at, them) >= SpreadingDrawCallbacks.PET_KEEP);
				}
			}
		}
		Assert.assertEquals("drawn every frame", times("1", 150), seen(trace));
		Assert.assertTrue("walked over, never jumped: " + largestStep(trace), largestStep(trace) <= STRIDE);
		Assert.assertTrue("somewhere else now", apart(last(trace), spot) >= SpreadingDrawCallbacks.PET_GAP);
	}

	@Test
	public void anotherPluginHidingAPetBesideYouHidesItAtOnce()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		boolean[] hiding = {false};
		g.hide(r -> hiding[0] && r == pet.actor);
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		Assert.assertEquals(times("1", on.size()), seen(on));
		hiding[0] = true;
		Assert.assertEquals("hidden while the other plugin hides it", times("0", 50), g.run(pet, 50));
		List<int[]> off = g.trace(pet, 80, () -> g.stepTowards(g.you, MIDDLE + 256, MIDDLE));
		Assert.assertEquals("and as you step off", times("0", 80), seen(off));
		hiding[0] = false;
		Assert.assertEquals("back where it stands", times("1", 20), g.run(pet, 20));
		Assert.assertArrayEquals(new int[]{MIDDLE, MIDDLE}, new int[]{g.at(pet)[0], g.at(pet)[1]});
	}

	@Test
	public void withTheEffectOffAPetStaysUnderYouAsInTheGame()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		Assert.assertEquals(times("1", on.size()), seen(on));
		// Switched off (or into a PvP area): at once, as everyone else goes back to where they are.
		g.effectOff();
		Assert.assertEquals(times("0", 50), g.run(pet, 50));
		List<int[]> off = g.trace(pet, 80, () -> g.stepTowards(g.you, MIDDLE + 256, MIDDLE));
		for (int[] at : off)
		{
			Assert.assertTrue("only ever where it stands", at == null || at[0] == MIDDLE && at[1] == MIDDLE);
		}
		Assert.assertEquals("left to the game once you step off", times("1", 40), seen(off.subList(40, 80)));
		List<int[]> back = g.trace(pet, 100, () -> g.stepTowards(g.you, MIDDLE, MIDDLE));
		Assert.assertEquals("and with it off, under you again", times("0", 30), seen(back.subList(70, 100)));
		g.effectOn();
		List<int[]> on2 = g.trace(pet, 60);
		Assert.assertEquals("beside you again once it's on", times("1", 60), seen(on2));
	}

	@Test
	public void aPetWithNoRoomLeftRoundThemWalksBackUnderThem()
	{
		// The crowd is laid out afresh in a ring close all round the one waiting in the middle, with
		// no room left beside them: the pet walks back in under them, rather than vanish where it stood.
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE + 3);
		Body waiting = g.player(3, TILE, TILE);
		Body[] ring = new Body[8];
		for (int i = 0; i < ring.length; i++)
		{
			ring[i] = g.player(10 + i, i < 2 ? TILE : TILE - 6, TILE);
		}
		Body pet = g.npc(7, TILE, TILE, true);
		g.crowd(TILE, TILE, waiting, true, ring[0], ring[1]);
		g.run(pet, 150);
		int[] spot = g.at(pet);
		Assert.assertTrue("beside them to start with", spot != null && apart(spot, MIDDLE, MIDDLE) >= SpreadingDrawCallbacks.PET_GAP);
		long key = StackRegistry.key(0, TILE, TILE);
		List<StackSpreader.Placement> members = new ArrayList<>();
		members.add(new StackSpreader.Placement(waiting.id, key, 0, 0));
		for (int i = 0; i < ring.length; i++)
		{
			int dx = (int) Math.round(64 * Math.cos(i * Math.PI / 4));
			int dz = (int) Math.round(64 * Math.sin(i * Math.PI / 4));
			ring[i].x = MIDDLE;
			ring[i].z = MIDDLE;
			members.add(new StackSpreader.Placement(ring[i].id, key, dx, dz));
			g.offsets.setTarget(ring[i].id, dx, dz);
		}
		g.stacks.rebuild(members, new HashSet<>(), Set.of(waiting.id));
		List<int[]> trace = g.trace(pet, 100);
		String seen = seen(trace);
		int back = seen.indexOf('0');
		Assert.assertTrue("walked back for a while first: " + seen, back >= 8);
		Assert.assertEquals("then hidden under them for good", times("1", back) + times("0", 100 - back), seen);
		List<int[]> walk = joined(List.of(spot), trace.subList(0, back));
		Assert.assertTrue("never jumps: " + largestStep(walk), largestStep(walk) <= STRIDE);
		Assert.assertTrue("with its own walk", walkingFrames(walk) >= back / 2);
		Assert.assertTrue("gone only once under them", apart(last(walk), MIDDLE, MIDDLE) <= STRIDE);
		Assert.assertEquals("the one waiting is drawn all along", times("1", 20), g.run(waiting, 20));
	}

	@Test
	public void aPetWithNoRoomRoundThemIsNotLookedForEveryFrame()
	{
		// Every way round you blocked: it stays under you, as in the game, and its spot is looked for
		// again now and then rather than every frame. Once there's room, it steps out.
		Game g = new Game();
		int[] looks = {0};
		boolean[] room = {false};
		g.shim.ground = FakeMeshes.fake(CrowdPlanner.Surroundings.class, Map.of("canStand", (Function<Object[], Object>) args ->
		{
			looks[0]++;
			return room[0] && g.ground.canStand((Long) args[0], (Integer) args[1], (Integer) args[2]);
		}));
		g.you = g.player(0, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		Assert.assertEquals("under you", times("0", 100), seen(on.subList(on.size() - 100, on.size())));
		looks[0] = 0;
		g.run(pet, 100);
		int most = SpreadingDrawCallbacks.PET_DIRECTIONS * 2 * (100 / SpreadingDrawCallbacks.PET_RETRY_FRAMES + 1);
		Assert.assertTrue("looked for now and then: " + looks[0], looks[0] <= most);
		room[0] = true;
		List<int[]> later = g.trace(pet, SpreadingDrawCallbacks.PET_RETRY_FRAMES + 60);
		Assert.assertTrue("beside you once there's room", last(later) != null && apart(last(later), MIDDLE, MIDDLE) >= CrowdPlanner.OBJECT_YOUR_GAP);
	}

	@Test
	public void aPetSteppingBackIntoTheMiddleFacesTheWayItWalks()
	{
		// As you step off, it turns round and walks back in, rather than walk backwards most of the way.
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body owner = g.player(1, TILE - 3, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		pet.interacting = owner.actor;
		pet.orientation = 512;
		g.run(pet, 20);
		List<int[]> on = stepOntoPet(g, pet);
		List<int[]> all = joined(on, g.trace(pet, 100, () -> g.stepTowards(g.you, MIDDLE + 256, MIDDLE)));
		int backwards = 0;
		for (int i = 1; i < all.size(); i++)
		{
			int[] a = all.get(i - 1);
			int[] b = all.get(i);
			if (b[3] == 1 && (b[0] != a[0] || b[1] != a[1]))
			{
				int way = OffsetTable.facing(b[0] - a[0], b[1] - a[1]);
				int turned = Math.abs(((way - b[2]) % 2048 + 3072) % 2048 - 1024);
				backwards += turned > 512 ? 1 : 0;
			}
		}
		Assert.assertTrue("walked backwards for " + backwards + " frames", backwards <= 3);
	}

	@Test
	public void anNpcGivenAGonePetsIndexIsDrawnWhereItStands()
	{
		// The pet beside you is picked up, and the game gives its index to someone else close by.
		Game g = new Game();
		g.you = g.player(0, TILE + 2, TILE);
		Body pet = g.npc(7, TILE, TILE, true);
		g.run(pet, 20);
		stepOntoPet(g, pet);
		Assert.assertNotNull(g.at(pet));
		g.npcs.remove(pet);
		Body banker = g.npc(7, TILE, TILE + 1, false);
		for (int[] at : g.trace(banker, 30))
		{
			Assert.assertNotNull(at);
			Assert.assertArrayEquals("where it stands", new int[]{MIDDLE, MIDDLE + 128}, new int[]{at[0], at[1]});
		}
	}

	@Test
	public void anNpcThatIsNotAFollowerIsLeftAsTheGameHasIt()
	{
		Game g = new Game();
		g.you = g.player(0, TILE + 3, TILE);
		Body a = g.player(1, TILE, TILE);
		Body b = g.player(2, TILE, TILE);
		Body banker = g.npc(7, TILE, TILE, false);
		g.crowd(TILE, TILE, a, b);
		Assert.assertEquals(times("0", 150), g.run(banker, 150));
	}
}
