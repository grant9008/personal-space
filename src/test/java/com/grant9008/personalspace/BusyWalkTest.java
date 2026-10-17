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
		/** The animation and frame the client would have drawn the model with (see {@link #shown}). */
		int[] drawn;
		final List<String> calls = new ArrayList<>();
		boolean failRestore;
		boolean failModel;
		/** setAnimation of this id throws, after choosing it half-way like the client would. */
		int failOn = -2;
		/** The client's crowd rule: more than 200 players about and standing in the idle pose. */
		boolean unanimated;
		/** An action still waiting out its start delay: the client leaves it out of the model. */
		boolean actionDelayed;
		/** The frame the client's idle fallback was left at. */
		int idleFallbackFrame = 9;

		/**
		 * What the client draws, as in its model choice: the action unless delayed; the pose unless
		 * the player is marked unanimated, or it is the idle pose under an action; the idle fallback
		 * at its leftover frame if neither. A walk has no interleave mask, so with an action the
		 * pose is not seen.
		 */
		int[] shown()
		{
			if (animation != -1 && !actionDelayed)
			{
				return new int[]{animation, animationFrame};
			}
			boolean poseUsed = pose != -1 && !unanimated && !(pose == IDLE && animation != -1 && !actionDelayed);
			return poseUsed ? new int[]{pose, poseFrame} : new int[]{IDLE, idleFallbackFrame};
		}

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
						if (id == failOn)
						{
							failOn = -2;
							animation = id;
							throw new IllegalStateException("definition lookup failed");
						}
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
					case "getIdlePoseAnimation":
						return IDLE;
					case "getModel":
						builtFrom = new int[]{animation, pose, poseFrame};
						drawn = shown();
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

	// ---- walking to a spot: the walk in the action slot too ----------------------------------

	@Test
	public void inACrowdThePoseAloneLeftTheLegsStill()
	{
		// The 1.9.3 report: 246 players about, walks drawn with legs still. The pose is dropped.
		FakePlayer f = new FakePlayer();
		f.unanimated = true;
		for (int frame = 0; frame < 4; frame++)
		{
			SpreadingDrawCallbacks.modelMidStep(f.proxy(), WALK, frame);
			Assert.assertArrayEquals(new int[]{IDLE, 9}, f.drawn);
		}
	}

	@Test
	public void inACrowdTheWalkIsDrawnFrameByFrame()
	{
		for (boolean busy : new boolean[]{false, true})
		{
			FakePlayer f = busy ? tending() : new FakePlayer();
			f.unanimated = true;
			for (int frame = 0; frame < 4; frame++)
			{
				SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, frame);
				Assert.assertArrayEquals(new int[]{WALK, frame}, f.drawn);
			}
		}
	}

	@Test
	public void outOfACrowdTheWalkIsDrawnToo()
	{
		FakePlayer f = new FakePlayer();
		SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 6);
		Assert.assertArrayEquals(new int[]{WALK, WALK, 6}, f.builtFrom);
		Assert.assertArrayEquals(new int[]{WALK, 6}, f.drawn);
	}

	@Test
	public void anActionWaitingToStartStillLetsTheWalkShow()
	{
		FakePlayer f = tending();
		f.actionDelayed = true;
		SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 2);
		Assert.assertArrayEquals(new int[]{WALK, 2}, f.drawn);
	}

	@Test
	public void steppingPutsAnIdlePlayerBackExactly()
	{
		FakePlayer f = new FakePlayer();
		f.animationFrame = 11;
		f.poseFrame = 4;
		SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 5);
		Assert.assertEquals(-1, f.animation);
		Assert.assertEquals(11, f.animationFrame);
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(4, f.poseFrame);
		Assert.assertEquals("setAnimation -1", f.calls.get(f.calls.size() - 2));
	}

	@Test
	public void steppingPutsABusyPlayerBackAtTheFrameTheyHadReached()
	{
		FakePlayer f = tending();
		SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 5);
		Assert.assertEquals(CAMPFIRE, f.animation);
		Assert.assertEquals(7, f.animationFrame);
		Assert.assertEquals(IDLE, f.pose);
		Assert.assertEquals(3, f.poseFrame);
	}

	@Test
	public void steppingPutsEverythingBackWhenTheModelFails()
	{
		FakePlayer f = tending();
		f.failModel = true;
		try
		{
			SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 5);
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
	public void aWalkThatCantBeChosenStillPutsTheActionBack()
	{
		FakePlayer f = tending();
		f.failOn = WALK;
		try
		{
			SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 5);
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
	public void aFailedRestoreAfterSteppingLeavesTheActionClearedNotHalfChosen()
	{
		FakePlayer f = tending();
		f.failOn = CAMPFIRE;
		try
		{
			SpreadingDrawCallbacks.modelStepping(f.proxy(), WALK, 5);
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
