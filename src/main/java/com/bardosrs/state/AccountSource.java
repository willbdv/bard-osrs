package com.bardosrs.state;

import com.google.inject.ImplementedBy;
import java.util.concurrent.CompletableFuture;

/** Where the plugin gets the player's account from. The real one reads the game client. */
@ImplementedBy(AccountTracker.class)
public interface AccountSource
{
	/** Reads the account now. Safe to call from any thread; the future completes once the game thread has read it. */
	CompletableFuture<AccountProfile> capture();

	/** The most recent profile read, or a "not shared" profile if the player has switched sharing off. */
	AccountProfile latest();
}
