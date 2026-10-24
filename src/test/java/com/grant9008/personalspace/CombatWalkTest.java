package com.grant9008.personalspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * People busy on the move, frame after frame, as the shim draws them. An
 * alcher walking is drawn walking with the cast left out; but anyone fighting is drawn as the game
 * draws them, attack and all (woox walking at Vorkath, each attack has to be seen to fire as you
 * step), and so is everyone while the effect is off, paused while you fight or in a PvP area. Your
 * own action is never left out as you really walk. Each body's model is built from whatever action
 * and pose it has at the time, as the client's is, so the model the renderer is handed says what
 * was drawn. The game adds people to the scene by its own rules, as in FollowerOnCrowdedTileTest:
 * one in the middle of a tile a frame, the render callbacks asked first.
 */
public class CombatWalkTest
{
	private static final int TILE = 50;
	private static final int MIDDLE = TILE * 128 + 64;
	private static final int IDLE = 808;
	private static final int WALK = 819;
	private static final int RUN = 824;
	/** A crossbow shot: what you see fire, woox walking at Vorkath. */
	private static final int ATTACK = AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD_PVN;
	private static final int ALCH = AnimationID.HUMAN_CASTHIGHLVLALCHEMY;
	private static final int EAT = AnimationID.HUMAN_EAT;
	/** The game's walking pace, a frame at a time: a tile every 32 frames. */
	private static final int PACE = 4;
	/** The camera, 1500 south of the tile, as the shim's sort and boxes see it. */
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
		Player actor;
		GameObject object;
		int pose = IDLE;
		int poseFrame;
		int animation = -1;
		int animationFrame;
		Actor interacting;
		/** -1 while no health bar shows over them. */
		int healthRatio = -1;

		Body(int id, int x, int z)
		{
			this.id = id;
			this.x = x;
			this.z = z;
		}

		/** A step east at walking pace, walking for real. */
		void step()
		{
			pose = WALK;
			x += PACE;
		}
	}

	private static final class Game
	{
		final List<Body> players = new ArrayList<>();
		Body you;
		int cycle;
		final int[][] lastDrawnActor = new int[104][104];
		final StackRegistry stacks = new StackRegistry();
		final OffsetTable offsets = new OffsetTable();
		final RenderCallbackManager callbacks = new RenderCallbackManager();
		final WorldView wv;
		final Scene scene;
		final StackProbe probe;
		final SpreadingDrawCallbacks shim;
		/** Vorkath before the first shot lands: a combat level, but no health bar showing yet. */
		final NPC vorkath = FakeMeshes.fake(NPC.class, Map.of("getIndex", 7, "getName", "Vorkath", "getCombatLevel", 732, "getHealthRatio", -1));
		/** A fishing spot: an NPC fishers face, with no combat level and never a health bar. */
		final NPC fishingSpot = FakeMeshes.fake(NPC.class, Map.of("getIndex", 8, "getName", "Fishing spot", "getCombatLevel", 0, "getHealthRatio", -1));
		/** Each model built this frame, and whose it was and what with: {id, action, pose}. */
		final Map<Model, int[]> built = new IdentityHashMap<>();
		/** What the game handed over this frame, and what the renderer was handed: {model, x, z}; and which of the latter came in the opaque pass. */
		final List<Object[]> handed = new ArrayList<>();
		final List<Object[]> drawn = new ArrayList<>();
		final List<Model> inPass = new ArrayList<>();
		private boolean passing;

		Game()
		{
			scene = FakeMeshes.fake(Scene.class, Map.of("getTiles", new Tile[4][104][104]));
			Map<String, Object> w = new HashMap<>();
			w.put("getScene", scene);
			w.put("getSizeX", 104);
			w.put("getSizeY", 104);
			w.put("getTileHeights", new int[4][105][105]);
			w.put("getTileSettings", new byte[4][104][104]);
			w.put("players", set(players));
			w.put("npcs", set(new ArrayList<>()));
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
			Client client = FakeMeshes.fake(Client.class, c);
			probe = new StackProbe(client, offsets, stacks);
			probe.enabled = true;
			callbacks.register(probe);
			Map<String, Object> d = new HashMap<>();
			d.put("drawTemp", (Function<Object[], Object>) args ->
			{
				drawn.add(new Object[]{args[3], args[5], args[7]});
				if (passing)
				{
					inPass.add((Model) args[3]);
				}
				return null;
			});
			shim = new SpreadingDrawCallbacks(client, offsets, stacks, probe, callbacks, FakeMeshes.fake(DrawCallbacks.class, d));
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

		Body player(int id, int tileX, int tileZ)
		{
			Body b = new Body(id, tileX * 128 + 64, tileZ * 128 + 64);
			Map<String, Object> a = new HashMap<>();
			a.put("getId", id);
			a.put("getName", "Player " + id);
			a.put("getLocalLocation", (Function<Object[], Object>) args -> new LocalPoint(b.x, b.z, 0));
			a.put("getWorldLocation", new WorldPoint(3200, 3200, 0));
			a.put("getWorldView", wv);
			a.put("getModel", (Function<Object[], Object>) args ->
			{
				Model m = FakeMeshes.model(FakeMeshes.strip(4), 1000 + b.id);
				built.put(m, new int[]{b.id, b.animation, b.pose});
				return m;
			});
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
			a.put("getInteracting", (Function<Object[], Object>) args -> b.interacting);
			a.put("getHealthRatio", (Function<Object[], Object>) args -> b.healthRatio);
			a.put("getIdlePoseAnimation", IDLE);
			a.put("getWalkAnimation", WALK);
			a.put("getRunAnimation", RUN);
			b.actor = FakeMeshes.fake(Player.class, a);
			Map<String, Object> o = new HashMap<>();
			o.put("getRenderable", b.actor);
			o.put("getX", (Function<Object[], Object>) args -> b.x);
			o.put("getY", (Function<Object[], Object>) args -> b.z);
			b.object = FakeMeshes.fake(GameObject.class, o);
			players.add(b);
			return b;
		}

		Body you(int tileX, int tileZ)
		{
			you = player(0, tileX, tileZ);
			return you;
		}

		/** These players stand in the middle of the tile, given spots round it as the planner would have them. */
		void crowd(int tileX, int tileZ, Body... people)
		{
			List<StackSpreader.Placement> placements = new ArrayList<>();
			long key = StackRegistry.key(0, tileX, tileZ);
			for (int i = 0; i < people.length; i++)
			{
				Body p = people[i];
				p.x = tileX * 128 + 64;
				p.z = tileZ * 128 + 64;
				int dx = i % 2 == 0 ? 96 : -96;
				int dz = i / 2 * 64;
				placements.add(new StackSpreader.Placement(p.id, key, dx, dz));
				offsets.setTarget(p.id, dx, dz);
			}
			stacks.rebuild(placements, new HashSet<>(), new HashSet<>());
		}

		/**
		 * The effect switched off, paused while you fight, or a PvP area: as the plugin's reset,
		 * everyone straight back where they stand and nothing known, and the shim told so each frame.
		 */
		void effectOff(boolean youFighting)
		{
			probe.enabled = false;
			offsets.snapAllToZero();
			stacks.clear();
			shim.ground = null;
			shim.spreading = false;
			shim.youFighting = youFighting;
		}

		/** One frame: who the game adds to the scene, handed to the shim, then the renderer's opaque pass. */
		void frame()
		{
			cycle++;
			offsets.advance(0.02f);
			built.clear();
			handed.clear();
			drawn.clear();
			inPass.clear();
			List<Body> added = new ArrayList<>();
			add(you, added);
			for (Body p : players)
			{
				if (p != you)
				{
					add(p, added);
				}
			}
			for (Body b : added)
			{
				Model model = b.actor.getModel();
				handed.add(new Object[]{model, b.x, b.z});
				shim.drawTemp(CAMERA, scene, b.object, model, 0, b.x, 0, b.z);
			}
			passing = true;
			shim.drawPass(CAMERA, scene, DrawCallbacks.PASS_OPAQUE);
			passing = false;
		}

		/** The game adding someone to the scene: one actor in the middle of a tile a frame, the render callbacks asked first. */
		private void add(Body b, List<Body> added)
		{
			if (b == null)
			{
				return;
			}
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
			added.add(b);
		}

		/** What {@code who} was drawn with in the last frame, as {action, pose}; null if they weren't drawn. Fails if drawn twice. */
		int[] drawnAs(Body who)
		{
			int[] found = null;
			for (Object[] d : drawn)
			{
				int[] from = built.get(d[0]);
				if (from != null && from[0] == who.id)
				{
					Assert.assertNull("drawn twice", found);
					found = new int[]{from[1], from[2]};
				}
			}
			return found;
		}

		/** The action {@code who} was drawn with in the last frame, -1 for none; fails unless they were drawn. */
		int actionDrawn(Body who)
		{
			int[] as = drawnAs(who);
			Assert.assertNotNull("drawn", as);
			return as[0];
		}

		/** Whether {@code who} was drawn in the last frame's opaque pass, with the crowd, rather than as handed over. */
		boolean drawnInPass(Body who)
		{
			for (Model m : inPass)
			{
				int[] from = built.get(m);
				if (from != null && from[0] == who.id)
				{
					return true;
				}
			}
			return false;
		}

		/** Whether the renderer was handed exactly what the game handed over, where it was handed over: drawn as the game draws it. */
		boolean drawnAsTheGameDoes()
		{
			if (drawn.size() != handed.size())
			{
				return false;
			}
			for (int i = 0; i < drawn.size(); i++)
			{
				Object[] d = drawn.get(i);
				Object[] h = handed.get(i);
				if (d[0] != h[0] || !d[1].equals(h[1]) || !d[2].equals(h[2]))
				{
					return false;
				}
			}
			return true;
		}
	}

	// ---- fighting on the move: the game's own model, attack and all --------------------------

	@Test
	public void youWalkingWhileAttackingSeeEveryAttackFire()
	{
		// The report: "not playing attack animations while walking, hard to woox walk at Vorkath".
		// Alone there, and walking past a crowd, where you're drawn in order with it.
		for (boolean crowd : new boolean[]{false, true})
		{
			Game g = new Game();
			Body you = g.you(TILE, TILE);
			if (crowd)
			{
				g.crowd(TILE + 2, TILE, g.player(2, TILE + 2, TILE), g.player(3, TILE + 2, TILE));
			}
			g.frame();
			you.x += 40;
			you.animation = ATTACK;
			you.interacting = g.vorkath;
			for (int i = 0; i < 30; i++)
			{
				you.step();
				g.frame();
				Assert.assertEquals("frame " + i + (crowd ? " past a crowd" : ""), ATTACK, g.actionDrawn(you));
				Assert.assertEquals(crowd, g.drawnInPass(you));
			}
		}
	}

	@Test
	public void someoneElseWalkingWhileAttackingIsSeenAttacking()
	{
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body ranger = g.player(2, TILE, TILE);
		ranger.x += 40;
		ranger.animation = ATTACK;
		ranger.interacting = g.vorkath;
		for (int i = 0; i < 30; i++)
		{
			ranger.step();
			g.frame();
			Assert.assertEquals("frame " + i, ATTACK, g.actionDrawn(ranger));
		}
		Assert.assertEquals(0, g.shim.movingBusyWalks);
		Assert.assertTrue(g.shim.walkKeptFighting > 0);
	}

	@Test
	public void steppingAwayBetweenAttacksTheAttackStillShows()
	{
		// Woox walking: the shot fires facing the target, then a click on the ground. The game stops
		// having them face it as they step off, while the shot still plays.
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body woox = g.player(2, TILE, TILE);
		woox.animation = ATTACK;
		woox.interacting = g.vorkath;
		g.frame();
		Assert.assertEquals(ATTACK, g.actionDrawn(woox));
		woox.interacting = null;
		for (int i = 0; i < 40; i++)
		{
			woox.step();
			g.frame();
			Assert.assertEquals("frame " + i, ATTACK, g.actionDrawn(woox));
		}
	}

	@Test
	public void hitAMomentAgoCountsAsFighting()
	{
		// Eating on the run with a health bar up: fighting, whoever it's with.
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body hurt = g.player(2, TILE, TILE);
		hurt.x += 40;
		hurt.animation = EAT;
		hurt.healthRatio = 12;
		for (int i = 0; i < 20; i++)
		{
			hurt.step();
			g.frame();
			Assert.assertEquals("frame " + i, EAT, g.actionDrawn(hurt));
		}
	}

	@Test
	public void aMonsterWithNoLevelIsAFightOnceItsHealthBarShows()
	{
		// Some things fought have no combat level; once hit, their health bar shows.
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body ranger = g.player(2, TILE, TILE);
		ranger.x += 40;
		ranger.animation = ATTACK;
		ranger.interacting = FakeMeshes.fake(NPC.class, Map.of("getIndex", 9, "getName", "Portal", "getCombatLevel", 0, "getHealthRatio", 20));
		for (int i = 0; i < 20; i++)
		{
			ranger.step();
			g.frame();
			Assert.assertEquals("frame " + i, ATTACK, g.actionDrawn(ranger));
		}
	}

	@Test
	public void longAfterAFightAnAlcherOnTheRunWalksAgain()
	{
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body p = g.player(2, TILE, TILE);
		p.animation = ATTACK;
		p.interacting = g.vorkath;
		g.frame();
		p.interacting = null;
		p.animation = ALCH;
		g.cycle += SpreadingDrawCallbacks.FIGHT_HOLD_CYCLES;
		p.step();
		g.frame();
		Assert.assertArrayEquals("the cast left out, walking", new int[]{-1, WALK}, g.drawnAs(p));
	}

	// ---- skilling on the move: still drawn walking, but never you ----------------------------

	@Test
	public void anotherAlcherWalkingIsStillDrawnWalking()
	{
		for (boolean crowd : new boolean[]{false, true})
		{
			Game g = new Game();
			g.you(TILE - 4, TILE);
			if (crowd)
			{
				g.crowd(TILE + 2, TILE, g.player(3, TILE + 2, TILE), g.player(4, TILE + 2, TILE));
			}
			Body alcher = g.player(2, TILE, TILE);
			alcher.x += 40;
			alcher.animation = ALCH;
			g.frame();
			for (int i = 0; i < 20; i++)
			{
				alcher.step();
				g.frame();
				Assert.assertArrayEquals("frame " + i, new int[]{-1, WALK}, g.drawnAs(alcher));
			}
		}
	}

	@Test
	public void aFisherFacingTheirSpotIsStillDrawnWalking()
	{
		// A fishing spot is an NPC, but fishing isn't fighting: walking off to the next spot, and
		// walked to a spot of their own on a crowded tile, a fisher is drawn walking, as an alcher is.
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body walker = g.player(2, TILE, TILE + 2);
		walker.x += 40;
		walker.animation = AnimationID.HUMAN_HARPOON;
		walker.interacting = g.fishingSpot;
		Body fisher = g.player(3, TILE, TILE);
		Body other = g.player(4, TILE, TILE);
		fisher.animation = AnimationID.HUMAN_HARPOON;
		fisher.interacting = g.fishingSpot;
		g.crowd(TILE, TILE, fisher, other);
		int fisherWalking = 0;
		for (int i = 0; i < 40; i++)
		{
			walker.step();
			g.frame();
			Assert.assertArrayEquals("frame " + i, new int[]{-1, WALK}, g.drawnAs(walker));
			int[] f = g.drawnAs(fisher);
			if (g.offsets.isWalking(fisher.id) && f != null)
			{
				Assert.assertEquals("frame " + i, WALK, f[0]);
				fisherWalking++;
			}
		}
		Assert.assertTrue(fisherWalking > 5);
		Assert.assertEquals(0, g.shim.walkKeptFighting);
	}

	@Test
	public void youAlchingOnTheRunSeeYourOwnCast()
	{
		// Your own action is what you time your next click by, crowd or not: never left out of you.
		Game g = new Game();
		Body you = g.you(TILE, TILE);
		g.crowd(TILE + 2, TILE, g.player(2, TILE + 2, TILE), g.player(3, TILE + 2, TILE));
		g.frame();
		you.x += 40;
		you.animation = ALCH;
		for (int i = 0; i < 20; i++)
		{
			you.step();
			g.frame();
			Assert.assertEquals("frame " + i, ALCH, g.actionDrawn(you));
		}
	}

	// ---- off, paused or in a PvP area: drawn exactly as the game draws them ------------------

	/** An alcher and a fire tender walking about, a fighter, and you doing {@code yours}: nobody's action is touched. */
	private static void nobodysActionIsSwapped(boolean youFighting, int yours)
	{
		Game g = new Game();
		Body you = g.you(TILE - 4, TILE);
		Body alcher = g.player(2, TILE, TILE);
		Body tender = g.player(3, TILE, TILE + 2);
		Body fighter = g.player(4, TILE + 2, TILE + 2);
		g.crowd(TILE + 4, TILE, g.player(5, TILE + 4, TILE), g.player(6, TILE + 4, TILE));
		for (int i = 0; i < 40; i++)
		{
			g.frame();
		}
		g.effectOff(youFighting);
		you.animation = yours;
		you.interacting = youFighting ? g.vorkath : null;
		alcher.animation = ALCH;
		tender.animation = AnimationID.FORESTRY_CAMPFIRE_BURNING_YEW_LOGS;
		fighter.animation = ATTACK;
		fighter.interacting = alcher.actor;
		for (Body b : new Body[]{you, alcher, tender, fighter})
		{
			b.x += 40;
		}
		for (int i = 0; i < 20; i++)
		{
			you.step();
			alcher.step();
			tender.step();
			fighter.step();
			g.frame();
			Assert.assertTrue("frame " + i, g.drawnAsTheGameDoes());
			Assert.assertEquals(ALCH, g.actionDrawn(alcher));
			Assert.assertEquals(AnimationID.FORESTRY_CAMPFIRE_BURNING_YEW_LOGS, g.actionDrawn(tender));
		}
		Assert.assertEquals(0, g.shim.movingBusyWalks);
	}

	@Test
	public void switchedOffNobodysActionIsSwapped()
	{
		nobodysActionIsSwapped(false, ALCH);
	}

	@Test
	public void pausedWhileYouFightNobodysActionIsSwapped()
	{
		// "Pause in fights and raids" (on by default): the effect is off, so the alcher walking past
		// you isn't drawn walking either, not just you.
		nobodysActionIsSwapped(true, ATTACK);
	}

	@Test
	public void inAPvpAreaNobodysActionIsSwapped()
	{
		// The Wilderness, a PvP world or Castle Wars: the plugin is off there whoever is fighting.
		nobodysActionIsSwapped(false, EAT);
	}

	// ---- moved by the plugin while fighting ---------------------------------------------------

	@Test
	public void walkingOffASpotWhileFightingKeepsTheAttack()
	{
		// Legs keep pace walking off a spot, as before, for the alcher; the fighters keep their attack.
		Game g = new Game();
		Body you = g.you(TILE, TILE);
		Body ranger = g.player(2, TILE, TILE);
		Body alcher = g.player(3, TILE, TILE);
		Body idle = g.player(4, TILE, TILE);
		g.crowd(TILE, TILE, you, ranger, alcher, idle);
		for (int i = 0; i < 60; i++)
		{
			g.frame();
		}
		you.animation = ATTACK;
		you.interacting = g.vorkath;
		ranger.animation = ATTACK;
		ranger.interacting = g.vorkath;
		alcher.animation = ALCH;
		for (Body b : new Body[]{you, ranger, alcher, idle})
		{
			Assert.assertTrue(g.offsets.dx(b.id) != 0);
			g.offsets.setWalkingOff(b.id, true);
			g.offsets.setTarget(b.id, 0, 0);
			b.x += 8;
		}
		for (int i = 0; i < 20; i++)
		{
			for (Body b : new Body[]{you, ranger, alcher, idle})
			{
				b.step();
			}
			g.frame();
			Assert.assertTrue(g.offsets.dx(ranger.id) != 0);
			Assert.assertEquals("frame " + i, ATTACK, g.actionDrawn(you));
			Assert.assertEquals(ATTACK, g.actionDrawn(ranger));
			Assert.assertArrayEquals(new int[]{-1, WALK}, g.drawnAs(alcher));
			Assert.assertArrayEquals(new int[]{-1, WALK}, g.drawnAs(idle));
		}
		Assert.assertTrue("legs kept pace", g.shim.legsMatched > 0);
	}

	@Test
	public void walkedToASpotWhileFightingKeepsTheAttack()
	{
		// Shooting from the middle of a crowd as it's spread: the plugin walks them to a spot, and
		// leaving their attack out for the walk, they'd only be seen walking.
		Game g = new Game();
		g.you(TILE - 4, TILE);
		Body ranger = g.player(2, TILE, TILE);
		Body alcher = g.player(3, TILE, TILE);
		ranger.animation = ATTACK;
		ranger.interacting = g.vorkath;
		alcher.animation = ALCH;
		g.crowd(TILE, TILE, ranger, alcher);
		int rangerWalking = 0;
		int alcherWalking = 0;
		for (int i = 0; i < 40; i++)
		{
			g.frame();
			int[] r = g.drawnAs(ranger);
			int[] a = g.drawnAs(alcher);
			if (g.offsets.isWalking(ranger.id) && r != null)
			{
				Assert.assertEquals("frame " + i, ATTACK, r[0]);
				rangerWalking++;
			}
			if (g.offsets.isWalking(alcher.id) && a != null)
			{
				Assert.assertEquals("frame " + i, WALK, a[0]);
				alcherWalking++;
			}
		}
		Assert.assertTrue(rangerWalking > 5);
		Assert.assertTrue(alcherWalking > 5);
	}
}
