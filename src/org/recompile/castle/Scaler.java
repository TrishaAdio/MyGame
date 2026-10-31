package org.recompile.castle;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.VolatileImage;

/**
 * Draws the game's frame to the window.
 *
 * The CPU only touches the small game-resolution image. It is uploaded once per frame to a
 * VolatileImage (video memory), and the GPU does the scaling through Java2D's Direct3D pipeline.
 */
final class Scaler
{
	private int srcW, srcH;
	private BufferedImage frame;      // game resolution (or 2x for HQ2x), CPU side
	private int[] framePixels;
	private VolatileImage upload;     // same size as frame, GPU side
	private VolatileImage prescaled;  // integer nearest-neighbour upscale for "Sharp + Smooth"
	private int prescaleFactor;

	/** Copies a new game frame in. Called on the render thread. */
	void setFrame(int[] argb, int w, int h, int filter)
	{
		boolean hq = filter == 3;
		int fw = hq ? w * 2 : w, fh = hq ? h * 2 : h;
		if (frame == null || frame.getWidth() != fw || frame.getHeight() != fh)
		{
			frame = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB);
			framePixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
			upload = null; prescaled = null;
		}
		srcW = fw; srcH = fh;
		if (hq) { scale2x(argb, w, h, framePixels); }
		else { System.arraycopy(argb, 0, framePixels, 0, w * h); }
		frameDirty = true;
	}

	private boolean frameDirty;

	boolean hasFrame() { return frame != null; }

	/** Rectangle the game occupies inside a canvas of the given size. */
	static Rectangle fit(int canvasW, int canvasH, int gameW, int gameH, boolean integerOnly)
	{
		double s = Math.min(canvasW / (double) gameW, canvasH / (double) gameH);
		if (integerOnly && s >= 1) { s = Math.floor(s); }
		int w = (int) Math.round(gameW * s), h = (int) Math.round(gameH * s);
		return new Rectangle((canvasW - w) / 2, (canvasH - h) / 2, w, h);
	}

	void draw(Graphics2D g, GraphicsConfiguration gc, int canvasW, int canvasH, int gameW, int gameH, int filter)
	{
		if (frame == null) { return; }
		Rectangle r = fit(canvasW, canvasH, gameW, gameH, filter == 1);

		// 1) upload the CPU frame to video memory
		if (upload == null || upload.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) { upload = gc.createCompatibleVolatileImage(srcW, srcH, Transparency.OPAQUE); frameDirty = true; prescaled = null; }
		if (frameDirty || upload.contentsLost())
		{
			Graphics2D ug = upload.createGraphics();
			ug.setComposite(AlphaComposite.Src);
			ug.drawImage(frame, 0, 0, null);
			ug.dispose();
			frameDirty = false;
			if (filter == 0 || filter == 3) { prescaleFresh = false; }
		}

		switch (filter)
		{
			case 1: // Pixel perfect: nearest neighbour, whole-number scale
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g.drawImage(upload, r.x, r.y, r.width, r.height, null);
				break;
			case 2: // Smooth
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(upload, r.x, r.y, r.width, r.height, null);
				break;
			default: // Sharp + Smooth (and HQ2x): nearest-neighbour to the next whole multiple, then bilinear down
				int k = Math.max(1, (int) Math.ceil(Math.max(r.width / (double) srcW, r.height / (double) srcH)));
				if (k == 1)
				{
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g.drawImage(upload, r.x, r.y, r.width, r.height, null);
					break;
				}
				if (prescaled == null || prescaleFactor != k || prescaled.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE)
				{
					prescaled = gc.createCompatibleVolatileImage(srcW * k, srcH * k, Transparency.OPAQUE);
					prescaleFactor = k;
					prescaleFresh = false;
				}
				if (!prescaleFresh || prescaled.contentsLost())
				{
					Graphics2D pg = prescaled.createGraphics();
					pg.setComposite(AlphaComposite.Src);
					pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					pg.drawImage(upload, 0, 0, srcW * k, srcH * k, null);
					pg.dispose();
					prescaleFresh = true;
				}
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(prescaled, r.x, r.y, r.width, r.height, null);
		}
	}

	private boolean prescaleFresh;

	/** Software path used by tests and as a fallback: same result drawn from the CPU image. */
	void drawSoftware(Graphics2D g, int canvasW, int canvasH, int gameW, int gameH, int filter)
	{
		if (frame == null) { return; }
		Rectangle r = fit(canvasW, canvasH, gameW, gameH, filter == 1);
		if (filter == 0 || filter == 3)
		{
			int k = Math.max(1, (int) Math.ceil(Math.max(r.width / (double) srcW, r.height / (double) srcH)));
			BufferedImage big = new BufferedImage(srcW * k, srcH * k, BufferedImage.TYPE_INT_RGB);
			Graphics2D bg = big.createGraphics();
			bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			bg.drawImage(frame, 0, 0, srcW * k, srcH * k, null);
			bg.dispose();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(big, r.x, r.y, r.width, r.height, null);
		}
		else
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter == 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(frame, r.x, r.y, r.width, r.height, null);
		}
	}

	/** Scale2x / EPX: doubles resolution and rounds off staircase edges without blurring. */
	static void scale2x(int[] src, int w, int h, int[] dst)
	{
		int dw = w * 2;
		for (int y = 0; y < h; y++)
		{
			int row = y * w;
			int up = (y > 0 ? y - 1 : y) * w;
			int down = (y < h - 1 ? y + 1 : y) * w;
			int o = y * 2 * dw;
			for (int x = 0; x < w; x++)
			{
				int p = src[row + x];
				int a = src[up + x];
				int b = src[row + (x < w - 1 ? x + 1 : x)];
				int c = src[row + (x > 0 ? x - 1 : x)];
				int d = src[down + x];
				int e0 = p, e1 = p, e2 = p, e3 = p;
				if (c == a && c != d && a != b) { e0 = a; }
				if (a == b && a != c && b != d) { e1 = b; }
				if (d == c && d != b && c != a) { e2 = c; }
				if (b == d && b != a && d != c) { e3 = d; }
				int ox = o + x * 2;
				dst[ox] = e0; dst[ox + 1] = e1;
				dst[ox + dw] = e2; dst[ox + dw + 1] = e3;
			}
		}
	}
}
