package com.grant9008.personalspace;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Names over players, drawn where Personal Space draws them.
 *
 * <p>RuneLite's Player Indicators puts a name over where a player really stands, which in a
 * spread-out crowd is the middle of the pile: everyone's name lands in one heap while their
 * bodies stand round it. Player Indicators can't be told where the bodies went, so this overlay
 * draws the same kind of label, in the same colours, over the drawn body instead. Someone using
 * it turns Player Indicators' own names off (its "Player name position" set to Disabled) or gets
 * both.
 *
 * <p>Who gets a name follows Player Indicators' defaults: friends, friends chat, team and clan
 * members; or everyone. You aren't given one, you know who you are. Rank icons aren't drawn.
 */
final class NamesOverlay extends Overlay
{
	/** Player Indicators' default colours, so switching over changes nothing but where the names sit. */
	static final Color FRIEND = new Color(0, 200, 83);
	static final Color FRIENDS_CHAT = new Color(170, 0, 255);
	static final Color TEAM = new Color(19, 110, 247);
	static final Color CLAN = new Color(36, 15, 171);
	/** Player Indicators uses red for strangers, meant for PvP; over a bank crowd plain white reads better. */
	static final Color OTHERS = Color.WHITE;

	/** How far above the top of the model the name sits, as in Player Indicators. */
	private static final int ABOVE_HEAD = 40;

	private final Client client;
	private final PersonalSpaceConfig config;
	private final OffsetTable offsets;

	NamesOverlay(Client client, PersonalSpaceConfig config, OffsetTable offsets)
	{
		this.client = client;
		this.config = config;
		this.offsets = offsets;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		PersonalSpaceConfig.Names names = config.names();
		if (names == PersonalSpaceConfig.Names.OFF)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (wv == null || local == null)
		{
			return null;
		}
		for (Player player : wv.players())
		{
			if (player == null || player == local)
			{
				continue;
			}
			Color colour = colourFor(names, player.isFriend(), player.isFriendsChatMember(),
				player.getTeam() != 0 && player.getTeam() == local.getTeam(), player.isClanMember());
			String name = player.getName();
			LocalPoint at = player.getLocalLocation();
			if (colour == null || name == null || at == null)
			{
				continue;
			}
			int dx = offsets.dx(player.getId());
			int dz = offsets.dz(player.getId());
			if (dx != 0 || dz != 0)
			{
				at = new LocalPoint(at.getX() + dx, at.getY() + dz, wv);
			}
			name = Text.sanitize(name);
			Point where = Perspective.getCanvasTextLocation(client, graphics, at, name, player.getLogicalHeight() + ABOVE_HEAD);
			if (where != null)
			{
				OverlayUtil.renderTextLocation(graphics, where, name, colour);
			}
		}
		return null;
	}

	/** The colour a player's name gets, or null for no name. Friends first, as in Player Indicators. */
	static Color colourFor(PersonalSpaceConfig.Names names, boolean friend, boolean friendsChat, boolean team, boolean clan)
	{
		if (names == PersonalSpaceConfig.Names.OFF)
		{
			return null;
		}
		if (friend)
		{
			return FRIEND;
		}
		if (friendsChat)
		{
			return FRIENDS_CHAT;
		}
		if (team)
		{
			return TEAM;
		}
		if (clan)
		{
			return CLAN;
		}
		return names == PersonalSpaceConfig.Names.EVERYONE ? OTHERS : null;
	}
}
