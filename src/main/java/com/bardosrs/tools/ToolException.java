package com.bardosrs.tools;

/** A tool failure whose message is safe to show the model, so it can recover (try another title, retry later). */
public class ToolException extends RuntimeException
{
	public ToolException(String message)
	{
		super(message);
	}
}
