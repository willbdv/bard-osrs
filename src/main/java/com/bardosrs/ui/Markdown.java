package com.bardosrs.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small Markdown subset: what chat models actually emit in a
 * narrow side panel (headings, bold, italic, inline code, lists, code fences
 * and simple tables). No Swing in here, so it is easy to unit test.
 */
public final class Markdown
{
	public enum Kind
	{
		PARAGRAPH, HEADING, LIST_ITEM, CODE
	}

	public static final class Span
	{
		public final String text;
		public final boolean bold;
		public final boolean italic;
		public final boolean code;

		Span(String text, boolean bold, boolean italic, boolean code)
		{
			this.text = text;
			this.bold = bold;
			this.italic = italic;
			this.code = code;
		}
	}

	public static final class Block
	{
		public final Kind kind;
		/** "- " or "3. " for list items, otherwise empty. */
		public final String marker;
		/** Nesting level of list items, 0 to 3. */
		public final int level;
		public final List<Span> spans;

		Block(Kind kind, String marker, int level, List<Span> spans)
		{
			this.kind = kind;
			this.marker = marker;
			this.level = level;
			this.spans = Collections.unmodifiableList(spans);
		}

		/** The block's text without styling. */
		public String plainText()
		{
			StringBuilder text = new StringBuilder(marker);
			for (Span span : spans)
			{
				text.append(span.text);
			}
			return text.toString();
		}
	}

	private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+)$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
	private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.+)$");
	private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}$");
	private static final Pattern TABLE_SEPARATOR =
		Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

	private Markdown()
	{
	}

	public static List<Block> parse(String markdown)
	{
		List<Block> blocks = new ArrayList<>();
		boolean inFence = false;
		boolean previousWasTableRow = false;

		for (String rawLine : markdown.split("\n", -1))
		{
			String line = rawLine.replace("\r", "");
			String trimmed = line.trim();

			if (trimmed.startsWith("```"))
			{
				inFence = !inFence;
				previousWasTableRow = false;
				continue;
			}
			if (inFence)
			{
				if (!trimmed.isEmpty())
				{
					blocks.add(new Block(Kind.CODE, "", 0,
						Collections.singletonList(new Span(line, false, false, true))));
				}
				continue;
			}
			if (trimmed.isEmpty() || RULE.matcher(trimmed).matches())
			{
				previousWasTableRow = false;
				continue;
			}

			Matcher heading = HEADING.matcher(line);
			if (heading.matches())
			{
				blocks.add(new Block(Kind.HEADING, "", 0, inline(heading.group(1).trim(), true)));
				previousWasTableRow = false;
				continue;
			}
			Matcher bullet = BULLET.matcher(line);
			if (bullet.matches())
			{
				blocks.add(new Block(Kind.LIST_ITEM, "- ", level(bullet.group(1)), inline(bullet.group(2).trim(), false)));
				previousWasTableRow = false;
				continue;
			}
			Matcher numbered = NUMBERED.matcher(line);
			if (numbered.matches())
			{
				blocks.add(new Block(Kind.LIST_ITEM, numbered.group(2) + ". ", level(numbered.group(1)),
					inline(numbered.group(3).trim(), false)));
				previousWasTableRow = false;
				continue;
			}
			if (trimmed.startsWith("|"))
			{
				if (TABLE_SEPARATOR.matcher(trimmed).matches())
				{
					continue;
				}
				blocks.add(new Block(Kind.PARAGRAPH, "", 0, inline(tableRow(trimmed), !previousWasTableRow)));
				previousWasTableRow = true;
				continue;
			}

			blocks.add(new Block(Kind.PARAGRAPH, "", 0, inline(trimmed, false)));
			previousWasTableRow = false;
		}
		return blocks;
	}

	private static int level(String indent)
	{
		return Math.min(3, indent.replace("\t", "  ").length() / 2);
	}

	/** "| a | b |" becomes "a | b". */
	private static String tableRow(String row)
	{
		String inner = row.substring(1, row.endsWith("|") && row.length() > 1 ? row.length() - 1 : row.length());
		String[] cells = inner.split("\\|", -1);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < cells.length; i++)
		{
			if (i > 0)
			{
				out.append(" | ");
			}
			out.append(cells[i].trim());
		}
		return out.toString();
	}

	private static List<Span> inline(String text, boolean bold)
	{
		List<Span> spans = new ArrayList<>();
		inline(text, bold, false, spans);
		return spans;
	}

	private static void inline(String s, boolean bold, boolean italic, List<Span> out)
	{
		StringBuilder plain = new StringBuilder();
		int i = 0;
		while (i < s.length())
		{
			char c = s.charAt(i);
			if (c == '`')
			{
				int end = s.indexOf('`', i + 1);
				if (end > i + 1)
				{
					flush(plain, bold, italic, out);
					out.add(new Span(s.substring(i + 1, end), bold, italic, true));
					i = end + 1;
					continue;
				}
			}
			else if (s.startsWith("**", i))
			{
				int end = s.indexOf("**", i + 2);
				if (end > i + 2)
				{
					flush(plain, bold, italic, out);
					inline(s.substring(i + 2, end), true, italic, out);
					i = end + 2;
					continue;
				}
			}
			else if (c == '*' && i + 1 < s.length() && !Character.isWhitespace(s.charAt(i + 1)))
			{
				int end = closingItalic(s, i + 1);
				if (end > 0)
				{
					flush(plain, bold, italic, out);
					inline(s.substring(i + 1, end), bold, true, out);
					i = end + 1;
					continue;
				}
			}
			plain.append(c);
			i++;
		}
		flush(plain, bold, italic, out);
	}

	/** Index of the single '*' closing an italic run that starts before {@code from}, or -1. */
	private static int closingItalic(String s, int from)
	{
		for (int j = from; j < s.length(); j++)
		{
			if (s.charAt(j) != '*')
			{
				continue;
			}
			boolean doubled = (j + 1 < s.length() && s.charAt(j + 1) == '*') || s.charAt(j - 1) == '*';
			if (!doubled && !Character.isWhitespace(s.charAt(j - 1)))
			{
				return j;
			}
		}
		return -1;
	}

	private static void flush(StringBuilder plain, boolean bold, boolean italic, List<Span> out)
	{
		if (plain.length() > 0)
		{
			out.add(new Span(plain.toString(), bold, italic, false));
			plain.setLength(0);
		}
	}
}
