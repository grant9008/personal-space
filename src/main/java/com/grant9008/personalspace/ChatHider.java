package com.grant9008.personalspace;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * Drops public chat from players the level filter hides: a hidden casino bot's spam went with its
 * body and its overhead text, but still filled the chat box. And, with "Hide chat further than",
 * chat from anyone further from you than that: at an anvil, the bank across the road kept talking.
 *
 * <p>Uses the same hook as RuneLite's Chat Filter, the {@code chatFilterCheck} script callback,
 * which asks, as each chat line is built, whether to show it. Someone hidden when they speak is
 * remembered for the session, by name, so their lines stay hidden after they walk out of view.
 * Nothing is saved or sent anywhere; turning the filter off shows everything again.
 */
final class ChatHider
{
	/** People remembered, at most: older names drop off first. */
	static final int REMEMBERED = 500;

	private final Client client;
	private final LevelFilter filter;
	private final Set<String> hiddenSpeakers = new LinkedHashSet<>();

	/** Public chat lines remembered, at most: the oldest drop off first. */
	static final int REMEMBERED_LINES = 2000;

	/**
	 * Public chat lines, by message id: how many tiles from you the speaker stood as they said it.
	 * Kept so a line stays shown or hidden by where they were then, not where they are when the chat
	 * box is next rebuilt, and so a new distance applies to what's already in the box.
	 */
	private final Map<Integer, Integer> spokenFrom = new LinkedHashMap<Integer, Integer>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest)
		{
			return size() > REMEMBERED_LINES;
		}
	};

	ChatHider(Client client, LevelFilter filter)
	{
		this.client = client;
		this.filter = filter;
	}

	/** Whether a chat line of this type from this sender should be dropped now. Client thread. */
	boolean drops(ChatMessageType type, String sender)
	{
		if (!isPublic(type) || sender == null || !filter.enabled || filter.below <= PersonalSpaceConfig.MIN_HIDE_BELOW)
		{
			return false;
		}
		String name = standard(sender);
		if (hiddenSpeakers.contains(name))
		{
			return true;
		}
		Player speaker = find(name);
		if (speaker != null && filter.hides(speaker))
		{
			remember(name);
			return true;
		}
		return false;
	}

	/**
	 * Called from the chat filter hook: blank the line if it's one to drop, for the level filter
	 * ({@code byLevel}: Hide their chat too) or for being said further than {@code range} tiles
	 * away (0 is off).
	 */
	void check(boolean byLevel, int range)
	{
		int[] ints = client.getIntStack();
		int intCount = client.getIntStackSize();
		if (ints == null || intCount < 3)
		{
			return;
		}
		ChatMessageType type = ChatMessageType.of(ints[intCount - 2]);
		int id = ints[intCount - 1];
		MessageNode node = client.getMessages().get(id);
		if (node == null)
		{
			return;
		}
		boolean far = filter.enabled && isPublic(type) && tooFar(spokenFrom.get(id), range);
		if (far || byLevel && drops(type, node.getName()))
		{
			ints[intCount - 3] = 0;
		}
	}

	/** As a chat line arrives: if it's public, note how far from you its speaker stands. Client thread. */
	void heard(ChatMessageType type, int id, String sender)
	{
		if (!isPublic(type) || sender == null)
		{
			return;
		}
		Player speaker = find(standard(sender));
		int d = distanceFromYou(speaker);
		if (d >= 0)
		{
			spokenFrom.put(id, d);
		}
	}

	/** Whether this player's overhead chat is left out now: they're further than {@code range} tiles from you. */
	boolean overheadTooFar(Player speaker, int range)
	{
		return filter.enabled && tooFar(distanceFromYou(speaker), range);
	}

	/**
	 * How many tiles from you someone stands, or -1 if it doesn't count: you, a friend, friends
	 * chat or clan member (always shown), or nowhere to be found.
	 */
	private int distanceFromYou(Player speaker)
	{
		Player you = client.getLocalPlayer();
		if (speaker == null || you == null || speaker == you
			|| speaker.isFriend() || speaker.isFriendsChatMember() || speaker.isClanMember())
		{
			return -1;
		}
		WorldPoint a = speaker.getWorldLocation();
		WorldPoint b = you.getWorldLocation();
		return a == null || b == null ? -1 : tilesApart(a.getX(), a.getY(), b.getX(), b.getY());
	}

	/** Tiles apart, as the game counts them: diagonal steps count one. */
	static int tilesApart(int x1, int y1, int x2, int y2)
	{
		return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
	}

	/** The rule: said further away than the range, which is on. Unknown distances are never too far. */
	static boolean tooFar(Integer distance, int range)
	{
		return range > 0 && distance != null && distance > range;
	}

	void clear()
	{
		hiddenSpeakers.clear();
	}

	static boolean isPublic(ChatMessageType type)
	{
		return type == ChatMessageType.PUBLICCHAT || type == ChatMessageType.AUTOTYPER;
	}

	/** A name as the game compares them: no tags or icons, spaces made plain, any case. */
	static String standard(String name)
	{
		return Text.toJagexName(Text.removeTags(name)).toLowerCase();
	}

	private void remember(String name)
	{
		hiddenSpeakers.remove(name);
		hiddenSpeakers.add(name);
		while (hiddenSpeakers.size() > REMEMBERED)
		{
			String oldest = hiddenSpeakers.iterator().next();
			hiddenSpeakers.remove(oldest);
		}
	}

	private Player find(String name)
	{
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return null;
		}
		Player p = find(top, name);
		for (WorldView boat : top.worldViews())
		{
			if (p == null && boat != null)
			{
				p = find(boat, name);
			}
		}
		return p;
	}

	private static Player find(WorldView world, String name)
	{
		for (Player p : world.players())
		{
			if (p != null && p.getName() != null && standard(p.getName()).equals(name))
			{
				return p;
			}
		}
		return null;
	}
}
