package com.grant9008.personalspace;

/**
 * Where you fight alongside other players: inside a raid, or in the room of a boss fought as a
 * team. With "Pause in fights and raids" on, spreading is off for the whole time you're in one, not
 * only while you're fighting yourself: a team spreads out round the room between fights, and
 * moving people about on top of that only gets in the way.
 *
 * <p>Where a party forms or waits is outside: Mount Quidamortem, Ver Sinhaza and the Tombs of
 * Amascut's entrance hall stay spread, and so do the God Wars Dungeon's strongholds and the room
 * before Nex's barrier.
 *
 * <p>Knows nothing about RuneLite; unit tested.
 */
final class GroupFights
{
	/** RAIDS_CLIENT_INDUNGEON, inside the Chambers of Xeric (Challenge Mode too), as RuneLite's own raids plugin reads it. */
	static final int COX_INSIDE = 1;
	/** TOB_CLIENT_PARTYSTATUS and TOA_CLIENT_PARTYSTATUS: your party's raid is under way. */
	static final int RAID_UNDER_WAY = 2;
	/** ...and you died in it, and watch the rest of the room. */
	static final int RAID_DEAD = 3;

	/**
	 * The Tombs of Amascut's rooms, as map squares: the Nexus, each path's puzzle room and boss,
	 * the Wardens and the chest room. A corner of the game's map kept for the raid and only ever
	 * copied into a party's instance; RuneLite's Discord plugin lists the same (all but the chest
	 * room). The game's own scripts only read the Tombs' party status in the entrance hall, so
	 * inside, the room you stand in says you're in the raid too.
	 */
	private static final int[] TOMBS_ROOMS = {14160, 14162, 14164, 14672, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700};

	/**
	 * The rooms of bosses fought as a team, each as its west, south, east and north edges
	 * (inclusive) and its plane, or -1 for any. In the game's own map, which is what an instance is
	 * copied from, and read from it: only the room itself, not where people wait outside it. Region
	 * ids cross-checked with RuneLite's Discord plugin and the Watchdog plugin's Region list.
	 */
	private static final int[][] BOSS_ROOMS = {
		// Nex's arena in the Ancient Prison, past the barrier: not the room before it, where the
		// team gathers, nor the bank further back.
		{2910, 5188, 2943, 5218, 0},
		// The God Wars Dungeon's generals, each in the room behind their door: not the strongholds
		// outside, where people gather their kill count.
		{2864, 5351, 2876, 5372, 2}, // General Graardor
		{2885, 5258, 2907, 5275, 0}, // Commander Zilyana
		{2821, 5296, 2842, 5308, 2}, // Kree'arra
		{2918, 5318, 2940, 5331, 2}, // K'ril Tsutsaroth
		// The Nightmare's arena: always an instance, copied from a corner of the map kept for it
		// (plane 3 of map square 15515). The sanctuary, where people wait for the next fight, isn't.
		{3859, 9938, 3885, 9964, 3},
		// The Corporeal Beast's cave, in both of the game's copies of it (map squares 11844 and 11842).
		{2944, 4352, 3007, 4415, -1},
		{2944, 4224, 3007, 4287, -1},
		// The Royal Titans' arena in the Asgarnian Ice Dungeon (map square 11669).
		{2880, 9536, 2943, 9599, -1},
		// Yama's lair (map square 6045).
		{1472, 10048, 1535, 10111, -1},
	};

	private GroupFights()
	{
	}

	/**
	 * Whether you're inside a raid: from stepping into it until you leave, every mode and raid
	 * level alike.
	 *
	 * @param coxInDungeon   RAIDS_CLIENT_INDUNGEON: 1 from the room at the bottom of the steps on
	 * @param tobPartyStatus TOB_CLIENT_PARTYSTATUS: 0 no party, 1 in a party, 2 its raid under way, 3 dead in it
	 * @param toaPartyStatus TOA_CLIENT_PARTYSTATUS, the same for the Tombs of Amascut
	 * @param inInstance     you're in an instance. A Theatre or Tombs raid under way says 2 to all
	 *                       its party, even anyone still in the lobby, where it says "Step inside
	 *                       now!": the game itself only shows the raid's own interface once you're in
	 *                       an instance.
	 * @param x              where you stand in the game's own map (an instance's template), or -1
	 * @param y              the same, north
	 */
	static boolean inRaid(int coxInDungeon, int tobPartyStatus, int toaPartyStatus, boolean inInstance, int x, int y)
	{
		if (coxInDungeon == COX_INSIDE)
		{
			return true;
		}
		return inInstance && (underWay(tobPartyStatus) || underWay(toaPartyStatus) || inTombsRoom(x, y));
	}

	private static boolean underWay(int partyStatus)
	{
		return partyStatus == RAID_UNDER_WAY || partyStatus == RAID_DEAD;
	}

	private static boolean inTombsRoom(int x, int y)
	{
		if (x < 0 || y < 0)
		{
			return false;
		}
		int square = ((x >> 6) << 8) | (y >> 6);
		for (int room : TOMBS_ROOMS)
		{
			if (room == square)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether this spot, in the game's own map (an instance's template), is in a group boss's room. */
	static boolean inBossRoom(int x, int y, int plane)
	{
		for (int[] room : BOSS_ROOMS)
		{
			if (x >= room[0] && y >= room[1] && x <= room[2] && y <= room[3] && (room[4] == -1 || room[4] == plane))
			{
				return true;
			}
		}
		return false;
	}
}
