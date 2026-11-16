package com.bardosrs.tools;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.goals.GoalTargets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.runelite.api.Skill;

/** Adds, completes or removes a goal, only when the player has asked for it. */
public class EditGoalTool implements AgentTool
{
	private static final long MAX_ITEM_QUANTITY = 2_000_000_000L;

	private final GoalStore goals;

	public EditGoalTool(GoalStore goals)
	{
		this.goals = goals;
	}

	@Override
	public String name()
	{
		return "edit_goal";
	}

	@Override
	public String description()
	{
		return "Add, complete or remove one of the player's goals. Only call it when the player asks. To add: give "
			+ "type 'skill' (skill and level), 'quest' (quest), 'item' (item and quantity) or 'custom' (title), and "
			+ "optionally a short title. To complete or remove: give the goal's id (from list_goals) or part of its title.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject properties = new JsonObject();
		properties.add("action", enumProperty("add, complete or remove", "add", "complete", "remove"));
		properties.add("type", enumProperty("For add: what kind of goal", "skill", "quest", "item", "custom"));
		properties.add("title", property("string", "A short title. Required for custom goals; optional otherwise."));
		properties.add("skill", property("string", "For a skill goal: the skill, e.g. Crafting."));
		properties.add("level", property("integer", "For a skill goal: the target level, 2 to 126."));
		properties.add("quest", property("string", "For a quest goal: the quest name."));
		properties.add("item", property("string", "For an item goal: the item name."));
		properties.add("quantity", property("integer", "For an item goal: how many to hold. Defaults to 1."));
		properties.add("id", property("string", "For complete or remove: the goal id, or part of its title."));
		JsonArray required = new JsonArray();
		required.add("action");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		String action = text(args, "action").toLowerCase(Locale.ROOT);
		JsonObject result = new JsonObject();
		switch (action)
		{
			case "add":
				int before = goals.list().size();
				Goal added = add(args);
				result.addProperty("result", goals.list().size() == before ? "already_set" : "added");
				result.add("goal", ListGoalsTool.describe(added));
				break;
			case "complete":
				result.addProperty("result", "completed");
				result.add("goal", ListGoalsTool.describe(goals.complete(idOrTitle(args))));
				break;
			case "remove":
				result.addProperty("result", "removed");
				result.add("goal", ListGoalsTool.describe(goals.remove(idOrTitle(args))));
				break;
			default:
				throw new IllegalArgumentException("action must be add, complete or remove.");
		}
		return result.toString();
	}

	private Goal add(JsonObject args)
	{
		String title = text(args, "title");
		switch (type(args))
		{
			case SKILL:
				Skill skill = GoalTargets.skill(text(args, "skill"));
				int level = GoalTargets.level(number(args, "level", 0));
				return goals.add(Goal.Type.SKILL, title, skill.getName(), level);
			case QUEST:
				return goals.add(Goal.Type.QUEST, title, GoalTargets.quest(text(args, "quest")), 0);
			case ITEM:
				String item = text(args, "item");
				if (item.isEmpty())
				{
					throw new IllegalArgumentException("item is required for an item goal.");
				}
				long quantity = number(args, "quantity", 1);
				if (quantity < 1 || quantity > MAX_ITEM_QUANTITY)
				{
					throw new IllegalArgumentException("quantity must be between 1 and " + MAX_ITEM_QUANTITY + ".");
				}
				return goals.add(Goal.Type.ITEM, title, item, quantity);
			default:
				if (title.isEmpty())
				{
					throw new IllegalArgumentException("title is required for a custom goal.");
				}
				return goals.add(Goal.Type.CUSTOM, title, "", 0);
		}
	}

	/** The goal type: as given, or worked out from which fields were filled in. */
	private static Goal.Type type(JsonObject args)
	{
		String given = text(args, "type").toLowerCase(Locale.ROOT);
		switch (given)
		{
			case "skill":
				return Goal.Type.SKILL;
			case "quest":
				return Goal.Type.QUEST;
			case "item":
				return Goal.Type.ITEM;
			case "custom":
				return Goal.Type.CUSTOM;
			case "":
				if (!text(args, "skill").isEmpty())
				{
					return Goal.Type.SKILL;
				}
				if (!text(args, "quest").isEmpty())
				{
					return Goal.Type.QUEST;
				}
				if (!text(args, "item").isEmpty())
				{
					return Goal.Type.ITEM;
				}
				return Goal.Type.CUSTOM;
			default:
				throw new IllegalArgumentException("type must be skill, quest, item or custom.");
		}
	}

	private static String idOrTitle(JsonObject args)
	{
		String id = text(args, "id");
		return id.isEmpty() ? text(args, "title") : id;
	}

	@Override
	public String summary(JsonObject args)
	{
		String action = text(args, "action").toLowerCase(Locale.ROOT);
		String what = text(args, "title");
		if (what.isEmpty())
		{
			what = text(args, "skill") + text(args, "quest") + text(args, "item") + text(args, "id");
		}
		return "goal " + (action.isEmpty() ? "change" : action) + (what.isEmpty() ? "" : ": " + what);
	}

	private static String text(JsonObject args, String key)
	{
		return WikiSearchTool.text(args, key);
	}

	private static long number(JsonObject args, String key, long fallback)
	{
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull() || (value.isJsonPrimitive() && value.getAsString().trim().isEmpty()))
		{
			return fallback;
		}
		try
		{
			return (long) value.getAsDouble();
		}
		catch (RuntimeException e)
		{
			throw new IllegalArgumentException(key + " must be a number.");
		}
	}

	private static JsonObject property(String type, String description)
	{
		JsonObject property = new JsonObject();
		property.addProperty("type", type);
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject enumProperty(String description, String... values)
	{
		JsonObject property = property("string", description);
		JsonArray allowed = new JsonArray();
		for (String value : values)
		{
			allowed.add(value);
		}
		property.add("enum", allowed);
		return property;
	}
}
