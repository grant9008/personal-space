package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Player;
import org.junit.Assert;
import org.junit.Test;

/**
 * Leaving a busy player's action out of their walk model: what the model is built from, and that
 * everything is put back exactly, even when something throws.
 */
public class BusyWalkTest
{
	private static final int CAMPFIRE = 10573;
	private static final int IDLE = 808;
	private static final int WALK = 819;

	/** A player whose animation behaves like the client's: choosing an action restarts it, clearing it forgets the frame. */
	private static final class FakePlayer
	{
		int animation = -1;
		int animationFrame = -1;
		int pose = IDLE;
		int poseFrame;
		/** What the model was built from: action, pose, pose frame. */
		int[] builtFrom;
		final List<String> calls = new ArrayList<>();
		boolean failRestore;
		boolean failModel;

		Player proxy()
		{
			return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, args) ->
			{
				switch (m.getName())
				{
					case "getAnimation":
						return animation;
					case "getAnimationFrame":
						return animationFrame;
					case "getPoseAnimation":
						return pose;
					case "getPoseAnimationFrame":
						return poseFrame;
					case "setAnimation":
					{
						int id = (Integer) args[0];
						calls.add("setAnimation " + id);
						if (id >= 0 && failRestore)
						{
							failRestore = false;
							animation = id;
							animationFrame = -1;
							throw new IllegalStateException("definition lookup failed");
						}
						if (id < 0)
						{
							animation = -1;
							animationFrame = -1;
						}
						else if (id != animation)
						{
							animation = id;
							if (animationFrame == -1)
							{
								animationFrame = 0;
							}
						}
						return null;
					}
					case "setAnimationFrame":
						calls.add("setAnimationFrame " + args[0]);
						animationFrame = (Integer) args[0];
						return null;
					case "setPoseAnimation":
						pose = (Integer) args[0];
						return null;
					case "setPoseAnimationFrame":
						poseFrame = (Integer) args[0];
						return null;
					case "getModel":
						builtFrom = new int[]{animation, pose, poseFrame};
						if (failModel)
						{
							throw new IllegalStateException("model failed");
						}
						return null;
					case "hashCode":
						return System.identityHashCode(p);
					case "equals":
						return p == args[0];
					default:
						Class<?> r = m.getReturnType();
						return r == boolean.class ? false : r == int.class ? 0 : r == long.class ? 0L : null;
				}
			});
		}
	}

	private static FakePlayer tending()
	{
		FakePlayer f = new FakePlayer();
		f.animation = CAMPFIRE;
		f.animationFrame = 7;
		f.pose = IDLE;
		f.poseFrame = 3;
		return f;
	}

	@Test
	public void busyPlayerIsBuiltWalkingWithTheActionLeftOut()
	{
		FakePlayer f = tending();
		SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, 5);
		Assert.assertArrayEquals(new int[]{-1, WALK, 5}, f.builtFrom);
	}

	@Test
	public void actionComesBackAtTheFrameItHadReached()
	{
		FakePlayer f = tending();
		SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, 5);
		Assert.assertEquals(CAMPFIRE, f.animation);
		Assert.assertEquals(7, f.animationFrame);
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(3, f.poseFrame);
	}

	@Test
	public void idlePlayersActionIsNeverTouched()
	{
		FakePlayer f = new FakePlayer();
		f.poseFrame = 2;
		SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, 4);
		Assert.assertArrayEquals(new int[]{-1, WALK, 4}, f.builtFrom);
		Assert.assertTrue(f.calls.isEmpty());
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(2, f.poseFrame);
	}

	@Test
	public void everythingIsPutBackWhenTheModelFails()
	{
		FakePlayer f = tending();
		f.failModel = true;
		try
		{
			SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, 5);
			Assert.fail();
		}
		catch (IllegalStateException expected)
		{
		}
		Assert.assertEquals(CAMPFIRE, f.animation);
		Assert.assertEquals(7, f.animationFrame);
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(3, f.poseFrame);
	}

	@Test
	public void aFailedRestoreLeavesTheActionClearedNotHalfChosen()
	{
		FakePlayer f = tending();
		f.failRestore = true;
		try
		{
			SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, 5);
			Assert.fail();
		}
		catch (IllegalStateException expected)
		{
		}
		Assert.assertEquals(-1, f.animation);
		Assert.assertEquals("setAnimation -1", f.calls.get(f.calls.size() - 1));
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(3, f.poseFrame);
	}
}
