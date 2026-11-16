package com.bardosrs.tools;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MediaWiki-rendered HTML into compact plain text that a language
 * model can read cheaply: headings as "## Title", lists as "- item", table rows
 * as "cell | cell", with navigation, footnotes, images and hidden blocks dropped.
 * Pure string work: no Swing and no parser dependencies.
 */
final class HtmlText
{
	private static final Set<String> VOID_TAGS = new HashSet<>(Arrays.asList(
		"br", "img", "hr", "input", "meta", "link", "wbr", "col", "source", "area", "base", "embed", "param", "track"));
	private static final Set<String> SKIPPED_TAGS = new HashSet<>(Arrays.asList(
		"script", "style", "noscript", "label", "button", "select", "svg", "head"));
	private static final Set<String> SKIPPED_CLASSES = new HashSet<>(Arrays.asList(
		"toc", "mw-editsection", "reference", "references", "mw-references-wrap", "printfooter", "catlinks",
		"mw-empty-elt", "leagues-global-flag", "league-badge", "mw-cite-backlink"));
	private static final Set<String> BLOCK_TAGS = new HashSet<>(Arrays.asList(
		"p", "div", "blockquote", "dl", "dt", "dd", "pre", "figure", "section", "article"));

	private static final int MAX_ICON_ALT_LENGTH = 30;

	private static final Pattern CLASS_ATTR = attributePattern("class");
	private static final Pattern STYLE_ATTR = attributePattern("style");
	private static final Pattern ID_ATTR = attributePattern("id");
	private static final Pattern ALT_ATTR = attributePattern("alt");
	private static final Pattern TITLE_ATTR = attributePattern("title");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

	private HtmlText()
	{
	}

	private static Pattern attributePattern(String name)
	{
		return Pattern.compile("(?:^|\\s)" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
			Pattern.CASE_INSENSITIVE);
	}

	static String toText(String html)
	{
		return html == null ? "" : new Converter().convert(html);
	}

	/** Cuts text at the last line break before {@code max} characters. */
	static String truncate(String text, int max)
	{
		if (text.length() <= max)
		{
			return text;
		}
		int cut = text.lastIndexOf('\n', max);
		return text.substring(0, cut > max / 2 ? cut : max).trim();
	}

	private static final class Frame
	{
		final String name;
		final boolean skipped;
		final boolean ordered;
		int counter;
		int cells;
		/** For table rows: the last cell filled from a link title, so a repeat in the next cell can replace it. */
		String fallbackText;
		int fallbackStart;
		/** For table cells: where the cell's text starts in the output, and alt text of icons seen inside it. */
		int startLength;
		final StringBuilder iconAlt = new StringBuilder();
		/** Titles of links inside a table cell: the fallback for icon-only cells whose images have no alt text. */
		final StringBuilder linkTitles = new StringBuilder();

		Frame(String name, boolean skipped, boolean ordered)
		{
			this.name = name;
			this.skipped = skipped;
			this.ordered = ordered;
		}
	}

	private static final class Converter
	{
		private final StringBuilder out = new StringBuilder();
		private final Deque<Frame> stack = new ArrayDeque<>();
		private int cellDepth;
		private int listDepth;
		private boolean pendingSpace;

		String convert(String html)
		{
			int i = 0;
			int n = html.length();
			while (i < n)
			{
				if (html.charAt(i) == '<')
				{
					if (html.startsWith("<!--", i))
					{
						int end = html.indexOf("-->", i + 4);
						i = end < 0 ? n : end + 3;
						continue;
					}
					int close = tagEnd(html, i);
					if (close < 0)
					{
						text(html.substring(i));
						break;
					}
					tag(html.substring(i + 1, close));
					i = close + 1;
				}
				else
				{
					int next = html.indexOf('<', i);
					if (next < 0)
					{
						next = n;
					}
					text(html.substring(i, next));
					i = next;
				}
			}
			return tidy(out.toString());
		}

		/** Index of the '>' ending the tag that starts at {@code from}, ignoring '>' inside quoted attributes. */
		private static int tagEnd(String html, int from)
		{
			char quote = 0;
			for (int i = from + 1; i < html.length(); i++)
			{
				char c = html.charAt(i);
				if (quote != 0)
				{
					if (c == quote)
					{
						quote = 0;
					}
				}
				else if (c == '"' || c == '\'')
				{
					quote = c;
				}
				else if (c == '>')
				{
					return i;
				}
			}
			return -1;
		}

		private void tag(String raw)
		{
			if (raw.isEmpty() || raw.charAt(0) == '!' || raw.charAt(0) == '?')
			{
				return;
			}
			boolean end = raw.charAt(0) == '/';
			String body = end ? raw.substring(1) : raw;
			boolean selfClosing = body.endsWith("/");
			int nameEnd = 0;
			while (nameEnd < body.length() && Character.isLetterOrDigit(body.charAt(nameEnd)))
			{
				nameEnd++;
			}
			String name = body.substring(0, nameEnd).toLowerCase();
			if (name.isEmpty())
			{
				return;
			}
			if (end)
			{
				endElement(name);
			}
			else
			{
				startElement(name, body.substring(nameEnd), selfClosing || VOID_TAGS.contains(name));
			}
		}

		private void startElement(String name, String attrs, boolean isVoid)
		{
			Frame parent = stack.peek();
			boolean skipped = parent != null && parent.skipped;
			if (!skipped)
			{
				skipped = SKIPPED_TAGS.contains(name) || hiddenByAttributes(attrs);
			}
			if (isVoid)
			{
				if (!skipped && name.equals("img"))
				{
					noteIcon(attribute(ALT_ATTR, attrs));
				}
				if (!skipped && name.equals("br"))
				{
					if (cellDepth > 0)
					{
						space();
					}
					else
					{
						newline();
					}
				}
				return;
			}

			Frame frame = new Frame(name, skipped, name.equals("ol"));
			stack.push(frame);
			if (skipped)
			{
				return;
			}
			if (name.equals("a"))
			{
				noteLinkTitle(attribute(TITLE_ATTR, attrs));
			}
			if (name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1)))
			{
				blockBreak();
				flushSpace();
				int level = Math.max(1, Math.min(6, name.charAt(1) - '0'));
				for (int i = 0; i < level; i++)
				{
					out.append('#');
				}
				out.append(' ');
				pendingSpace = false;
			}
			else if (name.equals("ul") || name.equals("ol"))
			{
				blockBreak();
				listDepth++;
			}
			else if (name.equals("li"))
			{
				blockBreak();
				flushSpace();
				Frame list = enclosing("ul", "ol");
				for (int i = 1; i < listDepth; i++)
				{
					out.append("  ");
				}
				if (list != null && list.ordered)
				{
					out.append(++list.counter).append(". ");
				}
				else
				{
					out.append("- ");
				}
				pendingSpace = false;
			}
			else if (name.equals("tr"))
			{
				blockBreak();
			}
			else if (name.equals("td") || name.equals("th"))
			{
				Frame row = enclosing("tr");
				if (row != null && row.cells++ > 0)
				{
					out.append(" | ");
					pendingSpace = false;
				}
				frame.startLength = out.length();
				cellDepth++;
			}
			else if (name.equals("table"))
			{
				blockBreak();
			}
			else if (BLOCK_TAGS.contains(name))
			{
				blockBreak();
			}
		}

		private void endElement(String name)
		{
			boolean open = false;
			for (Frame frame : stack)
			{
				if (frame.name.equals(name))
				{
					open = true;
					break;
				}
			}
			if (!open)
			{
				return;
			}
			Frame frame;
			do
			{
				frame = stack.pop();
				if (!frame.skipped)
				{
					closeFrame(frame);
				}
			}
			while (!frame.name.equals(name));
		}

		private void closeFrame(Frame frame)
		{
			String name = frame.name;
			if (name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1)))
			{
				blockBreak();
			}
			else if (name.equals("ul") || name.equals("ol"))
			{
				listDepth--;
				blockBreak();
			}
			else if (name.equals("li") || name.equals("tr") || name.equals("table") || BLOCK_TAGS.contains(name))
			{
				blockBreak();
			}
			else if (name.equals("td") || name.equals("th"))
			{
				cellDepth--;
				// An icon-only cell (e.g. a stat column headed by a sword icon) reads as its alt text.
				Frame row = enclosing("tr");
				if (out.length() == frame.startLength)
				{
					if (frame.iconAlt.length() > 0)
					{
						out.append(frame.iconAlt);
						forgetFallback(row);
					}
					else if (frame.linkTitles.length() > 0)
					{
						if (row != null)
						{
							row.fallbackStart = out.length();
							row.fallbackText = frame.linkTitles.toString();
						}
						out.append(frame.linkTitles);
					}
					else
					{
						forgetFallback(row);
					}
				}
				else if (row != null && row.fallbackText != null)
				{
					// An icon cell followed by a text cell naming the same thing: keep only the text.
					if (out.substring(frame.startLength).trim().equals(row.fallbackText))
					{
						out.delete(row.fallbackStart, row.fallbackStart + row.fallbackText.length());
					}
					forgetFallback(row);
				}
			}
		}

		private static void forgetFallback(Frame row)
		{
			if (row != null)
			{
				row.fallbackText = null;
			}
		}

		/**
		 * Remembers a link's title for the table cell it sits in; used only if the cell has no text and no icon
		 * alt. Item icons such as a required tool are an image inside a link titled with the item's name.
		 */
		private void noteLinkTitle(String title)
		{
			Frame cell = enclosing("td", "th");
			if (cell == null || title == null)
			{
				return;
			}
			String cleaned = decode(title).trim();
			// Item names have no ':' (file links) or '/' (sub-pages such as "Weapons/Categories").
			if (cleaned.isEmpty() || cleaned.length() > MAX_ICON_ALT_LENGTH || cleaned.indexOf(':') >= 0
				|| cleaned.indexOf('/') >= 0)
			{
				return;
			}
			for (String existing : cell.linkTitles.toString().split(", "))
			{
				if (existing.equals(cleaned))
				{
					return;
				}
			}
			if (cell.linkTitles.length() > 0)
			{
				cell.linkTitles.append(", ");
			}
			cell.linkTitles.append(cleaned);
		}

		/** Remembers an icon's alt text for the table cell it sits in; used only if the cell has no text. */
		private void noteIcon(String alt)
		{
			Frame cell = enclosing("td", "th");
			if (cell == null || alt == null)
			{
				return;
			}
			String cleaned = decode(alt).replaceAll("(?i)\\.(png|gif|jpe?g|svg)$", "").replace('_', ' ').trim();
			// Stat icons have short alts ("Stab"); drop-table icons carry long descriptive ones that repeat the row.
			if (!cleaned.isEmpty() && cleaned.length() <= MAX_ICON_ALT_LENGTH && cleaned.indexOf(':') < 0)
			{
				if (cell.iconAlt.length() > 0)
				{
					cell.iconAlt.append(' ');
				}
				cell.iconAlt.append(cleaned);
			}
		}

		private Frame enclosing(String... names)
		{
			for (Frame frame : stack)
			{
				for (String name : names)
				{
					if (frame.name.equals(name))
					{
						return frame;
					}
				}
			}
			return null;
		}

		private static boolean hiddenByAttributes(String attrs)
		{
			if (attrs.isEmpty())
			{
				return false;
			}
			String style = attribute(STYLE_ATTR, attrs);
			if (style != null && style.replace(" ", "").toLowerCase().contains("display:none"))
			{
				return true;
			}
			String id = attribute(ID_ATTR, attrs);
			if ("toc".equals(id) || "catlinks".equals(id))
			{
				return true;
			}
			String classes = attribute(CLASS_ATTR, attrs);
			if (classes != null)
			{
				for (String token : classes.trim().split("\\s+"))
				{
					if (SKIPPED_CLASSES.contains(token) || token.startsWith("navbox"))
					{
						return true;
					}
				}
			}
			return false;
		}

		private static String attribute(Pattern pattern, String attrs)
		{
			Matcher matcher = pattern.matcher(attrs);
			if (!matcher.find())
			{
				return null;
			}
			for (int group = 1; group <= 3; group++)
			{
				if (matcher.group(group) != null)
				{
					return matcher.group(group);
				}
			}
			return null;
		}

		private void text(String raw)
		{
			Frame frame = stack.peek();
			if (frame != null && frame.skipped)
			{
				return;
			}
			String decoded = decode(raw);
			for (int i = 0; i < decoded.length(); i++)
			{
				char c = decoded.charAt(i);
				if (Character.isWhitespace(c) || c == '\u00A0')
				{
					pendingSpace = true;
					continue;
				}
				flushSpace();
				out.append(c);
			}
		}

		/** Writes a pending word separator, unless the output already ends with one. */
		private void flushSpace()
		{
			if (pendingSpace && out.length() > 0 && !endsWithBreakOrSpace())
			{
				out.append(' ');
			}
			pendingSpace = false;
		}

		private boolean endsWithBreakOrSpace()
		{
			char last = out.charAt(out.length() - 1);
			return last == '\n' || last == ' ';
		}

		private void space()
		{
			pendingSpace = true;
		}

		private void newline()
		{
			if (out.length() > 0 && out.charAt(out.length() - 1) != '\n')
			{
				out.append('\n');
			}
			pendingSpace = false;
		}

		private void blockBreak()
		{
			if (cellDepth > 0)
			{
				space();
			}
			else
			{
				newline();
			}
		}
	}

	private static String decode(String text)
	{
		if (text.indexOf('&') < 0)
		{
			return text;
		}
		String result = text
			.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
			.replace("&apos;", "'").replace("&ndash;", "-").replace("&mdash;", "-").replace("&hellip;", "...");
		Matcher matcher = NUMERIC_ENTITY.matcher(result);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find())
		{
			String number = matcher.group(1);
			String replacement;
			try
			{
				int codePoint = number.charAt(0) == 'x' || number.charAt(0) == 'X'
					? Integer.parseInt(number.substring(1), 16) : Integer.parseInt(number);
				replacement = new String(Character.toChars(codePoint));
			}
			catch (IllegalArgumentException e)
			{
				replacement = "";
			}
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(buffer);
		// &amp; last, so "&amp;lt;" stays a literal "&lt;".
		return buffer.toString().replace("&amp;", "&");
	}

	/** Collapses spacing, drops empty table rows and bare list markers, and keeps list indentation. */
	private static String tidy(String raw)
	{
		StringBuilder result = new StringBuilder();
		boolean lastBlank = true;
		for (String line : raw.split("\n"))
		{
			int indent = 0;
			while (indent < line.length() && line.charAt(indent) == ' ')
			{
				indent++;
			}
			String body = line.trim().replaceAll("[ \\t]{2,}", " ").replaceAll("(\\s*\\|)+$", "");
			if (body.matches("[|\\s]*") || body.matches("-|\\d+\\."))
			{
				continue;
			}
			if (body.startsWith("#") && !lastBlank)
			{
				result.append('\n');
			}
			boolean listItem = body.startsWith("- ") || body.matches("\\d+\\. .*");
			for (int i = 0; listItem && i < indent; i++)
			{
				result.append(' ');
			}
			result.append(body).append('\n');
			lastBlank = false;
		}
		return result.toString().trim();
	}
}
