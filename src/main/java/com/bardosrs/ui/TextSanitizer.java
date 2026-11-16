package com.bardosrs.ui;

import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * RuneLite draws its UI in the RuneScape pixel font, which lacks glyphs for
 * common typographic characters (curly quotes, en dashes, arrows, emoji) that
 * language models like to emit. Those render as empty boxes, so map the common
 * ones to ASCII and drop whatever the font still cannot draw.
 */
public final class TextSanitizer
{
	private static final Map<Integer, String> REPLACEMENTS = new HashMap<>();

	static
	{
		map("‘’‛′´ʼ", "'");
		map("“”‟″", "\"");
		map("‐‑‒–—―−", "-");
		map("…", "...");
		map("→", "->");
		map("←", "<-");
		map("↔", "<->");
		map("⇒", "=>");
		map("•●▪◦‣", "-");
		map("≥", ">=");
		map("≤", "<=");
		map("≠", "!=");
		map("≈", "~");
		map("×", "x");
		map("÷", "/");
		map("±", "+/-");
		map("½", "1/2");
		map("¼", "1/4");
		map("¾", "3/4");
		map("      　", " ");
		map("​‌‍⁠﻿­", "");
	}

	private TextSanitizer()
	{
	}

	private static void map(String chars, String replacement)
	{
		chars.codePoints().forEach(cp -> REPLACEMENTS.put(cp, replacement));
	}

	/** Replaces known typographic characters with ASCII equivalents. */
	public static String sanitize(String text)
	{
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp ->
		{
			String replacement = REPLACEMENTS.get(cp);
			if (replacement != null)
			{
				out.append(replacement);
			}
			else
			{
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	/** Drops characters the font cannot draw, so they do not show as boxes. Keeps newlines and tabs. */
	public static String dropUndisplayable(String text, Font font)
	{
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp ->
		{
			if (cp == '\n' || cp == '\t' || (cp >= 0x20 && font.canDisplay(cp)))
			{
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}
}
