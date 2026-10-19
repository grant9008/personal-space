package com.grant9008.personalspace;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import org.junit.Assert;
import org.junit.Test;

/** Who counts as wearing a cape: anything at all in the cape slot, and nothing anywhere else. */
public class WearsCapeTest
{
	/** A player's look, wearing these items (item ids) in these slots and nothing else. */
	private static PlayerComposition wearing(Map<KitType, Integer> items)
	{
		return (PlayerComposition) Proxy.newProxyInstance(PlayerComposition.class.getClassLoader(), new Class<?>[]{PlayerComposition.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getEquipmentId"))
				{
					return items.getOrDefault((KitType) args[0], -1);
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? false : type == int.class ? 0 : null;
			});
	}

	@Test
	public void anythingInTheCapeSlotIsACape()
	{
		Map<KitType, Integer> items = new EnumMap<>(KitType.class);
		Assert.assertFalse("nothing on", PersonalSpacePlugin.wearsCape(wearing(items)));
		items.put(KitType.TORSO, 1121);
		items.put(KitType.AMULET, 1704);
		Assert.assertFalse("a body and an amulet, no cape", PersonalSpacePlugin.wearsCape(wearing(items)));
		items.put(KitType.CAPE, 9795);
		Assert.assertTrue("a smithing cape", PersonalSpacePlugin.wearsCape(wearing(items)));
		items.put(KitType.CAPE, 10499);
		Assert.assertTrue("an Ava's accumulator", PersonalSpacePlugin.wearsCape(wearing(items)));
		Assert.assertFalse("a look the game hasn't sent yet", PersonalSpacePlugin.wearsCape(null));
	}

	@Test
	public void anEntryWearsNoCapeUnlessItSaysSo()
	{
		Assert.assertFalse(new StackSpreader.Entry(1, 2L, false, 0, true).cape);
		Assert.assertFalse(new StackSpreader.Entry(1, 2L, false).cape);
		Assert.assertTrue(new StackSpreader.Entry(1, 2L, false, 0, true, true).cape);
	}
}
