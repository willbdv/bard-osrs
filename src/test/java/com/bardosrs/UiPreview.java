package com.bardosrs;

import com.bardosrs.goals.Goal;
import com.bardosrs.goals.GoalStore;
import com.bardosrs.state.FakeAccountSource;
import com.bardosrs.state.FakeProfileStore;
import com.bardosrs.state.TestProfiles;
import com.bardosrs.ui.ChatPanel;
import com.bardosrs.ui.GoalsPanel;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.runelite.client.ui.FontManager;

/**
 * Renders the panel offscreen to PNGs for visual review: gradlew uiPreview -PpreviewOut=chat.png
 * (the Goals tab is written next to it as chat-goals.png).
 */
public class UiPreview
{
	private static final String REPLY =
		"From 42–56 Crafting (about 138k XP), it depends on budget/account type:\n\n"
			+ "- **Main / can buy materials:** Glassblowing is the standard route:\n"
			+ "  - **42–46:** fishbowls (42.5 XP)\n"
			+ "  - **46–49:** unpowered orbs (52.5 XP)\n"
			+ "- **If you can afford gems:** Do **ruby rings to 43**, then **diamond rings 43–56** (85 XP each).\n\n"
			+ "## Tip\n"
			+ "I’m sure it’s fine — use `Banker` and *check GE prices* first.\n\n"
			+ "| Level | Method |\n|---|---|\n| 42 | Fishbowls |\n| 46 | Orbs |\n";

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		Path out = Paths.get(args.length > 0 ? args[0] : "bard-panel.png");
		String fileName = out.getFileName().toString();
		Path goalsOut = out.resolveSibling(fileName.replaceFirst("(\\.png)?$", "-goals.png"));

		Font runescape = FontManager.getRunescapeFont();
		for (String key : new String[]{"TextPane.font", "TextArea.font", "Label.font", "Button.font", "ToggleButton.font",
			"TextField.font"})
		{
			UIManager.put(key, runescape);
		}

		// Sample goals against a sample account (Crafting 42, 14 molten glass, 300 soda ash in the bank).
		Gson gson = new Gson();
		GoalStore store = new GoalStore(new FakeProfileStore(), gson);
		store.add(Goal.Type.SKILL, "56 Crafting for the colossal pouch", "Crafting", 56);
		store.add(Goal.Type.ITEM, "", "Soda ash", 1000);
		store.add(Goal.Type.QUEST, "", "Cook's Assistant", 0);
		store.add(Goal.Type.CUSTOM, "Get the fire cape", "", 0);
		store.add(Goal.Type.SKILL, "Reached already", "Crafting", 30);
		FakeAccountSource account = new FakeAccountSource();
		account.profile = TestProfiles.ironmanZerkerWithItems();

		ChatPanel[] holder = new ChatPanel[1];
		SwingUtilities.invokeAndWait(() -> holder[0] = new ChatPanel(null, new GoalsPanel(store, account)));
		ChatPanel panel = holder[0];

		panel.onAccountSummary("Ironman | Zerker (auto) | CB 87\nBank: 412 items (25m ago)");
		panel.onUserMessage("What should I train from 42-56 crafting?");
		panel.onAssistantStart();
		panel.onToolUse("XP calculator: level 42 to level 56");
		for (int i = 0; i < REPLY.length(); i += 7)
		{
			panel.onAssistantDelta(REPLY.substring(i, Math.min(REPLY.length(), i + 7)));
		}
		panel.onAssistantDone();
		panel.onNotice("Stopped.");
		SwingUtilities.invokeAndWait(() -> { });
		paint(panel, out);

		SwingUtilities.invokeAndWait(panel::showGoals);
		Thread.sleep(300);
		SwingUtilities.invokeAndWait(() -> { });
		paint(panel, goalsOut);
		System.exit(0);
	}

	private static void paint(ChatPanel panel, Path out) throws Exception
	{
		int width = 242;
		int height = 760;
		float scale = 2f;
		BufferedImage image = new BufferedImage((int) (width * scale), (int) (height * scale), BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() ->
		{
			panel.setSize(width, height);
			layoutAll(panel);
			Graphics2D g = image.createGraphics();
			g.setColor(new Color(0x282828));
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.scale(scale, scale);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			panel.paint(g);
			g.dispose();
		});
		ImageIO.write(image, "png", out.toFile());
		System.out.println("Wrote " + out.toAbsolutePath());
	}

	private static void layoutAll(Container container)
	{
		container.doLayout();
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layoutAll((Container) child);
			}
		}
	}
}
