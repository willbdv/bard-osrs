package com.bardosrs.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class MarkdownTest
{
	private static Markdown.Block only(String text)
	{
		List<Markdown.Block> blocks = Markdown.parse(text);
		assertEquals(blocks.toString(), 1, blocks.size());
		return blocks.get(0);
	}

	@Test
	public void plainTextIsOneParagraphSpan()
	{
		Markdown.Block block = only("Just text.");
		assertEquals(Markdown.Kind.PARAGRAPH, block.kind);
		assertEquals(1, block.spans.size());
		assertFalse(block.spans.get(0).bold);
	}

	@Test
	public void boldAndItalicAndCodeSpans()
	{
		Markdown.Block block = only("a **bold** b *it* c `code` d");
		assertEquals("a bold b it c code d", block.plainText());
		assertEquals(7, block.spans.size());
		assertTrue(block.spans.get(1).bold);
		assertTrue(block.spans.get(3).italic);
		assertTrue(block.spans.get(5).code);
		assertFalse(block.spans.get(0).bold);
	}

	@Test
	public void bulletsAndNumberedItemsKeepStyledContent()
	{
		List<Markdown.Block> blocks = Markdown.parse("- **42-46:** fishbowls\n* second\n3. third");
		assertEquals(3, blocks.size());
		assertEquals(Markdown.Kind.LIST_ITEM, blocks.get(0).kind);
		assertEquals("- ", blocks.get(0).marker);
		assertTrue(blocks.get(0).spans.get(0).bold);
		assertEquals("- 42-46: fishbowls", blocks.get(0).plainText());
		assertEquals("- ", blocks.get(1).marker);
		assertEquals("3. ", blocks.get(2).marker);
	}

	@Test
	public void nestedListItemsGetALevel()
	{
		List<Markdown.Block> blocks = Markdown.parse("- top\n  - nested\n    - deeper");
		assertEquals(0, blocks.get(0).level);
		assertEquals(1, blocks.get(1).level);
		assertEquals(2, blocks.get(2).level);
	}

	@Test
	public void headingsAreBold()
	{
		Markdown.Block block = only("## Plan");
		assertEquals(Markdown.Kind.HEADING, block.kind);
		assertEquals("Plan", block.plainText());
		assertTrue(block.spans.get(0).bold);
	}

	@Test
	public void unclosedMarkersStayLiteralWhileStreaming()
	{
		assertEquals("**Ma", only("**Ma").plainText());
		assertEquals("use `tick", only("use `tick").plainText());
		assertEquals("*x", only("*x").plainText());
	}

	@Test
	public void asterisksUsedAsMultiplicationAreNotItalics()
	{
		Markdown.Block block = only("2 * 3 * 4");
		assertEquals("2 * 3 * 4", block.plainText());
		assertEquals(1, block.spans.size());
	}

	@Test
	public void lineStartingWithBoldIsNotAList()
	{
		Markdown.Block block = only("**Main:** buy it");
		assertEquals(Markdown.Kind.PARAGRAPH, block.kind);
		assertEquals("Main: buy it", block.plainText());
	}

	@Test
	public void codeFencesBecomeCodeBlocks()
	{
		List<Markdown.Block> blocks = Markdown.parse("before\n```\nline *one*\n  indented\n```\nafter");
		assertEquals(4, blocks.size());
		assertEquals(Markdown.Kind.CODE, blocks.get(1).kind);
		assertEquals("line *one*", blocks.get(1).plainText());
		assertEquals("  indented", blocks.get(2).plainText());
		assertEquals("after", blocks.get(3).plainText());
	}

	@Test
	public void tablesBecomePipeSeparatedLinesWithBoldHeader()
	{
		List<Markdown.Block> blocks = Markdown.parse("| Level | Method |\n|---|---|\n| 42 | Fishbowls |\n| 46 | Orbs |");
		assertEquals(3, blocks.size());
		assertEquals("Level | Method", blocks.get(0).plainText());
		assertTrue(blocks.get(0).spans.get(0).bold);
		assertEquals("42 | Fishbowls", blocks.get(1).plainText());
		assertFalse(blocks.get(1).spans.get(0).bold);
	}

	@Test
	public void blankLinesAndRulesProduceNoBlocks()
	{
		List<Markdown.Block> blocks = Markdown.parse("one\n\n---\n\ntwo");
		assertEquals(2, blocks.size());
	}
}
