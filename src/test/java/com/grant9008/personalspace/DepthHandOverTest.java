package com.grant9008.personalspace;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * Handing kept people to the renderer with "Depth between players" (see {@link DepthSpacer}),
 * checked against RuneLite's real GPU plugin, run without a screen: its drawTemp, model uploaders
 * and VAO ranges are the real ones, only the GL buffer is plain memory. If the GPU plugin's
 * insides have changed shape, those tests are skipped; if they're there but behave differently,
 * they fail, as depth may then not work in the game either.
 */
public class DepthHandOverTest
{
	private static final int X = 6400;
	private static final int Z = 6400;

	/** The camera as the sorted path sees it: 1000 in front of everyone, looking along +z. */
	private static final Projection CAMERA = new Projection()
	{
		@Override
		public float[] project(float x, float y, float z)
		{
			return project(x, y, z, new float[3]);
		}

		@Override
		public float[] project(float x, float y, float z, float[] out)
		{
			out[0] = x - X;
			out[1] = y;
			out[2] = z - Z + 1000;
			return out;
		}
	};

	// ---------------------------------------------------------------- the real GPU plugin, offline

	/** RuneLite's GPU plugin with its buffers in memory; null (test skipped) if it's not built as expected. */
	private static final class Gpu
	{
		DrawCallbacks plugin;
		Object vaoO;
		Object vaoA;
		Class<?> vaoClass;
		Class<?> vboClass;
		Class<?> rangeClass;
		final List<Integer> modes = new ArrayList<>();
		/** Where each object handed over says it is, and the model behind its renderable. */
		final List<Integer> xs = new ArrayList<>();
		final List<Model> models = new ArrayList<>();

		/** Render method of each range of drawn models so far, and how many ints each holds. */
		List<int[]> ranges() throws ReflectiveOperationException
		{
			List<int[]> out = new ArrayList<>();
			int count = field(vaoClass, "off").getInt(vaoO);
			Object[] ranges = (Object[]) field(vaoClass, "ranges").get(vaoO);
			int start = 0;
			for (int i = 0; i < count; i++)
			{
				int end = field(rangeClass, "endpos").getInt(ranges[i]);
				out.add(new int[]{field(rangeClass, "renderMethod").getByte(ranges[i]), end - start});
				start = end;
			}
			return out;
		}

		String methods() throws ReflectiveOperationException
		{
			StringBuilder b = new StringBuilder();
			for (int[] r : ranges())
			{
				b.append(b.length() == 0 ? "" : " ").append(r[0]);
			}
			return b.toString();
		}

		int alphaInts() throws ReflectiveOperationException
		{
			Object vbo = field(vaoClass, "vbo").get(vaoA);
			return ((IntBuffer) field(vboClass, "vb").get(vbo)).position();
		}
	}

	private static Field field(Class<?> c, String name) throws NoSuchFieldException
	{
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static Constructor<?> ctor(Class<?> c, Class<?>... types) throws NoSuchMethodException
	{
		Constructor<?> k = c.getDeclaredConstructor(types);
		k.setAccessible(true);
		return k;
	}

	private static Gpu gpu()
	{
		try
		{
			String p = "net.runelite.client.plugins.gpu.";
			Class<?> gpuClass = Class.forName(SpreadingDrawCallbacks.GPU_PLUGIN);
			Class<?> sceneContext = Class.forName(p + "GpuPlugin$SceneContext");
			Class<?> renderThread = Class.forName(p + "GpuPlugin$RenderThread");
			Class<?> vaoList = Class.forName(p + "VAOList");
			Class<?> uploader = Class.forName(p + "ModelUploader");
			Gpu g = new Gpu();
			g.vaoClass = Class.forName(p + "VAO");
			g.vboClass = Class.forName(p + "VBO");
			g.rangeClass = Class.forName(p + "VAO$Range");
			g.plugin = (DrawCallbacks) ctor(gpuClass).newInstance();
			field(gpuClass, "root").set(g.plugin, ctor(sceneContext, int.class, int.class).newInstance(23, 23));
			Object rt = ctor(renderThread).newInstance();
			Object[] lists = new Object[2];
			Object[] vaos = new Object[2];
			for (int i = 0; i < 2; i++)
			{
				lists[i] = ctor(vaoList, boolean.class).newInstance(false);
				vaos[i] = ctor(g.vaoClass, int.class).newInstance(1 << 20);
				Object vbo = field(g.vaoClass, "vbo").get(vaos[i]);
				field(g.vboClass, "vb").set(vbo, IntBuffer.allocate(1 << 18));
				field(g.vboClass, "mapped").setBoolean(vbo, true);
				@SuppressWarnings("unchecked")
				List<Object> list = (List<Object>) field(vaoList, "vaos").get(lists[i]);
				list.add(vaos[i]);
			}
			field(renderThread, "vaoO").set(rt, lists[0]);
			field(renderThread, "vaoA").set(rt, lists[1]);
			field(renderThread, "modelUploader").set(rt, ctor(uploader).newInstance());
			Object rts = Array.newInstance(renderThread, 1);
			Array.set(rts, 0, rt);
			field(gpuClass, "rts").set(g.plugin, rts);
			RenderCallbackManager callbacks = new RenderCallbackManager();
			callbacks.register(new RenderCallback()
			{
				@Override
				public boolean drawObject(Scene scene, TileObject object)
				{
					Renderable r = ((GameObject) object).getRenderable();
					g.modes.add(r.getRenderMode());
					g.xs.add(((GameObject) object).getX());
					g.models.add(r.getModel());
					return true;
				}
			});
			field(gpuClass, "renderCallbackManager").set(g.plugin, callbacks);
			g.vaoO = vaos[0];
			g.vaoA = vaos[1];
			g.ranges();
			g.alphaInts();
			return g;
		}
		catch (ReflectiveOperationException | LinkageError | ClassCastException | IllegalArgumentException e)
		{
			Assume.assumeNoException("the GPU plugin's insides have changed shape", e);
			return null;
		}
	}

	// ---------------------------------------------------------------- the shim

	/** Model ids the fake cache has, and how often it was asked. */
	private final Map<Integer, ModelData> cache = new HashMap<>();
	private int cacheReads;
	/** The last shim's frame counter. */
	private OffsetTable offsets;

	private SpreadingDrawCallbacks shim(DrawCallbacks delegate)
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("loadModelData", (Function<Object[], Object>) args ->
		{
			cacheReads++;
			return cache.get((Integer) args[0]);
		});
		answers.put("isClientThread", true);
		Client client = FakeMeshes.fake(Client.class, answers);
		offsets = new OffsetTable();
		StackRegistry stacks = new StackRegistry();
		return new SpreadingDrawCallbacks(client, offsets, stacks, new StackProbe(client, offsets, stacks), new RenderCallbackManager(), delegate);
	}

	/** Someone as the game hands them over, standing {@code dx} east of the middle. */
	private static final class Person
	{
		final Model model;
		final GameObject object;
		final int x;

		Person(int faces, int dx)
		{
			FakeMeshes.Mesh mesh = FakeMeshes.strip(faces);
			mesh.priorities = new byte[faces];
			model = FakeMeshes.model(mesh, 2000);
			x = X + dx;
			object = FakeMeshes.object(FakeMeshes.player(model), x, Z);
		}
	}

	private static void handOver(SpreadingDrawCallbacks s, Person p, boolean depth)
	{
		s.handOver(CAMERA, FakeMeshes.scene(), p.object, null, 0, p.model, 0, p.x, 0, Z, depth);
	}

	{
		cache.put(DepthSpacer.CANDIDATES[0], FakeMeshes.modelData(FakeMeshes.strip(16), false));
	}

	// ---------------------------------------------------------------- tests

	@Test
	public void theGameDrawsARunOfPeopleAllInOneGo() throws Exception
	{
		Gpu g = gpu();
		Person a = new Person(6, 0);
		Person b = new Person(6, 30);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), a.object, a.model, 0, a.x, 0, Z);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), b.object, b.model, 0, b.x, 0, Z);
		// One range drawn with render method 2: every colour with no depth written, then every depth.
		Assert.assertEquals("2", g.methods());
	}

	@Test
	public void withDepthEachPersonIsDrawnOnTheirOwnInTheirOwnLayering() throws Exception
	{
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		Person a = new Person(6, 0);
		Person b = new Person(6, 30);
		handOver(s, a, true);
		handOver(s, b, true);
		// Each person a range of their own, still drawn as a person (method 2: the game's layering),
		// each after a spacer's range of one face (18 ints) that ends the run before it.
		Assert.assertEquals("0 2 0 2", g.methods());
		List<int[]> r = g.ranges();
		Assert.assertEquals(18, r.get(0)[1]);
		Assert.assertEquals(18, r.get(2)[1]);
		Assert.assertEquals("nothing goes down the see-through pass", 0, g.alphaInts());
		Assert.assertEquals(2, s.depthDraws);
		Assert.assertEquals(0, s.depthNoSpacer);
		Assert.assertEquals("the spacer is made once", 1, cacheReads);
		// What other plugins see (Entity Hider and the like): the spacer as something other than a
		// person, and each person just as the game hands them over.
		Assert.assertEquals(List.of(Renderable.RENDERMODE_SORTED, Renderable.RENDERMODE_SORTED_NO_DEPTH,
			Renderable.RENDERMODE_SORTED, Renderable.RENDERMODE_SORTED_NO_DEPTH), g.modes);
	}

	@Test
	public void withoutDepthEveryoneIsDrawnJustAsTheGameDoes() throws Exception
	{
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		Person a = new Person(6, 0);
		Person b = new Person(6, 30);
		handOver(s, a, false);
		handOver(s, b, false);
		Assert.assertEquals("2", g.methods());
		Assert.assertEquals(0, cacheReads);

		g = gpu();
		s = shim(g.plugin);
		s.depthBetweenPlayers = false;
		handOver(s, a, true);
		handOver(s, b, true);
		Assert.assertEquals("the setting off", "2", g.methods());
		Assert.assertEquals(0, s.depthDraws);
	}

	@Test
	public void someoneOverlappingNobodyJoinsTheRunBeforeThem() throws Exception
	{
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		handOver(s, new Person(6, 0), false);
		handOver(s, new Person(6, 30), true);
		handOver(s, new Person(6, 60), false);
		Assert.assertEquals("2 0 2", g.methods());
	}

	@Test
	public void withNoSpacerEveryoneIsDrawnAsTheGameDoesAndItIsCounted() throws Exception
	{
		cache.clear();
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		handOver(s, new Person(6, 0), true);
		handOver(s, new Person(6, 30), true);
		Assert.assertEquals("2", g.methods());
		Assert.assertEquals(2, s.depthNoSpacer);
		Assert.assertEquals(0, s.depthDraws);
		Assert.assertEquals("every candidate tried, once a frame", DepthSpacer.CANDIDATES.length, cacheReads);
		// Retried each frame a while (the cache may not have it yet), then given up on.
		for (int i = 0; i < SpreadingDrawCallbacks.SPACER_TRIES + 10; i++)
		{
			offsets.advance(0.02f);
			handOver(s, new Person(2, 0), true);
			handOver(s, new Person(2, 30), true);
		}
		Assert.assertEquals(SpreadingDrawCallbacks.SPACER_TRIES * DepthSpacer.CANDIDATES.length, cacheReads);
	}

	@Test
	public void aSpacerThatTurnsUpLaterIsUsedFromThen() throws Exception
	{
		ModelData later = cache.remove(DepthSpacer.CANDIDATES[0]);
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		handOver(s, new Person(6, 0), true);
		cache.put(DepthSpacer.CANDIDATES[0], later);
		handOver(s, new Person(6, 30), true);
		Assert.assertEquals("not tried again in the same frame", "2", g.methods());
		offsets.advance(0.02f);
		handOver(s, new Person(6, 60), true);
		Assert.assertEquals("2 0 2", g.methods());
	}

	@Test
	public void drawnAsAnythingButAPersonABodyLosesTheGamesLayering() throws Exception
	{
		// Why 1.9.17 drew gear wrong: handed over as something other than a person, a body goes down
		// the opaque path, every face in model order, by depth alone (method 0), priorities unused.
		Gpu g = gpu();
		Person a = new Person(6, 0);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), new DepthTestedObject().wrap(a.object), a.model, 0, a.x, 0, Z);
		Assert.assertEquals("0", g.methods());
		Assert.assertEquals("all 6 faces, none sorted or culled", 6 * 18, g.ranges().get(0)[1]);
	}

	@Test
	public void aSpacerHandedOverAsAPersonWouldNotEndTheRun() throws Exception
	{
		// The sorted path drops a face of no size, so nothing is written and the run goes on.
		Gpu g = gpu();
		Person a = new Person(6, 0);
		Person b = new Person(6, 30);
		Model spacer = DepthSpacer.build(cache.get(DepthSpacer.CANDIDATES[0]));
		Assert.assertNotNull(spacer);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), a.object, a.model, 0, a.x, 0, Z);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), FakeMeshes.object(FakeMeshes.player(spacer), X, Z), spacer, 0, X, 0, Z);
		g.plugin.drawTemp(CAMERA, FakeMeshes.scene(), b.object, b.model, 0, b.x, 0, Z);
		Assert.assertEquals("2", g.methods());
	}

	@Test
	public void lentSomeonesObjectTheyAreStillDrawnAsThemselves() throws Exception
	{
		Gpu g = gpu();
		SpreadingDrawCallbacks s = shim(g.plugin);
		Person lender = new Person(6, 0);
		Person who = new Person(6, 30);
		s.handOver(CAMERA, FakeMeshes.scene(), lender.object, who.object.getRenderable(), 0, who.model, 0, who.x, 0, Z, true);
		Assert.assertEquals("0 2", g.methods());
		Assert.assertEquals(List.of(Renderable.RENDERMODE_SORTED, Renderable.RENDERMODE_SORTED_NO_DEPTH), g.modes);
		// The spacer too stands in for the one drawn, where they're drawn: never the lender, whose
		// model rebuilt by another plugin would overwrite the one about to be drawn.
		Assert.assertEquals(List.of(who.x, who.x), g.xs);
		Assert.assertEquals(List.of(who.model, who.model), g.models);
	}

	@Test
	public void aSpacerTheRendererChokesOnIsDroppedAndThePersonStillDrawn() throws Exception
	{
		Gpu g = gpu();
		List<Model> drawn = new ArrayList<>();
		Map<String, Object> answers = new HashMap<>();
		answers.put("drawTemp", (Function<Object[], Object>) args ->
		{
			Model m = (Model) args[3];
			if (((GameObject) args[2]).getRenderable().getRenderMode() != Renderable.RENDERMODE_SORTED_NO_DEPTH)
			{
				throw new IllegalStateException("choked");
			}
			drawn.add(m);
			return null;
		});
		DrawCallbacks choking = FakeMeshes.fake(DrawCallbacks.class, answers);
		// The shim only sends spacers to the GPU plugin, so hand it the real one and swap the
		// delegate afterwards.
		SpreadingDrawCallbacks s = shim(g.plugin);
		Field delegate = SpreadingDrawCallbacks.class.getDeclaredField("delegate");
		delegate.setAccessible(true);
		delegate.set(s, choking);
		Person a = new Person(6, 0);
		handOver(s, a, true);
		Assert.assertEquals(List.of(a.model), drawn);
		Assert.assertEquals(1, s.depthNoSpacer);
		offsets.advance(0.02f);
		handOver(s, a, true);
		Assert.assertEquals(2, drawn.size());
		Assert.assertEquals("not tried again", 2, s.depthNoSpacer);
		Assert.assertEquals(1, cacheReads);
	}

	@Test
	public void inAnyOtherRendererEveryoneIsDrawnAsTheGameDoes()
	{
		// 117 HD draws all its people in one batch a spacer can't split: nothing is sent but them.
		List<Object[]> calls = new ArrayList<>();
		Map<String, Object> answers = new HashMap<>();
		answers.put("drawTemp", (Function<Object[], Object>) args ->
		{
			GameObject o = (GameObject) args[2];
			calls.add(new Object[]{o, o.getRenderable().getRenderMode(), args[3]});
			return null;
		});
		DrawCallbacks other = FakeMeshes.fake(DrawCallbacks.class, answers);
		SpreadingDrawCallbacks s = shim(other);
		Person a = new Person(6, 0);
		handOver(s, a, true);
		Assert.assertEquals(1, calls.size());
		Assert.assertSame(a.object, calls.get(0)[0]);
		Assert.assertEquals(Renderable.RENDERMODE_SORTED_NO_DEPTH, calls.get(0)[1]);
		Assert.assertSame(a.model, calls.get(0)[2]);
		Assert.assertEquals(0, cacheReads);
		Assert.assertEquals(0, s.depthDraws + s.depthNoSpacer);
	}
}
