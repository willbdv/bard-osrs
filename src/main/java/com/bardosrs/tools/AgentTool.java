package com.bardosrs.tools;

import com.google.gson.JsonObject;

/** A function the model can call. Implementations must be fast and must not block the calling thread for long. */
public interface AgentTool
{
	/** The function name the model uses; lower_snake_case. */
	String name();

	String description();

	/** JSON Schema (type "object") describing the arguments. */
	JsonObject parameters();

	/**
	 * Runs the tool and returns a JSON string for the model.
	 *
	 * @throws IllegalArgumentException for bad arguments; the message is sent back so the model can correct itself
	 */
	String run(JsonObject arguments);

	/** A short human-readable description of this call, shown in the chat panel. */
	String summary(JsonObject arguments);

	/** False while the player has this tool switched off; it is then not offered to the model. */
	default boolean available()
	{
		return true;
	}

	/** For an unavailable tool: a line for the system prompt saying what to do without it. Null for none. */
	default String unavailableNote()
	{
		return null;
	}

	/** For an unavailable tool: a line for the player saying how to switch it on. Null for none. */
	default String unavailableHint()
	{
		return null;
	}
}
