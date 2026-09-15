package com.grant9008.personalspace;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev entrypoint: runs a full RuneLite client with the plugin loaded, for testing in-game.
 * Started by "Run Dev Client.bat" (which runs {@code gradlew run}). The real unit tests
 * live in {@link StackSpreaderTest}.
 */
public class PersonalSpacePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PersonalSpacePlugin.class);
		RuneLite.main(args);
	}
}
