package com.grant9008.personalspace;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Node;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * A stand-in for a player's or NPC's object, handed to the renderer until the next call. Two uses:
 *
 * <ul>
 * <li>{@link #wrap}: carrying a {@link DepthSpacer} in place of a person, between two people drawn
 * with depth against each other. The game draws its people with
 * {@link Renderable#RENDERMODE_SORTED_NO_DEPTH}; handed over as that, the spacer would be put
 * with them. Handed over with this object's render mode, the GPU plugin draws it as a plain opaque
 * model, which is what ends their run (see {@link DepthSpacer}). The people themselves are always
 * handed over just as the game hands them over: drawn as an opaque model themselves, their own
 * faces hid each other by depth instead of in the game's layering, and gear was drawn wrong.</li>
 * <li>{@link #lend}: drawing someone the game had no room for with another player's object.</li>
 * </ul>
 *
 * <p>Everything else is passed straight through. One instance, reused for each draw: the renderer
 * copies what it needs during the call. Client thread only.
 */
final class DepthTestedObject implements GameObject
{
	private GameObject inner;
	private final DepthTestedRenderable renderable = new DepthTestedRenderable();
	/** Lent (see {@link #lend}): where the one it's lent for is drawn. */
	private boolean lent;
	private int lentX;
	private int lentY;
	private int lentPlane;
	/** Handed over as something other than a person ({@link #wrap}, {@link #lendAsOther}), or as the person lent for. */
	private boolean asOther = true;

	/**
	 * This object, standing in for {@code object} until the next call, as something other than a
	 * person: for drawing a {@link DepthSpacer} where {@code object}'s person is.
	 */
	DepthTestedObject wrap(GameObject object)
	{
		inner = object;
		renderable.inner = object.getRenderable();
		lent = false;
		asOther = true;
		return this;
	}

	/**
	 * This object, standing in until the next call for {@code object}, another player's, lent to draw
	 * {@code who} at (x, y) on {@code plane}: the renderer files a body by the object it's handed,
	 * and filed by the lender's, its see-through parts were sorted as if it stood where they do.
	 * {@code who} is handed over just as they are.
	 */
	DepthTestedObject lend(GameObject object, Renderable who, int x, int y, int plane)
	{
		inner = object;
		renderable.inner = who;
		lent = true;
		lentX = x;
		lentY = y;
		lentPlane = plane;
		asOther = false;
		return this;
	}

	/**
	 * As {@link #lend}, but handed over as something other than a person, as {@link #wrap}: for
	 * drawing a {@link DepthSpacer} where {@code who} is drawn with {@code object} lent. Wrapped round
	 * the lender's object, it showed other plugins the lender, standing where the lender stands.
	 */
	DepthTestedObject lendAsOther(GameObject object, Renderable who, int x, int y, int plane)
	{
		lend(object, who, x, y, plane);
		asOther = true;
		return this;
	}

	@Override
	public Renderable getRenderable()
	{
		if (renderable.inner == null)
		{
			return null;
		}
		return asOther ? renderable : renderable.inner;
	}

	@Override
	public int sizeX()
	{
		return inner.sizeX();
	}

	@Override
	public int sizeY()
	{
		return inner.sizeY();
	}

	@Override
	public Point getSceneMinLocation()
	{
		return inner.getSceneMinLocation();
	}

	@Override
	public Point getSceneMaxLocation()
	{
		return inner.getSceneMaxLocation();
	}

	@Override
	public Shape getConvexHull()
	{
		return inner.getConvexHull();
	}

	@Override
	public int getOrientation()
	{
		return inner.getOrientation();
	}

	@Override
	public int getModelOrientation()
	{
		return inner.getModelOrientation();
	}

	@Override
	public int getConfig()
	{
		return inner.getConfig();
	}

	@Override
	public long getHash()
	{
		return inner.getHash();
	}

	@Override
	public int getX()
	{
		return lent ? lentX : inner.getX();
	}

	@Override
	public int getY()
	{
		return lent ? lentY : inner.getY();
	}

	@Override
	public int getZ()
	{
		return inner.getZ();
	}

	@Override
	public int getPlane()
	{
		return lent ? lentPlane : inner.getPlane();
	}

	@Override
	public WorldView getWorldView()
	{
		return inner.getWorldView();
	}

	@Override
	public int getId()
	{
		return inner.getId();
	}

	@Override
	public WorldPoint getWorldLocation()
	{
		return inner.getWorldLocation();
	}

	@Override
	public LocalPoint getLocalLocation()
	{
		return inner.getLocalLocation();
	}

	@Override
	public Point getCanvasLocation()
	{
		return inner.getCanvasLocation();
	}

	@Override
	public Point getCanvasLocation(int zOffset)
	{
		return inner.getCanvasLocation(zOffset);
	}

	@Override
	public Polygon getCanvasTilePoly()
	{
		return inner.getCanvasTilePoly();
	}

	@Override
	public Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset)
	{
		return inner.getCanvasTextLocation(graphics, text, zOffset);
	}

	@Override
	public Point getMinimapLocation()
	{
		return inner.getMinimapLocation();
	}

	@Override
	public Shape getClickbox()
	{
		return inner.getClickbox();
	}

	@Override
	public String getOpOverride(int index)
	{
		return inner.getOpOverride(index);
	}

	@Override
	public boolean isOpShown(int index)
	{
		return inner.isOpShown(index);
	}

	/**
	 * The person, passed straight through but for its render mode: not
	 * {@link Renderable#RENDERMODE_SORTED_NO_DEPTH}, so the GPU plugin draws what's handed over with
	 * it as a plain opaque model. Only ever handed over with a {@link DepthSpacer}.
	 */
	private static final class DepthTestedRenderable implements Renderable
	{
		private Renderable inner;

		@Override
		public Model getModel()
		{
			return inner.getModel();
		}

		@Override
		public int getModelHeight()
		{
			return inner.getModelHeight();
		}

		@Override
		public void setModelHeight(int modelHeight)
		{
			inner.setModelHeight(modelHeight);
		}

		@Override
		public int getAnimationHeightOffset()
		{
			return inner.getAnimationHeightOffset();
		}

		@Override
		public int getRenderMode()
		{
			return RENDERMODE_SORTED;
		}

		@Override
		public Node getNext()
		{
			return inner.getNext();
		}

		@Override
		public Node getPrevious()
		{
			return inner.getPrevious();
		}

		@Override
		public long getHash()
		{
			return inner.getHash();
		}
	}
}
