package com.bardosrs.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Test;

public class BankTrackerTest
{
	private static class FakeStore implements ProfileStore
	{
		volatile String profile = "rsprofile.one";
		final Map<String, String> data = Collections.synchronizedMap(new HashMap<>());
		final List<String> writes = Collections.synchronizedList(new ArrayList<>());

		@Override
		public String profileKey()
		{
			return profile;
		}

		@Override
		public String read(String profileKey, String key)
		{
			return data.get(profileKey + "|" + key);
		}

		@Override
		public void write(String profileKey, String key, String value)
		{
			data.put(profileKey + "|" + key, value);
			writes.add(profileKey + "|" + key);
		}
	}

	private final FakeStore store = new FakeStore();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable ->
	{
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});

	@After
	public void stop()
	{
		executor.shutdownNow();
	}

	private BankTracker tracker(long saveDelayMillis)
	{
		return new BankTracker(store, executor, saveDelayMillis);
	}

	private long writesOf(String key)
	{
		synchronized (store.writes)
		{
			return store.writes.stream().filter(w -> w.endsWith("|" + key)).count();
		}
	}

	@Test
	public void aBankThatWasNeverOpenedIsUnknown()
	{
		assertNull(tracker(50).saved());
	}

	@Test
	public void rememberedAfterTheBankIsOpened()
	{
		BankTracker tracker = tracker(60_000);
		tracker.onBankChanged(new int[]{4151, 995}, new int[]{1, 500});

		BankTracker.Saved saved = tracker.saved();
		assertNotNull(saved);
		assertArrayEquals(new int[]{4151, 995}, saved.ids);
		assertArrayEquals(new int[]{1, 500}, saved.quantities);
		assertTrue(System.currentTimeMillis() - saved.capturedAtMillis < 5_000);
	}

	@Test
	public void nothingIsTrackedWhenNobodyIsLoggedIn()
	{
		store.profile = null;
		BankTracker tracker = tracker(50);
		tracker.onBankChanged(new int[]{995}, new int[]{5});
		assertNull(tracker.saved());
		assertTrue(store.writes.isEmpty());
	}

	@Test
	public void manyQuickChangesAreSavedOnceAfterTheDelay() throws Exception
	{
		BankTracker tracker = tracker(150);
		for (int i = 1; i <= 20; i++)
		{
			tracker.onBankChanged(new int[]{995}, new int[]{i});
		}
		assertEquals("nothing is written while the bank is still changing", 0, writesOf("bankItems"));

		long deadline = System.currentTimeMillis() + 5_000;
		while (writesOf("bankItems") == 0 && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(25);
		}
		Thread.sleep(300);
		assertEquals(1, writesOf("bankItems"));
		assertEquals("995:20", store.data.get("rsprofile.one|bankItems"));
	}

	@Test
	public void flushSavesImmediatelyUnderTheCharactersOwnKey()
	{
		BankTracker tracker = tracker(60_000);
		tracker.onBankChanged(new int[]{4151}, new int[]{1});
		tracker.flush();
		assertEquals("4151:1", store.data.get("rsprofile.one|bankItems"));
		assertNotNull(store.data.get("rsprofile.one|bankTime"));
		tracker.flush();
		assertEquals("an unchanged bank is not written twice", 1, writesOf("bankItems"));
	}

	@Test
	public void aSavedBankIsLoadedForTheCharacter()
	{
		store.data.put("rsprofile.one|bankItems", "4151:1,995:500");
		store.data.put("rsprofile.one|bankTime", "1700000000000");

		BankTracker.Saved saved = tracker(50).saved();

		assertNotNull(saved);
		assertArrayEquals(new int[]{4151, 995}, saved.ids);
		assertEquals(1700000000000L, saved.capturedAtMillis);
	}

	@Test
	public void damagedSavedDataNeverBreaksTheTracker()
	{
		store.data.put("rsprofile.one|bankItems", "garbage,4151:1,:5");
		store.data.put("rsprofile.one|bankTime", "not a number");
		// Entries still load, but a missing timestamp means the age is unknown, so the bank counts as never seen.
		assertNull(tracker(50).saved());

		store.data.put("rsprofile.one|bankTime", "1700000000000");
		BankTracker.Saved saved = tracker(50).saved();
		assertNotNull(saved);
		assertArrayEquals(new int[]{4151}, saved.ids);
	}

	@Test
	public void switchingCharactersKeepsEachBankSeparate()
	{
		BankTracker tracker = tracker(60_000);
		tracker.onBankChanged(new int[]{4151}, new int[]{1});

		store.profile = "rsprofile.two";
		assertNull("the second character has never opened their bank", tracker.saved());
		assertEquals("the first character's bank was saved under its own key", "4151:1",
			store.data.get("rsprofile.one|bankItems"));
		assertNull(store.data.get("rsprofile.two|bankItems"));

		tracker.onBankChanged(new int[]{995}, new int[]{9});
		store.profile = "rsprofile.one";
		BankTracker.Saved first = tracker.saved();
		assertNotNull(first);
		assertArrayEquals(new int[]{4151}, first.ids);
		assertEquals("995:9", store.data.get("rsprofile.two|bankItems"));
	}

	@Test
	public void shutDownSavesPendingChangesAndForgetsThem()
	{
		BankTracker tracker = tracker(60_000);
		tracker.onBankChanged(new int[]{995}, new int[]{7});
		tracker.shutDown();
		assertEquals("995:7", store.data.get("rsprofile.one|bankItems"));
		// Memory is cleared, but the saved copy is still there for the next session.
		assertNotNull(tracker.saved());
	}
}
