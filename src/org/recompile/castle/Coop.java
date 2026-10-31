package org.recompile.castle;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.PlatformImage;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Two-player view for the 240x320 "Full Adventure" build, without changing the game.
 *
 * Reads where the local hero is (castle or level, position, animation) from the running game
 * and draws the friend's hero with the game's own sprite code in a yellow palette that is added
 * at runtime. Everything here must run on the game thread (inside the frame painter), because
 * it calls into the game's objects.
 *
 * Field names come from the obfuscated 2008 jar (Castle of Magic v1.0.2, 240x320 build):
 *   i.aR game state (10 = castle, 14 = level), i.aU frame counter
 *   g.i world (-1 = castle hall), g.j level, g.k level part
 *   f.h hero entity: l, m = position (1/256 px); a = sprite; a = animation player (a.d anim, a.e frame, a.c flags, a.a/a.b screen x/y)
 *   f.aa, f.ab level camera (1/256 px)
 *   castle view: g.az, g.aA hero position (px), g.aB animation, g.aC flags, g.ax, g.ay camera, g.z room bounds, g.a[1] hero sprite
 */
public final class Coop
{
	static final int STATE_CASTLE = 10, STATE_LEVEL = 14;

	/** Snapshot of one player, sent to the friend about 16 times a second. */
	public static final class State
	{
		public String mode = "menu";       // "level", "castle" or "menu"
		int world, level, part;
		int x, y, anim, frame, flags;
		public String place = "";
		// local only: where the own hero is on screen (game pixels), -9999 if unknown
		transient int screenX = -9999, screenY = -9999;

		public String toJson(String name)
		{
			return Json.of("type", "state", "name", name, "mode", mode, "w", world, "l", level, "p", part,
				"x", x, "y", y, "an", anim, "fr", frame, "fl", flags, "place", place);
		}

		static State fromJson(Map<String, Object> m)
		{
			State s = new State();
			s.mode = String.valueOf(Json.str(m, "mode"));
			s.world = Json.num(m, "w", 0); s.level = Json.num(m, "l", 0); s.part = Json.num(m, "p", 0);
			s.x = Json.num(m, "x", 0); s.y = Json.num(m, "y", 0);
			s.anim = Json.num(m, "an", -1); s.frame = Json.num(m, "fr", 0); s.flags = Json.num(m, "fl", 0);
			String p = Json.str(m, "place");
			s.place = p == null ? "" : p;
			return s;
		}

		public boolean samePlace(State o)
		{
			if (o == null || !mode.equals(o.mode)) { return false; }
			if (mode.equals("level")) { return world == o.world && level == o.level && part == o.part; }
			if (mode.equals("castle")) { return world == o.world; }
			return false;
		}
	}

	// ------------------------------------------------------------ reflection handles

	private boolean ready, broken;
	private Field iState, iTick;
	private Field gWorld, gLevel, gPart, gAz, gAA, gAB, gAC, gAx, gAy, gBounds, gSprites;
	private Field fHero, fCamX, fCamY, eX, eY, eSprite, eAnimPlayer;
	private Field aAnim, aFrame, aFlags, aScreenX, aScreenY;
	private Field cPalettes, cPalCount, cPalNow, cImages;
	private Method cDraw, cFrameCount, cFrameTime;

	private final IdentityHashMap<Object, Integer> yellowIndex = new IdentityHashMap<>();
	private PlatformImage overlay;

	private boolean init()
	{
		if (ready) { return true; }
		if (broken) { return false; }
		try
		{
			ClassLoader cl = Mobile.getPlatform().loader;
			Class<?> ci = Class.forName("i", false, cl), cg = Class.forName("g", false, cl), cf = Class.forName("f", false, cl);
			Class<?> ca = Class.forName("a", false, cl), cc = Class.forName("c", false, cl);

			iState = field(ci, "aR", int.class, true); iTick = field(ci, "aU", int.class, true);
			gWorld = field(cg, "i", int.class, true); gLevel = field(cg, "j", int.class, true); gPart = field(cg, "k", int.class, true);
			gAz = field(cg, "az", int.class, true); gAA = field(cg, "aA", int.class, true);
			gAB = field(cg, "aB", int.class, true); gAC = field(cg, "aC", int.class, true);
			gAx = field(cg, "ax", int.class, true); gAy = field(cg, "ay", int.class, true);
			gBounds = field(cg, "z", int[].class, true);
			gSprites = field(cg, "a", Array.newInstance(cc, 0).getClass(), true);
			fHero = field(cf, "h", cf, true); fCamX = field(cf, "aa", int.class, true); fCamY = field(cf, "ab", int.class, true);
			eX = field(cf, "l", int.class, false); eY = field(cf, "m", int.class, false);
			eSprite = field(cf, "a", cc, false); eAnimPlayer = field(cf, "a", ca, false);
			aAnim = field(ca, "d", int.class, false); aFrame = field(ca, "e", int.class, false); aFlags = field(ca, "c", int.class, false);
			aScreenX = field(ca, "a", int.class, false); aScreenY = field(ca, "b", int.class, false);
			cPalettes = field(cc, "a", int[][].class, false); cPalCount = field(cc, "r", int.class, false); cPalNow = field(cc, "t", int.class, false);
			cImages = field(cc, "a", Class.forName("javax.microedition.lcdui.Image").arrayType().arrayType(), false);
			cDraw = method(cc, "a", void.class, Class.forName("javax.microedition.lcdui.Graphics"), int.class, int.class, int.class, int.class, int.class, int.class, int.class);
			cFrameCount = method(cc, "a", int.class, int.class);
			cFrameTime = method(cc, "a", int.class, int.class, int.class);
			ready = true;
			return true;
		}
		catch (Throwable t)
		{
			System.out.println("Multiplayer view not available for this game version: " + t);
			broken = true;
			return false;
		}
	}

	private static Field field(Class<?> c, String name, Class<?> type, boolean isStatic) throws NoSuchFieldException
	{
		for (Field f : c.getDeclaredFields())
		{
			if (f.getName().equals(name) && f.getType() == type && Modifier.isStatic(f.getModifiers()) == isStatic) { f.setAccessible(true); return f; }
		}
		throw new NoSuchFieldException(c.getName() + "." + name + " " + type.getSimpleName());
	}

	private static Method method(Class<?> c, String name, Class<?> ret, Class<?>... params) throws NoSuchMethodException
	{
		for (Method m : c.getDeclaredMethods())
		{
			if (m.getName().equals(name) && m.getReturnType() == ret && java.util.Arrays.equals(m.getParameterTypes(), params)) { m.setAccessible(true); return m; }
		}
		throw new NoSuchMethodException(c.getName() + "." + name);
	}

	// ------------------------------------------------------------ reading the local player

	/** Reads the local player. Game thread only. */
	public State capture()
	{
		State s = new State();
		if (!init()) { return s; }
		try
		{
			int state = iState.getInt(null);
			Object hero = fHero.get(null);
			if (state == STATE_LEVEL && hero != null)
			{
				Object ap = eAnimPlayer.get(hero);
				s.mode = "level";
				s.world = gWorld.getInt(null); s.level = gLevel.getInt(null); s.part = gPart.getInt(null);
				s.x = eX.getInt(hero); s.y = eY.getInt(hero);
				if (ap != null)
				{
					s.anim = aAnim.getInt(ap); s.frame = aFrame.getInt(ap); s.flags = aFlags.getInt(ap);
					s.screenX = aScreenX.getInt(ap); s.screenY = aScreenY.getInt(ap);
				}
				s.place = "Level " + (s.world + 1) + "-" + (s.level + 1);
			}
			else if (state == STATE_CASTLE)
			{
				s.mode = "castle";
				s.world = gWorld.getInt(null);
				s.x = gAz.getInt(null); s.y = gAA.getInt(null);
				s.anim = gAB.getInt(null); s.flags = gAC.getInt(null);
				s.screenX = castleOffset() + s.x - gAx.getInt(null);
				s.screenY = s.y - gAy.getInt(null);
				s.place = s.world < 0 ? "Castle hall" : "Castle, world " + (s.world + 1);
			}
			else
			{
				s.place = "In the menus";
			}
		}
		catch (Throwable t) { s = new State(); s.place = "?"; }
		return s;
	}

	private int castleOffset() throws IllegalAccessException
	{
		int[] z = (int[]) gBounds.get(null);
		if (z == null || z.length < 3) { return 0; }
		int w = z[2] - z[0];
		return 240 > w ? -(240 - w) / 2 : 0;
	}

	// ------------------------------------------------------------ drawing the friend

	/**
	 * Draws the friend into a copy of the game frame if both are in the same place.
	 * @return the copy to show (or the original frame if the friend is not here), and fills pos with
	 *         the friend's screen position (game pixels) or -9999.
	 */
	public PlatformImage drawFriend(PlatformImage frame, State me, State friend, int[] pos)
	{
		pos[0] = pos[1] = -9999;
		if (friend == null || me == null || !me.samePlace(friend) || friend.anim < 0 || !init()) { return frame; }
		try
		{
			Object sprite;
			int sx, sy, fr;
			if (friend.mode.equals("level"))
			{
				Object hero = fHero.get(null);
				if (hero == null) { return frame; }
				sprite = eSprite.get(hero);
				sx = (friend.x - fCamX.getInt(null) + 128) >> 8;
				sy = (friend.y - fCamY.getInt(null) + 128) >> 8;
				fr = friend.frame;
			}
			else
			{
				Object[] sprites = (Object[]) gSprites.get(null);
				if (sprites == null || sprites.length < 2) { return frame; }
				sprite = sprites[1];
				sx = castleOffset() + friend.x - gAx.getInt(null);
				sy = friend.y - gAy.getInt(null);
				int step = Math.max(1, (int) cFrameTime.invoke(sprite, friend.anim, 0));
				fr = iTick.getInt(null) / step;
			}
			if (sprite == null) { return frame; }
			int frames = (int) cFrameCount.invoke(sprite, friend.anim);
			if (frames <= 0) { return frame; }
			fr = Math.floorMod(fr, frames);
			if (sx < -120 || sx > 360 || sy < -120 || sy > 440) { return frame; } // far off screen

			int w = frame.getWidth(), h = frame.getHeight();
			if (overlay == null || overlay.getWidth() != w || overlay.getHeight() != h) { overlay = new PlatformImage(w, h); }
			System.arraycopy(frame.getDataBuffer(), 0, overlay.getDataBuffer(), 0, w * h);

			int yellow = yellowPalette(sprite);
			int saved = cPalNow.getInt(sprite);
			if (yellow >= 0) { cPalNow.setInt(sprite, yellow); }
			try { cDraw.invoke(sprite, overlay.getMIDPGraphics(), friend.anim, fr, sx, sy, friend.flags, 0, 0); }
			finally { cPalNow.setInt(sprite, saved); }

			pos[0] = sx; pos[1] = sy;
			return overlay;
		}
		catch (Throwable t)
		{
			return frame;
		}
	}

	/** Adds a yellow version of the sprite's first palette (once per sprite object). */
	private int yellowPalette(Object sprite) throws Exception
	{
		Integer known = yellowIndex.get(sprite);
		int[][] pals = (int[][]) cPalettes.get(sprite);
		if (pals == null) { return -1; }
		if (known != null && known < pals.length && pals[known] != null && cPalCount.getInt(sprite) > known) { return known; }
		int count = cPalCount.getInt(sprite);
		if (count >= pals.length || pals[0] == null) { return -1; }
		pals[count] = toYellow(pals[0]);
		cPalCount.setInt(sprite, count + 1);
		Object[] imgs = (Object[]) cImages.get(sprite);
		if (imgs != null && imgs.length <= count) { cImages.set(sprite, java.util.Arrays.copyOf(imgs, count + 1)); }
		yellowIndex.put(sprite, count);
		return count;
	}

	/** Turns blues and purples into golden yellows, leaves skin, outlines and other colours alone. */
	static int[] toYellow(int[] pal)
	{
		int[] out = pal.clone();
		float[] hsb = new float[3];
		for (int k = 0; k < pal.length; k++)
		{
			int c = pal[k];
			int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
			java.awt.Color.RGBtoHSB(r, g, b, hsb);
			float deg = hsb[0] * 360f;
			if (hsb[1] < 0.18f || deg < 185f || deg > 290f) { continue; }
			float t = (deg - 185f) / 105f;                 // 0..1 across blue..purple
			float hue = (52f - 14f * t) / 360f;            // yellow .. deep gold
			float bri = Math.min(1f, hsb[2] * 1.18f + 0.06f);
			float sat = Math.min(1f, hsb[1] * 0.95f + 0.05f);
			int rgb = java.awt.Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
			out[k] = (c & 0xFF000000) | rgb;
		}
		return out;
	}
}
