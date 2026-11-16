package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class XpCalculatorToolTest
{
	private static final Gson GSON = new Gson();
	private final XpCalculatorTool tool = new XpCalculatorTool();

	private static JsonObject args(String json)
	{
		return GSON.fromJson(json, JsonObject.class);
	}

	private JsonObject run(String json)
	{
		return GSON.fromJson(tool.run(args(json)), JsonObject.class);
	}

	@Test
	public void xpBetweenTwoLevels()
	{
		JsonObject result = run("{\"from_level\":42,\"to_level\":56}");
		assertEquals(45529, result.get("from_xp").getAsLong());
		assertEquals(184040, result.get("to_xp").getAsLong());
		assertEquals(138511, result.get("xp_needed").getAsLong());
		assertFalse(result.has("actions_needed"));
	}

	@Test
	public void xpFromCurrentXpUsesTheLevelItFallsIn()
	{
		JsonObject result = run("{\"current_xp\":50000,\"to_level\":56}");
		assertEquals(XpTable.levelForXp(50000), result.get("from_level").getAsInt());
		assertEquals(184040 - 50000, result.get("xp_needed").getAsLong());
	}

	@Test
	public void countsActionsRoundingUp()
	{
		JsonObject result = run("{\"from_level\":46,\"to_level\":56,\"xp_per_action\":52.5}");
		assertEquals(184040 - 67983, result.get("xp_needed").getAsLong());
		assertEquals(2211, result.get("actions_needed").getAsLong());
	}

	@Test
	public void acceptsNumbersSentAsStrings()
	{
		assertEquals(138511, run("{\"from_level\":\"42\",\"to_level\":\"56\"}").get("xp_needed").getAsLong());
	}

	@Test
	public void alreadyThereNeedsNoXp()
	{
		JsonObject result = run("{\"from_level\":60,\"to_level\":50}");
		assertEquals(0, result.get("xp_needed").getAsLong());
		assertTrue(result.has("note"));
	}

	@Test
	public void flagsVirtualLevels()
	{
		JsonObject result = run("{\"from_level\":99,\"to_level\":120}");
		assertTrue(result.get("note").getAsString().contains("virtual"));
	}

	@Test
	public void rejectsBadArgumentsWithAHelpfulMessage()
	{
		assertRejected("{\"from_level\":42}", "to_level is required");
		assertRejected("{\"to_level\":56}", "from_level or current_xp");
		assertRejected("{\"from_level\":42,\"to_level\":200}", "between 2 and 126");
		assertRejected("{\"from_level\":0,\"to_level\":56}", "between 1 and 126");
		assertRejected("{\"from_level\":\"abc\",\"to_level\":56}", "must be a number");
		assertRejected("{\"from_level\":42,\"to_level\":56,\"xp_per_action\":0}", "greater than 0");
	}

	private void assertRejected(String json, String expectedFragment)
	{
		try
		{
			tool.run(args(json));
			throw new AssertionError("expected rejection for " + json);
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
		}
	}

	@Test
	public void summaryDescribesTheCall()
	{
		assertEquals("XP calculator: level 42 to level 56", tool.summary(args("{\"from_level\":42,\"to_level\":56}")));
		assertEquals("XP calculator: current xp to level 56", tool.summary(args("{\"current_xp\":1,\"to_level\":56}")));
	}
}
