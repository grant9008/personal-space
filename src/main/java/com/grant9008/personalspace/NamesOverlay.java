package com.grant9008.personalspace;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.FriendsChatRank;
import net.runelite.api.Nameable;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Names over players, drawn where Personal Space draws them, the way Player Indicators would.
 *
 * <p>RuneLite's Player Indicators puts a name over where a player really stands, which in a
 * spread-out crowd is the middle of the pile: everyone's name lands in one heap while their
 * bodies stand round it. Player Indicators can't be told where the bodies went, so this overlay
 * draws the same labels over the drawn bodies instead, reading Player Indicators' own settings
 * (who gets a name, each colour, rank icons) so that nothing changes but where the names sit.
 * Someone using it turns Player Indicators' own names off (its "Name position" set to Disabled)
 * or gets both.
 */
final class NamesOverlay extends Overlay
{
	/** Player Indicators' settings group. Two of its colour keys keep older names. */
	static final String PLAYER_INDICATORS = "playerindicators";

	/** A Player Indicators highlight setting: off, on, or only where you can be attacked. */
	enum Highlight
	{
		DISABLED,
		ENABLED,
		PVP
	}

	/** Player Indicators' choices: who gets a name and in what colour. Its defaults when unset. */
	static final class Look
	{
		Highlight own = Highlight.DISABLED;
		Highlight party = Highlight.ENABLED;
		Highlight friend = Highlight.ENABLED;
		Highlight friendsChat = Highlight.ENABLED;
		Highlight team = Highlight.ENABLED;
		Highlight clan = Highlight.ENABLED;
		Highlight others = Highlight.DISABLED;
		Color ownColour = new Color(0, 184, 212);
		Color partyColour = new Color(234, 123, 91);
		Color friendColour = new Color(0, 200, 83);
		Color friendsChatColour = new Color(170, 0, 255);
		Color teamColour = new Color(19, 110, 247);
		Color clanColour = new Color(36, 15, 171);
		Color othersColour = Color.RED;
		boolean friendsChatRanks = true;
		boolean clanRanks = true;

		/** Player Indicators' current settings, straight from RuneLite's settings store. */
		static Look read(ConfigManager configs)
		{
			Look look = new Look();
			look.own = highlight(configs, "highlightSelf", look.own);
			look.party = highlight(configs, "highlightPartyMembers", look.party);
			look.friend = highlight(configs, "highlightFriends", look.friend);
			look.friendsChat = highlight(configs, "highlightFriendsChat", look.friendsChat);
			look.team = highlight(configs, "highlightTeamMembers", look.team);
			look.clan = highlight(configs, "highlightClanMembers", look.clan);
			look.others = highlight(configs, "highlightOthers", look.others);
			look.ownColour = colour(configs, "ownNameColor", look.ownColour);
			look.partyColour = colour(configs, "partyMemberNameColor", look.partyColour);
			look.friendColour = colour(configs, "friendNameColor", look.friendColour);
			look.friendsChatColour = colour(configs, "clanMemberColor", look.friendsChatColour);
			look.teamColour = colour(configs, "teamMemberColor", look.teamColour);
			look.clanColour = colour(configs, "clanChatMemberColor", look.clanColour);
			look.othersColour = colour(configs, "nonClanMemberColor", look.othersColour);
			look.friendsChatRanks = flag(configs, "showFriendsChatRanks", look.friendsChatRanks);
			look.clanRanks = flag(configs, "showClanChatRanks", look.clanRanks);
			return look;
		}

		private static Highlight highlight(ConfigManager configs, String key, Highlight fallback)
		{
			String value = configs.getConfiguration(PLAYER_INDICATORS, key);
			if (value == null)
			{
				return fallback;
			}
			try
			{
				return Highlight.valueOf(value);
			}
			catch (IllegalArgumentException e)
			{
				return fallback;
			}
		}

		private static Color colour(ConfigManager configs, String key, Color fallback)
		{
			try
			{
				Color value = configs.getConfiguration(PLAYER_INDICATORS, key, Color.class);
				return value == null ? fallback : value;
			}
			catch (RuntimeException e)
			{
				return fallback;
			}
		}

		private static boolean flag(ConfigManager configs, String key, boolean fallback)
		{
			String value = configs.getConfiguration(PLAYER_INDICATORS, key);
			return value == null ? fallback : Boolean.parseBoolean(value);
		}
	}

	/** How far above the top of the model the name sits, as in Player Indicators. */
	private static final int ABOVE_HEAD = 40;
	/** The game's "in the Wilderness" and "PvP" flags, as Player Indicators reads them. */
	private static final int IN_WILDERNESS = 5963;
	private static final int PVP_SPEC_ORB = 8121;

	private final Client client;
	private final PersonalSpaceConfig config;
	private final ConfigManager configs;
	private final OffsetTable offsets;
	private final PartyService party;
	private final ChatIconManager icons;

	/** Player Indicators' settings, re-read once a game tick. */
	private Look look;
	private int lookTick = -1;

	NamesOverlay(Client client, PersonalSpaceConfig config, ConfigManager configs, OffsetTable offsets, PartyService party, ChatIconManager icons)
	{
		this.client = client;
		this.config = config;
		this.configs = configs;
		this.offsets = offsets;
		this.party = party;
		this.icons = icons;
		// Player Indicators draws on the default layer, above overhead chat and hitsplats; so does this.
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.namesFollowPlayers())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (wv == null || local == null)
		{
			return null;
		}
		int tick = client.getTickCount();
		if (look == null || tick != lookTick)
		{
			look = Look.read(configs);
			lookTick = tick;
		}
		boolean pvp = client.getVarbitValue(IN_WILDERNESS) == 1 || client.getVarbitValue(PVP_SPEC_ORB) == 1;
		draw(graphics, wv, local, pvp);
		for (WorldView boat : wv.worldViews())
		{
			if (boat != null)
			{
				draw(graphics, boat, local, pvp);
			}
		}
		return null;
	}

	/** Names over the players of one world: the main world, or a boat's deck. */
	private void draw(Graphics2D graphics, WorldView world, Player local, boolean pvp)
	{
		boolean inParty = look.party != Highlight.DISABLED && party.isInParty();
		for (Player player : world.players())
		{
			if (player == null)
			{
				continue;
			}
			String name = player.getName();
			LocalPoint at = player.getLocalLocation();
			if (name == null || at == null)
			{
				continue;
			}
			boolean you = player == local;
			boolean partyMember = !you && inParty && party.getMemberByDisplayName(name) != null;
			Color colour = colourFor(look, pvp, you, partyMember, player.isFriend(), player.isFriendsChatMember(),
				player.getTeam() != 0 && player.getTeam() == local.getTeam(), player.isClanMember());
			if (colour == null)
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
			String shown = Text.sanitize(name);
			FontMetrics metrics = graphics.getFontMetrics();
			int width = (int) metrics.getStringBounds(shown, graphics).getWidth();
			Point where = new Point(foot.getX() - width / 2, foot.getY());
			BufferedImage rank = you ? null : rankImage(player, name);
			if (rank != null)
			{
				// Laid out as Player Indicators does: the icon to the left, the name nudged right.
				int textHeight = metrics.getHeight() - metrics.getMaxDescent();
				OverlayUtil.renderImageLocation(graphics,
					new Point(where.getX() - rank.getWidth() / 2 - 1, where.getY() - textHeight / 2 - rank.getHeight() / 2), rank);
				where = new Point(where.getX() + rank.getWidth() / 2, where.getY());
			}
			OverlayUtil.renderTextLocation(graphics, where, shown, colour);
		}
	}

	/** The friends chat or clan rank icon Player Indicators would show beside this name, or null. */
	private BufferedImage rankImage(Player player, String name)
	{
		try
		{
			if (look.friendsChatRanks && player.isFriendsChatMember())
			{
				FriendsChatManager chat = client.getFriendsChatManager();
				Nameable member = chat == null ? null : chat.findByName(Text.removeTags(name));
				if (member instanceof FriendsChatMember)
				{
					FriendsChatRank rank = ((FriendsChatMember) member).getRank();
					if (rank != null && rank != FriendsChatRank.UNRANKED)
					{
						return icons.getRankImage(rank);
					}
				}
			}
			if (look.clanRanks && player.isClanMember())
			{
				ClanChannel channel = client.getClanChannel();
				ClanSettings settings = client.getClanSettings();
				ClanChannelMember member = channel == null ? null : channel.findMember(Text.removeTags(name));
				if (member != null && settings != null)
				{
					ClanTitle title = settings.titleForRank(member.getRank());
					if (title != null)
					{
						return icons.getRankImage(title);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			// no icon, then
		}
		return null;
	}

	/**
	 * The colour a player's name gets, or null for no name: Player Indicators' own order and
	 * rules. You are only ever "you"; anyone else is the first of party, friend, friends chat,
	 * team, clan that they are and that is switched on, else "others" if that is on and they are
	 * in neither your friends chat nor your clan.
	 */
	static Color colourFor(Look look, boolean pvp, boolean you, boolean party, boolean friend, boolean friendsChat, boolean team, boolean clan)
	{
		if (you)
		{
			return on(look.own, pvp) ? look.ownColour : null;
		}
		if (party && on(look.party, pvp))
		{
			return look.partyColour;
		}
		if (friend && on(look.friend, pvp))
		{
			return look.friendColour;
		}
		if (friendsChat && on(look.friendsChat, pvp))
		{
			return look.friendsChatColour;
		}
		if (team && on(look.team, pvp))
		{
			return look.teamColour;
		}
		if (clan && on(look.clan, pvp))
		{
			return look.clanColour;
		}
		if (!friendsChat && !clan && on(look.others, pvp))
		{
			return look.othersColour;
		}
		return null;
	}

	static boolean on(Highlight setting, boolean pvp)
	{
		return setting == Highlight.ENABLED || (setting == Highlight.PVP && pvp);
	}
}
