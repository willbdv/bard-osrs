package com.bardosrs.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Exact experience arithmetic, so the model never has to estimate it. */
public class XpCalculatorTool implements AgentTool
{
	@Override
	public String name()
	{
		return "xp_calculator";
	}

	@Override
	public String description()
	{
		return "Exact OSRS experience arithmetic. Use it for every xp or level figure instead of estimating; the xp "
			+ "table is identical for all skills. Give to_level plus either from_level or current_xp. Optionally "
			+ "give xp_per_action to also get how many actions are needed.";
	}

	@Override
	public JsonObject parameters()
	{
		JsonObject properties = new JsonObject();
		properties.add("to_level", property("integer", "Target level, 2 to 126 (levels above 99 are virtual)."));
		properties.add("from_level", property("integer", "Starting level, 1 to 126. Omit if current_xp is given."));
		properties.add("current_xp", property("integer", "Current total xp in the skill. Used instead of from_level."));
		properties.add("xp_per_action", property("number", "Xp gained per action (e.g. 52.5), to count actions needed."));

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("to_level");
		schema.add("required", required);
		return schema;
	}

	@Override
	public String run(JsonObject args)
	{
		int toLevel = requireInt(args, "to_level", 2, XpTable.MAX_LEVEL);

		long fromXp;
		int fromLevel;
		if (args.has("current_xp") && !args.get("current_xp").isJsonNull())
		{
			long currentXp = requireLong(args, "current_xp", 0, XpTable.MAX_XP);
			fromXp = currentXp;
			fromLevel = XpTable.levelForXp(currentXp);
		}
		else if (args.has("from_level") && !args.get("from_level").isJsonNull())
		{
			fromLevel = requireInt(args, "from_level", 1, XpTable.MAX_LEVEL);
			fromXp = XpTable.xpForLevel(fromLevel);
		}
		else
		{
			throw new IllegalArgumentException("Provide either from_level or current_xp.");
		}

		long toXp = XpTable.xpForLevel(toLevel);
		long needed = Math.max(0, toXp - fromXp);

		JsonObject result = new JsonObject();
		result.addProperty("from_level", fromLevel);
		result.addProperty("from_xp", fromXp);
		result.addProperty("to_level", toLevel);
		result.addProperty("to_xp", toXp);
		result.addProperty("xp_needed", needed);

		if (args.has("xp_per_action") && !args.get("xp_per_action").isJsonNull())
		{
			double perAction = requireDouble(args, "xp_per_action");
			if (perAction <= 0)
			{
				throw new IllegalArgumentException("xp_per_action must be greater than 0.");
			}
			result.addProperty("xp_per_action", perAction);
			result.addProperty("actions_needed", (long) Math.ceil(needed / perAction));
		}
		if (needed == 0)
		{
			result.addProperty("note", "The starting point is already at or above the target level.");
		}
		else if (toLevel > 99)
		{
			result.addProperty("note", "Levels above 99 are virtual levels.");
		}
		return result.toString();
	}

	@Override
	public String summary(JsonObject args)
	{
		String from = args.has("current_xp") ? "current xp" : "level " + text(args, "from_level");
		return "XP calculator: " + from + " to level " + text(args, "to_level");
	}

	private static JsonObject property(String type, String description)
	{
		JsonObject property = new JsonObject();
		property.addProperty("type", type);
		property.addProperty("description", description);
		return property;
	}

	private static String text(JsonObject args, String key)
	{
		JsonElement value = args.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "?";
	}

	private static int requireInt(JsonObject args, String key, int min, int max)
	{
		long value = requireLong(args, key, min, max);
		return (int) value;
	}

	private static long requireLong(JsonObject args, String key, long min, long max)
	{
		JsonElement element = args.get(key);
		if (element == null || element.isJsonNull())
		{
			throw new IllegalArgumentException(key + " is required.");
		}
		long value;
		try
		{
			value = (long) element.getAsDouble();
		}
		catch (RuntimeException e)
		{
			throw new IllegalArgumentException(key + " must be a number.");
		}
		if (value < min || value > max)
		{
			throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ".");
		}
		return value;
	}

	private static double requireDouble(JsonObject args, String key)
	{
		try
		{
			return args.get(key).getAsDouble();
		}
		catch (RuntimeException e)
		{
			throw new IllegalArgumentException(key + " must be a number.");
		}
	}
}
