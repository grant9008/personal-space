package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import org.junit.Assert;
import org.junit.Test;

public class IdleSitterTest
{
	private static final class FakePlayer
	{
		int idle;
		int animation = -1;
		int x = 6464;
		int id = 5;
		/** The pose the game is showing, the frame it has reached, and game cycles into that frame. */
		int pose;
		int poseFrame;
		int poseCycle;

		FakePlayer(int idle)
		{
			this.idle = idle;
			this.pose = idle;
		}

		final Player proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, args) ->
		{
			switch (m.getName())
			{
				case "getIdlePoseAnimation":
					return idle;
				case "setIdlePoseAnimation":
					idle = (Integer) args[0];
					return null;
				case "getAnimation":
					return animation;
				case "getLocalLocation":
					return new LocalPoint(x, 6464, -1);
				case "getId":
					return id;
				case "getPoseAnimation":
					return pose;
				case "setPoseAnimation":
					// As the game does: a new pose carries on at the frame the last one had reached.
					pose = (Integer) args[0];
					return null;
				case "getPoseAnimationFrame":
					return poseFrame;
				case "setPoseAnimationFrame":
					poseFrame = (Integer) args[0];
					return null;
				case "getIdleRotateLeft":
				case "getIdleRotateRight":
					return TURN;
				case "getWalkAnimation":
					return WALK;
				case "getRunAnimation":
					return 824;
				case "getWalkRotate180":
				case "getWalkRotateLeft":
				case "getWalkRotateRight":
					return 820;
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == args[0];
				default:
					return null;
			}
		});
	}

	/** One tick, 30 game cycles. */
	private static final int TICK = 30;
	/** The standing pose, turning on the spot and walking, as most players have them. */
	private static final int STAND = 808;
	private static final int TURN = 823;
	private static final int WALK = 819;
	/** The frame lengths of the seated loop, as the game has them. */
	private static final int[] SEATED_LENGTHS = {5, 5, 5, 5, 5, 5, 5, 5};

	/**
	 * How the game moves a player's pose on, as client 1.13.2 does it, for the tests below, with the
	 * frame lengths it has for each pose. The Sit emote's loop: nine frames of sitting down, then
	 * eight seated ones, going back to the first seated one at the end.
	 */
	private static final class Game
	{
		/** More than 200 players about: anyone whose pose is their standing pose isn't animated. */
		boolean crowd;
		/** Turning on the spot (their facing catching up with where they look). */
		boolean turning;

		static int[] lengths(int pose)
		{
			switch (pose)
			{
				case IdleSitter.SIT:
					return new int[]{5, 5, 5, 5, 5, 5, 6, 7, 8, 5, 5, 5, 5, 5, 5, 5, 5};
				case IdleSitter.SEATED:
					return SEATED_LENGTHS;
				case TURN:
					return new int[]{11, 11, 11, 11};
				case WALK:
					return new int[]{6, 6, 6, 6, 6, 6, 6, 6};
				default:
					return new int[]{21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21};
			}
		}

		/**
		 * One game cycle for someone standing still: their standing pose (or their turn pose while
		 * turning) put in as their pose at the frame the pose before had reached, then moved on a cycle.
		 */
		void cycle(FakePlayer f)
		{
			f.pose = turning ? TURN : f.idle;
			int[] lengths = lengths(f.pose);
			if (f.poseFrame >= lengths.length)
			{
				f.poseFrame = 0;
				f.poseCycle = 0;
			}
			f.poseCycle++;
			while (f.poseCycle > lengths[f.poseFrame])
			{
				f.poseCycle -= lengths[f.poseFrame];
				f.poseFrame++;
				if (f.poseFrame >= lengths.length)
				{
					f.poseFrame = f.pose == IdleSitter.SIT ? 9 : 0;
				}
			}
		}

		/**
		 * The pose and frame drawn this frame. In a crowd, someone whose pose is their standing pose
		 * is drawn in it at the frame the game last held for them (never moved on since: 0).
		 */
		int[] drawn(FakePlayer f)
		{
			return crowd && f.pose == f.idle ? new int[]{f.idle, 0} : new int[]{f.pose, f.poseFrame};
		}

		static boolean seated(int[] drawn)
		{
			return drawn[0] == IdleSitter.SEATED || drawn[0] == IdleSitter.SIT && drawn[1] >= 9;
		}
	}

	/** Ticks, cycles and frames until {@code a} has sat down, ending on the cycle they did. */
	private static int sitDown(IdleSitter sitter, Game game, FakePlayer a, List<Player> here)
	{
		int cycle = 0;
		while (a.idle != IdleSitter.SIT)
		{
			if (cycle % TICK == 0)
			{
				sitter.update(true, here, null, cycle);
			}
			game.cycle(a);
			sitter.seat(cycle, SEATED_LENGTHS);
			cycle++;
		}
		return cycle;
	}

	@Test
	public void someoneIdleAWhileSitsAndStandsBackUpInTheirOwnPose()
	{
		FakePlayer a = new FakePlayer(808);
		FakePlayer you = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy, you.proxy);
		IdleSitter sitter = new IdleSitter(null);
		int cycle = 1000;
		sitter.update(true, here, you.proxy, cycle);
		Assert.assertEquals("just arrived: standing", 808, a.idle);
		for (cycle += TICK; cycle < 1000 + IdleSitter.SIT_AFTER_CYCLES; cycle += TICK)
		{
			sitter.update(true, here, you.proxy, cycle);
			Assert.assertEquals("not idle long enough yet", 808, a.idle);
		}
		sitter.update(true, here, you.proxy, cycle);
		Assert.assertEquals(IdleSitter.SIT, a.idle);
		Assert.assertEquals("you never sit", 808, you.idle);
		sitter.update(false, here, you.proxy, cycle + TICK);
		Assert.assertEquals(808, a.idle);
		Assert.assertEquals(0, sitter.sitting());
	}

	@Test
	public void movingOrDoingSomethingStandsThemUpAndTheyWaitAgainBeforeSitting()
	{
		FakePlayer a = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		int cycle = 0;
		for (; cycle <= IdleSitter.SIT_AFTER_CYCLES + TICK; cycle += TICK)
		{
			sitter.update(true, here, null, cycle);
		}
		Assert.assertEquals(IdleSitter.SIT, a.idle);
		a.animation = 713;
		sitter.update(true, here, null, cycle);
		Assert.assertEquals("busy: up", 808, a.idle);
		a.animation = -1;
		cycle += TICK;
		sitter.update(true, here, null, cycle);
		Assert.assertEquals("a moment between casts: still up", 808, a.idle);
		a.x += 128;
		cycle += IdleSitter.SIT_AFTER_CYCLES - TICK;
		sitter.update(true, here, null, cycle);
		Assert.assertEquals("walked off: up", 808, a.idle);
	}

	@Test
	public void someoneWhoseCastIsHiddenIsBusyAndNeverSits()
	{
		// Alchers behavior on Still clears their cast: the game shows them idle, but they're alching.
		FakePlayer a = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		for (int cycle = 0; cycle < 20 * IdleSitter.SIT_AFTER_CYCLES; cycle += TICK)
		{
			if (cycle % 150 == 0)
			{
				sitter.busy(a.proxy, cycle);
			}
			sitter.update(true, here, null, cycle);
			Assert.assertEquals("cycle " + cycle + ": sat down mid-alch", 808, a.idle);
		}
	}

	@Test
	public void newGearsStanceIsRememberedAndTheyStaySitting()
	{
		FakePlayer a = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		int cycle = 0;
		for (; cycle <= IdleSitter.SIT_AFTER_CYCLES + TICK; cycle += TICK)
		{
			sitter.update(true, here, null, cycle);
		}
		a.idle = 2065;
		sitter.update(true, here, null, cycle);
		Assert.assertEquals(IdleSitter.SIT, a.idle);
		sitter.standAll();
		Assert.assertEquals("back in the new stance", 2065, a.idle);
	}

	@Test
	public void inABigCrowdTheyAreDrawnSeatedNotHeldHalfWayDown()
	{
		// Over 200 players about, the game draws someone in their standing pose at a frame it held
		// long before: the Sit emote's first, the start of sitting down. They showed standing or
		// leaning half-way down, and stood up and sat again whenever the count crossed 200.
		FakePlayer a = new FakePlayer(STAND);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		Game game = new Game();
		int cycle = sitDown(sitter, game, a, here);
		for (int end = cycle + 20 * TICK; cycle < end; cycle++)
		{
			if (cycle % TICK == 0)
			{
				sitter.update(true, here, null, cycle);
			}
			game.crowd = cycle / (4 * TICK) % 2 == 0;
			game.cycle(a);
			sitter.seat(cycle, SEATED_LENGTHS);
			int[] drawn = game.drawn(a);
			Assert.assertTrue("cycle " + cycle + (game.crowd ? " in a crowd" : "") + ": drawn at frame " + drawn[1] + " of " + drawn[0],
				Game.seated(drawn));
		}
		Assert.assertEquals("still sat down", IdleSitter.SIT, a.idle);
	}

	@Test
	public void turningOnTheSpotTheyStaySeated()
	{
		// The game stands people up to turn, and afterwards their pose carried on at the turn's frame:
		// the start of sitting down. They got up and sat down again every time they turned.
		FakePlayer a = new FakePlayer(STAND);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		Game game = new Game();
		int cycle = sitDown(sitter, game, a, here);
		for (int end = cycle + 6 * TICK; cycle < end; cycle++)
		{
			if (cycle % TICK == 0)
			{
				sitter.update(true, here, null, cycle);
			}
			game.turning = (cycle / 20) % 3 == 0;
			game.cycle(a);
			sitter.seat(cycle, SEATED_LENGTHS);
			int[] drawn = game.drawn(a);
			Assert.assertTrue("cycle " + cycle + (game.turning ? " turning" : "") + ": drawn at frame " + drawn[1] + " of " + drawn[0],
				Game.seated(drawn));
		}
	}

	@Test
	public void theySitStraightDownWhateverFrameTheirStandingPoseHadReached()
	{
		for (int start = 0; start < 12; start++)
		{
			FakePlayer a = new FakePlayer(STAND);
			a.poseFrame = start;
			List<Player> here = Arrays.asList(a.proxy);
			IdleSitter sitter = new IdleSitter(null);
			Game game = new Game();
			int cycle = sitDown(sitter, game, a, here);
			// The cycle they sat down was drawn already: from it on, always seated.
			int[] drawn = game.drawn(a);
			Assert.assertTrue("from frame " + start + ": drawn at frame " + drawn[1] + " of " + drawn[0], Game.seated(drawn));
			for (int end = cycle + 2 * TICK; cycle < end; cycle++)
			{
				game.cycle(a);
				sitter.seat(cycle, SEATED_LENGTHS);
				drawn = game.drawn(a);
				Assert.assertTrue("from frame " + start + ", cycle " + cycle + ": drawn at frame " + drawn[1] + " of " + drawn[0],
					Game.seated(drawn));
			}
		}
	}

	@Test
	public void walkingOrBusyTheGameShowsItAndOffTheirOwnPoseComesBack()
	{
		FakePlayer a = new FakePlayer(STAND);
		a.id = 9;
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		Game game = new Game();
		int cycle = sitDown(sitter, game, a, here);
		a.pose = WALK;
		sitter.seat(cycle, SEATED_LENGTHS);
		Assert.assertEquals("walking off: drawn walking", WALK, a.pose);
		a.pose = IdleSitter.SIT;
		a.animation = 713;
		sitter.seat(cycle, SEATED_LENGTHS);
		Assert.assertEquals("busy: the game's own pose", IdleSitter.SIT, a.pose);
		a.animation = -1;
		sitter.seat(cycle, null);
		Assert.assertEquals("seated loop not read yet: the game's own pose", IdleSitter.SIT, a.pose);
		sitter.seat(cycle, SEATED_LENGTHS);
		Assert.assertEquals(IdleSitter.SEATED, a.pose);
		Assert.assertTrue(a.poseFrame >= 0 && a.poseFrame < SEATED_LENGTHS.length);
		sitter.update(false, here, null, cycle + TICK);
		Assert.assertEquals(STAND, a.idle);
		sitter.seat(cycle + TICK, SEATED_LENGTHS);
		game.cycle(a);
		Assert.assertEquals("stood up: their own pose again, the game's next cycle", STAND, a.pose);
		Assert.assertTrue(a.poseFrame >= 0 && a.poseFrame < Game.lengths(STAND).length);
	}
}
