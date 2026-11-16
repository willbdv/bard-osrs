package com.bardosrs.state;

import com.bardosrs.BardConfig;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Reads the account on demand, when the player asks a question, rather than
 * polling the game every tick. The read itself runs on the client thread.
 */
@Slf4j
@Singleton
public class AccountTracker implements AccountSource
{
	private final Client client;
	private final ClientThread clientThread;
	private final BardConfig config;
	private final ItemManager itemManager;
	private final BankTracker bankTracker;

	private volatile AccountProfile latest = AccountProfile.unavailable();

	@Inject
	AccountTracker(Client client, ClientThread clientThread, BardConfig config, ItemManager itemManager,
		BankTracker bankTracker)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.itemManager = itemManager;
		this.bankTracker = bankTracker;
	}

	@Override
	public CompletableFuture<AccountProfile> capture()
	{
		CompletableFuture<AccountProfile> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				AccountProfile profile = AccountReader.read(client, config.accountBuild(),
					config.shareItems() ? () -> ItemReader.read(client, itemManager, bankTracker) : null);
				latest = profile;
				result.complete(profile);
			}
			catch (RuntimeException e)
			{
				log.debug("Could not read the account", e);
				result.complete(AccountProfile.unavailable());
			}
		});
		return result;
	}

	@Override
	public AccountProfile latest()
	{
		// Switching sharing off must take effect immediately, even for a profile read a moment ago.
		if (!config.shareAccountData())
		{
			return AccountProfile.notShared();
		}
		return config.shareItems() ? latest : latest.withoutItems();
	}
}
