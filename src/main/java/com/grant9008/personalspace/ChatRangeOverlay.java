package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * While "Hide chat further than" is being set, the area it keeps on the ground round you: chat from
 * anyone inside it shows, from anyone outside it doesn't. A square, as the game counts tiles
 * (a diagonal step is one), the same way the chat filter does. Gone a few seconds after the
 * slider stops moving.
 */
final class ChatRangeOverlay extends Overlay
{
	/** How long the area stays up after the last change, then how long it takes to fade, in ms. */
	static final long SHOW_MS = 3000;
	static final long FADE_MS = 600;
	private static final Color LINE = new Color(255, 152, 31);
	private static final BasicStroke STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	private final Client client;
	/** The distance shown, in tiles, and until when (System.currentTimeMillis). */
	private volatile int tiles;
	private volatile long until;

	ChatRangeOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** The slider moved to {@code tiles} (0 is off): show that area for a few seconds. Any thread. */
	void show(int tiles)
	{
		this.tiles = tiles;
		this.until = System.currentTimeMillis() + SHOW_MS;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		int n = tiles;
		long left = until - System.currentTimeMillis();
		Player you = client.getLocalPlayer();
		if (n <= 0 || left <= 0 || you == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		LocalPoint at = you.getLocalLocation();
		if (wv == null || at == null || you.getWorldView() == null || you.getWorldView().getId() != WorldView.TOPLEVEL)
		{
			return null;
		}
		int plane = wv.getPlane();
		Path2D outline = new Path2D.Double();
		boolean started = false;
		Point top = null;
		for (int[] corner : boundary(at.getSceneX(), at.getSceneY(), n))
		{
			Point p = Perspective.localToCanvas(client, new LocalPoint(corner[0], corner[1], wv), plane);
			if (p == null)
			{
				// Off the edge of what's loaded or behind the camera: start the line again after it.
				started = false;
				continue;
			}
			if (started)
			{
				outline.lineTo(p.getX(), p.getY());
			}
			else
			{
				outline.moveTo(p.getX(), p.getY());
				started = true;
			}
			top = top == null || p.getY() < top.getY() ? p : top;
		}
		float alpha = left >= FADE_MS ? 1f : left / (float) FADE_MS;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(STROKE);
		g.setColor(new Color(0, 0, 0, (int) (120 * alpha)));
		g.translate(1, 1);
		g.draw(outline);
		g.translate(-1, -1);
		g.setColor(new Color(LINE.getRed(), LINE.getGreen(), LINE.getBlue(), (int) (230 * alpha)));
		g.draw(outline);
		if (top != null)
		{
			String label = n == 1 ? "Chat within 1 tile" : "Chat within " + n + " tiles";
			g.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g.getFontMetrics();
			int x = top.getX() - fm.stringWidth(label) / 2;
			int y = top.getY() - 6;
			g.setColor(new Color(0, 0, 0, (int) (200 * alpha)));
			g.drawString(label, x + 1, y + 1);
			g.setColor(new Color(LINE.getRed(), LINE.getGreen(), LINE.getBlue(), (int) (255 * alpha)));
			g.drawString(label, x, y);
		}
		return null;
	}

	/**
	 * The corners, in local units, round the square of tiles within {@code n} of the tile at
	 * ({@code sceneX}, {@code sceneY}), one tile apart so the line follows the ground: a closed
	 * loop, the first corner repeated at the end.
	 */
	static java.util.List<int[]> boundary(int sceneX, int sceneY, int n)
	{
		int tile = Perspective.LOCAL_TILE_SIZE;
		int x0 = (sceneX - n) * tile;
		int z0 = (sceneY - n) * tile;
		int side = 2 * n + 1;
		java.util.List<int[]> out = new java.util.ArrayList<>(4 * side + 1);
		for (int i = 0; i < side; i++)
		{
			out.add(new int[]{x0 + i * tile, z0});
		}
		for (int i = 0; i < side; i++)
		{
			out.add(new int[]{x0 + side * tile, z0 + i * tile});
		}
		for (int i = 0; i < side; i++)
		{
			out.add(new int[]{x0 + (side - i) * tile, z0 + side * tile});
		}
		for (int i = 0; i < side; i++)
		{
			out.add(new int[]{x0, z0 + (side - i) * tile});
		}
		out.add(out.get(0));
		return out;
	}
}
