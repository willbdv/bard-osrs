package com.bardosrs.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HtmlTextTest
{
	@Test
	public void paragraphsAndInlineMarkup()
	{
		assertEquals("Hello bold world, and a link.\nSecond paragraph.",
			HtmlText.toText("<p>Hello <b>bold</b> world, and <a href=\"/w/X\">a link</a>.</p><p>Second paragraph.</p>"));
	}

	@Test
	public void headingsGetHashesAndAreSeparatedByABlankLine()
	{
		String text = HtmlText.toText("<p>Intro</p><div class=\"mw-heading mw-heading2\"><h2 id=\"A\">Strategy</h2></div><p>Body</p>");
		assertEquals("Intro\n\n## Strategy\nBody", text);
	}

	@Test
	public void bulletedNumberedAndNestedLists()
	{
		String text = HtmlText.toText("<ul><li>one</li><li>two<ul><li>nested</li></ul></li></ul><ol><li>first</li><li>second</li></ol>");
		assertEquals("- one\n- two\n  - nested\n1. first\n2. second", text);
	}

	@Test
	public void tableRowsBecomePipeSeparatedLines()
	{
		String text = HtmlText.toText(
			"<table class=\"infobox\"><tbody>"
				+ "<tr><th colspan=\"2\">Dragon scimitar</th></tr>"
				+ "<tr><td class=\"infobox-padding\"></td></tr>"
				+ "<tr><th>Members</th><td>Yes</td></tr>"
				+ "<tr><th>Value</th><td>100,000 <small>coins</small></td></tr>"
				+ "</tbody></table>");
		assertEquals("Dragon scimitar\nMembers | Yes\nValue | 100,000 coins", text);
	}

	@Test
	public void blockContentInsideCellsStaysOnOneLine()
	{
		String text = HtmlText.toText("<table><tr><td><p>a</p><p>b</p><ul><li>c</li><li>d</li></ul></td><td>e<br />f</td></tr></table>");
		assertEquals("a b - c - d | e f", text);
	}

	@Test
	public void dropsNavigationFootnotesHiddenBlocksAndEditLinks()
	{
		String text = HtmlText.toText(
			"<div style=\"display: none\">hidden</div>"
				+ "<p>Keep this.<sup class=\"reference\"><a href=\"#cite\">[1]</a></sup></p>"
				+ "<h2>Title<span class=\"mw-editsection\">[edit]</span></h2>"
				+ "<div class=\"toc\">Contents</div>"
				+ "<table class=\"navbox\"><tr><td>Navigation</td></tr></table>"
				+ "<script>var x = 1;</script><style>.a{}</style>"
				+ "<!-- a comment --><p>And this.</p>");
		assertEquals("Keep this.\n\n## Title\nAnd this.", text);
	}

	@Test
	public void skippedContainersSkipEverythingInsideThem()
	{
		String text = HtmlText.toText("<div class=\"navbox-group\"><p>deep <b>nested</b></p><ul><li>x</li></ul></div><p>after</p>");
		assertEquals("after", text);
	}

	@Test
	public void imagesAndCollapsibleToggleLabelsAreDropped()
	{
		String text = HtmlText.toText("<p><img src=\"x.png\" alt=\"icon\" />Item <input type=\"checkbox\"><label>Show</label> name</p>");
		assertEquals("Item name", text);
	}

	@Test
	public void decodesEntities()
	{
		assertEquals("Tom & Jerry <3 \"quotes\" it's 5 x", HtmlText.toText("<p>Tom &amp; Jerry &lt;3 &quot;quotes&quot; it&#39;s&nbsp;&#x35; x</p>"));
		assertEquals("&lt; stays literal", HtmlText.toText("<p>&amp;lt; stays literal</p>"));
	}

	@Test
	public void attributeValuesMayContainAngleBrackets()
	{
		assertEquals("text", HtmlText.toText("<span title=\"a > b\">text</span>"));
	}

	@Test
	public void toleratesBrokenMarkup()
	{
		assertEquals("a b", HtmlText.toText("<p>a</i> <b>b"));
		assertEquals("", HtmlText.toText(null));
		assertEquals("", HtmlText.toText(""));
	}

	@Test
	public void truncateCutsAtALineBreak()
	{
		String text = "line one\nline two\nline three";
		assertEquals("line one\nline two", HtmlText.truncate(text, 20));
		assertEquals(text, HtmlText.truncate(text, 100));
		assertFalse(HtmlText.truncate("x".repeat(50), 10).length() > 10);
		assertTrue(HtmlText.truncate("abc", 2).length() <= 2);
	}

	@Test
	public void iconOnlyCellsReadAsTheirAltText()
	{
		String text = HtmlText.toText(
			"<table><tr><th><a href=\"/w/Stab\"><img alt=\"Stab\" src=\"a.png\" /></a></th>"
				+ "<th><span><img alt=\"Slash\" src=\"b.png\" /></span></th></tr>"
				+ "<tr><td>+8</td><td>+67</td></tr></table>");
		assertEquals("Stab | Slash\n+8 | +67", text);
	}

	@Test
	public void iconsNextToTextDoNotDuplicateIt()
	{
		assertEquals("Dragon bones | 100", HtmlText.toText(
			"<table><tr><td><img alt=\"Dragon bones.png\" src=\"x\" /> Dragon bones</td><td><img alt=\"Coins\" />100</td></tr></table>"));
	}

	@Test
	public void iconAltFileNamesAreCleaned()
	{
		assertEquals("Dragon bones", HtmlText.toText("<table><tr><td><img alt=\"Dragon_bones.png\" /></td></tr></table>"));
	}

	@Test
	public void iconsOutsideTablesAreStillDropped()
	{
		assertEquals("Hello", HtmlText.toText("<p><img alt=\"Decoration\" />Hello</p>"));
	}

	@Test
	public void descriptiveIconAltsAreIgnored()
	{
		// The icon cell stays empty instead of repeating the whole row as a sentence.
		assertEquals("| Item | 2", HtmlText.toText(
			"<table><tr><td><img alt=\"Dragon bones.png: Vorkath drops Dragon bones with rarity Always\" /></td>"
				+ "<td>Item</td><td>2</td></tr></table>"));
	}

	@Test
	public void iconOnlyCellsFallBackToTheLinkTitle()
	{
		// Shaped like the real Creation table: the required tool is an icon inside a link titled with its name.
		String text = HtmlText.toText(
			"<table><tr><th>Tools</th><td><span class=\"inventory-image\"><a href=\"/w/Glassblowing_pipe\" "
				+ "title=\"Glassblowing pipe\"><img src=\"x.png\" /></a></span></td><th>Facilities</th><td>None</td></tr></table>");
		assertEquals("Tools | Glassblowing pipe | Facilities | None", text);
	}

	@Test
	public void severalIconsInOneCellListEachTitleOnce()
	{
		String text = HtmlText.toText(
			"<table><tr><th>Tools</th><td><a title=\"Hammer\"><img src=\"a\" /></a>"
				+ "<a title=\"Glassblowing pipe\"><img src=\"b\" /></a><a title=\"Hammer\"><img src=\"c\" /></a></td></tr></table>");
		assertEquals("Tools | Hammer, Glassblowing pipe", text);
	}

	@Test
	public void linkTitlesNeverDuplicateCellTextOrIconAlt()
	{
		assertEquals("Members | Yes", HtmlText.toText(
			"<table><tr><th><a title=\"Members\">Members</a></th><td><a title=\"Yes\">Yes</a></td></tr></table>"));
		assertEquals("Stab | Slash", HtmlText.toText(
			"<table><tr><th><a title=\"Stab\"><img alt=\"Stab\" /></a></th><th><a title=\"Slash style\"><img alt=\"Slash\" /></a></th></tr></table>"));
		// File links carry a "File:" title, which is noise, not an item name.
		assertEquals("Before", HtmlText.toText(
			"<table><tr><td>Before</td><td><a title=\"File:X.png\"><img src=\"x\" /></a></td></tr></table>"));
	}

	@Test
	public void anIconCellIsDroppedWhenTheNextCellNamesTheSameThing()
	{
		String text = HtmlText.toText(
			"<table><tr><th colspan=\"2\">Item</th><th>Quantity</th><th>Cost</th></tr>"
				+ "<tr><td><a title=\"Molten glass\"><img src=\"x\" /></a></td><td><a title=\"Molten glass\">Molten glass</a></td>"
				+ "<td>1</td><td>89</td></tr></table>");
		assertEquals("Item | Quantity | Cost\n| Molten glass | 1 | 89", text);
	}

	@Test
	public void genuineRepeatedCellsAreLeftAlone()
	{
		assertEquals("+0 | +0 | +0", HtmlText.toText("<table><tr><td>+0</td><td>+0</td><td>+0</td></tr></table>"));
		assertEquals("Yes | Yes", HtmlText.toText("<table><tr><td>Yes</td><td>Yes</td></tr></table>"));
	}

	@Test
	public void trailingEmptyCellsAreTrimmed()
	{
		assertEquals("Attack bonuses", HtmlText.toText("<table><tr><th>Attack bonuses</th><td></td><td></td></tr></table>"));
	}

	@Test
	public void subPageLinkTitlesAreNotTreatedAsItemNames()
	{
		assertEquals("Chop | Slash", HtmlText.toText(
			"<table><tr><td><a title=\"Weapons/Categories\"><img src=\"x\" /></a></td><td>Chop</td><td>Slash</td></tr></table>")
			.replaceFirst("^\\| ", ""));
	}
}
