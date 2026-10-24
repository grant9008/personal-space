package com.grant9008.personalspace;

import org.junit.Assert;
import org.junit.Test;

/**
 * Where you fight as a team: inside each raid, from stepping in to leaving, and the rooms of the
 * group bosses, but never where a party forms or waits outside them.
 */
public class GroupFightsTest
{
	@Test
	public void insideEachRaid()
	{
		// The Chambers of Xeric (and Challenge Mode): the game says so from the first room on.
		Assert.assertTrue(GroupFights.inRaid(1, 0, 0, true, -1, -1));
		// The Theatre of Blood and the Tombs of Amascut, every mode and level: under way, in its instance.
		Assert.assertTrue(GroupFights.inRaid(0, 2, 0, true, -1, -1));
		Assert.assertTrue(GroupFights.inRaid(0, 0, 2, true, -1, -1));
		// Dead, watching the rest of the room: still inside until the party leaves.
		Assert.assertTrue(GroupFights.inRaid(0, 3, 0, true, -1, -1));
		Assert.assertTrue(GroupFights.inRaid(0, 0, 3, true, -1, -1));
	}

	@Test
	public void insideTheTombsWhateverItsPartyStatusSays()
	{
		// The Nexus, a path's puzzle room and boss, the Wardens, the chest room: the room you stand
		// in says you're in the raid, whatever the party status reads in there.
		Assert.assertTrue("the Nexus", GroupFights.inRaid(0, 0, 0, true, 3550, 5150));
		Assert.assertTrue("the Path of Crondis", GroupFights.inRaid(0, 0, 1, true, 3935, 5280));
		Assert.assertTrue("Zebak", GroupFights.inRaid(0, 0, 4, true, 3935, 5405));
		Assert.assertTrue("Kephri", GroupFights.inRaid(0, 0, 0, true, 3550, 5405));
		Assert.assertTrue("Akkha", GroupFights.inRaid(0, 0, 0, true, 3680, 5405));
		Assert.assertTrue("Ba-Ba", GroupFights.inRaid(0, 0, 0, true, 3805, 5405));
		Assert.assertTrue("the Wardens", GroupFights.inRaid(0, 0, 0, true, 3805, 5150));
		Assert.assertTrue("the Wardens, the second room", GroupFights.inRaid(0, 0, 0, true, 3935, 5150));
		Assert.assertTrue("the chest room", GroupFights.inRaid(0, 0, 0, true, 3680, 5150));
		// Only in an instance: the game never puts anyone there otherwise.
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, false, 3550, 5150));
		// The empty map squares between the rooms are nothing.
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, true, 3615, 5150));
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, true, 3550, 5215));
	}

	@Test
	public void lobbiesAreOutside()
	{
		// Mount Quidamortem, with or without a party.
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, false, 1255, 3560));
		// Ver Sinhaza and the Tombs' entrance hall in a party, before the raid.
		Assert.assertFalse(GroupFights.inRaid(0, 1, 0, false, 3670, 3219));
		Assert.assertFalse(GroupFights.inRaid(0, 0, 1, false, 3360, 9120));
		// Under way but you're still in the lobby: the game says "Step inside now!", not yet inside.
		Assert.assertFalse(GroupFights.inRaid(0, 2, 0, false, 3670, 3219));
		Assert.assertFalse(GroupFights.inRaid(0, 0, 2, false, 3360, 9120));
		// A party formed, waiting in your house (an instance) for the rest.
		Assert.assertFalse(GroupFights.inRaid(0, 1, 1, true, 1890, 5710));
		// Any other instance, with no raid at all.
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, true, 1890, 5710));
		Assert.assertFalse(GroupFights.inRaid(0, 0, 0, true, -1, -1));
	}

	@Test
	public void eachGroupBossRoom()
	{
		Assert.assertTrue("Nex", GroupFights.inBossRoom(2925, 5203, 0));
		Assert.assertTrue("General Graardor", GroupFights.inBossRoom(2870, 5360, 2));
		Assert.assertTrue("Commander Zilyana", GroupFights.inBossRoom(2897, 5266, 0));
		Assert.assertTrue("Kree'arra", GroupFights.inBossRoom(2832, 5302, 2));
		Assert.assertTrue("K'ril Tsutsaroth", GroupFights.inBossRoom(2928, 5324, 2));
		Assert.assertTrue("the Nightmare", GroupFights.inBossRoom(3872, 9951, 3));
		Assert.assertTrue("Corporeal Beast", GroupFights.inBossRoom(2985, 4385, 2));
		Assert.assertTrue("Corporeal Beast, the other copy", GroupFights.inBossRoom(2985, 4257, 2));
		Assert.assertTrue("the Royal Titans", GroupFights.inBossRoom(2911, 9568, 0));
		Assert.assertTrue("Yama", GroupFights.inBossRoom(1503, 10080, 0));
	}

	@Test
	public void whereTheTeamWaitsIsOutside()
	{
		// Nex: the room before the barrier, and the bank further back.
		Assert.assertFalse(GroupFights.inBossRoom(2905, 5203, 0));
		Assert.assertFalse(GroupFights.inBossRoom(2890, 5210, 0));
		// Each general's stronghold, right outside their door, and the doorway itself.
		Assert.assertFalse(GroupFights.inBossRoom(2850, 5340, 2));
		Assert.assertFalse(GroupFights.inBossRoom(2863, 5355, 2));
		Assert.assertFalse(GroupFights.inBossRoom(2908, 5266, 0));
		Assert.assertFalse(GroupFights.inBossRoom(2850, 5300, 2));
		Assert.assertFalse(GroupFights.inBossRoom(2925, 5332, 2));
		// The Nightmare's arena's spot in the map, but on the floor below it.
		Assert.assertFalse(GroupFights.inBossRoom(3872, 9951, 0));
		// The Grand Exchange, Ver Sinhaza, the Tombs' entrance hall.
		Assert.assertFalse(GroupFights.inBossRoom(3165, 3487, 0));
		Assert.assertFalse(GroupFights.inBossRoom(3670, 3219, 0));
		Assert.assertFalse(GroupFights.inBossRoom(3360, 9120, 0));
	}

	@Test
	public void eachRoomOnItsOwnFloor()
	{
		// The generals' rooms are on the floors they're on: the same spot a floor down is nothing.
		Assert.assertFalse(GroupFights.inBossRoom(2870, 5360, 0));
		Assert.assertFalse(GroupFights.inBossRoom(2897, 5266, 1));
	}
}
