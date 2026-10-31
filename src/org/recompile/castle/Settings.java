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
		public final int width, height;

		Edition(String id, String name, String detail, String jar, int width, int height)
		{
			this.id = id; this.name = name; this.detail = detail; this.jar = jar;
			this.width = width; this.height = height;
		}
	}

	public static final Edition[] EDITIONS =
	{
		new Edition("adventure", "Normal", "240x320, castle hub + levels, supports multiplayer", "app/games/adventure.jar", 240, 320),
		new Edition("hd", "HD", "352x416, side-scrolling levels only, single player", "app/games/platformer.jar", 352, 416),
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
	public boolean fullscreen = false;
	public int windowScale = 0; // 0 = automatic (largest that fits)
	public int filter = 0;
	public boolean vsync = true;
	public boolean sound = true;
	public boolean showFps = false;

	// Multiplayer
	public static final String DEFAULT_SERVER = "https://castle-of-magic-rooms.onrender.com";
	public String playerName = "";
	public String serverUrl = DEFAULT_SERVER;
	public String serverKey = "";
	public String roomKey = "";
	public String roomCode = "";

	private final File file;

	public Settings(File file) { this.file = file; }

	public Edition edition() { return EDITIONS[Math.max(0, Math.min(edition, EDITIONS.length - 1))]; }

	public void load()
	{
		if (!file.isFile()) { return; }
		Properties p = new Properties();
		try (InputStream in = new FileInputStream(file)) { p.load(in); }
		catch (Exception e) { return; }
		String id = p.getProperty("editionId", "");
		for (int k = 0; k < EDITIONS.length; k++) { if (EDITIONS[k].id.equals(id)) { edition = k; } }
		fullscreen = boolOf(p, "fullscreen", fullscreen);
		windowScale = intOf(p, "windowScale", windowScale);
		filter = Math.floorMod(intOf(p, "filter", filter), FILTERS.length);
		vsync = boolOf(p, "vsync", vsync);
		sound = boolOf(p, "sound", sound);
		showFps = boolOf(p, "showFps", showFps);
		playerName = p.getProperty("playerName", playerName);
		serverUrl = p.getProperty("serverUrl", serverUrl);
		serverKey = p.getProperty("serverKey", serverKey);
		roomKey = p.getProperty("roomKey", roomKey);
		roomCode = p.getProperty("roomCode", roomCode);
		if (edition < 0 || edition >= EDITIONS.length) { edition = 0; }
	}

	public void save()
	{
		Properties p = new Properties();
		p.setProperty("editionId", edition().id);
		p.setProperty("fullscreen", "" + fullscreen);
		p.setProperty("windowScale", "" + windowScale);
		p.setProperty("filter", "" + filter);
		p.setProperty("vsync", "" + vsync);
		p.setProperty("sound", "" + sound);
		p.setProperty("showFps", "" + showFps);
		p.setProperty("playerName", playerName);
		p.setProperty("serverUrl", serverUrl);
		p.setProperty("serverKey", serverKey);
		p.setProperty("roomKey", roomKey);
		p.setProperty("roomCode", roomCode);
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
