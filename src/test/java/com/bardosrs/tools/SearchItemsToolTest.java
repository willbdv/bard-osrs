package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.ItemStack;
import com.bardosrs.state.ItemsSnapshot;
import com.bardosrs.state.TestProfiles;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import org.junit.Test;

public class SearchItemsToolTest
{
	private static final Gson GSON = new Gson();

	private final FakeAccountSource account = new FakeAccountSource();
	private final SearchItemsTool tool = new SearchItemsTool(account);

	private static JsonObject args(String json)
	{
		return GSON.fromJson(json, JsonObject.class);
	}

	private JsonObject run(String json)
	{
		return GSON.fromJson(tool.run(args(json)), JsonObject.class);
	}

	@Test
	public void findsItemsInEachPlaceInOneBatchedCall()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		JsonObject result = run("{\"queries\":[\"molten glass\",\"soda ash\",\"dragon\",\"unicorn\"]}");

		JsonArray results = result.getAsJsonArray("results");
		assertEquals(4, results.size());
		JsonObject glass = results.get(0).getAsJsonObject().getAsJsonArray("matches").get(0).getAsJsonObject();
		assertEquals("Molten glass", glass.get("item").getAsString());
		assertEquals("inventory", glass.get("where").getAsString());
		assertEquals(14, glass.get("quantity").getAsLong());
		JsonObject ash = results.get(1).getAsJsonObject().getAsJsonArray("matches").get(0).getAsJsonObject();
		assertEquals("bank", ash.get("where").getAsString());
		assertEquals(300, ash.get("quantity").getAsLong());
		assertEquals("equipped", results.get(2).getAsJsonObject().getAsJsonArray("matches").get(0).getAsJsonObject()
			.get("where").getAsString());
		assertEquals(0, results.get(3).getAsJsonObject().getAsJsonArray("matches").size());
		assertEquals("25 minutes ago", result.get("bank_last_seen").getAsString());
	}

	@Test
	public void sayssoWhenTheBankHasNotBeenOpened()
	{
		account.profile = profileWith(ItemsSnapshot.of(Collections.singletonList(new ItemStack(1, "Coins", 5)),
			Collections.emptyList(), null, 0));
		JsonObject result = run("{\"queries\":[\"coins\"]}");
		assertTrue(result.get("note").getAsString().contains("bank has not been opened"));
		assertFalse(result.has("bank_last_seen"));
	}

	@Test
	public void acceptsASingleQueryStringToo()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		assertEquals(1, run("{\"query\":\"coins\"}").getAsJsonArray("results").size());
		assertEquals(1, run("{\"queries\":\"coins\"}").getAsJsonArray("results").size());
	}

	@Test
	public void limitsHowManyQueriesAreAnswered()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		StringBuilder json = new StringBuilder("{\"queries\":[");
		for (int i = 0; i < 40; i++)
		{
			json.append(i == 0 ? "" : ",").append("\"item ").append(i).append("\"");
		}
		json.append("]}");
		assertEquals(20, run(json.toString()).getAsJsonArray("results").size());
	}

	@Test
	public void rejectsAnEmptyList()
	{
		account.profile = TestProfiles.ironmanZerkerWithItems();
		for (String json : new String[]{"{}", "{\"queries\":[]}", "{\"queries\":[\"  \"]}"})
		{
			try
			{
				run(json);
				fail("expected rejection for " + json);
			}
			catch (IllegalArgumentException e)
			{
				assertTrue(e.getMessage(), e.getMessage().contains("queries"));
			}
		}
	}

	@Test
	public void refusesWhenItemsAreNotShared()
	{
		account.profile = TestProfiles.ironmanZerker();
		try
		{
			run("{\"queries\":[\"coins\"]}");
			fail("expected a ToolException");
		}
		catch (ToolException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("not shared their items"));
		}
	}

	@Test
	public void refusesWhenTheAccountCannotBeRead()
	{
		for (AccountProfile profile : new AccountProfile[]{
			AccountProfile.notShared(), AccountProfile.notLoggedIn(), AccountProfile.unavailable()})
		{
			account.profile = profile;
			try
			{
				run("{\"queries\":[\"coins\"]}");
				fail("expected a ToolException for " + profile.status);
			}
			catch (ToolException e)
			{
				assertEquals(profile.unavailableReason(), e.getMessage());
			}
		}
	}

	@Test
	public void summariesDescribeTheCall()
	{
		assertEquals("item check", tool.summary(args("{}")));
		assertEquals("item check: coins", tool.summary(args("{\"queries\":[\"coins\"]}")));
		assertEquals("item check: coins and 2 more", tool.summary(args("{\"queries\":[\"coins\",\"pipe\",\"glass\"]}")));
	}

	private static AccountProfile profileWith(ItemsSnapshot items)
	{
		return com.bardosrs.state.AccountProfile.ready(net.runelite.api.vars.AccountType.IRONMAN, true,
			TestProfiles.levels(net.runelite.api.Skill.HITPOINTS, 40), new java.util.EnumMap<>(net.runelite.api.Skill.class),
			0, TestProfiles.quests(), com.bardosrs.state.AccountBuild.AUTO, items);
	}
}
