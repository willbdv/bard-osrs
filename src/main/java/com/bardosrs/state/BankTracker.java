package com.bardosrs.state;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Remembers what was in the player's bank the last time they opened it. The game only sends the bank while it is
 * open, so the snapshot is kept in memory and saved per character, which lets Bard answer after a relog. Saves are
 * debounced, because the bank changes many times a second while the player is depositing.
 */
@Slf4j
@Singleton
public class BankTracker
{
	private static final String ITEMS_KEY = "bankItems";
	private static final String TIME_KEY = "bankTime";
	private static final long DEFAULT_SAVE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(5);

	/** The last known bank: parallel arrays of item ids and quantities, and when it was seen. */
	public static final class Saved
	{
		public final int[] ids;
		public final int[] quantities;
		public final long capturedAtMillis;

		Saved(int[] ids, int[] quantities, long capturedAtMillis)
		{
			this.ids = ids;
			this.quantities = quantities;
			this.capturedAtMillis = capturedAtMillis;
		}
	}

	private final ProfileStore store;
	private final ScheduledExecutorService executor;
	private final long saveDelayMillis;

	private String loadedKey;
	private int[] ids = new int[0];
	private int[] quantities = new int[0];
	private long capturedAt;
	private boolean known;
	private boolean dirty;
	private ScheduledFuture<?> pendingSave;

	@Inject
	BankTracker(ProfileStore store, ScheduledExecutorService executor)
	{
		this(store, executor, DEFAULT_SAVE_DELAY_MILLIS);
	}

	BankTracker(ProfileStore store, ScheduledExecutorService executor, long saveDelayMillis)
	{
		this.store = store;
		this.executor = executor;
		this.saveDelayMillis = saveDelayMillis;
	}

	/** Called whenever the game sends the bank. Items with no quantity (placeholders, empty slots) must be left out. */
	public synchronized void onBankChanged(int[] newIds, int[] newQuantities)
	{
		if (!switchToCurrentCharacter())
		{
			return;
		}
		ids = newIds.clone();
		quantities = newQuantities.clone();
		capturedAt = System.currentTimeMillis();
		known = true;
		dirty = true;
		if (pendingSave == null || pendingSave.isDone())
		{
			pendingSave = executor.schedule(this::flush, saveDelayMillis, TimeUnit.MILLISECONDS);
		}
	}

	/** The last known bank for the current character, or null if it has never been opened. */
	public synchronized Saved saved()
	{
		if (!switchToCurrentCharacter() || !known)
		{
			return null;
		}
		return new Saved(ids.clone(), quantities.clone(), capturedAt);
	}

	/** Writes any unsaved change now. Safe to call from any thread. */
	public synchronized void flush()
	{
		if (!dirty || loadedKey == null)
		{
			return;
		}
		try
		{
			// Written under the key the data was read for, so a late save can never land on another character.
			store.write(loadedKey, ITEMS_KEY, ItemCodec.encode(ids, quantities));
			store.write(loadedKey, TIME_KEY, String.valueOf(capturedAt));
			dirty = false;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not save the bank snapshot", e);
		}
	}

	public synchronized void shutDown()
	{
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
		flush();
		loadedKey = null;
		known = false;
		ids = new int[0];
		quantities = new int[0];
	}

	/** Makes the in-memory snapshot belong to whoever is logged in, saving the previous character's first. */
	private boolean switchToCurrentCharacter()
	{
		String key = store.profileKey();
		if (key == null)
		{
			return false;
		}
		if (key.equals(loadedKey))
		{
			return true;
		}
		flush();
		loadedKey = key;
		known = false;
		dirty = false;
		ids = new int[0];
		quantities = new int[0];
		capturedAt = 0;
		load(key);
		return true;
	}

	private void load(String key)
	{
		try
		{
			String saved = store.read(key, ITEMS_KEY);
			if (saved == null)
			{
				return;
			}
			List<int[]> entries = ItemCodec.decode(saved);
			ids = new int[entries.size()];
			quantities = new int[entries.size()];
			for (int i = 0; i < entries.size(); i++)
			{
				ids[i] = entries.get(i)[0];
				quantities[i] = entries.get(i)[1];
			}
			capturedAt = parseTime(store.read(key, TIME_KEY));
			known = capturedAt > 0;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load the saved bank", e);
		}
	}

	private static long parseTime(String text)
	{
		try
		{
			return text == null ? 0 : Long.parseLong(text.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
