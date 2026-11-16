package com.bardosrs.state;

/** One kind of item the player holds, with the total quantity. */
public final class ItemStack
{
	public final int id;
	public final String name;
	public final long quantity;

	public ItemStack(int id, String name, long quantity)
	{
		this.id = id;
		this.name = name;
		this.quantity = quantity;
	}
}
