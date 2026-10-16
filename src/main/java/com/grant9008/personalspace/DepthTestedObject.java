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
 * A player or NPC handed to the renderer as drawn with depth against everyone else.
 *
 * <p>The game draws all its people with {@link Renderable#RENDERMODE_SORTED_NO_DEPTH}: they don't
 * hide each other by depth, so whoever is drawn later is on top, whole. In a packed crowd that
 * means a shield or a cape reaching into a neighbour is either all in front of them or all
 * behind, and pops from one to the other as the camera turns. Drawn with depth, each part of each
 * body is hidden only where it really is behind another. Everything is passed straight through but
 * the render mode. One instance, reused for each draw: the renderer copies what it needs during
 * the call. Client thread only.
 */
final class DepthTestedObject implements GameObject
{
	private GameObject inner;
	private final DepthTestedRenderable renderable = new DepthTestedRenderable();

	/** This object, standing in for {@code object} until the next call. */
	DepthTestedObject wrap(GameObject object)
	{
		inner = object;
		renderable.inner = object.getRenderable();
		return this;
	}

	@Override
	public Renderable getRenderable()
	{
		return renderable.inner == null ? null : renderable;
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
		return inner.getX();
	}

	@Override
	public int getY()
	{
		return inner.getY();
	}

	@Override
	public int getZ()
	{
		return inner.getZ();
	}

	@Override
	public int getPlane()
	{
		return inner.getPlane();
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

	/** The body, passed straight through but for its render mode. */
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
