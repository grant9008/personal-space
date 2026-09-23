package com.grant9008.personalspace;

import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.client.util.Text;

/**
 * Drops public chat from players the level filter hides: a hidden casino bot's spam went with its
 * body and its overhead text, but still filled the chat box.
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

	/** Called from the chat filter hook: blank the line if it's one to drop. */
	void check()
	{
		int[] ints = client.getIntStack();
		int intCount = client.getIntStackSize();
		if (ints == null || intCount < 3)
		{
			return;
		}
		ChatMessageType type = ChatMessageType.of(ints[intCount - 2]);
		MessageNode node = client.getMessages().get(ints[intCount - 1]);
		if (node != null && drops(type, node.getName()))
		{
			ints[intCount - 3] = 0;
		}
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
