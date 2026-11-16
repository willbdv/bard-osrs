package com.bardosrs.llm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LeakedMarkupTest
{
	private static final String BAR = "｜";

	@Test
	public void findsDeepSeekAndOtherToolCallSyntax()
	{
		assertEquals(4, LeakedMarkup.find("text<" + BAR + "DSML" + BAR + "function_calls>", 0));
		assertEquals(0, LeakedMarkup.find("<|python_tag|>x", 0));
		assertEquals(2, LeakedMarkup.find("a <tool_call>{}", 0));
		assertEquals(0, LeakedMarkup.find("<function_calls><invoke>", 0));
		assertEquals(0, LeakedMarkup.find("<DSML calls>", 0));
		assertEquals(5, LeakedMarkup.find("ok.  <TOOL_CALLS>", 0));
	}

	@Test
	public void ignoresOrdinaryAngleBrackets()
	{
		assertEquals(-1, LeakedMarkup.find("if a < b and c<d, x <3 and <b>bold</b> and <tool>", 0));
		assertEquals(-1, LeakedMarkup.find("use the tool_call word without a bracket", 0));
	}

	@Test
	public void searchStartsAtTheGivenOffset()
	{
		String text = "<tool_call> then more";
		assertEquals(0, LeakedMarkup.find(text, 0));
		assertEquals(-1, LeakedMarkup.find(text, 1));
	}

	@Test
	public void holdsBackOnlyTextThatCouldBecomeAMarker()
	{
		assertEquals(1, LeakedMarkup.pendingPrefixLength("hello <"));
		assertEquals(2, LeakedMarkup.pendingPrefixLength("hello <" + BAR));
		assertEquals(2, LeakedMarkup.pendingPrefixLength("hello <|"));
		assertEquals(4, LeakedMarkup.pendingPrefixLength("hello <dsm"));
		assertEquals(6, LeakedMarkup.pendingPrefixLength("x <tool_"));
		assertEquals(0, LeakedMarkup.pendingPrefixLength("hello"));
		assertEquals(0, LeakedMarkup.pendingPrefixLength("a <3"));
		assertEquals(0, LeakedMarkup.pendingPrefixLength("a < b"));
		assertEquals(0, LeakedMarkup.pendingPrefixLength("<b>bold"));
		assertEquals(0, LeakedMarkup.pendingPrefixLength(""));
	}
}
