package com.grant9008.personalspace;

import java.util.Arrays;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.junit.Assert;
import org.junit.Test;

/** The invisible model drawn between two people with depth (see {@link DepthSpacer}). */
public class DepthSpacerTest
{
	@Test
	public void aSpacerHasOneFaceOfNoSizeAndNothingSeeThrough()
	{
		Model spacer = DepthSpacer.build(FakeMeshes.modelData(FakeMeshes.strip(16), false));
		Assert.assertNotNull(spacer);
		Assert.assertTrue(DepthSpacer.usable(spacer));
		Assert.assertNull("no see-through faces: the GPU plugin's opaque path", spacer.getFaceTransparencies());
		Assert.assertEquals(0, spacer.getTransparency());
		int shown = 0;
		for (int f = 0; f < spacer.getFaceCount(); f++)
		{
			if (spacer.getFaceColors3()[f] != DepthSpacer.HIDDEN)
			{
				shown++;
				int a = spacer.getFaceIndices1()[f];
				int b = spacer.getFaceIndices2()[f];
				int c = spacer.getFaceIndices3()[f];
				Assert.assertEquals(spacer.getVerticesX()[a], spacer.getVerticesX()[b], 0);
				Assert.assertEquals(spacer.getVerticesX()[a], spacer.getVerticesX()[c], 0);
				Assert.assertEquals(spacer.getVerticesY()[a], spacer.getVerticesY()[c], 0);
				Assert.assertEquals(spacer.getVerticesZ()[b], spacer.getVerticesZ()[c], 0);
			}
		}
		Assert.assertEquals("one face, so only 3 vertices are sent", 1, shown);
	}

	@Test
	public void theCachesModelIsNeverChanged()
	{
		FakeMeshes.Mesh mesh = FakeMeshes.strip(16);
		float[] x = mesh.x.clone();
		float[] y = mesh.y.clone();
		float[] z = mesh.z.clone();
		int[] a = mesh.a.clone();
		int[] b = mesh.b.clone();
		int[] c = mesh.c.clone();
		ModelData data = FakeMeshes.modelData(mesh, false);
		// Lit before and after, as the game does with the same model: its colours stay its own.
		Model before = data.light();
		int[] beforeColors = before.getFaceColors3().clone();
		Model spacer = DepthSpacer.build(data);
		Assert.assertNotNull(spacer);
		Assert.assertArrayEquals(x, mesh.x, 0);
		Assert.assertArrayEquals(y, mesh.y, 0);
		Assert.assertArrayEquals(z, mesh.z, 0);
		Assert.assertArrayEquals(a, mesh.a);
		Assert.assertArrayEquals(b, mesh.b);
		Assert.assertArrayEquals(c, mesh.c);
		Assert.assertArrayEquals(beforeColors, before.getFaceColors3());
		Assert.assertArrayEquals(beforeColors, data.light().getFaceColors3());
		Assert.assertNotSame(mesh.x, spacer.getVerticesX());
	}

	@Test
	public void modelDataWhoseVerticesCantBeCopiedIsntUsed()
	{
		// cloneVertices handing back the cache's own arrays: moving them would move the game's model.
		FakeMeshes.Mesh mesh = FakeMeshes.strip(8);
		ModelData stubborn = FakeMeshes.fake(ModelData.class, new java.util.HashMap<String, Object>()
		{
			{
				ModelData real = FakeMeshes.modelData(mesh, false);
				put("getFaceCount", 8);
				put("getVerticesCount", mesh.x.length);
				put("getVerticesX", mesh.x);
				put("getVerticesY", mesh.y);
				put("getVerticesZ", mesh.z);
				put("shallowCopy", real);
				put("light", (java.util.function.Function<Object[], Object>) args -> real.light());
			}
		});
		float[] x = mesh.x.clone();
		Assert.assertNull(DepthSpacer.build(stubborn));
		Assert.assertArrayEquals(x, mesh.x, 0);
	}

	@Test
	public void seeThroughTexturedEmptyOrMissingModelsArentUsed()
	{
		Assert.assertNull(DepthSpacer.build(null));
		FakeMeshes.Mesh alpha = FakeMeshes.strip(4);
		alpha.alphas = new byte[4];
		Assert.assertNull("all-zero transparencies still take the sorted path", DepthSpacer.build(FakeMeshes.modelData(alpha, false)));
		FakeMeshes.Mesh textured = FakeMeshes.strip(4);
		textured.textures = new short[]{-1, -1, 7, -1};
		Assert.assertNull(DepthSpacer.build(FakeMeshes.modelData(textured, false)));
		FakeMeshes.Mesh untextured = FakeMeshes.strip(4);
		untextured.textures = new short[]{-1, -1, -1, -1};
		Assert.assertNotNull("a texture array with no texture in it is fine", DepthSpacer.build(FakeMeshes.modelData(untextured, false)));
		FakeMeshes.Mesh empty = FakeMeshes.strip(0);
		Assert.assertNull(DepthSpacer.build(FakeMeshes.modelData(empty, false)));
	}

	@Test
	public void onlyOneInvisibleOpaqueFaceIsUsable()
	{
		FakeMeshes.Mesh mesh = FakeMeshes.strip(3);
		Arrays.fill(mesh.x, 0);
		Arrays.fill(mesh.y, 0);
		Arrays.fill(mesh.z, 0);
		Model m = FakeMeshes.model(mesh, 1000);
		Assert.assertFalse("three faces shown", DepthSpacer.usable(m));
		m.getFaceColors3()[1] = DepthSpacer.HIDDEN;
		m.getFaceColors3()[2] = DepthSpacer.HIDDEN;
		Assert.assertTrue(DepthSpacer.usable(m));
		mesh.x[2] = 1;
		Assert.assertFalse("a face with a size to it", DepthSpacer.usable(m));
		mesh.x[2] = 0;
		Arrays.fill(m.getFaceColors3(), DepthSpacer.HIDDEN);
		Assert.assertFalse("nothing sent at all: the run isn't ended", DepthSpacer.usable(m));
		m.getFaceColors3()[0] = DepthSpacer.FLAT;
		Assert.assertTrue(DepthSpacer.usable(m));
		mesh.alphas = new byte[3];
		Model seeThrough = FakeMeshes.model(mesh, 1000);
		seeThrough.getFaceColors3()[1] = DepthSpacer.HIDDEN;
		seeThrough.getFaceColors3()[2] = DepthSpacer.HIDDEN;
		Assert.assertFalse("see-through faces: the sorted path", DepthSpacer.usable(seeThrough));
		Assert.assertFalse(DepthSpacer.usable(null));
	}

	@Test
	public void thereAreModelsToMakeItFrom()
	{
		Assert.assertTrue(DepthSpacer.CANDIDATES.length > 0);
	}
}
