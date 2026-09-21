package com.grant9008.personalspace;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.imageio.ImageIO;

/**
 * Paints the sidebar, with its defaults and a lively status, to a PNG for the README. A developer tool,
 * not part of the plugin: run its main with the output path and the width (242) as arguments.
 */
public class RenderSidebar
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		PersonalSpaceConfig config = (PersonalSpaceConfig) Proxy.newProxyInstance(
			PersonalSpaceConfig.class.getClassLoader(), new Class<?>[]{PersonalSpaceConfig.class},
			(proxy, method, margs) ->
			{
				if (method.isDefault())
				{
					return MethodHandles.privateLookupIn(PersonalSpaceConfig.class, MethodHandles.lookup())
						.unreflectSpecial(method, PersonalSpaceConfig.class).bindTo(proxy).invokeWithArguments(margs == null ? new Object[0] : margs);
				}
				if (method.getName().equals("toString"))
				{
					return "config";
				}
				return null;
			});
		PersonalSpacePanel panel = new PersonalSpacePanel(null, config);

		Snapshot s = new Snapshot();
		set(s, "active", true);
		set(s, "gate", Snapshot.Gate.SAFE);
		set(s, "nearby", 24);
		set(s, "still", 21);
		set(s, "stackedTiles", 4);
		set(s, "moving", 18);
		set(s, "renderer", "GPU");
		set(s, "hooked", true);
		set(s, "mode", PersonalSpaceConfig.Mode.SPREAD);
		set(s, "nudgedDrawsPerSec", 200.0);
		set(s, "revealedDrawsPerSec", 600.0);
		set(s, "pluginVersion", PersonalSpacePlugin.VERSION);
		set(s, "yourShape", "You're in a row along the counter or wall, kept close by Auto-space");
		set(s, "spacing", 256);
		set(s, "maxStack", 5);
		set(s, "smallGroupsClose", true);
		set(s, "pauseInCombat", true);
		set(s, "hover", true);
		set(s, "hoverArrow", true);
		for (Method m : PersonalSpacePanel.class.getDeclaredMethods())
		{
			if (m.getName().equals("update") && m.getParameterCount() == 1)
			{
				m.setAccessible(true);
				m.invoke(panel, s);
			}
		}

		int width = Integer.parseInt(args[1]);
		panel.setSize(width, 4000);
		layout(panel);
		int height = contentHeight(panel);
		panel.setSize(width, height);
		layout(panel);

		int scale = 2;
		BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		panel.printAll(g);
		g.dispose();
		ImageIO.write(image, "png", new File(args[0]));
		System.out.println("wrote " + args[0] + " " + width + "x" + height);
	}

	/** The height everything needs when nothing scrolls: the tallest chain of preferred sizes. */
	private static int contentHeight(Component c)
	{
		if (c instanceof javax.swing.JScrollPane)
		{
			Component view = ((javax.swing.JScrollPane) c).getViewport().getView();
			return view == null ? 0 : view.getPreferredSize().height;
		}
		if (!(c instanceof Container))
		{
			return c.getPreferredSize().height;
		}
		Container container = (Container) c;
		if (container.getLayout() instanceof java.awt.BorderLayout)
		{
			int total = 0;
			for (Component child : container.getComponents())
			{
				total += contentHeight(child);
			}
			return total;
		}
		return c.getPreferredSize().height;
	}

	private static void layout(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layout(child);
			}
		}
	}

	private static void set(Object o, String field, Object value)
	{
		try
		{
			Field f = o.getClass().getDeclaredField(field);
			f.setAccessible(true);
			Class<?> type = f.getType();
			if (value instanceof Number)
			{
				Number n = (Number) value;
				value = type == int.class ? (Object) n.intValue() : type == long.class ? (Object) n.longValue()
					: type == float.class ? (Object) n.floatValue() : type == double.class ? (Object) n.doubleValue() : value;
			}
			f.set(o, value);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			System.out.println("couldn't set " + field + ": " + e);
		}
	}

}
