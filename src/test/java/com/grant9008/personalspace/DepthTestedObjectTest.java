package com.grant9008.personalspace;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameObject;
import net.runelite.api.Renderable;
import org.junit.Assert;
import org.junit.Test;

/** The stand-in object a depth spacer, or someone lent another's object, is handed over with. */
public class DepthTestedObjectTest
{
	/** A proxy recording every call and answering each method with something of its own. */
	private static final class Recorder
	{
		final List<String> calls = new ArrayList<>();
		final Map<String, Object> answers = new HashMap<>();

		<T> T proxy(Class<T> type)
		{
			return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (p, m, args) ->
			{
				switch (m.getName())
				{
					case "equals":
						return p == args[0];
					case "hashCode":
						return System.identityHashCode(p);
					case "toString":
						return "recorder";
					default:
						calls.add(m.getName() + Arrays.toString(args == null ? new Object[0] : args));
						return answers.computeIfAbsent(m.toGenericString(), k -> answerFor(m));
				}
			}));
		}
	}

	/** Something distinct for a method to answer with. */
	private static Object answerFor(Method m)
	{
		Class<?> r = m.getReturnType();
		int seed = Math.abs(m.toGenericString().hashCode() % 1000) + 3;
		if (r == void.class)
		{
			return null;
		}
		if (r == int.class)
		{
			return seed;
		}
		if (r == long.class)
		{
			return (long) seed * 1_000_003L;
		}
		if (r == boolean.class)
		{
			return true;
		}
		if (r == String.class)
		{
			return m.getName();
		}
		if (r.isInterface())
		{
			return FakeMeshes.fake(r, new HashMap<>());
		}
		for (Constructor<?> c : r.getConstructors())
		{
			if (Arrays.stream(c.getParameterTypes()).allMatch(t -> t == int.class))
			{
				Object[] args = new Object[c.getParameterCount()];
				Arrays.fill(args, seed);
				try
				{
					return c.newInstance(args);
				}
				catch (ReflectiveOperationException e)
				{
					throw new AssertionError(e);
				}
			}
		}
		throw new AssertionError("no answer for " + m);
	}

	private static Object[] argsFor(Method m)
	{
		Object[] args = new Object[m.getParameterCount()];
		Class<?>[] types = m.getParameterTypes();
		for (int i = 0; i < args.length; i++)
		{
			args[i] = types[i] == int.class ? 17 + i : types[i] == String.class ? "text" : null;
		}
		return args;
	}

	private static List<Method> methodsOf(Class<?> type)
	{
		List<Method> out = new ArrayList<>();
		for (Method m : type.getMethods())
		{
			if (!Modifier.isStatic(m.getModifiers()) && !m.isDefault())
			{
				out.add(m);
			}
		}
		Assert.assertFalse(out.isEmpty());
		return out;
	}

	@Test
	public void wrappedEveryObjectCallButTheRenderableGoesToTheRealObject() throws Exception
	{
		Recorder inner = new Recorder();
		GameObject real = inner.proxy(GameObject.class);
		GameObject wrapped = new DepthTestedObject().wrap(real);
		inner.calls.clear();
		for (Method m : methodsOf(GameObject.class))
		{
			if (m.getName().equals("getRenderable"))
			{
				continue;
			}
			Object[] args = argsFor(m);
			inner.calls.clear();
			Object got = m.invoke(wrapped, args);
			Assert.assertEquals(m.getName(), Arrays.asList(m.getName() + Arrays.toString(args)), inner.calls);
			Assert.assertEquals(m.getName(), m.invoke(real, args), got);
		}
	}

	@Test
	public void wrappedItsRenderableIsTheRealOneButForTheRenderMode() throws Exception
	{
		Recorder body = new Recorder();
		Renderable person = body.proxy(Renderable.class);
		body.answers.put(Renderable.class.getMethod("getRenderMode").toGenericString(), Renderable.RENDERMODE_SORTED_NO_DEPTH);
		GameObject real = FakeMeshes.object(person, 1, 2);
		Renderable r = new DepthTestedObject().wrap(real).getRenderable();
		Assert.assertNotSame(person, r);
		Assert.assertNotEquals("not drawn as a person: the GPU plugin's opaque path",
			Renderable.RENDERMODE_SORTED_NO_DEPTH, r.getRenderMode());
		Assert.assertEquals(Renderable.RENDERMODE_SORTED, r.getRenderMode());
		for (Method m : methodsOf(Renderable.class))
		{
			if (m.getName().equals("getRenderMode"))
			{
				continue;
			}
			Object[] args = argsFor(m);
			body.calls.clear();
			Object got = m.invoke(r, args);
			Assert.assertEquals(m.getName(), Arrays.asList(m.getName() + Arrays.toString(args)), body.calls);
			Assert.assertEquals(m.getName(), m.invoke(person, args), got);
		}
	}

	@Test
	public void lentThePersonIsHandedOverAsTheyAreWhereTheyStand() throws Exception
	{
		Recorder lender = new Recorder();
		GameObject lendersObject = lender.proxy(GameObject.class);
		Renderable who = FakeMeshes.player(null);
		GameObject lent = new DepthTestedObject().lend(lendersObject, who, 640, 896, 2);
		Assert.assertSame("drawn as the person they are, with their own render mode", who, lent.getRenderable());
		Assert.assertEquals(Renderable.RENDERMODE_SORTED_NO_DEPTH, lent.getRenderable().getRenderMode());
		Assert.assertEquals(640, lent.getX());
		Assert.assertEquals(896, lent.getY());
		Assert.assertEquals(2, lent.getPlane());
		for (Method m : methodsOf(GameObject.class))
		{
			String n = m.getName();
			if (n.equals("getRenderable") || n.equals("getX") || n.equals("getY") || n.equals("getPlane"))
			{
				continue;
			}
			Object[] args = argsFor(m);
			lender.calls.clear();
			Object got = m.invoke(lent, args);
			Assert.assertEquals(n, Arrays.asList(n + Arrays.toString(args)), lender.calls);
			Assert.assertEquals(n, m.invoke(lendersObject, args), got);
		}
	}

	@Test
	public void lentAsOtherItIsTheLentPersonWhereTheyStandButNotAsAPerson() throws Exception
	{
		Recorder body = new Recorder();
		Renderable who = body.proxy(Renderable.class);
		body.answers.put(Renderable.class.getMethod("getRenderMode").toGenericString(), Renderable.RENDERMODE_SORTED_NO_DEPTH);
		GameObject lendersObject = FakeMeshes.object(FakeMeshes.player(null), 100, 200);
		DepthTestedObject o = new DepthTestedObject();
		GameObject stand = o.lendAsOther(lendersObject, who, 640, 896, 2);
		Assert.assertNotSame(who, stand.getRenderable());
		Assert.assertEquals(Renderable.RENDERMODE_SORTED, stand.getRenderable().getRenderMode());
		Assert.assertEquals(640, stand.getX());
		Assert.assertEquals(896, stand.getY());
		Assert.assertEquals(2, stand.getPlane());
		body.calls.clear();
		Object model = stand.getRenderable().getModel();
		Assert.assertEquals("the lent person's own model", Arrays.asList("getModel[]"), body.calls);
		Assert.assertSame(who.getModel(), model);
		// and the next call, lending them as they are, hands them over as the person
		Assert.assertSame(who, o.lend(lendersObject, who, 640, 896, 2).getRenderable());
	}

	@Test
	public void reusedEachCallForgetsTheLastOne()
	{
		DepthTestedObject o = new DepthTestedObject();
		Renderable a = FakeMeshes.player(null);
		Renderable b = FakeMeshes.player(null);
		GameObject first = FakeMeshes.object(a, 100, 200);
		GameObject second = FakeMeshes.object(b, 300, 400);
		o.lend(first, b, 5, 6, 1);
		o.wrap(second);
		Assert.assertEquals(300, o.getX());
		Assert.assertEquals(400, o.getY());
		Assert.assertEquals(0, o.getPlane());
		Assert.assertEquals(Renderable.RENDERMODE_SORTED, o.getRenderable().getRenderMode());
		o.lend(first, b, 5, 6, 1);
		Assert.assertSame(b, o.getRenderable());
		Assert.assertEquals(5, o.getX());
		o.wrap(first);
		Assert.assertEquals(100, o.getX());
		Assert.assertNotSame(a, o.getRenderable());
	}

	@Test
	public void aPersonWithoutARenderableHasNone()
	{
		GameObject real = FakeMeshes.object(null, 1, 2);
		Assert.assertNull(new DepthTestedObject().wrap(real).getRenderable());
		Assert.assertNull(new DepthTestedObject().lend(real, null, 1, 2, 0).getRenderable());
	}
}
