package com.grant9008.personalspace;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.runelite.api.gameval.AnimationID;
import org.junit.Assert;
import org.junit.Test;

public class HideLogTest
{
	/** Hide this player every frame from {@code fromFrame} for {@code frames} frames, a cycle per frame. */
	private static void hide(HideLog log, int id, String name, int reason, int holder, String holderName, int fromFrame, int frames)
	{
		for (int f = fromFrame; f < fromFrame + frames; f++)
		{
			if (log.notedThisFrame(id, f))
			{
				continue;
			}
			if (log.continues(id, f, reason))
			{
				log.extend(id, f, f);
				if (holder != log.toId(id))
				{
					log.setTo(id, holder, holderName);
				}
			}
			else
			{
				log.start(id, f, f, reason, name, holder, holderName, false);
			}
		}
	}

	@Test
	public void nobodyHiddenForASecondReportsNothing()
	{
		HideLog log = new HideLog();
		hide(log, 3, "Quick", HideLog.UNCONFIRMED, -1, null, 1, 20);
		Assert.assertNull(log.longest(100, 100));
	}

	@Test
	public void theLongVanishStaysInTheReportAfterManyShortOnes()
	{
		HideLog log = new HideLog();
		hide(log, 7, "Notfancysea", HideLog.WAITING_MIDDLE_TAKEN, 9, "Newcomer", 1, 300);
		// Then a hundred shorter ones within the same minute, the report read now and then.
		for (int i = 0; i < 100; i++)
		{
			hide(log, 100 + i, "Banker" + i, HideLog.WAITING_MIDDLE_TAKEN, 9, "Newcomer", 400 + 20 * i, 60);
			log.longest(462 + 20 * i, 462 + 20 * i);
		}
		String report = log.longest(2500, 2500);
		Assert.assertNotNull(report);
		Assert.assertTrue(report, report.startsWith("Notfancysea 6.0 s (waiting for a break, middle shown to Newcomer"));
	}

	@Test
	public void peopleKeptOffAFullTileDontCrowdOutSomeoneVanishing()
	{
		HideLog log = new HideLog();
		for (int i = 0; i < 5; i++)
		{
			hide(log, 20 + i, "Heap" + i, HideLog.NO_SPOT_MIDDLE_TAKEN, 1, "First", 1, 2000);
		}
		hide(log, 7, "Tender", HideLog.WAITING_MIDDLE_TAKEN, 1, "First", 1500, 300);
		String report = log.longest(2000, 2000);
		Assert.assertTrue(report, report.contains("Tender 6.0 s"));
		Assert.assertEquals("one of each reason", 1, report.split("Heap").length - 1);
	}

	@Test
	public void theMiddleChangingHandsNamesWhoHasItNow()
	{
		HideLog log = new HideLog();
		hide(log, 7, "Tender", HideLog.WAITING_MIDDLE_TAKEN, 1, "First", 1, 100);
		hide(log, 7, "Tender", HideLog.WAITING_MIDDLE_TAKEN, 2, "Second", 101, 100);
		String report = log.longest(201, 201);
		Assert.assertTrue(report, report.startsWith("Tender 4.0 s (waiting for a break, middle shown to Second, still hidden)"));
	}

	@Test
	public void aStreakEndsWhenTheyAreDrawnAgain()
	{
		HideLog log = new HideLog();
		hide(log, 7, "Tender", HideLog.UNCONFIRMED, -1, null, 1, 100);
		hide(log, 7, "Tender", HideLog.UNCONFIRMED, -1, null, 103, 100); // drawn on frames 101 and 102
		String report = log.longest(300, 300);
		Assert.assertTrue(report, report.startsWith("Tender 2.0 s (not shown by the game lately)"));
	}

	@Test
	public void everyForestersCampfireActionIsNeverWaitedOn() throws IllegalAccessException
	{
		int checked = 0;
		for (Field f : AnimationID.class.getFields())
		{
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class
				&& f.getName().startsWith("FORESTRY_CAMPFIRE_BURNING_") && !f.getName().endsWith("SPOTANIM"))
			{
				Assert.assertTrue(f.getName(), PersonalSpacePlugin.neverPauses(f.getInt(null)));
				checked++;
			}
		}
		Assert.assertTrue("found the campfire actions", checked >= 13);
		Assert.assertFalse("high alchemy pauses", PersonalSpacePlugin.neverPauses(713));
		Assert.assertFalse("the spotanim isn't a player action", PersonalSpacePlugin.neverPauses(AnimationID.FORESTRY_CAMPFIRE_BURNING_SPOTANIM));
	}
}
