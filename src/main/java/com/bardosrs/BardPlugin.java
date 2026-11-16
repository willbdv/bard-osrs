package com.bardosrs;

import com.bardosrs.agent.ChatSession;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.AccountSource;
import com.bardosrs.state.BankTracker;
import com.bardosrs.ui.BardIcon;
import com.bardosrs.ui.ChatPanel;
import com.bardosrs.ui.GoalsPanel;
import com.google.inject.Provides;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Bard",
	description = "Account-aware OSRS assistant: knows your mode, build, stats and quests",
	tags = {"ai", "assistant", "llm", "wiki", "ironman"}
)
public class BardPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatSession chatSession;

	@Inject
	private BankTracker bankTracker;

	@Inject
	private GoalStore goalStore;

	@Inject
	private AccountSource accountSource;

	@Inject
	private BardConfig config;

	private NavigationButton navigationButton;
	private GoalsPanel goalsPanel;

	@Override
	protected void startUp() throws Exception
	{
		goalsPanel = new GoalsPanel(goalStore, accountSource);
		ChatPanel panel = new ChatPanel(chatSession, goalsPanel);
		navigationButton = NavigationButton.builder()
			.tooltip("Bard")
			.icon(BardIcon.create())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		log.debug("Bard started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		goalsPanel.dispose();
		goalsPanel = null;
		chatSession.shutDown();
		bankTracker.shutDown();
		log.debug("Bard stopped");
	}

	/** The game sends the bank only while it is open; remember it, but only if the player opted in to sharing. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK || !config.shareAccountData() || !config.shareItems())
		{
			return;
		}
		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] quantities = new int[items.length];
		int count = 0;
		for (Item item : items)
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				ids[count] = item.getId();
				quantities[count] = item.getQuantity();
				count++;
			}
		}
		bankTracker.onBankChanged(Arrays.copyOf(ids, count), Arrays.copyOf(quantities, count));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bankTracker.flush();
		}
	}

	@Provides
	BardConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BardConfig.class);
	}
}
