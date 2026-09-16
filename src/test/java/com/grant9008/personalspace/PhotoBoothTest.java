package com.grant9008.personalspace;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

public class PhotoBoothTest
{
	private static BufferedImage frame(int w, int h, Color color)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, w, h);
		g.dispose();
		return img;
	}

	@Test
	public void beforeAndAfterSitSideBySideWithACaptionStrip()
	{
		BufferedImage before = frame(400, 300, Color.BLUE);
		BufferedImage after = frame(400, 300, Color.GREEN);
		BufferedImage out = PhotoBooth.compose(before, after, "12 players spread out on 4 crowded tiles");

		Assert.assertTrue("wide enough for both", out.getWidth() >= 800);
		Assert.assertTrue("taller than the frames, for the caption", out.getHeight() > 300);
		// Middle of the left half is the before frame, middle of the right half is the after frame.
		Assert.assertEquals(Color.BLUE.getRGB(), out.getRGB(200, 250));
		Assert.assertEquals(Color.GREEN.getRGB(), out.getRGB(out.getWidth() - 200, 250));
	}

	@Test
	public void savesIntoItsOwnFolder() throws Exception
	{
		File dir = Files.createTempDirectory("ps-photo").toFile();
		File saved = PhotoBooth.save(frame(10, 10, Color.RED), dir);
		Assert.assertTrue(saved.isFile());
		Assert.assertEquals(PhotoBooth.FOLDER_NAME, saved.getParentFile().getName());
		Assert.assertTrue(saved.getName().startsWith("Before and after"));
		Assert.assertTrue(saved.getName().endsWith(".png"));
	}
}
