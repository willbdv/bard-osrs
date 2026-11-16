package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.TestProfiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class QuestToolTest
{
	private static final Gson GSON = new Gson();

	private final FakeAccountSource account = new FakeAccountSource();
	private final QuestTool tool = new QuestTool(account);

	private JsonObject run(String json)
	{
		return GSON.fromJson(tool.run(GSON.fromJson(json, JsonObject.class)), JsonObject.class);
	}

	@Test
	public void overviewHasCountsPointsAndQuestsInProgress()
	{
		account.profile = TestProfiles.ironmanZerker();
		JsonObject result = run("{}");
		assertEquals(5, result.get("quest_points").getAsInt());
		assertEquals(1, result.getAsJsonObject("counts").get("finished").getAsInt());
		assertEquals(2, result.getAsJsonObject("counts").get("not_started").getAsInt());
		assertEquals("Dragon Slayer I", result.getAsJsonArray("in_progress").get(0).getAsString());
	}

	@Test
	public void checkingOneQuestByPartOfItsName()
	{
		account.profile = TestProfiles.ironmanZerker();
		JsonObject result = run("{\"name\":\"slayer\"}");
		assertEquals(2, result.getAsJsonArray("matches").size());
		JsonObject first = result.getAsJsonArray("matches").get(0).getAsJsonObject();
		assertEquals("Dragon Slayer I", first.get("quest").getAsString());
		assertEquals("in_progress", first.get("state").getAsString());
		assertTrue(run("{\"name\":\"zzz\"}").has("note"));
	}

	@Test
	public void listingByState()
	{
		account.profile = TestProfiles.ironmanZerker();
		JsonObject result = run("{\"state\":\"not_started\"}");
		assertEquals(2, result.get("count").getAsInt());
		assertEquals("Dragon Slayer II", result.getAsJsonArray("quests").get(0).getAsString());
		assertEquals(1, run("{\"state\":\"Finished\"}").get("count").getAsInt());
		assertEquals(1, run("{\"state\":\"in progress\"}").get("count").getAsInt());
	}

	@Test
	public void anUnknownStateIsRejectedWithTheValidOnes()
	{
		account.profile = TestProfiles.ironmanZerker();
		try
		{
			run("{\"state\":\"maybe\"}");
			fail("expected rejection");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("finished, in_progress, not_started"));
		}
	}

	@Test
	public void refusesWhenTheAccountIsNotAvailable()
	{
		for (AccountProfile profile : new AccountProfile[]{
			AccountProfile.notShared(), AccountProfile.notLoggedIn(), AccountProfile.unavailable()})
		{
			account.profile = profile;
			try
			{
				run("{}");
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
		assertEquals("quest overview", tool.summary(GSON.fromJson("{}", JsonObject.class)));
		assertEquals("quest check: Dragon Slayer", tool.summary(GSON.fromJson("{\"name\":\"Dragon Slayer\"}", JsonObject.class)));
		assertEquals("quests: not started", tool.summary(GSON.fromJson("{\"state\":\"not_started\"}", JsonObject.class)));
	}
}
