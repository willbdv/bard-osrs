package com.bardosrs.state;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** A scriptable stand-in for the game client in tests. */
public class FakeAccountSource implements AccountSource
{
	public volatile AccountProfile profile = AccountProfile.notShared();
	/** If set, capture() returns this future so a test controls when (or whether) the read finishes. */
	public volatile CompletableFuture<AccountProfile> pending;
	public volatile boolean failRead;
	public final AtomicInteger captures = new AtomicInteger();

	@Override
	public CompletableFuture<AccountProfile> capture()
	{
		captures.incrementAndGet();
		if (pending != null)
		{
			return pending;
		}
		if (failRead)
		{
			CompletableFuture<AccountProfile> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalStateException("client thread unavailable"));
			return failed;
		}
		return CompletableFuture.completedFuture(profile);
	}

	@Override
	public AccountProfile latest()
	{
		return profile;
	}
}
