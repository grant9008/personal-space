package com.grant9008.personalspace;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * The plugin's game tick, in and out of raids and group bosses' rooms, with Pause in fights and
 * raids on: inside, everyone goes straight back to where they really stand, the whole time and
 * whether you're fighting or not, and the Copy report says why; in a raid's lobby or outside a
 * boss's room, crowds are spread as before. The game is stood in for with only what the tick
 * asks of it, as in CombatWalkTest.
 */
public class RaidPauseTest
{
	/** Where you and a teammate stand in the scene: the middle of one tile, the same one. */
	private static final int SCENE_TILE = 52;
	private static final int MATE = 1;
	/** How far the teammate was drawn from the middle of the tile before the tick: one spot of a spread crowd. */
	private static final int SPOT = 96;

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<String, Object> settings = new HashMap<>();
	/** Where you stand. In an instance, where the part of the map you stand on was copied from. */
	private WorldPoint where = new WorldPoint(3165, 3487, 0);
	private boolean instance;
	/** Your health bar is showing: something just hit you. */
	private boolean hit;

	private PersonalSpacePlugin plugin;
	private OffsetTable offsets;
	private LevelFilter filter;

	@Test
	public void insideEachRaidEveryoneIsWhereTheyReallyStand()
	{
		// The Chambers of Xeric, in the first room: no fight yet.
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		assertPaused("IN_RAID", "the Chambers of Xeric");

		reset();
		// The Theatre of Blood: under way, in its instance (at the Maiden, say).
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		instanceOf(new WorldPoint(3180, 4440, 0));
		assertPaused("IN_RAID", "the Theatre of Blood");

		reset();
		// ...and dead, watching the rest of the room.
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 3);
		instanceOf(new WorldPoint(3180, 4440, 0));
		assertPaused("IN_RAID", "dead in the Theatre of Blood");

		reset();
		// The Tombs of Amascut, in the Nexus between paths.
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		instanceOf(new WorldPoint(3550, 5160, 0));
		assertPaused("IN_RAID", "the Tombs of Amascut");
	}

	@Test
	public void insideTheTombsWhateverItsPartyStatusSays()
	{
		// The game's own scripts only read the Tombs' party status in the entrance hall. Inside, the
		// room you stand in says it: the Nexus, Zebak's room, the Wardens, the chest room.
		instanceOf(new WorldPoint(3550, 5160, 0));
		assertPaused("IN_RAID", "the Nexus, no party status");
		reset();
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		instanceOf(new WorldPoint(3935, 5405, 0));
		assertPaused("IN_RAID", "Zebak's room, party status 1");
		reset();
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 4);
		instanceOf(new WorldPoint(3805, 5150, 0));
		assertPaused("IN_RAID", "the Wardens, party status 4");
		reset();
		instanceOf(new WorldPoint(3680, 5150, 0));
		assertPaused("IN_RAID", "the chest room");

		reset();
		// The same spot but not in an instance: the game never puts anyone there, so nothing.
		where = new WorldPoint(3550, 5160, 0);
		assertSpread("the Nexus's spot, not in an instance");
	}

	@Test
	public void raidLobbiesStaySpreadUntilYouStepInside()
	{
		// Mount Quidamortem, by the bank; then down the steps, into the first room.
		where = new WorldPoint(1255, 3560, 0);
		assertSpread("Mount Quidamortem");
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		instanceOf(new WorldPoint(3310, 5280, 0));
		assertPaused("IN_RAID", "the Chambers of Xeric's first room");

		reset();
		// Ver Sinhaza in a party, before the raid; then as it starts, with "Step inside now!"; then inside.
		where = new WorldPoint(3670, 3219, 0);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		assertSpread("Ver Sinhaza, in a party");
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		assertSpread("Ver Sinhaza, the raid under way");
		instanceOf(new WorldPoint(3180, 4440, 0));
		assertPaused("IN_RAID", "the Theatre of Blood, stepped inside");

		reset();
		// The Tombs' entrance hall, the same.
		where = new WorldPoint(3360, 9120, 0);
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		assertSpread("the Tombs' entrance hall, in a party");
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		assertSpread("the Tombs' entrance hall, the raid under way");
		instanceOf(new WorldPoint(3550, 5160, 0));
		assertPaused("IN_RAID", "the Tombs of Amascut, stepped inside");

		reset();
		// Out again, the raid over: spread at once.
		where = new WorldPoint(3670, 3219, 0);
		assertSpread("Ver Sinhaza, back out");
	}

	@Test
	public void groupBossRoomsPause()
	{
		// Nex's arena, and the Nightmare's, an instance copied from its own part of the map.
		where = new WorldPoint(2925, 5203, 0);
		assertPaused("IN_BOSS_ROOM", "Nex");

		reset();
		instanceOf(new WorldPoint(3872, 9951, 3));
		assertPaused("IN_BOSS_ROOM", "the Nightmare");

		reset();
		// General Graardor's room, but not his stronghold outside it.
		where = new WorldPoint(2870, 5360, 2);
		assertPaused("IN_BOSS_ROOM", "General Graardor");
		reset();
		where = new WorldPoint(2850, 5340, 2);
		assertSpread("the Bandos stronghold");
	}

	@Test
	public void switchedOffRaidsAndBossRoomsAreSpreadToo()
	{
		// The one switch decides: on, paused in the raid; off, spread there like anywhere else.
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		assertPaused("IN_RAID", "the Chambers of Xeric, switch on");
		settings.put("pauseInCombat", false);
		assertSpread("the Chambers of Xeric, switch off");

		reset();
		where = new WorldPoint(2925, 5203, 0);
		assertPaused("IN_BOSS_ROOM", "Nex, switch on");
		settings.put("pauseInCombat", false);
		assertSpread("Nex, switch off");
	}

	@Test
	public void fightingInARaidStillSaysTheRaid()
	{
		// Hit in the raid: "In a raid" the whole time, not "You are in combat" on and off between fights.
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		hit = true;
		assertPaused("IN_RAID", "hit in the Chambers of Xeric");

		reset();
		// Anywhere else, a fight pauses as it always has.
		hit = true;
		assertPaused("IN_COMBAT", "hit at the Grand Exchange");
	}

	@Test
	public void copyReportSaysWhy()
	{
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		Snapshot s = reportAfterATick();
		Assert.assertTrue(StatusSummary.report(s), StatusSummary.report(s).contains("Safety: In a raid\n"));
		Assert.assertEquals(StatusSummary.Level.PAUSED, StatusSummary.headline(s).level);
		Assert.assertTrue(StatusSummary.headline(s).detail.startsWith("In a raid."));

		reset();
		where = new WorldPoint(2925, 5203, 0);
		s = reportAfterATick();
		Assert.assertTrue(StatusSummary.report(s), StatusSummary.report(s).contains("Safety: In a group boss room\n"));
		Assert.assertTrue(StatusSummary.report(s).contains("pause in fights and raids yes"));
	}

	// ---- the game, stood in for -------------------------------------------------------------

	private void reset()
	{
		varbits.clear();
		settings.clear();
		where = new WorldPoint(3165, 3487, 0);
		instance = false;
		hit = false;
	}

	/** In an instance, standing on a part copied from this spot in the game's map. */
	private void instanceOf(WorldPoint template)
	{
		instance = true;
		where = template;
	}

	/** One game tick with a teammate drawn in a spot beside you: what the plugin decided, and where they're drawn now. */
	private Object[] tick()
	{
		plugin = new PersonalSpacePlugin();
		List<Player> players = new ArrayList<>();
		Map<String, Object> w = new HashMap<>();
		w.put("getId", WorldView.TOPLEVEL);
		w.put("isInstance", instance);
		// Your chunk of the instance was copied from the one holding this spot, unturned.
		int[][][] chunks = new int[4][13][13];
		chunks[0][SCENE_TILE / 8][SCENE_TILE / 8] = (where.getPlane() << 24) | ((where.getX() >> 3) << 14) | ((where.getY() >> 3) << 3);
		w.put("getInstanceTemplateChunks", chunks);
		w.put("getBaseX", instance ? 7000 : where.getX() - SCENE_TILE);
		w.put("getBaseY", instance ? 7000 : where.getY() - SCENE_TILE);
		w.put("getPlane", instance ? 0 : where.getPlane());
		w.put("getSizeX", 104);
		w.put("getSizeY", 104);
		w.put("getScene", FakeMeshes.fake(Scene.class, Map.of("getTiles", new Tile[4][104][104])));
		w.put("players", set(players));
		w.put("worldViews", set(new ArrayList<>()));
		WorldView wv = FakeMeshes.fake(WorldView.class, w);
		// In an instance, the part of the map copied to your chunk keeps your spot within it.
		int sceneX = instance ? (where.getX() & 7) + SCENE_TILE / 8 * 8 : SCENE_TILE;
		int sceneY = instance ? (where.getY() & 7) + SCENE_TILE / 8 * 8 : SCENE_TILE;
		WorldPoint real = instance ? new WorldPoint(7000 + sceneX, 7000 + sceneY, 0) : where;
		Player you = person(0, wv, sceneX, sceneY, real);
		players.add(you);
		players.add(person(MATE, wv, sceneX, sceneY, real));

		Map<String, Object> c = new HashMap<>();
		c.put("isClientThread", true);
		c.put("getGameState", GameState.LOGGED_IN);
		c.put("getLocalPlayer", you);
		c.put("getTopLevelWorldView", wv);
		c.put("getWorldView", wv);
		c.put("getWorldType", EnumSet.noneOf(WorldType.class));
		c.put("getVarbitValue", (Function<Object[], Object>) args -> varbits.getOrDefault((Integer) args[0], 0));
		c.put("getCameraX", sceneX * 128 + 64);
		c.put("getCameraY", sceneY * 128 + 64 - 1500);
		Client client = FakeMeshes.fake(Client.class, c);

		offsets = get(plugin, "offsets");
		filter = new LevelFilter(client);
		set(plugin, "client", client);
		set(plugin, "config", config());
		set(plugin, "levelFilter", filter);
		set(plugin, "renderCallbackManager", new RenderCallbackManager());
		set(plugin, "probe", new StackProbe(client, offsets, get(plugin, "stacks")));

		// Spread before the tick: the teammate is drawn in a spot beside you.
		offsets.setTarget(MATE, SPOT, 0);
		offsets.advance(5f);
		Assert.assertEquals(SPOT, offsets.dx(MATE));

		plugin.onGameTick(new GameTick());
		Object gate = get(plugin, "gate");
		return new Object[]{gate, offsets.dx(MATE)};
	}

	private void assertPaused(String gate, String place)
	{
		Object[] after = tick();
		Assert.assertEquals(place, gate, String.valueOf(after[0]));
		Assert.assertEquals(place + ": back where they really stand", 0, after[1]);
		Assert.assertFalse(place, filter.enabled);
	}

	private void assertSpread(String place)
	{
		Object[] after = tick();
		Assert.assertEquals(place, "SAFE", String.valueOf(after[0]));
		Assert.assertEquals(place + ": still in their spot", SPOT, after[1]);
		Assert.assertTrue(place, filter.enabled);
	}

	/** After a tick, the sidebar's report as it would be built from what the tick found. */
	private Snapshot reportAfterATick()
	{
		Object[] after = tick();
		Snapshot s = new Snapshot();
		s.active = true;
		s.pauseInCombat = true;
		s.gate = (Snapshot.Gate) after[0];
		return s;
	}

	private Player person(int id, WorldView wv, int sceneX, int sceneY, WorldPoint real)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getId", id);
		a.put("getName", "Player " + id);
		a.put("getLocalLocation", new LocalPoint(sceneX * 128 + 64, sceneY * 128 + 64, WorldView.TOPLEVEL));
		a.put("getWorldLocation", real);
		a.put("getWorldView", wv);
		a.put("getAnimation", -1);
		a.put("getHealthRatio", id == 0 && hit ? 20 : -1);
		a.put("getCombatLevel", 126);
		return FakeMeshes.fake(Player.class, a);
	}

	/** The plugin's settings: their defaults, but for those set in {@link #settings}. */
	private PersonalSpaceConfig config()
	{
		return (PersonalSpaceConfig) Proxy.newProxyInstance(PersonalSpaceConfig.class.getClassLoader(), new Class<?>[]{PersonalSpaceConfig.class},
			(proxy, method, args) ->
			{
				if (settings.containsKey(method.getName()))
				{
					return settings.get(method.getName());
				}
				if (method.isDefault())
				{
					return MethodHandles.privateLookupIn(PersonalSpaceConfig.class, MethodHandles.lookup())
						.unreflectSpecial(method, PersonalSpaceConfig.class).bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
				}
				return FakeMeshes.zero(method.getReturnType());
			});
	}

	private static <T> IndexedObjectSet<T> set(List<?> items)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("iterator", (Function<Object[], Object>) args -> new ArrayList<>(items).iterator());
		a.put("byIndex", (Function<Object[], Object>) args -> null);
		@SuppressWarnings("unchecked")
		IndexedObjectSet<T> s = FakeMeshes.fake(IndexedObjectSet.class, a);
		return s;
	}

	private static void set(Object target, String name, Object value)
	{
		try
		{
			Field f = PersonalSpacePlugin.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Object target, String name)
	{
		try
		{
			Field f = PersonalSpacePlugin.class.getDeclaredField(name);
			f.setAccessible(true);
			return (T) f.get(target);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}
}
