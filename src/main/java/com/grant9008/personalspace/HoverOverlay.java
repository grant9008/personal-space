package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
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
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;

/**
 * Hover a drawn body to see who it is.
 *
 * <p>Clicking stays with the game: you click people where they really stand, because a plugin
 * may not move a click zone. But you can be told who you're looking at. With the mouse over a
 * body drawn away from its tile, a tooltip gives the name and combat level, and a small arrow
 * points from the body to where they really stand.
 */
final class HoverOverlay extends Overlay
{
	/** The arrow from the body to where they really are. */
	private static final Color ARROW = new Color(255, 232, 0, 200);
	private static final BasicStroke ARROW_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	/** Closer than this on the screen and the arrow would be a smudge: they're standing right there. */
	private static final int ARROW_MIN = 24;

	private final Client client;
	private final PersonalSpaceConfig config;
	private final OffsetTable offsets;
	private final StackRegistry stacks;
	private final TooltipManager tooltips;

	/** Where the hovered body's feet and its real tile are on the screen this frame. */
	private Point hoveredFeet;
	private Point hoveredReal;

	HoverOverlay(Client client, PersonalSpaceConfig config, OffsetTable offsets, StackRegistry stacks, TooltipManager tooltips)
	{
		this.client = client;
		this.config = config;
		this.offsets = offsets;
		this.stacks = stacks;
		this.tooltips = tooltips;
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.hoverShowsWho() || client.isMenuOpen())
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
		hoveredFeet = null;
		hoveredReal = null;
		Player best = nearestUnderMouse(top, local, mouse, null);
		for (WorldView boat : top.worldViews())
		{
			if (boat != null)
			{
				best = nearestUnderMouse(boat, local, mouse, best);
			}
		}
		if (best == null)
		{
			return null;
		}
		String name = best.getName();
		if (name != null)
		{
			tooltips.add(new Tooltip(Text.sanitize(name) + "  <col=" + levelColour(local.getCombatLevel(), best.getCombatLevel())
				+ ">(level-" + best.getCombatLevel() + ")</col>"));
		}
		if (config.hoverArrow() && hoveredFeet != null && hoveredReal != null)
		{
			arrow(graphics, hoveredFeet, hoveredReal);
		}
		return null;
	}

	/** Of this world's players drawn away from where they stand, the one under the mouse nearest the camera. */
	private Player nearestUnderMouse(WorldView world, Player local, Point mouse, Player best)
	{
		int mx = mouse.getX();
		int my = mouse.getY();
		int bestY = hoveredFeet == null ? Integer.MIN_VALUE : hoveredFeet.getY();
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
			Point realFeet;
			try
			{
				feet = Perspective.localToCanvas(client, drawn, plane);
				head = Perspective.localToCanvas(client, drawn, plane, p.getLogicalHeight());
				realFeet = away ? Perspective.localToCanvas(client, lp, plane) : feet;
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
				hoveredFeet = feet;
				hoveredReal = away ? realFeet : null;
			}
		}
		return best;
	}

	/**
	 * The colour the game gives "(level-N)" beside a name: green for someone well below you,
	 * through yellow for your own level, to red for someone well above.
	 */
	static String levelColour(int yours, int theirs)
	{
		int diff = theirs - yours;
		if (diff < -9)
		{
			return "00ff00";
		}
		if (diff < -6)
		{
			return "40ff00";
		}
		if (diff < -3)
		{
			return "80ff00";
		}
		if (diff < 0)
		{
			return "c0ff00";
		}
		if (diff > 9)
		{
			return "ff0000";
		}
		if (diff > 6)
		{
			return "ff3000";
		}
		if (diff > 3)
		{
			return "ff7000";
		}
		if (diff > 0)
		{
			return "ffb000";
		}
		return "ffff00";
	}

	/** A small arrow from the drawn body's feet to where they really stand. */
	private static void arrow(Graphics2D graphics, Point from, Point to)
	{
		double dx = to.getX() - from.getX();
		double dy = to.getY() - from.getY();
		double length = Math.hypot(dx, dy);
		if (length < ARROW_MIN)
		{
			return;
		}
		double ux = dx / length;
		double uy = dy / length;
		int x1 = (int) Math.round(from.getX() + ux * 8);
		int y1 = (int) Math.round(from.getY() + uy * 8);
		int x2 = (int) Math.round(to.getX() - ux * 3);
		int y2 = (int) Math.round(to.getY() - uy * 3);
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(ARROW);
		graphics.setStroke(ARROW_STROKE);
		graphics.drawLine(x1, y1, x2, y2);
		double angle = Math.atan2(dy, dx);
		Polygon head = new Polygon();
		head.addPoint(x2, y2);
		head.addPoint((int) Math.round(x2 - 9 * Math.cos(angle - 0.45)), (int) Math.round(y2 - 9 * Math.sin(angle - 0.45)));
		head.addPoint((int) Math.round(x2 - 9 * Math.cos(angle + 0.45)), (int) Math.round(y2 - 9 * Math.sin(angle + 0.45)));
		graphics.fillPolygon(head);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}
}
