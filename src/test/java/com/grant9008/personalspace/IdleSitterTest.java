package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Player;
import org.junit.Assert;
import org.junit.Test;

public class IdleSitterTest
{
	private static final class FakePlayer
	{
		int idle;

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
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == args[0];
				default:
					return null;
			}
		});
	}

	@Test
	public void everyoneElseSitsAndStandsBackUpInTheirOwnPose()
	{
		FakePlayer a = new FakePlayer(808);
		FakePlayer b = new FakePlayer(4646);
		FakePlayer you = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy, b.proxy, you.proxy);
		IdleSitter sitter = new IdleSitter(null);
		sitter.update(true, here, you.proxy);
		Assert.assertEquals(IdleSitter.SIT, a.idle);
		Assert.assertEquals(IdleSitter.SIT, b.idle);
		Assert.assertEquals("you stay standing", 808, you.idle);
		Assert.assertEquals(2, sitter.sitting());
		sitter.update(true, here, you.proxy);
		Assert.assertEquals("nothing changes tick to tick", 2, sitter.sitting());
		sitter.update(false, here, you.proxy);
		Assert.assertEquals(808, a.idle);
		Assert.assertEquals(4646, b.idle);
		Assert.assertEquals(0, sitter.sitting());
	}

	@Test
	public void newGearsStanceIsRememberedAndTheyStaySitting()
	{
		FakePlayer a = new FakePlayer(808);
		List<Player> here = Arrays.asList(a.proxy);
		IdleSitter sitter = new IdleSitter(null);
		sitter.update(true, here, null);
		// They wield a two-hander: the game sends its stance.
		a.idle = 2065;
		sitter.update(true, here, null);
		Assert.assertEquals(IdleSitter.SIT, a.idle);
		sitter.standAll();
		Assert.assertEquals("back in the new stance", 2065, a.idle);
	}

	@Test
	public void someoneWhoLeftIsForgottenAndNoStanceIsMissing()
	{
		FakePlayer a = new FakePlayer(808);
		FakePlayer none = new FakePlayer(-1);
		IdleSitter sitter = new IdleSitter(null);
		sitter.update(true, Arrays.asList(a.proxy, none.proxy), null);
		Assert.assertEquals("no stance to swap", -1, none.idle);
		sitter.update(true, Arrays.asList(none.proxy), null);
		Assert.assertEquals(0, sitter.sitting());
	}
}
