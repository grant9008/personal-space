package com.grant9008.personalspace;

import net.runelite.api.ChatMessageType;
import org.junit.Assert;
import org.junit.Test;

public class ChatHiderTest
{
	@Test
	public void onlyPublicChatIsEverDropped()
	{
		Assert.assertTrue(ChatHider.isPublic(ChatMessageType.PUBLICCHAT));
		Assert.assertTrue(ChatHider.isPublic(ChatMessageType.AUTOTYPER));
		Assert.assertFalse("private messages stay", ChatHider.isPublic(ChatMessageType.PRIVATECHAT));
		Assert.assertFalse("clan chat stays", ChatHider.isPublic(ChatMessageType.CLAN_CHAT));
		Assert.assertFalse("friends chat stays", ChatHider.isPublic(ChatMessageType.FRIENDSCHAT));
		Assert.assertFalse("game messages stay", ChatHider.isPublic(ChatMessageType.GAMEMESSAGE));
	}

	@Test
	public void chatFurtherThanTheRangeIsHidden()
	{
		Assert.assertFalse("off", ChatHider.tooFar(14, 0));
		Assert.assertFalse("within range", ChatHider.tooFar(5, 5));
		Assert.assertTrue("one past it", ChatHider.tooFar(6, 5));
		Assert.assertFalse("speaker never found: shown", ChatHider.tooFar(null, 5));
		Assert.assertEquals("a diagonal step counts one", 3, ChatHider.tilesApart(10, 10, 13, 12));
		Assert.assertEquals(7, ChatHider.tilesApart(3200, 3200, 3200, 3193));
	}

	@Test
	public void namesMatchWhateverIconsAndSpacesTheyCarry()
	{
		Assert.assertEquals(ChatHider.standard("Casino Bot"), ChatHider.standard("<img=41>casino\u00a0bot"));
	}
}
