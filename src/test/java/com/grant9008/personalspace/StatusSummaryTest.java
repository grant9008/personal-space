package com.grant9008.personalspace;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class StatusSummaryTest
{
	/** A snapshot of the plugin happily spreading players at the GE. Tests break one thing at a time. */
	private static Snapshot working()
	{
		Snapshot s = new Snapshot();
		s.active = true;
		s.mode = PersonalSpaceConfig.Mode.SPREAD;
		s.spacing = PersonalSpaceConfig.SPACING_NORMAL;
		s.maxStack = 5;
		s.testOffset = 32;
		s.gate = Snapshot.Gate.SAFE;
		s.renderer = "GPU plugin";
		s.hooked = true;
		s.playerDrawsPerSec = 300;
		s.nudgedDrawsPerSec = 40;
		s.nearby = 80;
		s.still = 50;
		s.stackedTiles = 2;
		s.moving = 4;
		return s;
	}

	private static StatusSummary.Headline headline(Snapshot s)
	{
		return StatusSummary.headline(s);
	}

	@Test
	public void workingSaysHowManyAreSpread()
	{
		StatusSummary.Headline h = headline(working());
		Assert.assertEquals(StatusSummary.Level.OK, h.level);
		Assert.assertEquals("Spreading 4 players on 2 tiles", h.title);
	}

	@Test
	public void singularWording()
	{
		Snapshot s = working();
		s.moving = 1;
		s.stackedTiles = 1;
		Assert.assertEquals("Spreading 1 player on 1 tile", headline(s).title);
	}

	@Test
	public void pausedWinsOverEverythingElse()
	{
		Snapshot s = working();
		s.active = false;
		s.renderer = null;
		Assert.assertEquals(StatusSummary.Level.PAUSED, headline(s).level);
	}

	@Test
	public void notLoggedInIsWaitingNotAProblem()
	{
		Snapshot s = working();
		s.gate = Snapshot.Gate.NOT_LOGGED_IN;
		s.hooked = false;
		Assert.assertEquals(StatusSummary.Level.WAITING, headline(s).level);
	}

	@Test
	public void safetyGatesSayWhy()
	{
		Snapshot s = working();
		s.gate = Snapshot.Gate.WILDERNESS;
		StatusSummary.Headline h = headline(s);
		Assert.assertEquals(StatusSummary.Level.PAUSED, h.level);
		Assert.assertTrue(h.detail.contains("Wilderness"));
	}

	@Test
	public void noRendererTellsYouToTurnOnGpu()
	{
		Snapshot s = working();
		s.renderer = null;
		s.hooked = false;
		StatusSummary.Headline h = headline(s);
		Assert.assertEquals(StatusSummary.Level.PROBLEM, h.level);
		Assert.assertTrue(h.title.contains("GPU"));
	}

	@Test
	public void notHookedIsAProblem()
	{
		Snapshot s = working();
		s.hooked = false;
		Assert.assertEquals(StatusSummary.Level.PROBLEM, headline(s).level);
	}

	@Test
	public void aBriefGapInPlayerDrawsIsNotAlarming()
	{
		Snapshot s = working();
		s.playerDrawsPerSec = 0;
		s.nudgedDrawsPerSec = 0;
		Assert.assertNotEquals(StatusSummary.Level.PROBLEM, headline(s).level);
	}

	@Test
	public void sustainedNoPlayerDrawsIsAProblem()
	{
		Snapshot s = working();
		s.playerDrawsPerSec = 0;
		s.noPlayerDrawsSustained = true;
		StatusSummary.Headline h = headline(s);
		Assert.assertEquals(StatusSummary.Level.PROBLEM, h.level);
		Assert.assertTrue(h.detail.contains("Copy report"));
	}

	@Test
	public void noStackedPlayersIsWaiting()
	{
		Snapshot s = working();
		s.stackedTiles = 0;
		s.moving = 0;
		s.nudgedDrawsPerSec = 0;
		StatusSummary.Headline h = headline(s);
		Assert.assertEquals(StatusSummary.Level.WAITING, h.level);
		Assert.assertEquals("No crowds here", h.title);
	}

	@Test
	public void revealingHiddenPlayersAloneCountsAsWorking()
	{
		Snapshot s = working();
		s.nudgedDrawsPerSec = 0; // e.g. you are the drawn player and stay put
		s.revealedDrawsPerSec = 120;
		Assert.assertEquals(StatusSummary.Level.OK, headline(s).level);
	}

	@Test
	public void oddityRowCoversEveryShouldNeverHappenCounter()
	{
		Snapshot s = working();
		s.offThreadDraws = 1;
		Assert.assertTrue(hasLabel(StatusSummary.checks(s), "Oddities"));
		s = working();
		s.revealErrors = 2;
		Assert.assertTrue(hasLabel(StatusSummary.checks(s), "Oddities"));
	}

	@Test
	public void stackedButNotYetDrawnMovedIsWaitingUntilSustained()
	{
		Snapshot s = working();
		s.nudgedDrawsPerSec = 0;
		Assert.assertEquals(StatusSummary.Level.WAITING, headline(s).level);
		s.nothingMovedSustained = true;
		Assert.assertEquals(StatusSummary.Level.PROBLEM, headline(s).level);
	}

	@Test
	public void testModeWorking()
	{
		Snapshot s = working();
		s.mode = PersonalSpaceConfig.Mode.TEST_SHIFT_ME;
		s.stackedTiles = 0;
		s.moving = 1;
		StatusSummary.Headline h = headline(s);
		Assert.assertEquals(StatusSummary.Level.OK, h.level);
		Assert.assertTrue(h.detail.contains("32 units"));
		Assert.assertEquals("Test mode is on", h.title);
	}

	@Test
	public void testModeWithZeroOffsetExplainsTheSlider()
	{
		Snapshot s = working();
		s.mode = PersonalSpaceConfig.Mode.TEST_SHIFT_ME;
		s.testOffset = 0;
		s.moving = 0;
		s.nudgedDrawsPerSec = 0;
		Assert.assertEquals(StatusSummary.Level.WAITING, headline(s).level);
	}

	@Test
	public void testModeSustainedNothingShiftedIsAProblem()
	{
		Snapshot s = working();
		s.mode = PersonalSpaceConfig.Mode.TEST_SHIFT_ME;
		s.moving = 1;
		s.nudgedDrawsPerSec = 0;
		s.nothingMovedSustained = true;
		Assert.assertEquals(StatusSummary.Level.PROBLEM, headline(s).level);
	}

	@Test
	public void checksHideSafetyAndDrawRateWhenLoggedOut()
	{
		Snapshot s = working();
		s.gate = Snapshot.Gate.NOT_LOGGED_IN;
		List<StatusSummary.Check> checks = StatusSummary.checks(s);
		for (StatusSummary.Check c : checks)
		{
			Assert.assertNotEquals("Safety", c.label);
			Assert.assertNotEquals("Players drawn", c.label);
		}
	}

	@Test
	public void oddityRowAppearsOnlyWhenSomethingOddHappened()
	{
		Snapshot s = working();
		Assert.assertFalse(hasLabel(StatusSummary.checks(s), "Oddities"));
		s.playersInOtherCalls = 3;
		Assert.assertTrue(hasLabel(StatusSummary.checks(s), "Oddities"));
	}

	@Test
	public void reportContainsTheNumbersNeededToDebug()
	{
		Snapshot s = working();
		String r = StatusSummary.report(s);
		Assert.assertTrue(r.startsWith("Personal Space " + PersonalSpacePlugin.VERSION));
		Assert.assertTrue(r.contains("Renderer: GPU plugin, connected yes"));
		Assert.assertTrue(r.contains("Player draws/sec: 300"));
		Assert.assertTrue(r.contains("stacked tiles: 2"));
		Assert.assertTrue(r.contains("players in other calls (should be 0): 0"));
		Assert.assertTrue(r.contains("hidden shown/sec: 0"));
		Assert.assertTrue(r.contains("pause in combat yes"));
	}

	private static boolean hasLabel(List<StatusSummary.Check> checks, String label)
	{
		for (StatusSummary.Check c : checks)
		{
			if (c.label.equals(label))
			{
				return true;
			}
		}
		return false;
	}
}
