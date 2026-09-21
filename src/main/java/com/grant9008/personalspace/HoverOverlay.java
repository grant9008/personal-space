package com.grant9008.personalspace;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Hover a drawn body to see who it is.
 *
 * <p>Clicking stays with the game: you click people where they really stand, because a plugin
 * may not move a click zone. But you can be told who you're looking at. With the mouse over a
 * body drawn away from its tile, a tooltip gives the name and combat level, the tile they really
 * stand on is outlined, and for a few seconds afterwards their lines in the game's right-click
 * menu are lit up and the other people on that tile dimmed (see the plugin's menu handler), so
 * in a pile of ten you see which "Trade with" is theirs. The menu's order and what a click does
 * are untouched.
 */
final class HoverOverlay extends Overlay
{
	/** How long the last body you hovered stays lit in the right-click menu: 8 ticks, about 5 s. */
	static final int HOVER_MEMORY = 8;
	/** Their lines in the menu, and everyone else's on that tile. */
	static final Color LIT = new Color(255, 232, 0);
	static final Color DIMMED = new Color(120, 120, 120);
	/** The outline of the tile they really stand on. */
	private static final Color REALLY_HERE = new Color(255, 232, 0, 140);

	private final Client client;
	private final PersonalSpaceConfig config;
	private final OffsetTable offsets;
	private final StackRegistry stacks;
	private final TooltipManager tooltips;

	private int hoveredId = -1;
	private int hoveredTick = Integer.MIN_VALUE;

	HoverOverlay(Client client, PersonalSpaceConfig config, OffsetTable offsets, StackRegistry stacks, TooltipManager tooltips)
	{
		this.client = client;
		this.config = config;
		this.offsets = offsets;
		this.stacks = stacks;
		this.tooltips = tooltips;
		setPosition(OverlayPosition.DYNAMIC);
	}

	/** The player whose drawn body was last under the mouse, if that was in the last few seconds; else -1. */
	int remembered(int tick)
	{
		return tick - hoveredTick <= HOVER_MEMORY ? hoveredId : -1;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.hoverShowsWho())
		{
			return null;
		}
		WorldView top = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		Point mouse = client.getMouseCanvasPosition();
		if (top == null || local == null || mouse == null || mouse.getX() < 0)
		{
			return null;
		}
		int tick = client.getTickCount();
		if (client.isMenuOpen())
		{
			// The mouse is on the menu now: keep showing where the person it's lit for really stands.
			int id = remembered(tick);
			Player who = id < 0 ? null : find(top, id);
			if (who != null)
			{
				outline(graphics, who);
			}
			return null;
		}
		Player best = null;
		int bestY = Integer.MIN_VALUE;
		best = nearestUnderMouse(graphics, top, local, mouse, best, bestY);
		for (WorldView boat : top.worldViews())
		{
			if (boat != null)
			{
				best = nearestUnderMouse(graphics, boat, local, mouse, best, bestY);
			}
		}
		if (best == null)
		{
			return null;
		}
		hoveredId = best.getId();
		hoveredTick = tick;
		String name = best.getName();
		if (name != null)
		{
			tooltips.add(new Tooltip(Text.sanitize(name) + "  (level-" + best.getCombatLevel() + ")"));
		}
		outline(graphics, best);
		return null;
	}

	private Player find(WorldView top, int id)
	{
		Player p = top.players().byIndex(id);
		if (p != null)
		{
			return p;
		}
		for (WorldView boat : top.worldViews())
		{
			p = boat == null ? null : boat.players().byIndex(id);
			if (p != null)
			{
				return p;
			}
		}
		return null;
	}

	/** Of this world's players drawn away from where they stand, the one under the mouse nearest the camera. */
	private Player nearestUnderMouse(Graphics2D graphics, WorldView world, Player local, Point mouse, Player best, int bestY)
	{
		int mx = mouse.getX();
		int my = mouse.getY();
		for (Player p : world.players())
		{
			if (p == null || p == local)
			{
				continue;
			}
			int id = p.getId();
			LocalPoint lp = p.getLocalLocation();
			if (lp == null)
			{
				continue;
			}
			int plane = p.getWorldLocation() == null ? world.getPlane() : p.getWorldLocation().getPlane();
			int dx = offsets.dx(id);
			int dz = offsets.dz(id);
			boolean away = dx != 0 || dz != 0;
			if (!away)
			{
				// Standing where they're drawn: the game's own hover text names them, unless they
				// are one of several in a tile's middle, where only the first is drawn at all.
				long tile = StackRegistry.key(StackRegistry.layer(world.getId(), plane), lp.getSceneX(), lp.getSceneY());
				if (stacks.membersAt(tile).length == 0 || stacks.isUnplaced(id))
				{
					continue;
				}
			}
			LocalPoint drawn = away ? new LocalPoint(lp.getX() + dx, lp.getY() + dz, world) : lp;
			if (drawn.getSceneX() < 0 || drawn.getSceneY() < 0 || drawn.getSceneX() >= world.getSizeX() || drawn.getSceneY() >= world.getSizeY())
			{
				continue;
			}
			Point feet;
			Point head;
			try
			{
				feet = Perspective.localToCanvas(client, drawn, plane);
				head = Perspective.localToCanvas(client, drawn, plane, p.getLogicalHeight());
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (feet == null || head == null || feet.getY() <= bestY)
			{
				continue;
			}
			// A quick look first: roughly a body's box on the screen.
			int height = feet.getY() - head.getY();
			int halfWidth = Math.max(12, height / 3);
			if (height <= 0 || mx < feet.getX() - halfWidth || mx > feet.getX() + halfWidth || my < head.getY() || my > feet.getY())
			{
				continue;
			}
			// Then the model's real outline, slid from where they stand to where they're drawn.
			boolean hit = true;
			try
			{
				Shape hull = p.getConvexHull();
				Point realFeet = away ? Perspective.localToCanvas(client, lp, plane) : feet;
				if (hull != null && realFeet != null)
				{
					Shape slid = AffineTransform.getTranslateInstance(feet.getX() - realFeet.getX(), feet.getY() - realFeet.getY())
						.createTransformedShape(hull);
					hit = slid.contains(mx, my);
				}
			}
			catch (RuntimeException e)
			{
				// the box will do
			}
			if (hit)
			{
				best = p;
				bestY = feet.getY();
			}
		}
		return best;
	}

	/** Outline the tile someone really stands on, when they're drawn somewhere else. */
	private void outline(Graphics2D graphics, Player who)
	{
		LocalPoint lp = who.getLocalLocation();
		if (lp == null || !offsets.isOffset(who.getId()))
		{
			return;
		}
		try
		{
			Polygon tile = Perspective.getCanvasTilePoly(client, lp);
			if (tile != null)
			{
				OverlayUtil.renderPolygon(graphics, tile, REALLY_HERE);
			}
		}
		catch (RuntimeException e)
		{
			// no outline, then
		}
	}

	/** A menu line for the person you hovered: lit, with a marker. */
	static String lit(String target)
	{
		return ColorUtil.wrapWithColorTag("> " + Text.removeTags(target), LIT);
	}

	/** A menu line for someone else on that tile: dimmed. */
	static String dimmed(String target)
	{
		return ColorUtil.wrapWithColorTag(Text.removeTags(target), DIMMED);
	}
}
