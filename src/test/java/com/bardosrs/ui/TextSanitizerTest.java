package com.bardosrs.ui;

import static org.junit.Assert.assertEquals;

import java.awt.Font;
import org.junit.Test;

public class TextSanitizerTest
{
	@Test
	public void mapsTypographicCharactersToAscii()
	{
		assertEquals("I'm Bard, you're \"fine\"", TextSanitizer.sanitize("I’m Bard, you’re “fine”"));
		assertEquals("42-56 Crafting", TextSanitizer.sanitize("42–56 Crafting"));
		assertEquals("a - b", TextSanitizer.sanitize("a — b"));
		assertEquals("Wait... go -> north", TextSanitizer.sanitize("Wait… go → north"));
		assertEquals("a >= b", TextSanitizer.sanitize("a ≥ b"));
	}

	@Test
	public void normalisesSpacesAndRemovesZeroWidthCharacters()
	{
		assertEquals("a b", TextSanitizer.sanitize("a b"));
		assertEquals("ab", TextSanitizer.sanitize("a​b"));
	}

	@Test
	public void leavesPlainAsciiAlone()
	{
		String text = "Glassblowing: 42.5 XP each (x2)\nnext line";
		assertEquals(text, TextSanitizer.sanitize(text));
	}

	@Test
	public void keepsDisplayableTextAndNewlines()
	{
		Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		assertEquals("plain text\nnext", TextSanitizer.dropUndisplayable("plain text\nnext", font));
		assertEquals("ab", TextSanitizer.dropUndisplayable("a\u0001b", font));
	}
}
