package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
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
 * points from the body to where they really stand. Each is its own switch.
 *
 * <p>With the name and level switch on, hovering a pet says whose it is, "Smolcano - Zezima's
 * pet", and a quest companion who it follows, "Zanik - following Zezima", wherever it's drawn:
 * where it stands, or beside whoever stands on it. Whichever is nearest the camera under the
 * mouse, a pet or someone moved, is the one named.
 *
 * <p>Only over the world: with the mouse over an interface drawn over it, the Grand Exchange or a
 * bank, or in resizable mode the inventory or the chatbox, nobody behind it is named and no arrow
 * is drawn. Beside it, over the world, they are as ever.
 */
final class HoverOverlay extends Overlay
{
	/** The arrow from the body to where they really are. */
	private static final Color ARROW = new Color(255, 232, 0, 200);
	private static final BasicStroke ARROW_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	/** Closer than this on the screen and the arrow would be a smudge: they're standing right there. */
	private static final int ARROW_MIN = 24;
	/**
	 * What the game puts in its menu only for the world under the mouse: "Walk here" ("Set heading"
	 * on a boat), and the options on whoever or whatever is there, using an item or spell on them too.
	 */
	private static final Set<MenuAction> WORLD = EnumSet.of(MenuAction.WALK, MenuAction.SET_HEADING,
		MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION, MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
		MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_PLAYER,
		MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION, MenuAction.WIDGET_TARGET_ON_NPC, MenuAction.EXAMINE_NPC,
		MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION, MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
		MenuAction.EXAMINE_OBJECT,
		MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION,
		MenuAction.GROUND_ITEM_FOURTH_OPTION, MenuAction.GROUND_ITEM_FIFTH_OPTION, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
		MenuAction.EXAMINE_ITEM_GROUND,
		MenuAction.WORLD_ENTITY_FIRST_OPTION, MenuAction.WORLD_ENTITY_SECOND_OPTION, MenuAction.WORLD_ENTITY_THIRD_OPTION,
		MenuAction.WORLD_ENTITY_FOURTH_OPTION, MenuAction.WORLD_ENTITY_FIFTH_OPTION, MenuAction.EXAMINE_WORLD_ENTITY);

	private final Client client;
	private final PersonalSpaceConfig config;
	private final OffsetTable offsets;
	private final StackRegistry stacks;
	private final TooltipManager tooltips;
	private final LevelFilter filter;

	/**
	 * Set by the plugin each frame: the shim drawing the scene, which knows where each pet was drawn
	 * this frame (see {@link SpreadingDrawCallbacks#drawnAt}). Null while nothing is hooked. Client
	 * thread.
	 */
	SpreadingDrawCallbacks shim;

	/** Where the hovered body's feet and its real tile are on the screen this frame. */
	private Point hoveredFeet;
	private Point hoveredReal;
	/**
	 * Where the body {@link #bodyUnderMouse} last found under the mouse has its feet on the screen,
	 * and its real tile's (null unless it's drawn away from it).
	 */
	private Point hitFeet;
	private Point hitReal;

	HoverOverlay(Client client, PersonalSpaceConfig config, OffsetTable offsets, StackRegistry stacks, TooltipManager tooltips, LevelFilter filter)
	{
		this.filter = filter;
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
		boolean who = config.hoverShowsWho();
		boolean arrow = config.hoverArrow();
		if ((!who && !arrow) || client.isMenuOpen() || !overTheWorld(client.getMenu().getMenuEntries()))
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
		// A pet in front of them, or under the mouse with nobody moved there, is the one named:
		// one tooltip at a time.
		NPC pet = who ? petUnderMouse(top, mouse, hoveredFeet == null ? Integer.MIN_VALUE : hoveredFeet.getY()) : null;
		if (pet != null)
		{
			String text = petTooltip(pet, local);
			if (text != null)
			{
				tooltips.add(new Tooltip(text));
			}
			return null;
		}
		if (best == null)
		{
			return null;
		}
		String name = best.getName();
		if (who && name != null)
		{
			tooltips.add(new Tooltip(Text.sanitize(name) + "  <col=" + levelColour(local.getCombatLevel(), best.getCombatLevel())
				+ ">(level-" + best.getCombatLevel() + ")</col>"));
		}
		if (arrow && hoveredFeet != null && hoveredReal != null)
		{
			arrow(graphics, hoveredFeet, hoveredReal);
		}
		return null;
	}

	/**
	 * Whether the game's menu, as it was last built, says the mouse is over the world rather than an
	 * interface drawn over it. The game builds it each client tick, before the frame this overlay
	 * draws in, from what's under the mouse. Where the world shows, it has "Walk here" and the
	 * options on whoever is there. Where an interface covers it (the Grand Exchange, a bank, the
	 * inventory or the chatbox in resizable mode), the interface puts the menu back to Cancel and
	 * adds its own: the game's hover text names nothing of the world there, and nor does this. With
	 * an item or spell picked to use there's no "Walk here", only whoever or whatever it could be
	 * used on, so over bare ground then nobody is named.
	 */
	static boolean overTheWorld(MenuEntry[] menu)
	{
		if (menu == null)
		{
			return false;
		}
		for (MenuEntry entry : menu)
		{
			if (entry != null && WORLD.contains(entry.getType()))
			{
				return true;
			}
		}
		return false;
	}

	/** Of this world's players drawn away from where they stand, the one under the mouse nearest the camera. */
	private Player nearestUnderMouse(WorldView world, Player local, Point mouse, Player best)
	{
		int bestY = hoveredFeet == null ? Integer.MIN_VALUE : hoveredFeet.getY();
		for (Player p : world.players())
		{
			if (p == null || p == local || filter.hides(p))
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
			if (bodyUnderMouse(p, world, lp, plane, dx, dz, mouse, bestY))
			{
				best = p;
				bestY = hitFeet.getY();
				hoveredFeet = hitFeet;
				hoveredReal = hitReal;
			}
		}
		return best;
	}

	/**
	 * Whether this player, drawn (dx, dz) from where they stand, is under the mouse with their feet
	 * lower on the screen than {@code nearerThan} (nearer the camera). If so, where their feet are
	 * goes into {@link #hitFeet}.
	 */
	private boolean bodyUnderMouse(Player p, WorldView world, LocalPoint lp, int plane, int dx, int dz, Point mouse, int nearerThan)
	{
		int mx = mouse.getX();
		int my = mouse.getY();
		boolean away = dx != 0 || dz != 0;
		LocalPoint drawn = away ? new LocalPoint(lp.getX() + dx, lp.getY() + dz, world) : lp;
		if (drawn.getSceneX() < 0 || drawn.getSceneY() < 0 || drawn.getSceneX() >= world.getSizeX() || drawn.getSceneY() >= world.getSizeY())
		{
			return false;
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
			return false;
		}
		if (feet == null || head == null || feet.getY() <= nearerThan)
		{
			return false;
		}
		// A quick look first: roughly a body's box on the screen.
		int height = feet.getY() - head.getY();
		int halfWidth = Math.max(12, height / 3);
		if (height <= 0 || mx < feet.getX() - halfWidth || mx > feet.getX() + halfWidth || my < head.getY() || my > feet.getY())
		{
			return false;
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
			hitFeet = feet;
			hitReal = away ? realFeet : null;
		}
		return hit;
	}

	/**
	 * Of the main world's pets and other followers drawn this frame, the one under the mouse nearest
	 * the camera, if it's nearer than whoever is hovered (their feet at {@code inFrontOf} on the
	 * screen; lower down is nearer) and nobody drawn in front of it covers it there. It's hit where
	 * it's drawn: by the game where it stands, or by the shim beside someone or off a fire (see
	 * {@link SpreadingDrawCallbacks#drawnAt}). One left undrawn, under someone as in the game or
	 * hidden by another plugin, is never hit. Null if there's none, or while the effect isn't running
	 * here (off, paused while you fight, or in a PvP area): nobody is hovered then either.
	 */
	private NPC petUnderMouse(WorldView world, Point mouse, int inFrontOf)
	{
		SpreadingDrawCallbacks drawing = shim;
		if (drawing == null || !filter.enabled)
		{
			return null;
		}
		int mx = mouse.getX();
		int my = mouse.getY();
		int plane = world.getPlane();
		NPC best = null;
		int bestY = inFrontOf;
		for (NPC npc : world.npcs())
		{
			int[] at = npc == null ? null : drawing.drawnAt(npc);
			NPCComposition composition = at == null ? null : npc.getComposition();
			LocalPoint lp = composition == null || !composition.isFollower() ? null : npc.getLocalLocation();
			if (lp == null)
			{
				continue;
			}
			LocalPoint drawn = new LocalPoint(lp.getX() + at[0], lp.getY() + at[1], world);
			if (drawn.getSceneX() < 0 || drawn.getSceneY() < 0 || drawn.getSceneX() >= world.getSizeX() || drawn.getSceneY() >= world.getSizeY())
			{
				continue;
			}
			Point feet;
			Point head;
			Point east;
			Point north;
			try
			{
				feet = Perspective.localToCanvas(client, drawn, plane);
				head = Perspective.localToCanvas(client, drawn, plane, npc.getLogicalHeight());
				east = Perspective.localToCanvas(client, new LocalPoint(drawn.getX() + Perspective.LOCAL_TILE_SIZE, drawn.getY(), world), plane);
				north = Perspective.localToCanvas(client, new LocalPoint(drawn.getX(), drawn.getY() + Perspective.LOCAL_TILE_SIZE, world), plane);
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (feet == null || head == null || east == null || north == null || feet.getY() <= bestY)
			{
				continue;
			}
			// A quick look first: within a tile's width of it on the screen, from a tile below its
			// feet to one above its head. Pets come in all shapes, many wider than they're tall.
			int reach = (int) Math.ceil(Math.max(Math.hypot(east.getX() - feet.getX(), east.getY() - feet.getY()),
				Math.hypot(north.getX() - feet.getX(), north.getY() - feet.getY())));
			if (Math.abs(mx - feet.getX()) > reach || my < Math.min(head.getY(), feet.getY()) - reach || my > feet.getY() + reach)
			{
				continue;
			}
			// Then its model's real outline, slid from where it stands to where it's drawn. A pet is
			// much smaller than that look round it, so with no outline it isn't hit.
			boolean hit = false;
			try
			{
				Shape hull = npc.getConvexHull();
				Point realFeet = at[0] == 0 && at[1] == 0 ? feet : Perspective.localToCanvas(client, lp, plane);
				if (hull != null && realFeet != null)
				{
					hit = AffineTransform.getTranslateInstance(feet.getX() - realFeet.getX(), feet.getY() - realFeet.getY())
						.createTransformedShape(hull).contains(mx, my);
				}
			}
			catch (RuntimeException e)
			{
				// not hit
			}
			if (hit)
			{
				best = npc;
				bestY = feet.getY();
			}
		}
		return best == null || coveredByPeople(world, mouse, bestY) ? null : best;
	}

	/**
	 * Whether anyone drawn in front of a pet, their feet lower on the screen than its {@code feetY},
	 * is under the mouse: you, or anyone else, wherever they're drawn. Pointing at them, it isn't the
	 * pet behind them you're pointing at, though its outline reaches there.
	 */
	private boolean coveredByPeople(WorldView world, Point mouse, int feetY)
	{
		for (Player p : world.players())
		{
			LocalPoint lp = p == null || filter.hides(p) ? null : p.getLocalLocation();
			if (lp == null)
			{
				continue;
			}
			int plane = p.getWorldLocation() == null ? world.getPlane() : p.getWorldLocation().getPlane();
			if (bodyUnderMouse(p, world, lp, plane, offsets.dx(p.getId()), offsets.dz(p.getId()), mouse, feetY))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * What hovering this pet says (see {@link #petText}), or null for nothing: a pet with no name,
	 * or your own with the game's left-click on it, which the game's hover text (and Mouse Tooltips)
	 * already names, with an option only its owner gets.
	 */
	private String petTooltip(NPC pet, Player local)
	{
		// Followers face their owner: whoever it's interacting with, if that's someone. Your own the
		// game also says, whatever it's doing (a cat chasing a rat is still yours).
		Actor with = pet.getInteracting();
		boolean yours = pet == client.getFollower() || with == local;
		if (yours && leftClickOn(pet))
		{
			return null;
		}
		String owner = yours || !(with instanceof Player) ? null : ((Player) with).getName();
		return petText(pet.getName(), owner, yours, canPickUp(pet.getComposition()));
	}

	/**
	 * Whether this follower is a pet: one its owner can pick up, as every pet in the game's cache
	 * can be. A quest companion (Zanik, Nieve) can't; it only follows them.
	 */
	static boolean canPickUp(NPCComposition composition)
	{
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Pick-up".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether the game's own left-click, the top of its menu, is on this NPC. */
	private boolean leftClickOn(NPC npc)
	{
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		return entries != null && entries.length > 0 && entries[entries.length - 1].getNpc() == npc;
	}

	/**
	 * What hovering a follower says: a pet's name and whose it is, "Smolcano - Zezima's pet" or
	 * "Smolcano - your pet", or a quest companion's and who it follows, "Zanik - following Zezima"
	 * or "Zanik - following you". Its name alone when its owner isn't known (gone out of sight, say).
	 * Null if it has no name.
	 */
	static String petText(String pet, String owner, boolean yours, boolean isPet)
	{
		String name = pet == null ? "" : Text.removeTags(pet).trim();
		if (name.isEmpty() || name.equals("null"))
		{
			return null;
		}
		String whose = yours || owner == null ? "" : Text.sanitize(owner).trim();
		if (!yours && whose.isEmpty())
		{
			return name;
		}
		if (isPet)
		{
			return name + " - " + (yours ? "your" : whose + "'s") + " pet";
		}
		return name + " - following " + (yours ? "you" : whose);
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
