package org.recompile.castle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/** User settings, stored in settings.properties next to the game. */
public final class Settings
{
	/** One playable build of the game. */
	public static final class Edition
	{
		public final String id, name, detail, jar;
		public final int width, height, origMs;
		/** Physics steps (1/256 s) offered in smooth mode, best first. Empty = original speed only. */
		public final int[] smoothSteps;

		Edition(String id, String name, String detail, String jar, int width, int height, int origMs, int... smoothSteps)
		{
			this.id = id; this.name = name; this.detail = detail; this.jar = jar;
			this.width = width; this.height = height; this.origMs = origMs; this.smoothSteps = smoothSteps;
		}

		public double fpsFor(int w) { return 1000.0 * 16 / (origMs * w); }
	}

	public static final Edition[] EDITIONS =
	{
		new Edition("hd", "HD Edition", "352x416, side-scrolling levels", "app/games/platformer.jar", 352, 416, 83, 1, 2, 3),
		new Edition("adventure", "Full Adventure", "240x320, castle hub + levels", "app/games/adventure.jar", 240, 320, 62),
	};

	public static final String[] FILTERS = { "Sharp + Smooth", "Pixel Perfect", "Smooth", "HQ2x" };
	public static final String[] FILTER_INFO =
	{
		"Crisp pixels, scaled evenly to any window size (recommended).",
		"Exact whole-number scaling only. Sharpest, may leave black borders.",
		"Soft bilinear scaling, like a phone screen up close.",
		"Rounds off jagged pixel edges (Scale2x), then scales smoothly.",
	};

	public int edition = 0;
	/** Index into the edition's smoothSteps, or -1 for original speed. */
	public int smoothIndex = 0;
	public boolean fullscreen = false;
	public int windowScale = 0; // 0 = automatic (largest that fits)
	public int filter = 0;
	public boolean vsync = true;
	public boolean sound = true;
	public boolean showFps = false;

	private final File file;

	public Settings(File file) { this.file = file; }

	public Edition edition() { return EDITIONS[Math.max(0, Math.min(edition, EDITIONS.length - 1))]; }

	public boolean smooth() { return smoothIndex >= 0 && smoothIndex < edition().smoothSteps.length; }

	public int stepW() { return smooth() ? edition().smoothSteps[smoothIndex] : 16; }

	public String frameRateLabel()
	{
		Edition e = edition();
		if (!smooth()) { return Math.round(1000.0 / e.origMs) + " FPS  (original)"; }
		int w = e.smoothSteps[smoothIndex];
		String tag = w == 1 ? "  (ultra smooth)" : (w == 2 ? "  (smooth)" : "  (lighter)");
		return Math.round(e.fpsFor(w)) + " FPS" + tag;
	}

	/** Cycle frame rate options: smooth steps first, then original. */
	public void cycleFrameRate(int dir)
	{
		int n = edition().smoothSteps.length + 1; // last slot = original
		int cur = smooth() ? smoothIndex : n - 1;
		cur = Math.floorMod(cur + dir, n);
		smoothIndex = cur == n - 1 ? -1 : cur;
	}

	public void load()
	{
		if (!file.isFile()) { return; }
		Properties p = new Properties();
		try (InputStream in = new FileInputStream(file)) { p.load(in); }
		catch (Exception e) { return; }
		edition = intOf(p, "edition", edition);
		smoothIndex = intOf(p, "smoothIndex", smoothIndex);
		fullscreen = boolOf(p, "fullscreen", fullscreen);
		windowScale = intOf(p, "windowScale", windowScale);
		filter = Math.floorMod(intOf(p, "filter", filter), FILTERS.length);
		vsync = boolOf(p, "vsync", vsync);
		sound = boolOf(p, "sound", sound);
		showFps = boolOf(p, "showFps", showFps);
		if (edition < 0 || edition >= EDITIONS.length) { edition = 0; }
		if (smoothIndex >= edition().smoothSteps.length) { smoothIndex = edition().smoothSteps.length > 0 ? 0 : -1; }
	}

	public void save()
	{
		Properties p = new Properties();
		p.setProperty("edition", "" + edition);
		p.setProperty("smoothIndex", "" + smoothIndex);
		p.setProperty("fullscreen", "" + fullscreen);
		p.setProperty("windowScale", "" + windowScale);
		p.setProperty("filter", "" + filter);
		p.setProperty("vsync", "" + vsync);
		p.setProperty("sound", "" + sound);
		p.setProperty("showFps", "" + showFps);
		try (OutputStream out = new FileOutputStream(file)) { p.store(out, "Castle of Magic PC settings"); }
		catch (Exception e) { System.err.println("Could not save settings: " + e); }
	}

	private static int intOf(Properties p, String k, int def)
	{
		try { return Integer.parseInt(p.getProperty(k, "" + def).trim()); } catch (Exception e) { return def; }
	}

	private static boolean boolOf(Properties p, String k, boolean def)
	{
		String v = p.getProperty(k);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}
}
