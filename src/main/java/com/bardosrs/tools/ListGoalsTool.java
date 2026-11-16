package com.bardosrs.tools;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** Shows the player's goals and how far along each one is. */
public class ListGoalsTool implements AgentTool
{
	private final GoalStore goals;
	private final AccountSource account;

	public ListGoalsTool(GoalStore goals, AccountSource account)
	{
		this.goals = goals;
		this.account = account;
	}

	@Override
	public String name()
	{
		return "list_goals";
	}

	@Override
	public String description()
	{
		return "List the player's goals with their progress (skill levels, quests, items held, or custom goals "
			+ "they tick off). Use it before planning, so advice serves what they are working towards.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		if (!goals.available())
		{
			throw new ToolException("Goals are saved per character, so the player must be logged in to have any.");
		}
		List<GoalStore.View> views = goals.applyProgress(account.latest());

		JsonArray list = new JsonArray();
		for (GoalStore.View view : views)
		{
			JsonObject entry = new JsonObject();
			entry.addProperty("id", view.goal.id);
			entry.addProperty("title", view.goal.title);
			entry.addProperty("type", view.goal.type.name().toLowerCase());
			entry.addProperty("done", view.goal.done);
			entry.addProperty("progress", view.progress.text);
			if (view.progress.known)
			{
				entry.addProperty("percent", (int) Math.round(view.progress.fraction * 100));
			}
			list.add(entry);
		}
		JsonObject result = new JsonObject();
		result.add("goals", list);
		if (views.isEmpty())
		{
			result.addProperty("note", "The player has no goals yet.");
		}
		return result.toString();
	}

	@Override
	public String summary(JsonObject args)
	{
		return "goals overview";
	}

	static JsonObject describe(Goal goal)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("id", goal.id);
		entry.addProperty("title", goal.title);
		entry.addProperty("type", goal.type.name().toLowerCase());
		entry.addProperty("done", goal.done);
		return entry;
	}
}
