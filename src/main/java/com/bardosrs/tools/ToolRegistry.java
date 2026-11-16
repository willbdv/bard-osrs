package com.bardosrs.tools;

import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountSource;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** The tools offered to the model, with safe dispatch of the calls it makes. */
@Slf4j
@Singleton
public class ToolRegistry
{
	private final Gson gson;
	private final Map<String, AgentTool> tools = new LinkedHashMap<>();

	@Inject
	public ToolRegistry(Gson gson, WikiClient wiki, AccountSource account, GoalStore goals)
	{
		this(gson, Arrays.<AgentTool>asList(new XpCalculatorTool(), new WikiSearchTool(wiki), new WikiPageTool(wiki),
			new QuestTool(account), new SearchItemsTool(account), new ListGoalsTool(goals, account),
			new EditGoalTool(goals)));
	}

	public ToolRegistry(Gson gson, List<AgentTool> tools)
	{
		this.gson = gson;
		for (AgentTool tool : tools)
		{
			register(tool);
		}
	}

	private void register(AgentTool tool)
	{
		tools.put(tool.name(), tool);
	}

	/** Tool specs in the OpenAI "tools" format. */
	public JsonArray specs()
	{
		JsonArray specs = new JsonArray();
		for (AgentTool tool : tools.values())
		{
			JsonObject function = new JsonObject();
			function.addProperty("name", tool.name());
			function.addProperty("description", tool.description());
			function.add("parameters", tool.parameters());
			JsonObject spec = new JsonObject();
			spec.addProperty("type", "function");
			spec.add("function", function);
			specs.add(spec);
		}
		return specs;
	}

	/** A short description of a call for the chat panel. */
	public String summary(String name, String argumentsJson)
	{
		AgentTool tool = tools.get(name);
		if (tool == null)
		{
			return name;
		}
		try
		{
			return tool.summary(parse(argumentsJson));
		}
		catch (RuntimeException e)
		{
			return name;
		}
	}

	/** Runs a call and always returns a JSON string; failures come back as {"error": "..."} for the model. */
	public String execute(String name, String argumentsJson)
	{
		AgentTool tool = tools.get(name);
		if (tool == null)
		{
			return error("Unknown tool: " + name);
		}
		try
		{
			return tool.run(parse(argumentsJson));
		}
		catch (IllegalArgumentException | ToolException e)
		{
			return error(e.getMessage());
		}
		catch (RuntimeException e)
		{
			log.debug("Tool {} failed", name, e);
			return error("The tool failed unexpectedly.");
		}
	}

	private JsonObject parse(String argumentsJson)
	{
		if (argumentsJson == null || argumentsJson.trim().isEmpty())
		{
			return new JsonObject();
		}
		try
		{
			JsonObject parsed = gson.fromJson(argumentsJson, JsonObject.class);
			return parsed == null ? new JsonObject() : parsed;
		}
		catch (JsonParseException e)
		{
			throw new IllegalArgumentException("The arguments were not valid JSON.");
		}
	}

	private static String error(String message)
	{
		JsonObject error = new JsonObject();
		error.addProperty("error", message);
		return error.toString();
	}
}
