package com.bardosrs.goals;

/** Something the player wants to achieve. Immutable; changes make a new copy. */
public final class Goal
{
	public enum Type
	{
		/** Reach a level in a skill. */
		SKILL,
		/** Finish a quest. */
		QUEST,
		/** Hold a quantity of an item. */
		ITEM,
		/** Free text the player ticks off themselves. */
		CUSTOM
	}

	public final String id;
	public final Type type;
	public final String title;
	/** SKILL: the skill's name. QUEST: the quest's name. ITEM: the item's name. CUSTOM: empty. */
	public final String target;
	/** SKILL: the target level. ITEM: the quantity. Otherwise 0. */
	public final long amount;
	public final long createdAt;
	public final long updatedAt;
	public final boolean done;
	public final long doneAt;

	public Goal(String id, Type type, String title, String target, long amount, long createdAt, long updatedAt,
		boolean done, long doneAt)
	{
		this.id = id;
		this.type = type;
		this.title = title;
		this.target = target;
		this.amount = amount;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.done = done;
		this.doneAt = doneAt;
	}

	public Goal asDone(long now)
	{
		return new Goal(id, type, title, target, amount, createdAt, now, true, now);
	}
}
