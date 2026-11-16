package com.bardosrs.tools;

import com.bardosrs.state.AccountProfile;
import com.bardosrs.state.AccountSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.QuestState;

/** Tells the model which quests the player has done, from the account data they chose to share. */
public class QuestTool implements AgentTool
{
	private final AccountSource account;

	public QuestTool(AccountSource account)
	{
		this.account = account;
	}

	@Override
	public String name()
	{
		return "get_quests";
	}

	@Override
	public String description()
	{
		return "Check the player's own quest progress. With 'name', returns the state of quests matching that text. "
			+ "With 'state', lists every quest in that state. With neither, returns counts, quest points and the "
			+ "quests in progress. Only works if the player shares account data.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject name = new JsonObject();
		name.addProperty("type", "string");
		name.addProperty("description", "Quest name, or part of it, e.g. 'Dragon Slayer'.");
		JsonArray states = new JsonArray();
		states.add("finished");
		states.add("in_progress");
		states.add("not_started");
		JsonObject state = new JsonObject();
		state.addProperty("type", "string");
		state.add("enum", states);
		state.addProperty("description", "List all quests in this state.");
		JsonObject properties = new JsonObject();
		properties.add("name", name);
		properties.add("state", state);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		AccountProfile profile = account.latest();
		if (!profile.isReady())
		{
			throw new ToolException(profile.unavailableReason());
		}

		String name = WikiSearchTool.text(args, "name");
		String state = WikiSearchTool.text(args, "state");
		JsonObject result = new JsonObject();

		if (!name.isEmpty())
		{
			JsonArray matches = new JsonArray();
			for (Map.Entry<String, QuestState> entry : profile.findQuests(name).entrySet())
			{
				JsonObject quest = new JsonObject();
				quest.addProperty("quest", entry.getKey());
				quest.addProperty("state", label(entry.getValue()));
				matches.add(quest);
			}
			result.addProperty("search", name);
			result.add("matches", matches);
			if (matches.size() == 0)
			{
				result.addProperty("note", "No quest name contains that text. Try a shorter part of the name.");
			}
		}
		else if (!state.isEmpty())
		{
			QuestState wanted = parse(state);
			JsonArray names = new JsonArray();
			for (String quest : profile.questsWithState(wanted))
			{
				names.add(quest);
			}
			result.addProperty("state", label(wanted));
			result.addProperty("count", names.size());
			result.add("quests", names);
		}
		else
		{
			JsonObject counts = new JsonObject();
			counts.addProperty("finished", profile.questCount(QuestState.FINISHED));
			counts.addProperty("in_progress", profile.questCount(QuestState.IN_PROGRESS));
			counts.addProperty("not_started", profile.questCount(QuestState.NOT_STARTED));
			result.addProperty("quest_points", profile.questPoints);
			result.add("counts", counts);
			JsonArray inProgress = new JsonArray();
			for (String quest : profile.questsWithState(QuestState.IN_PROGRESS))
			{
				inProgress.add(quest);
			}
			result.add("in_progress", inProgress);
		}
		return result.toString();
	}

	@Override
	public String summary(JsonObject args)
	{
		String name = WikiSearchTool.text(args, "name");
		String state = WikiSearchTool.text(args, "state");
		if (!name.isEmpty())
		{
			return "quest check: " + name;
		}
		return state.isEmpty() ? "quest overview" : "quests: " + state.replace('_', ' ');
	}

	private static QuestState parse(String state)
	{
		switch (state.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_'))
		{
			case "finished":
			case "complete":
			case "completed":
				return QuestState.FINISHED;
			case "in_progress":
			case "started":
				return QuestState.IN_PROGRESS;
			case "not_started":
			case "unstarted":
				return QuestState.NOT_STARTED;
			default:
				throw new IllegalArgumentException("state must be one of: finished, in_progress, not_started.");
		}
	}

	private static String label(QuestState state)
	{
		return state.name().toLowerCase(Locale.ROOT);
	}
}
