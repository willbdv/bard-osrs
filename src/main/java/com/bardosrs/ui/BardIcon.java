package com.bardosrs.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

public final class BardIcon
{
	private static final int SIZE = 16;

	private BardIcon()
	{
	}

	/** A small gold "B" badge, drawn at runtime so the plugin ships no image assets. */
	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(ColorScheme.BRAND_ORANGE);
		g.fillRoundRect(0, 0, SIZE, SIZE, 6, 6);
		g.setColor(new Color(30, 30, 30));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		FontMetrics metrics = g.getFontMetrics();
		int x = (SIZE - metrics.stringWidth("B")) / 2;
		int y = (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
		g.drawString("B", x, y);
		g.dispose();
		return image;
	}
}
