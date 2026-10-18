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

		FakePlayer(int idle)
		{
			this.idle = idle;
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
}
