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
}
