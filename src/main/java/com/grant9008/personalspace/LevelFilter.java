package com.grant9008.personalspace;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.client.callback.RenderCallback;

/**
 * Hides players below a combat level you choose: the Grand Exchange's level-3 crowd, say.
 *
 * <p>Works the way RuneLite's own Entity Hider does, through a render callback: a hidden player
 * isn't drawn, nor is their overhead chat, health bar or hitsplats, can't be clicked, and so
 * isn't given a spot in a crowd either (the rest of the
 * plugin only lays out players the render callbacks allow). Friends, friends chat and clan
 * members are never hidden, nor are you. It is off unless a level is set, and it switches off
 * with the rest of the plugin in PvP areas and while you fight.
 */
final class LevelFilter implements RenderCallback
{
	private final Client client;

	/** Set by the plugin each tick: whether the plugin is running here and now. */
	volatile boolean enabled;
	/** Set by the plugin each tick: hide players below this combat level; 0 is off. */
	volatile int below;

	LevelFilter(Client client)
	{
		this.client = client;
	}

	@Override
	public boolean addEntity(Renderable renderable, boolean drawingUI)
	{
		// Asked twice for each player: for the body, and (drawingUI) for what's drawn over it,
		// their overhead chat, health bar and hitsplats. Both go, or a hidden casino bot's spam
		// still floated over the empty spot where it stood.
		if (!(renderable instanceof Player))
		{
			return true;
		}
		return !hides((Player) renderable);
	}

	/** Whether this player is hidden right now. */
	boolean hides(Player player)
	{
		if (!enabled || below <= 0 || player == null || player == client.getLocalPlayer())
		{
			return false;
		}
		return hides(below, player.getCombatLevel(), player.isFriend(), player.isFriendsChatMember(), player.isClanMember());
	}

	/** The rule itself: below the level, and not a friend, friends chat or clan member. */
	static boolean hides(int below, int level, boolean friend, boolean friendsChat, boolean clan)
	{
		return below > 0 && level > 0 && level < below && !friend && !friendsChat && !clan;
	}
}
