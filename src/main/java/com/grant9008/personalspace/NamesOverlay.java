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
		// Player Indicators draws on the default layer, above overhead chat and hitsplats; so does this.
		setPosition(OverlayPosition.DYNAMIC);
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
		draw(graphics, wv, local, names);
		for (WorldView boat : wv.worldViews())
		{
			if (boat != null)
			{
				draw(graphics, boat, local, names);
			}
		}
		return null;
	}

	/** Names over the players of one world: the main world, or a boat's deck. */
	private void draw(Graphics2D graphics, WorldView world, Player local, PersonalSpaceConfig.Names names)
	{
		for (Player player : world.players())
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
				at = new LocalPoint(at.getX() + dx, at.getY() + dz, world);
			}
			// Off the edge of this world's tiles (a deck is only a few tiles across): no name.
			if (at.getSceneX() < 0 || at.getSceneY() < 0 || at.getSceneX() >= world.getSizeX() || at.getSceneY() >= world.getSizeY())
			{
				continue;
			}
			// The same sums as the game's own Actor.getCanvasTextLocation: the ground under the
			// player's footprint, less any lift from their animation, less the height of the name.
			Point foot;
			try
			{
				int ground = Perspective.getFootprintTileHeight(client, at, world.getPlane(), player.getFootprintSize())
					- player.getAnimationHeightOffset();
				foot = Perspective.localToCanvas(client, at.getWorldView(), at.getX(), at.getY(), ground - player.getLogicalHeight() - ABOVE_HEAD);
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (foot == null)
			{
				continue;
			}
			name = Text.sanitize(name);
			int width = (int) graphics.getFontMetrics().getStringBounds(name, graphics).getWidth();
			OverlayUtil.renderTextLocation(graphics, new Point(foot.getX() - width / 2, foot.getY()), name, colour);
		}
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
