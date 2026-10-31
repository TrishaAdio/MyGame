package org.recompile.castle;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The PC settings screen. Shown before the game starts and on F1 during play.
 * Drawn with Java2D on a 480x640 virtual layout that is scaled to the window.
 */
final class Menu
{
	interface Host
	{
		boolean gameStarted();
		void play();                // start or resume
		void quit();
		void restart();             // relaunch with current settings
		void displayChanged();      // fullscreen / window size / vsync changed
		int maxWindowScale();
		int autoWindowScale();
	}

	static final int VW = 480, VH = 640;

	private enum Item { PLAY, EDITION, MULTI, DISPLAY, SIZE, FILTER, VSYNC, SOUND, FPS, CONTROLS, RESTART, QUIT,
		MP_NAME, MP_KEY, MP_CREATE, MP_CODE, MP_JOIN, MP_SERVER, MP_SERVERKEY, MP_ROOM, MP_FRIEND, MP_LEAVE, MP_BACK }

	private static final java.util.EnumSet<Item> CENTERED = java.util.EnumSet.of(Item.PLAY, Item.QUIT, Item.CONTROLS, Item.RESTART,
		Item.MP_CREATE, Item.MP_JOIN, Item.MP_LEAVE, Item.MP_BACK);
	private static final java.util.EnumSet<Item> FIELDS = java.util.EnumSet.of(Item.MP_NAME, Item.MP_KEY, Item.MP_CODE, Item.MP_SERVER, Item.MP_SERVERKEY);

	private MultiSession mp;
	private boolean multiPage;
	private MultiSession.Phase lastPhase;
	private String notice = "";

	synchronized void setMultiplayer(MultiSession session) { this.mp = session; rebuild(); }

	private final Settings s;
	private final Host host;
	private final List<Item> items = new ArrayList<>();
	private int sel;
	private boolean controlsPage;
	private boolean needsRestart;   // a restart-only setting was changed while playing
	private final int startEdition;
	private final boolean startSound;
	private final Image icon;

	// layout cache for mouse hit testing (virtual coordinates)
	private final double[] rowY = new double[16];
	private double rowH, rowX0, rowX1;
	private final AffineTransform toVirtual = new AffineTransform();

	private final float[][] stars;

	Menu(Settings s, Host host, Image icon)
	{
		this.s = s;
		this.host = host;
		this.icon = icon;
		this.startEdition = s.edition;
		this.startSound = s.sound;
		Random r = new Random(2008);
		stars = new float[90][4];
		for (float[] st : stars) { st[0] = r.nextFloat() * VW; st[1] = r.nextFloat() * VH * 0.62f; st[2] = 0.6f + r.nextFloat() * 1.8f; st[3] = r.nextFloat() * 6.28f; }
		rebuild();
	}

	private void rebuild()
	{
		Item cur = items.isEmpty() ? null : items.get(Math.min(sel, items.size() - 1));
		items.clear();
		if (multiPage && mp != null)
		{
			lastPhase = mp.phase();
			items.add(Item.PLAY);
			items.add(Item.MP_NAME);
			if (mp.phase() == MultiSession.Phase.IDLE)
			{
				items.add(Item.MP_KEY);
				items.add(Item.MP_CREATE);
				items.add(Item.MP_CODE);
				items.add(Item.MP_JOIN);
				items.add(Item.MP_SERVER);
				items.add(Item.MP_SERVERKEY);
			}
			else if (mp.phase() == MultiSession.Phase.IN_ROOM)
			{
				items.add(Item.MP_ROOM);
				items.add(Item.MP_FRIEND);
				items.add(Item.MP_LEAVE);
			}
			items.add(Item.MP_BACK);
			sel = cur == null ? 0 : Math.max(0, items.indexOf(cur));
			if (cur != null && !items.contains(cur)) { sel = Math.min(sel, items.size() - 1); }
			return;
		}
		items.add(Item.PLAY);
		items.add(Item.EDITION);
		if (mp != null) { items.add(Item.MULTI); }
		items.add(Item.DISPLAY);
		if (!s.fullscreen) { items.add(Item.SIZE); }
		items.add(Item.FILTER);
		items.add(Item.VSYNC);
		items.add(Item.SOUND);
		items.add(Item.FPS);
		items.add(Item.CONTROLS);
		if (needsRestart) { items.add(Item.RESTART); }
		items.add(Item.QUIT);
		sel = cur == null ? 0 : Math.max(0, items.indexOf(cur));
	}

	synchronized void opened() { controlsPage = false; notice = ""; rebuild(); }

	// ---------------------------------------------------------------- input

	/** Abstract menu actions, from keyboard, mouse or controller. */
	synchronized void up() { if (controlsPage) { return; } sel = Math.floorMod(sel - 1, items.size()); }
	synchronized void down() { if (controlsPage) { return; } sel = Math.floorMod(sel + 1, items.size()); }
	synchronized void left() { if (!controlsPage) { change(items.get(sel), -1); } }
	synchronized void right() { if (!controlsPage) { change(items.get(sel), +1); } }

	synchronized void back()
	{
		if (controlsPage) { controlsPage = false; return; }
		if (multiPage) { multiPage = false; rebuild(); return; }
		if (host.gameStarted()) { host.play(); }
	}

	synchronized void activate()
	{
		if (controlsPage) { controlsPage = false; return; }
		Item it = items.get(sel);
		switch (it)
		{
			case PLAY:
				if (mp != null && mp.waitingForSave()) { notice = "Wait a moment, getting the host's progress..."; break; }
				if (mp != null && mp.inRoom() && !"adventure".equals(s.edition().id)) { notice = "Multiplayer needs the Normal version."; break; }
				s.save(); host.play(); break;
			case MULTI: multiPage = true; notice = ""; sel = 0; rebuild(); break;
			case MP_BACK: multiPage = false; rebuild(); break;
			case MP_CREATE: case MP_JOIN:
				if (!"adventure".equals(s.edition().id)) { notice = "Multiplayer works in the Normal version. Change Game version first."; break; }
				if (host.gameStarted() && it == Item.MP_JOIN) { notice = "Joining copies the host's progress, so join before pressing PLAY (restart the game first)."; break; }
				s.save();
				notice = "";
				if (it == Item.MP_CREATE) { mp.create(); } else { mp.join(); }
				break;
			case MP_LEAVE: mp.leave(); notice = ""; break;
			case MP_NAME: case MP_KEY: case MP_CODE: case MP_SERVER: case MP_SERVERKEY: case MP_ROOM: case MP_FRIEND:
				sel = Math.min(sel + 1, items.size() - 1); break; // Enter moves to the next row
			case CONTROLS: controlsPage = true; break;
			case RESTART: s.save(); host.restart(); break;
			case QUIT: s.save(); host.quit(); break;
			default: change(it, +1);
		}
	}

	private void change(Item it, int dir)
	{
		boolean started = host.gameStarted();
		switch (it)
		{
			case EDITION:
				s.edition = Math.floorMod(s.edition + dir, Settings.EDITIONS.length);
				s.windowScale = 0;
				if (!started && !s.fullscreen) { host.displayChanged(); } // show the new screen size right away
				break;
			case DISPLAY: s.fullscreen = !s.fullscreen; host.displayChanged(); break;
			case SIZE:
			{
				int max = host.maxWindowScale();
				int cur = s.windowScale == 0 ? host.autoWindowScale() : s.windowScale;
				s.windowScale = Math.max(1, Math.min(max, cur + dir));
				host.displayChanged();
				break;
			}
			case FILTER: s.filter = Math.floorMod(s.filter + dir, Settings.FILTERS.length); break;
			case VSYNC: s.vsync = !s.vsync; host.displayChanged(); break;
			case SOUND: s.sound = !s.sound; break;
			case FPS: s.showFps = !s.showFps; break;
			default: return;
		}
		if (started) { needsRestart = s.edition != startEdition || s.sound != startSound; }
		s.save();
		rebuild();
	}

	// ---------------------------------------------------------------- text fields

	synchronized boolean textSelected() { return !controlsPage && !items.isEmpty() && FIELDS.contains(items.get(sel)); }

	synchronized void typed(char c)
	{
		if (!textSelected() || c < 32 || c == 127) { return; }
		Item it = items.get(sel);
		String v = fieldValue(it);
		if (v.length() >= fieldLimit(it)) { return; }
		if (it == Item.MP_CODE) { c = Character.toUpperCase(c); if (!Character.isLetterOrDigit(c)) { return; } }
		setField(it, v + c);
	}

	synchronized void backspace()
	{
		if (!textSelected()) { return; }
		Item it = items.get(sel);
		String v = fieldValue(it);
		if (!v.isEmpty()) { setField(it, v.substring(0, v.length() - 1)); }
	}

	synchronized void paste(String text)
	{
		if (!textSelected() || text == null) { return; }
		Item it = items.get(sel);
		String clean = text.replaceAll("[\\r\\n\\t]", "").trim();
		if (it == Item.MP_CODE) { clean = clean.toUpperCase().replaceAll("[^A-Z0-9]", ""); }
		String v = fieldValue(it) + clean;
		setField(it, v.length() > fieldLimit(it) ? v.substring(0, fieldLimit(it)) : v);
	}

	private int fieldLimit(Item it)
	{
		switch (it) { case MP_NAME: return 20; case MP_CODE: return 8; case MP_SERVER: return 120; default: return 64; }
	}

	private String fieldValue(Item it)
	{
		switch (it)
		{
			case MP_NAME: return s.playerName;
			case MP_KEY: return s.roomKey;
			case MP_CODE: return s.roomCode;
			case MP_SERVER: return s.serverUrl;
			case MP_SERVERKEY: return s.serverKey;
			default: return "";
		}
	}

	private void setField(Item it, String v)
	{
		switch (it)
		{
			case MP_NAME: s.playerName = v; break;
			case MP_KEY: s.roomKey = v; break;
			case MP_CODE: s.roomCode = v; break;
			case MP_SERVER: s.serverUrl = v; break;
			case MP_SERVERKEY: s.serverKey = v; break;
			default: return;
		}
		s.save();
	}

	/** Mouse support. Coordinates are canvas pixels. */
	synchronized void mouseMoved(int x, int y)
	{
		int row = rowAt(x, y);
		if (row >= 0) { sel = row; }
	}

	synchronized void mouseClicked(int x, int y, int button)
	{
		if (controlsPage) { controlsPage = false; return; }
		int row = rowAt(x, y);
		if (row < 0) { return; }
		sel = row;
		Item it = items.get(row);
		boolean valueItem = !CENTERED.contains(it) && !FIELDS.contains(it) && it != Item.MULTI;
		if (valueItem && button == 3) { change(it, -1); } // right click goes backwards
		else { activate(); }
	}

	synchronized void mouseWheel(int dir) { if (dir > 0) { down(); } else { up(); } }

	private int rowAt(int x, int y)
	{
		Point2D p = toVirtual.transform(new Point2D.Double(x, y), null);
		if (p.getX() < rowX0 || p.getX() > rowX1) { return -1; }
		for (int i = 0; i < items.size(); i++) { if (p.getY() >= rowY[i] && p.getY() < rowY[i] + rowH) { return i; } }
		return -1;
	}

	// ---------------------------------------------------------------- drawing

	private static final Color GOLD_TOP = new Color(0xFFF1A6), GOLD_BOTTOM = new Color(0xFFA51F), INK = new Color(0x2A0B4F);
	private static final Color TEXT = new Color(0xF4EEFF), DIM = new Color(0xB9A8E8), ACCENT = new Color(0x7CF7FF);

	/**
	 * @param backdrop true when no game frame is drawn behind the menu
	 */
	synchronized void draw(Graphics2D g, int cw, int ch, boolean backdrop, long nowMs)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		if (multiPage && mp != null && mp.phase() != lastPhase) { rebuild(); }
		if (backdrop) { drawSky(g, cw, ch, nowMs); }
		else { g.setColor(new Color(10, 0, 30, 170)); g.fillRect(0, 0, cw, ch); }

		double scale = Math.min(cw / (double) VW, ch / (double) VH);
		double ox = (cw - VW * scale) / 2, oy = (ch - VH * scale) / 2;
		AffineTransform saved = g.getTransform();
		g.translate(ox, oy);
		g.scale(scale, scale);
		try { toVirtual.setTransform(g.getTransform()); toVirtual.preConcatenate(saved.createInverse()); toVirtual.invert(); }
		catch (Exception e) { toVirtual.setToIdentity(); }

		if (backdrop) { drawCastle(g); }
		drawTitle(g, nowMs);
		if (controlsPage) { drawControls(g); }
		else { drawItems(g, nowMs); }

		g.setTransform(saved);
	}

	private void drawSky(Graphics2D g, int cw, int ch, long now)
	{
		g.setPaint(new GradientPaint(0, 0, new Color(0x12053A), 0, ch * 0.55f, new Color(0x3A1A86)));
		g.fillRect(0, 0, cw, (int) (ch * 0.55f) + 1);
		g.setPaint(new GradientPaint(0, ch * 0.55f, new Color(0x3A1A86), 0, ch, new Color(0x0E6B78)));
		g.fillRect(0, (int) (ch * 0.55f), cw, ch - (int) (ch * 0.55f));

		double scale = Math.min(cw / (double) VW, ch / (double) VH);
		double ox = (cw - VW * scale) / 2, oy = (ch - VH * scale) / 2;
		// stars cover the whole window, laid out in virtual space then repeated sideways
		for (float[] st : stars)
		{
			float a = 0.35f + 0.65f * (float) Math.abs(Math.sin(st[3] + now / 700.0 * (0.4 + st[2] * 0.3)));
			g.setColor(new Color(1f, 1f, 0.9f, Math.min(1f, a)));
			for (double rep = -VW; rep <= VW; rep += VW)
			{
				double x = ox + (st[0] + rep) * scale, y = oy * 0.3 + st[1] * scale;
				if (x < -4 || x > cw + 4) { continue; }
				double r = st[2] * scale * 0.9;
				g.fill(new Ellipse2D.Double(x - r / 2, y - r / 2, r, r));
			}
		}
		// moon with glow
		double mx = ox + 392 * scale, my = oy + 86 * scale, mr = 46 * scale;
		for (int i = 6; i >= 1; i--)
		{
			g.setColor(new Color(1f, 0.92f, 0.65f, 0.035f * (7 - i)));
			double rr = mr + i * 14 * scale;
			g.fill(new Ellipse2D.Double(mx - rr, my - rr, rr * 2, rr * 2));
		}
		g.setPaint(new GradientPaint((float) (mx - mr), (float) (my - mr), new Color(0xFFF6D2), (float) (mx + mr), (float) (my + mr), new Color(0xF2C46B)));
		g.fill(new Ellipse2D.Double(mx - mr, my - mr, mr * 2, mr * 2));
	}

	private void drawCastle(Graphics2D g)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(-200, VH + 200);
		p.lineTo(-200, 560);
		double[][] towers = { {20, 520, 30}, {70, 470, 26}, {120, 500, 34}, {170, 430, 30}, {215, 380, 36}, {265, 440, 28}, {310, 400, 32}, {360, 480, 26}, {410, 455, 30}, {455, 515, 28} };
		for (double[] t : towers)
		{
			double x = t[0], top = t[1], w = t[2];
			p.lineTo(x - w / 2, 560);
			p.lineTo(x - w / 2, top);
			p.lineTo(x, top - w * 1.25);
			p.lineTo(x + w / 2, top);
			p.lineTo(x + w / 2, 560);
		}
		p.lineTo(VW + 200, 560);
		p.lineTo(VW + 200, VH + 200);
		p.closePath();
		g.setColor(new Color(0x170A33));
		g.fill(p);
		// lit windows
		g.setColor(new Color(0xFFD36B));
		for (double[] t : towers)
		{
			g.fill(new RoundRectangle2D.Double(t[0] - 3, t[1] + 18, 6, 10, 3, 3));
			if (t[2] > 29) { g.fill(new RoundRectangle2D.Double(t[0] - 3, t[1] + 44, 6, 10, 3, 3)); }
		}
	}

	private void drawTitle(Graphics2D g, long now)
	{
		Font big = new Font(Font.DIALOG, Font.BOLD, 52);
		drawFancy(g, "CASTLE", big, VW / 2.0, 70, 6f);
		Font big2 = new Font(Font.DIALOG, Font.BOLD | Font.ITALIC, 22);
		drawFancy(g, "of", big2, VW / 2.0 - 118, 104, 4f);
		drawFancy(g, "MAGIC", big, VW / 2.0 + 14, 122, 6f);
		g.setFont(new Font(Font.DIALOG, Font.BOLD, 15));
		String sub = controlsPage ? "CONTROLS" : (multiPage ? "MULTIPLAYER" : "PC SETTINGS");
		FontMetrics fm = g.getFontMetrics();
		g.setColor(new Color(0, 0, 0, 120));
		g.drawString(sub, (int) (VW / 2.0 - fm.stringWidth(sub) / 2.0) + 1, 151);
		g.setColor(ACCENT);
		g.drawString(sub, (int) (VW / 2.0 - fm.stringWidth(sub) / 2.0), 150);

	}

	/** Gold text with a dark outline, like the game's logo lettering. */
	private static void drawFancy(Graphics2D g, String text, Font f, double cx, double baseline, float outline)
	{
		GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), text);
		double w = gv.getLogicalBounds().getWidth();
		Shape sh = gv.getOutline((float) (cx - w / 2), (float) baseline);
		g.setStroke(new BasicStroke(outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0, 0, 0, 90));
		g.translate(2, 3); g.draw(sh); g.translate(-2, -3);
		g.setColor(INK);
		g.draw(sh);
		var b = sh.getBounds2D();
		g.setPaint(new GradientPaint(0, (float) b.getMinY(), GOLD_TOP, 0, (float) b.getMaxY(), GOLD_BOTTOM));
		g.fill(sh);
		g.setStroke(new BasicStroke(1f));
	}

	private void drawItems(Graphics2D g, long now)
	{
		int n = items.size();
		double top = 172, bottomInfo = 92;
		double avail = VH - top - bottomInfo - 14;
		rowH = Math.min(34, avail / n);
		rowX0 = 40; rowX1 = VW - 40;

		// panel
		g.setColor(new Color(14, 5, 40, 222));
		g.fill(new RoundRectangle2D.Double(rowX0 - 10, top - 8, rowX1 - rowX0 + 20, rowH * n + 16, 18, 18));
		g.setColor(new Color(0xFFC94A));
		g.setStroke(new BasicStroke(2f));
		g.draw(new RoundRectangle2D.Double(rowX0 - 10, top - 8, rowX1 - rowX0 + 20, rowH * n + 16, 18, 18));

		Font label = new Font(Font.DIALOG, Font.BOLD, (int) Math.round(Math.min(17, rowH * 0.52)));
		Font value = new Font(Font.DIALOG, Font.PLAIN, (int) Math.round(Math.min(16, rowH * 0.48)));
		for (int i = 0; i < n; i++)
		{
			double y = top + i * rowH;
			rowY[i] = y;
			Item it = items.get(i);
			boolean on = i == sel;
			if (on)
			{
				double pulse = 0.75 + 0.25 * Math.sin(now / 180.0);
				g.setPaint(new GradientPaint((float) rowX0, 0, new Color(255, 79, 216, (int) (150 * pulse)), (float) rowX1, 0, new Color(124, 60, 255, (int) (110 * pulse))));
				g.fill(new RoundRectangle2D.Double(rowX0, y + 2, rowX1 - rowX0, rowH - 4, 12, 12));
				drawGem(g, rowX0 + 14, y + rowH / 2, rowH * 0.32);
			}
			g.setFont(label);
			FontMetrics fm = g.getFontMetrics();
			double base = y + rowH / 2 + fm.getAscent() * 0.36;
			String name = labelOf(it);
			boolean centered = CENTERED.contains(it);
			if (it == Item.PLAY)
			{
				Font pf = new Font(Font.DIALOG, Font.BOLD, (int) Math.round(Math.min(21, rowH * 0.62)));
				g.setFont(pf); fm = g.getFontMetrics();
				base = y + rowH / 2 + fm.getAscent() * 0.36;
			}
			if (centered)
			{
				g.setColor(it == Item.PLAY || it == Item.MP_CREATE || it == Item.MP_JOIN ? new Color(0xFFE27A) : (it == Item.RESTART ? ACCENT : TEXT));
				g.drawString(name, (float) (VW / 2.0 - fm.stringWidth(name) / 2.0), (float) base);
				continue;
			}
			g.setColor(on ? Color.WHITE : TEXT);
			g.drawString(name, (float) (rowX0 + 30), (float) base);
			String v = valueOf(it);
			g.setFont(value);
			fm = g.getFontMetrics();
			double vx = rowX1 - 26 - fm.stringWidth(v);
			g.setColor(on ? Color.WHITE : DIM);
			g.drawString(v, (float) vx, (float) base);
			if (on && FIELDS.contains(it))
			{
				if ((now / 450) % 2 == 0) { g.setColor(Color.WHITE); g.fillRect((int) (rowX1 - 22), (int) (y + rowH * 0.25), 2, (int) (rowH * 0.5)); }
			}
			else if (on && it != Item.MULTI && it != Item.MP_ROOM && it != Item.MP_FRIEND)
			{
				g.setColor(new Color(0xFFE27A));
				drawArrow(g, vx - 14, y + rowH / 2, rowH * 0.18, -1);
				drawArrow(g, rowX1 - 12, y + rowH / 2, rowH * 0.18, +1);
			}
		}

		// description box
		double iy = VH - bottomInfo;
		g.setColor(new Color(14, 5, 40, 222));
		g.fill(new RoundRectangle2D.Double(rowX0 - 10, iy, rowX1 - rowX0 + 20, 58, 14, 14));
		g.setColor(TEXT);
		g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
		String info = infoOf(items.get(sel));
		if (multiPage && mp != null)
		{
			String problem = !notice.isEmpty() ? notice : mp.error();
			String line = (problem.isEmpty() ? "" : "! " + problem + "   ") + "Status: " + mp.status() + (mp.saveNote().isEmpty() ? "" : ". " + mp.saveNote());
			g.setColor(problem.isEmpty() ? ACCENT : new Color(0xFF8A8A));
			drawWrapped(g, line, rowX0 + 2, iy + 20, rowX1 - rowX0 - 4, 17, 2);
			g.setColor(TEXT);
			drawWrapped(g, info, rowX0 + 2, iy + 54, rowX1 - rowX0 - 4, 17, 1);
		}
		else { drawWrapped(g, info, rowX0 + 2, iy + 20, rowX1 - rowX0 - 4, 17, 3); }
		g.setColor(DIM);
		g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
		String hint = "Arrows: choose   Left/Right: change   Enter: select   " + (host.gameStarted() ? "Esc/F1: back to game" : "Mouse and controller work too");
		FontMetrics fm = g.getFontMetrics();
		g.drawString(hint, (float) (VW / 2.0 - fm.stringWidth(hint) / 2.0), (float) (VH - 14));
	}

	private void drawControls(Graphics2D g)
	{
		String[][] rows =
		{
			{ "Move", "Arrow keys or W A S D", "D-pad / left stick" },
			{ "OK / action (phone 5)", "Enter, Space or Z", "A" },
			{ "Menu / pause (left soft key)", "Esc or Q", "Start" },
			{ "Back / skip (right soft key)", "Backspace or X", "B" },
			{ "Phone keys 0-9, *, #", "Number keys / numpad, numpad * and /", "X = 1, Y = 3, LB = *, RB = #" },
			{ "PC settings", "F1", "View / Back" },
			{ "Fullscreen", "F11 or Alt+Enter", "" },
			{ "FPS counter", "F3", "" },
			{ "Screenshot", "F12  (saved in screenshots folder)", "" },
		};
		double x0 = 30, x1 = VW - 30, y = 168, rh = 48;
		g.setColor(new Color(14, 5, 40, 228));
		g.fill(new RoundRectangle2D.Double(x0 - 6, y - 8, x1 - x0 + 12, rows.length * rh + 10, 18, 18));
		g.setColor(new Color(0xFFC94A));
		g.setStroke(new BasicStroke(2f));
		g.draw(new RoundRectangle2D.Double(x0 - 6, y - 8, x1 - x0 + 12, rows.length * rh + 10, 18, 18));
		for (String[] r : rows)
		{
			g.setFont(new Font(Font.DIALOG, Font.BOLD, 14));
			g.setColor(new Color(0xFFE27A));
			g.drawString(r[0], (float) (x0 + 8), (float) (y + 12));
			g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
			g.setColor(TEXT);
			g.drawString("Keyboard: " + r[1], (float) (x0 + 18), (float) (y + 27));
			if (!r[2].isEmpty())
			{
				g.setColor(DIM);
				g.drawString("Controller: " + r[2], (float) (x0 + 18), (float) (y + 41));
			}
			y += rh;
		}
		g.setColor(DIM);
		g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
		String hint = "Press Enter or Esc to go back";
		FontMetrics fm = g.getFontMetrics();
		g.drawString(hint, (float) (VW / 2.0 - fm.stringWidth(hint) / 2.0), (float) (VH - 14));
	}

	private static void drawGem(Graphics2D g, double cx, double cy, double r)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(cx, cy - r); p.lineTo(cx + r * 0.8, cy); p.lineTo(cx, cy + r); p.lineTo(cx - r * 0.8, cy); p.closePath();
		g.setPaint(new GradientPaint((float) cx, (float) (cy - r), new Color(0xFFB8F2), (float) cx, (float) (cy + r), new Color(0xB0128F)));
		g.fill(p);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.2f));
		g.draw(p);
	}

	private static void drawArrow(Graphics2D g, double cx, double cy, double r, int dir)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(cx + dir * r, cy); p.lineTo(cx - dir * r * 0.8, cy - r); p.lineTo(cx - dir * r * 0.8, cy + r); p.closePath();
		g.fill(p);
	}

	private static void drawWrapped(Graphics2D g, String text, double x, double y, double w, double lineH, int maxLines)
	{
		FontMetrics fm = g.getFontMetrics();
		String[] words = text.split(" ");
		StringBuilder line = new StringBuilder();
		int lines = 0;
		for (String word : words)
		{
			String t = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(t) > w && line.length() > 0)
			{
				g.drawString(line.toString(), (float) x, (float) y);
				y += lineH; lines++;
				if (lines >= maxLines) { return; }
				line = new StringBuilder(word);
			}
			else { line = new StringBuilder(t); }
		}
		if (line.length() > 0) { g.drawString(line.toString(), (float) x, (float) y); }
	}

	private String labelOf(Item it)
	{
		switch (it)
		{
			case PLAY: return host.gameStarted() ? "RESUME" : "PLAY";
			case EDITION: return "Game version";
			case MULTI: return "Multiplayer";
			case MP_NAME: return "Your name";
			case MP_KEY: return "Room key";
			case MP_CREATE: return "Create game";
			case MP_CODE: return "Room code";
			case MP_JOIN: return "Join game";
			case MP_SERVER: return "Server";
			case MP_SERVERKEY: return "Server key";
			case MP_ROOM: return "Room code";
			case MP_FRIEND: return "Friend";
			case MP_LEAVE: return "Leave room";
			case MP_BACK: return "Back";
			case DISPLAY: return "Display";
			case SIZE: return "Window size";
			case FILTER: return "Image";
			case VSYNC: return "VSync";
			case SOUND: return "Sound";
			case FPS: return "FPS counter";
			case CONTROLS: return "Controls";
			case RESTART: return "Apply and restart game";
			case QUIT: return "Quit";
		}
		return "";
	}

	private String valueOf(Item it)
	{
		switch (it)
		{
			case EDITION: return s.edition().name;
			case MULTI: return mp == null ? "" : (mp.inRoom() ? "Room " + mp.roomCode() : (mp.phase() == MultiSession.Phase.WORKING ? "Connecting..." : "Off"));
			case MP_NAME: return s.playerName.isEmpty() ? "(type your name)" : s.playerName;
			case MP_KEY: return s.roomKey.isEmpty() ? "(type a key, 4+ letters)" : s.roomKey;
			case MP_CODE: return s.roomCode.isEmpty() ? "(from your friend)" : s.roomCode;
			case MP_SERVER: { String u = s.serverUrl.replaceFirst("^https?://", ""); return u.length() > 30 ? u.substring(0, 28) + ".." : u; }
			case MP_SERVERKEY: return s.serverKey.length() < 16 ? "Not set (paste with Ctrl+V)" : "Set (..." + s.serverKey.substring(s.serverKey.length() - 4) + ")";
			case MP_ROOM: return mp.roomCode() + (mp.isHost() ? "  (you host)" : "");
			case MP_FRIEND: return mp.friendName().isEmpty() ? "not here yet" : mp.friendName() + (mp.friendOnline() ? "  (online)" : "  (offline)");
			case DISPLAY: return s.fullscreen ? "Fullscreen" : "Window";
			case SIZE: return s.windowScale == 0 ? "Auto (" + host.autoWindowScale() + "x)" : s.windowScale + "x";
			case FILTER: return Settings.FILTERS[s.filter];
			case VSYNC: return s.vsync ? "On" : "Off";
			case SOUND: return s.sound ? "On" : "Off";
			case FPS: return s.showFps ? "On" : "Off";
			default: return "";
		}
	}

	private String infoOf(Item it)
	{
		Settings.Edition e = s.edition();
		switch (it)
		{
			case PLAY: return host.gameStarted() ? "Back to the game." : "Start Castle of Magic with these settings. Your choices are saved for next time.";
			case EDITION:
				return e.name + ": " + e.detail + ". The original game, unmodified."
					+ (host.gameStarted() && s.edition != startEdition ? " Needs a restart." : "") + " Each version keeps its own saves.";
			case DISPLAY: return "Window or borderless fullscreen. Shortcut: F11 or Alt+Enter.";
			case SIZE: return "Window size as a multiple of the game's " + e.width + "x" + e.height + " screen. You can also drag the window edges.";
			case FILTER: return Settings.FILTER_INFO[s.filter];
			case VSYNC: return s.vsync ? "Syncs to your monitor to avoid tearing." : "Shows every frame immediately. Lowest delay, may tear.";
			case SOUND: return "Music and sound effects." + (host.gameStarted() && s.sound != startSound ? " Needs a restart." : "");
			case FPS: return "Shows game and display frame rates in the corner. Shortcut: F3.";
			case CONTROLS: return "Keyboard and controller buttons.";
			case MULTI: return "Play online with a friend. Each of you plays your own game and sees the other as the yellow hero.";
			case MP_NAME: return "Shown above your hero on your friend's screen.";
			case MP_KEY: return "A secret word for your room. Your friend types the same key to join.";
			case MP_CREATE: return "Open a room. Tell your friend the room code and the room key.";
			case MP_CODE: return "The 6-letter code shown on the host's screen.";
			case MP_JOIN: return "Join your friend's room. Do this before PLAY: you get the host's progress.";
			case MP_SERVER: return "Address of your room server on Render.";
			case MP_SERVERKEY: return "The ACCESS_KEY from Render. Copy it, select this row and press Ctrl+V.";
			case MP_ROOM: return mp.isHost() ? "Tell your friend this code and the room key." : "You are in your friend's room.";
			case MP_FRIEND: return "You see your friend as the yellow hero when you are in the same place.";
			case MP_LEAVE: return mp.isHost() ? "Close the room for both of you." : "Leave the room.";
			case MP_BACK: return "Back to the settings.";
			case RESTART: return "Some changes only apply after restarting. Your progress is saved by the game itself.";
			case QUIT: return "Close the game.";
		}
		return "";
	}
}
