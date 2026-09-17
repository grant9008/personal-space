package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

public class CombatWatchTest
{
	@Test
	public void attackingAMonsterThatIsFightingSomeoneElseCounts()
	{
		// Multi-combat: you hit a giant that is attacking another player, so only its health bar shows.
		Assert.assertTrue(new CombatWatch().update(false, true, true, 1));
	}

	@Test
	public void beingHitCounts()
	{
		Assert.assertTrue(new CombatWatch().update(true, false, false, 1));
	}

	@Test
	public void fishingOrTalkingDoesNotCount()
	{
		// Interacting with a fishing spot or someone you talk to: no health bar on them.
		CombatWatch watch = new CombatWatch();
		Assert.assertFalse(watch.update(false, true, false, 1));
		Assert.assertFalse(watch.update(false, false, false, 2));
	}

	@Test
	public void staysInCombatForAFewTicksAfterTheFight()
	{
		CombatWatch watch = new CombatWatch();
		watch.update(false, true, true, 10);
		for (int t = 11; t <= 10 + CombatWatch.LINGER_TICKS; t++)
		{
			Assert.assertTrue("tick " + t, watch.update(false, false, false, t));
		}
		Assert.assertFalse(watch.update(false, false, false, 11 + CombatWatch.LINGER_TICKS));
	}

	@Test
	public void clearForgetsTheLastFight()
	{
		CombatWatch watch = new CombatWatch();
		watch.update(true, false, false, 5);
		watch.clear();
		Assert.assertFalse(watch.update(false, false, false, 6));
	}
}
