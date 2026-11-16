package com.bardosrs.goals;

/** A goal request that cannot be done; the message is safe to show the player or the model. */
public class GoalException extends IllegalArgumentException
{
	public GoalException(String message)
	{
		super(message);
	}
}
