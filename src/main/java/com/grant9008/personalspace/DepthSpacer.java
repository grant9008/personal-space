package com.grant9008.personalspace;

import java.util.Arrays;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * An invisible model drawn between two people so the GPU plugin gives each of them depth against
 * the other, while each is still layered the way the game layers them.
 *
 * <p>How the GPU plugin draws people (RuneLite 1.13.1 and 1.13.2, read from its bytecode): players
 * and NPCs are {@link net.runelite.api.Renderable#RENDERMODE_SORTED_NO_DEPTH}. Each one's faces are
 * put in the game's own order, by face priority (so a cape's emblem goes over the cape, and the
 * cape over the legs), and appended to one buffer, where a run of such draws one after another is
 * drawn in one go: every colour first, with no depth written, then every depth. Within the run
 * none of them hides another by depth, so whoever is drawn later is on top, whole. A draw of anything
 * else in between ends the run, and the next person starts a run of their own: drawn in their own
 * layering, but hidden wherever someone drawn before them is really nearer, part by part.
 *
 * <p>That's what this is for: one face with all three corners at one point, so nothing is ever
 * drawn, of a model the GPU plugin draws as a plain opaque one (no see-through faces, and handed
 * over as something other than a person, see {@link DepthTestedObject}). Drawing the people
 * themselves as such a model instead (Personal Space before 1.9.18) put each body's own faces
 * through the depth test too, ignoring the game's layering: a Smithing cape lost its anvil, legs
 * poked through the bottom of skill capes, and a cape's lining showed through it.
 *
 * <p>Made once from a small model out of the game's cache, which isn't changed: its vertices are
 * copied before they're moved, and the lit model's face colours are its own.
 */
final class DepthSpacer
{
	/**
	 * Models a spacer is made from, tried in turn: small ones from the default player kits (the
	 * jaw, hands, feet and head), none of them see-through or textured.
	 */
	static final int[] CANDIDATES = {249, 176, 181, 230};

	/** Faces the GPU plugin skips (lit colour -2: hidden). */
	static final int HIDDEN = -2;
	/** A face lit flat, in one colour (lit colour -1 in the third corner). */
	static final int FLAT = -1;

	private DepthSpacer()
	{
	}

	/**
	 * A spacer made from {@code data} (which isn't changed), or null if it can't be one: nothing to
	 * make it from, no faces, or see-through or textured faces, which the GPU plugin would draw down
	 * the sorted path, where a face of no size is dropped and the run isn't ended.
	 */
	static Model build(ModelData data)
	{
		if (data == null || data.getFaceCount() <= 0 || data.getVerticesCount() <= 0
			|| data.getFaceTransparencies() != null || textured(data.getFaceTextures()))
		{
			return null;
		}
		// shallowCopy shares every array with the cache's model; cloneVertices then gives the copy
		// vertices of its own, and light() makes face colours of its own.
		Model model = data.shallowCopy().cloneVertices().light();
		if (model == null || model.getVerticesX() == data.getVerticesX() || model.getVerticesY() == data.getVerticesY()
			|| model.getVerticesZ() == data.getVerticesZ())
		{
			return null;
		}
		Arrays.fill(model.getVerticesX(), 0f);
		Arrays.fill(model.getVerticesY(), 0f);
		Arrays.fill(model.getVerticesZ(), 0f);
		int[] colors3 = model.getFaceColors3();
		if (colors3 == null || colors3.length < model.getFaceCount())
		{
			return null;
		}
		// One face is enough to end the run; the rest are skipped, so only 3 vertices are sent.
		Arrays.fill(colors3, HIDDEN);
		colors3[0] = FLAT;
		return usable(model) ? model : null;
	}

	/**
	 * Whether {@code model} works as a spacer: drawn down the GPU plugin's opaque path (no
	 * see-through faces, not see-through as a whole, no textures), and sending it exactly one face,
	 * whose corners are all at one point, so it can't cover a single pixel.
	 */
	static boolean usable(Model model)
	{
		if (model == null || model.getFaceTransparencies() != null || model.getTransparency() != 0
			|| textured(model.getFaceTextures()))
		{
			return false;
		}
		int faces = model.getFaceCount();
		int[] colors3 = model.getFaceColors3();
		int[] a = model.getFaceIndices1();
		int[] b = model.getFaceIndices2();
		int[] c = model.getFaceIndices3();
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		if (faces <= 0 || colors3 == null || a == null || b == null || c == null || x == null || y == null || z == null
			|| colors3.length < faces || a.length < faces || b.length < faces || c.length < faces)
		{
			return false;
		}
		int shown = 0;
		for (int f = 0; f < faces; f++)
		{
			if (colors3[f] == HIDDEN)
			{
				continue;
			}
			shown++;
			if (!samePoint(x, y, z, a[f], b[f]) || !samePoint(x, y, z, a[f], c[f]))
			{
				return false;
			}
		}
		return shown == 1;
	}

	private static boolean samePoint(float[] x, float[] y, float[] z, int i, int j)
	{
		return i >= 0 && j >= 0 && i < x.length && j < x.length && i < y.length && j < y.length && i < z.length && j < z.length
			&& x[i] == x[j] && y[i] == y[j] && z[i] == z[j];
	}

	private static boolean textured(short[] textures)
	{
		if (textures == null)
		{
			return false;
		}
		for (short t : textures)
		{
			if (t != -1)
			{
				return true;
			}
		}
		return false;
	}
}
