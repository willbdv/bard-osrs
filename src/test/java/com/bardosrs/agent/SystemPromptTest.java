package com.bardosrs.agent;

import static org.junit.Assert.assertTrue;

import com.bardosrs.tools.AgentTool;
import com.bardosrs.tools.ToolRegistry;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import com.bardosrs.tools.EditGoalTool;
import com.bardosrs.tools.ListGoalsTool;
import com.bardosrs.tools.QuestTool;
import com.bardosrs.tools.SearchItemsTool;
import com.bardosrs.tools.WikiClient;
import com.bardosrs.tools.WikiPageTool;
import com.bardosrs.tools.WikiSearchTool;
import com.bardosrs.tools.XpCalculatorTool;
import com.google.gson.Gson;
import java.util.Arrays;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class SystemPromptTest
{
	/** The prompt names tools directly, so renaming a tool must fail here rather than silently confuse the model. */
	@Test
	public void namesEveryRegisteredTool()
	{
		Gson gson = new Gson();
		WikiClient wiki = new WikiClient(new OkHttpClient(), gson);
		GoalStore goals = new GoalStore(new FakeProfileStore(), gson);
		for (AgentTool tool : Arrays.<AgentTool>asList(new XpCalculatorTool(), new WikiSearchTool(wiki), new WikiPageTool(wiki),
			new QuestTool(new FakeAccountSource()), new SearchItemsTool(new FakeAccountSource()),
			new ListGoalsTool(goals, new FakeAccountSource()), new EditGoalTool(goals)))
		{
			assertTrue(tool.name() + " is not mentioned in the system prompt", ChatSession.SYSTEM_PROMPT.contains(tool.name()));
		}
		assertTrue(new ToolRegistry(gson, wiki, new FakeAccountSource(), goals).specs().size() == 7);
	}

	@Test
	public void carriesTheTrainingAdviceRules()
	{
		String prompt = ChatSession.SYSTEM_PROMPT;
		assertTrue(prompt.contains("Ironman Guide/<Skill>"));
		assertTrue(prompt.contains("standard, widely used method"));
		assertTrue(prompt.contains("products"));
		assertTrue(prompt.contains("profit or loss"));
		// Challenged answers must be re-checked, not reversed on a hunch.
		assertTrue(prompt.contains("questions something you said"));
		assertTrue(prompt.contains("similarly named items"));
		// Replies stay short.
		assertTrue(prompt.contains("be brief"));
		assertTrue(prompt.contains("under 150 words"));
		assertTrue(prompt.contains("do not greet or praise"));
	}

	/** Every request pays for the system prompt, so keep it from growing unnoticed. */
	@Test
	public void staysReasonablySmall()
	{
		assertTrue("system prompt is " + ChatSession.SYSTEM_PROMPT.length() + " chars",
			ChatSession.SYSTEM_PROMPT.length() < 4500);
	}
}
