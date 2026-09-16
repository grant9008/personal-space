package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.imageio.ImageIO;

/**
 * Builds the "before and after" picture: the same scene without and with Personal Space, side by
 * side, labelled, with a small caption. Pure image work so it can run on a background thread and be
 * unit tested.
 */
final class PhotoBooth
{
	static final String FOLDER_NAME = "Personal Space";

	private static final Color BAR = new Color(20, 20, 20);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final int GAP = 6;
	private static final int FOOTER = 34;

	private PhotoBooth()
	{
	}

	/** Progress of a photo, for the sidebar. */
	static final class Status
	{
		final String text;
		/** The saved picture, once done. */
		final File file;
		final boolean busy;
		final boolean failed;

		private Status(String text, File file, boolean busy, boolean failed)
		{
			this.text = text;
			this.file = file;
			this.busy = busy;
			this.failed = failed;
		}

		static Status working(String text)
		{
			return new Status(text, null, true, false);
		}

		static Status done(File file)
		{
			return new Status("Saved to your screenshots folder.", file, false, false);
		}

		static Status problem(String text)
		{
			return new Status(text, null, false, true);
		}
	}

	/** Place both frames side by side with BEFORE / AFTER tags and a caption strip. */
	static BufferedImage compose(BufferedImage before, BufferedImage after, String caption)
	{
		int h = Math.max(before.getHeight(), after.getHeight());
		int w = before.getWidth() + GAP + after.getWidth();
		BufferedImage out = new BufferedImage(w, h + FOOTER, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(BAR);
			g.fillRect(0, 0, w, h + FOOTER);
			g.drawImage(before, 0, 0, null);
			g.drawImage(after, before.getWidth() + GAP, 0, null);

			tag(g, "BEFORE", 10, 10, new Color(90, 90, 90));
			tag(g, "AFTER", before.getWidth() + GAP + 10, 10, ORANGE);

			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
			g.setColor(ORANGE);
			g.drawString("Personal Space", 12, h + 22);
			FontMetrics fm = g.getFontMetrics();
			int titleWidth = fm.stringWidth("Personal Space");
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(new Color(200, 200, 200));
			g.drawString(caption, 12 + titleWidth + 12, h + 22);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	private static void tag(Graphics2D g, String text, int x, int y, Color color)
	{
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text) + 16;
		int h = fm.getHeight() + 6;
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRoundRect(x, y, w, h, 10, 10);
		g.setStroke(new BasicStroke(2f));
		g.setColor(color);
		g.drawRoundRect(x, y, w, h, 10, 10);
		g.setColor(Color.WHITE);
		g.drawString(text, x + 8, y + fm.getAscent() + 3);
	}

	/** Save into {@code <screenshots>/Personal Space/}; returns the file written. */
	static File save(BufferedImage image, File screenshotsDir) throws IOException
	{
		File folder = new File(screenshotsDir, FOLDER_NAME);
		if (!folder.isDirectory() && !folder.mkdirs())
		{
			throw new IOException("Couldn't create " + folder);
		}
		String name = "Before and after " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date()) + ".png";
		File file = new File(folder, name);
		ImageIO.write(image, "png", file);
		return file;
	}
}
