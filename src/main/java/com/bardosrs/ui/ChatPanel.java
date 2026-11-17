package com.bardosrs.ui;

import com.bardosrs.agent.ChatSession;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ButtonGroup;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The Bard sidebar: a styled transcript, an input box and Send/Stop/Clear buttons.
 * All state here is touched on the Swing thread only.
 */
@Slf4j
public class ChatPanel extends PluginPanel implements ChatSession.View
{
	private static final String INTRO =
		"Ask Bard about OSRS. This is an early build. For answers checked against the OSRS Wiki, turn on "
			+ "\"Look things up on the OSRS Wiki\" in its settings. Bard also has an XP calculator. To let it see "
			+ "your account type, stats and quests, turn on \"Share account data with the "
			+ "model\" in its settings. To include your bank, inventory and equipment, also turn on \"Also share "
			+ "bank, inventory and equipment\", then open your bank once. Ask Bard to set a goal, and track it in "
			+ "the Goals tab.";

	private static final Color TEXT_COLOR = Color.WHITE;
	private static final Color CODE_COLOR = new Color(0xB5E48C);
	private static final int FOLLOW_THRESHOLD_PX = 30;

	private final ChatSession session;
	private final JTextPane transcript = new JTextPane();
	private final JScrollPane transcriptScroll;
	private final StyledDocument doc;
	private final JTextArea input = new JTextArea(3, 1);
	private final JButton sendButton = new JButton("Send");
	private final JButton stopButton = new JButton("Stop");
	private final JButton clearButton = new JButton("Clear");
	private final JLabel accountLabel = new JLabel(" ");
	private final GoalsPanel goalsPanel;
	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final JToggleButton chatTab = new JToggleButton("Chat", true);
	private final JToggleButton goalsTab = new JToggleButton("Goals");

	/** Raw Markdown of the reply being streamed, and where it starts in the document. */
	private final StringBuilder liveReply = new StringBuilder();
	private int liveStart = -1;

	public ChatPanel(ChatSession session, GoalsPanel goalsPanel)
	{
		super(false);
		this.session = session;
		this.goalsPanel = goalsPanel;
		transcript.setStyledDocument(new DefaultStyledDocument(new ComponentFontStyleContext(transcript::getFont)));
		doc = transcript.getStyledDocument();
		transcriptScroll = new JScrollPane(transcript);

		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		JLabel title = new JLabel("Bard");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(title, BorderLayout.WEST);
		header.add(clearButton, BorderLayout.EAST);
		accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(accountLabel, BorderLayout.SOUTH);

		ButtonGroup group = new ButtonGroup();
		group.add(chatTab);
		group.add(goalsTab);
		chatTab.addActionListener(e -> showTab(false));
		goalsTab.addActionListener(e -> showTab(true));
		JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0));
		tabs.setOpaque(false);
		tabs.add(chatTab);
		tabs.add(goalsTab);
		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setOpaque(false);
		top.add(header, BorderLayout.NORTH);
		top.add(tabs, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		transcript.setEditable(false);
		transcript.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		transcript.setForeground(TEXT_COLOR);
		transcript.setBorder(new EmptyBorder(4, 6, 4, 6));
		// We scroll ourselves, so appending text never yanks the view around.
		((DefaultCaret) transcript.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		transcriptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		transcriptScroll.setBorder(null);
		JPanel chatCard = new JPanel(new BorderLayout(0, 6));
		chatCard.setOpaque(false);
		chatCard.add(transcriptScroll, BorderLayout.CENTER);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		input.setForeground(TEXT_COLOR);
		input.setCaretColor(TEXT_COLOR);
		input.setBorder(new EmptyBorder(4, 4, 4, 4));
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "bard-send");
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
			DefaultEditorKit.insertBreakAction);
		input.getActionMap().put("bard-send", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				submit();
			}
		});
		JScrollPane inputScroll = new JScrollPane(input);
		inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		buttons.setOpaque(false);
		buttons.add(stopButton);
		buttons.add(sendButton);
		stopButton.setEnabled(false);

		JPanel bottom = new JPanel(new BorderLayout(0, 4));
		bottom.setOpaque(false);
		bottom.add(inputScroll, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.SOUTH);
		chatCard.add(bottom, BorderLayout.SOUTH);

		cardPanel.setOpaque(false);
		cardPanel.add(chatCard, "chat");
		cardPanel.add(goalsPanel, "goals");
		add(cardPanel, BorderLayout.CENTER);

		sendButton.addActionListener(e -> submit());
		stopButton.addActionListener(e -> session.stop(this));
		clearButton.addActionListener(e ->
		{
			session.clear(this);
			ui(this::resetTranscript);
		});

		resetTranscript();
	}

	/**
	 * A styled document ignores the component's font and defaults to Monospaced.
	 * Resolve fonts from the component instead, so the transcript matches the
	 * rest of RuneLite (code spans still ask for a monospaced family).
	 */
	private static final class ComponentFontStyleContext extends StyleContext
	{
		private final transient Supplier<Font> baseFont;

		ComponentFontStyleContext(Supplier<Font> baseFont)
		{
			this.baseFont = baseFont;
		}

		@Override
		public Font getFont(AttributeSet attr)
		{
			Font base = baseFont.get();
			int style = (StyleConstants.isBold(attr) ? Font.BOLD : 0) | (StyleConstants.isItalic(attr) ? Font.ITALIC : 0);
			if (attr.getAttribute(StyleConstants.FontFamily) != null)
			{
				// The pixel font has a small x-height, so a same-size monospaced font looks oversized.
				return new Font(StyleConstants.getFontFamily(attr), style, Math.max(10, base.getSize() * 3 / 4));
			}
			return base.deriveFont(style);
		}
	}

	/** Switches between the chat and the goals list; only the visible goals list reads the game. */
	private void showTab(boolean goals)
	{
		cards.show(cardPanel, goals ? "goals" : "chat");
		clearButton.setVisible(!goals);
		goalsPanel.setShowing(goals);
	}

	/** Shows the Goals tab, as if it had been clicked. */
	public void showGoals()
	{
		goalsTab.setSelected(true);
		showTab(true);
	}

	private void submit()
	{
		String text = input.getText();
		if (text.trim().isEmpty())
		{
			return;
		}
		input.setText("");
		if (!session.send(text, this))
		{
			input.setText(text);
		}
	}

	private static void ui(Runnable runnable)
	{
		SwingUtilities.invokeLater(runnable);
	}

	// ---- ChatSession.View (any thread) ----

	@Override
	public void onUserMessage(String text)
	{
		ui(() ->
		{
			boolean follow = isNearBottom();
			appendLabel("You");
			appendPlain(clean(text), charAttrs(false, false, false, TEXT_COLOR), 2);
			scrollIfFollowing(follow);
		});
	}

	@Override
	public void onAssistantStart()
	{
		ui(() ->
		{
			boolean follow = isNearBottom();
			appendLabel("Bard");
			liveReply.setLength(0);
			liveStart = doc.getLength();
			scrollIfFollowing(follow);
		});
	}

	@Override
	public void onAssistantDelta(String text)
	{
		ui(() ->
		{
			liveReply.append(text);
			renderLiveReply();
		});
	}

	@Override
	public void onToolUse(String description)
	{
		ui(() ->
		{
			boolean follow = isNearBottom();
			// Close the text so far, show the tool line, then continue the reply in a fresh region.
			renderLiveReply();
			appendPlain("[Used " + clean(description) + "]", charAttrs(false, true, false, ColorScheme.LIGHT_GRAY_COLOR), 3);
			liveReply.setLength(0);
			liveStart = doc.getLength();
			scrollIfFollowing(follow);
		});
	}

	@Override
	public void onAssistantDone()
	{
		ui(() ->
		{
			renderLiveReply();
			liveStart = -1;
		});
	}

	@Override
	public void onNotice(String message)
	{
		ui(() ->
		{
			boolean follow = isNearBottom();
			liveStart = -1;
			appendPlain("[" + clean(message) + "]", charAttrs(false, true, false, ColorScheme.LIGHT_GRAY_COLOR), 4);
			scrollIfFollowing(follow);
		});
	}

	@Override
	public void onAccountSummary(String summary)
	{
		ui(() -> accountLabel.setText(toHtml(clean(summary))));
	}

	@Override
	public void onBusyChanged(boolean busy)
	{
		ui(() ->
		{
			sendButton.setEnabled(!busy);
			stopButton.setEnabled(busy);
		});
	}

	// ---- transcript rendering (Swing thread) ----

	private void resetTranscript()
	{
		try
		{
			doc.remove(0, doc.getLength());
		}
		catch (BadLocationException e)
		{
			log.debug("Could not clear transcript", e);
		}
		liveReply.setLength(0);
		liveStart = -1;
		appendPlain(INTRO, charAttrs(false, true, false, ColorScheme.LIGHT_GRAY_COLOR), 4);
	}

	/** Re-renders the streamed reply from its raw Markdown, so spans that close mid-stream restyle correctly. */
	private void renderLiveReply()
	{
		if (liveStart < 0)
		{
			return;
		}
		boolean follow = isNearBottom();
		try
		{
			doc.remove(liveStart, doc.getLength() - liveStart);
		}
		catch (BadLocationException e)
		{
			log.debug("Could not clear live reply", e);
			return;
		}
		for (Markdown.Block block : Markdown.parse(clean(liveReply.toString())))
		{
			appendBlock(block);
		}
		scrollIfFollowing(follow);
	}

	private void appendBlock(Markdown.Block block)
	{
		int start = doc.getLength();
		if (block.kind == Markdown.Kind.LIST_ITEM)
		{
			insert(block.marker, charAttrs(false, false, false, TEXT_COLOR));
		}
		for (Markdown.Span span : block.spans)
		{
			boolean heading = block.kind == Markdown.Kind.HEADING;
			boolean code = span.code || block.kind == Markdown.Kind.CODE;
			Color color = heading ? ColorScheme.BRAND_ORANGE : code ? CODE_COLOR : TEXT_COLOR;
			insert(span.text, charAttrs(span.bold || heading, span.italic, code, color));
		}
		insert("\n", charAttrs(false, false, false, TEXT_COLOR));

		SimpleAttributeSet paragraph = new SimpleAttributeSet();
		switch (block.kind)
		{
			case LIST_ITEM:
				StyleConstants.setLeftIndent(paragraph, 14 + 10 * block.level);
				StyleConstants.setFirstLineIndent(paragraph, -10);
				StyleConstants.setSpaceBelow(paragraph, 1);
				break;
			case HEADING:
				StyleConstants.setSpaceAbove(paragraph, 3);
				StyleConstants.setSpaceBelow(paragraph, 3);
				break;
			case CODE:
				StyleConstants.setLeftIndent(paragraph, 6);
				break;
			default:
				StyleConstants.setSpaceBelow(paragraph, 4);
		}
		doc.setParagraphAttributes(start, doc.getLength() - start, paragraph, true);
	}

	private void appendLabel(String label)
	{
		int start = doc.getLength();
		insert(label + "\n", charAttrs(true, false, false, ColorScheme.BRAND_ORANGE));
		SimpleAttributeSet paragraph = new SimpleAttributeSet();
		StyleConstants.setSpaceAbove(paragraph, start == 0 ? 0 : 10);
		StyleConstants.setSpaceBelow(paragraph, 1);
		doc.setParagraphAttributes(start, doc.getLength() - start, paragraph, true);
	}

	private void appendPlain(String text, AttributeSet attrs, float spaceBelow)
	{
		int start = doc.getLength();
		insert(text + "\n", attrs);
		SimpleAttributeSet paragraph = new SimpleAttributeSet();
		StyleConstants.setSpaceBelow(paragraph, spaceBelow);
		doc.setParagraphAttributes(start, doc.getLength() - start, paragraph, true);
	}

	private void insert(String text, AttributeSet attrs)
	{
		try
		{
			doc.insertString(doc.getLength(), text, attrs);
		}
		catch (BadLocationException e)
		{
			log.debug("Could not insert text", e);
		}
	}

	/** The account summary can span lines, which a plain label cannot show. */
	private static String toHtml(String text)
	{
		return "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
			+ "</html>";
	}

	/** ASCII-fies typographic characters and drops anything the RuneScape font cannot draw. */
	private String clean(String text)
	{
		Font font = transcript.getFont();
		String ascii = TextSanitizer.sanitize(text);
		return font == null ? ascii : TextSanitizer.dropUndisplayable(ascii, font);
	}

	private static SimpleAttributeSet charAttrs(boolean bold, boolean italic, boolean code, Color color)
	{
		SimpleAttributeSet attrs = new SimpleAttributeSet();
		StyleConstants.setBold(attrs, bold);
		StyleConstants.setItalic(attrs, italic);
		StyleConstants.setForeground(attrs, color);
		if (code)
		{
			StyleConstants.setFontFamily(attrs, Font.MONOSPACED);
		}
		return attrs;
	}

	private boolean isNearBottom()
	{
		JScrollBar bar = transcriptScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - FOLLOW_THRESHOLD_PX;
	}

	/** Keeps the view pinned to the newest text, unless the user has scrolled up to read. */
	private void scrollIfFollowing(boolean follow)
	{
		if (follow)
		{
			SwingUtilities.invokeLater(() ->
			{
				JScrollBar bar = transcriptScroll.getVerticalScrollBar();
				bar.setValue(bar.getMaximum());
			});
		}
	}
}
