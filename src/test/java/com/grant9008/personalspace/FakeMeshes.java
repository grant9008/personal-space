package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;

/**
 * Test stand-ins for the game's models, people and objects, answering only what they're told to and
 * the type's zero for anything else.
 */
final class FakeMeshes
{
	private FakeMeshes()
	{
	}

	/** A proxy of {@code type}: each method named in {@code answers} returns that (a Function is called with the args). */
	@SuppressWarnings("unchecked")
	static <T> T fake(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(FakeMeshes.class.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) ->
		{
			if (answers.containsKey(m.getName()))
			{
				Object v = answers.get(m.getName());
				return v instanceof Function ? ((Function<Object[], Object>) v).apply(args) : v;
			}
			switch (m.getName())
			{
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
				case "toString":
					return "fake " + type.getSimpleName();
				default:
					return zero(m.getReturnType());
			}
		});
	}

	static Object zero(Class<?> r)
	{
		if (r == boolean.class)
		{
			return false;
		}
		if (r == byte.class)
		{
			return (byte) 0;
		}
		if (r == short.class)
		{
			return (short) 0;
		}
		if (r == char.class)
		{
			return (char) 0;
		}
		if (r == int.class)
		{
			return 0;
		}
		if (r == long.class)
		{
			return 0L;
		}
		if (r == float.class)
		{
			return 0f;
		}
		if (r == double.class)
		{
			return 0d;
		}
		return null;
	}

	/** Mesh arrays: vertices and faces, with optional per-face transparencies and textures. */
	static final class Mesh
	{
		float[] x;
		float[] y;
		float[] z;
		int[] a;
		int[] b;
		int[] c;
		byte[] alphas;
		short[] textures;
		byte[] priorities;

		Mesh copy()
		{
			Mesh m = new Mesh();
			m.x = x;
			m.y = y;
			m.z = z;
			m.a = a;
			m.b = b;
			m.c = c;
			m.alphas = alphas;
			m.textures = textures;
			m.priorities = priorities;
			return m;
		}
	}

	/**
	 * A mesh of {@code faces} triangles: face f has corners (f, f+1, f+2) of a strip of vertices
	 * spread out around (0, 0, 0), so none of them is of no size.
	 */
	static Mesh strip(int faces)
	{
		Mesh m = new Mesh();
		int verts = faces + 2;
		m.x = new float[verts];
		m.y = new float[verts];
		m.z = new float[verts];
		for (int v = 0; v < verts; v++)
		{
			m.x[v] = (v % 2) * 20 - 10;
			m.y[v] = -v * 10;
			m.z[v] = v % 3;
		}
		m.a = new int[faces];
		m.b = new int[faces];
		m.c = new int[faces];
		for (int f = 0; f < faces; f++)
		{
			m.a[f] = f;
			m.b[f] = f + 1;
			m.c[f] = f + 2;
		}
		return m;
	}

	/**
	 * Model data behaving as the client's does: {@code shallowCopy} shares every array (and marks
	 * the vertices shared), {@code cloneVertices} copies shared vertices (or returns itself if they
	 * are its own), {@code light} makes a model on the same vertices with face colours of its own.
	 */
	static ModelData modelData(Mesh mesh, boolean verticesShared)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getFaceCount", mesh.a.length);
		a.put("getVerticesCount", mesh.x.length);
		a.put("getVerticesX", mesh.x);
		a.put("getVerticesY", mesh.y);
		a.put("getVerticesZ", mesh.z);
		a.put("getFaceIndices1", mesh.a);
		a.put("getFaceIndices2", mesh.b);
		a.put("getFaceIndices3", mesh.c);
		a.put("getFaceTransparencies", mesh.alphas);
		a.put("getFaceTextures", mesh.textures);
		a.put("shallowCopy", (Function<Object[], Object>) args -> modelData(mesh.copy(), true));
		ModelData[] self = new ModelData[1];
		a.put("cloneVertices", (Function<Object[], Object>) args ->
		{
			if (!verticesShared)
			{
				return self[0];
			}
			Mesh m = mesh.copy();
			m.x = mesh.x.clone();
			m.y = mesh.y.clone();
			m.z = mesh.z.clone();
			return modelData(m, false);
		});
		a.put("light", (Function<Object[], Object>) args -> model(mesh, 1000));
		self[0] = fake(ModelData.class, a);
		return self[0];
	}

	/** A lit model on {@code mesh}'s arrays, every face lit {@code color}, with priorities if the mesh has them. */
	static Model model(Mesh mesh, int color)
	{
		int faces = mesh.a.length;
		int[] c1 = new int[faces];
		int[] c2 = new int[faces];
		int[] c3 = new int[faces];
		java.util.Arrays.fill(c1, color);
		java.util.Arrays.fill(c2, color);
		java.util.Arrays.fill(c3, color);
		Map<String, Object> a = new HashMap<>();
		a.put("getFaceCount", faces);
		a.put("getVerticesCount", mesh.x.length);
		a.put("getVerticesX", mesh.x);
		a.put("getVerticesY", mesh.y);
		a.put("getVerticesZ", mesh.z);
		a.put("getFaceIndices1", mesh.a);
		a.put("getFaceIndices2", mesh.b);
		a.put("getFaceIndices3", mesh.c);
		a.put("getFaceColors1", c1);
		a.put("getFaceColors2", c2);
		a.put("getFaceColors3", c3);
		a.put("getFaceTransparencies", mesh.alphas);
		a.put("getFaceTextures", mesh.textures);
		a.put("getFaceRenderPriorities", mesh.priorities);
		a.put("getRadius", 60);
		a.put("getDiameter", 121);
		return fake(Model.class, a);
	}

	/** A player as the game hands them over: {@link Renderable#RENDERMODE_SORTED_NO_DEPTH}, with this model. */
	static Player player(Model model)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getRenderMode", Renderable.RENDERMODE_SORTED_NO_DEPTH);
		a.put("getModel", model);
		a.put("getModelHeight", 200);
		return fake(Player.class, a);
	}

	/** The object the game draws someone with, at (x, y) on plane 0. */
	static GameObject object(Renderable renderable, int x, int y)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getRenderable", renderable);
		a.put("getX", x);
		a.put("getY", y);
		return fake(GameObject.class, a);
	}

	/** The main world's scene. */
	static Scene scene()
	{
		return fake(Scene.class, new HashMap<>());
	}
}
