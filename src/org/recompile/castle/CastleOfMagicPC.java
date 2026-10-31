package org.recompile.castle;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;
import org.recompile.mobile.PlatformImage;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.BufferCapabilities;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.ImageCapabilities;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Castle of Magic for PC: one window, a settings screen first, then the game.
 * The phone runtime is FreeJ2ME-Plus (GPLv3); this class is its PC front end.
 */
public final class CastleOfMagicPC implements Menu.Host
{
	// Phone key indexes understood by Mobile.getMobileKey()
	private static final int K_UP = 0, K_DOWN = 1, K_LEFT = 2, K_RIGHT = 3, K_9 = 4, K_7 = 5, K_0 = 6, K_FIRE = 7,
		K_RSOFT = 8, K_LSOFT = 9, K_1 = 10, K_3 = 11, K_STAR = 12, K_POUND = 13, K_2 = 14, K_4 = 15, K_6 = 16, K_8 = 17, K_5 = 18;

	private final Settings settings;
	private final Menu menu;
	private Settings.Edition edition;      // the edition actually running (fixed once started)

	private JFrame frame;
	private Canvas canvas;
	private BufferStrategy strategy;
	private final Object renderLock = new Object();
	private volatile boolean menuOpen = true;
	private volatile boolean started;
	private volatile boolean running = true;
	private Thread renderThread;

	// Game frame handoff (game thread -> render thread)
	private final Object frameLock = new Object();
	private int[] pending = new int[0], showing = new int[0];
	private int frameW, frameH;
	private boolean newFrame;
	private final Scaler scaler = new Scaler();

	// Stats
	private volatile int gameFrames;
	private int displayFrames;
	private long statsStart = System.nanoTime();
	private String statsText = "";

	// Input state: how many physical inputs hold each phone key
	private final int[] holdCount = new int[19];

	public static void main(String[] args)
	{
		try { start(); }
		catch (Throwable t)
		{
			t.printStackTrace();
			try { JOptionPane.showMessageDialog(null, "Castle of Magic could not start:\n" + t, "Castle of Magic", JOptionPane.ERROR_MESSAGE); }
			catch (Throwable ignored) { }
			System.exit(1);
		}
	}

	private static void start() throws Exception
	{
		File home = new File(System.getProperty("user.dir"));
		System.setProperty("sun.java2d.uiScale", "1");

		// FreeJ2ME requires this encoding; relaunch if started some other way (e.g. java -jar).
		if (!"ISO_8859_1".equals(System.getProperty("file.encoding")) && !"ISO-8859-1".equals(System.getProperty("file.encoding")))
		{
			List<String> cmd = relaunchCommand();
			new ProcessBuilder(cmd).inheritIO().start();
			return;
		}

		System.out.println("Castle of Magic PC, Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
		WinNative.raiseTimerResolution();
		Settings s = new Settings(new File(home, "settings.properties"));
		s.load();
		CastleOfMagicPC app = new CastleOfMagicPC(s);
		SwingUtilities.invokeAndWait(app::createWindow);
		app.startRenderThread();
		WinNative.startGamepadThread(app::padButton);
	}

	private CastleOfMagicPC(Settings s)
	{
		this.settings = s;
		this.menu = new Menu(s, this, loadIcon(s));
	}

	private static Image loadIcon(Settings s)
	{
		for (Settings.Edition e : Settings.EDITIONS)
		{
			try (ZipFile z = new ZipFile(new File(System.getProperty("user.dir"), e.jar)))
			{
				ZipEntry en = z.getEntry("icon.png");
				if (en != null) { try (InputStream in = z.getInputStream(en)) { return ImageIO.read(in); } }
			}
			catch (Exception ignored) { }
		}
		return null;
	}

	// ------------------------------------------------------------------ window

	private void createWindow()
	{
		canvas = new Canvas();
		canvas.setBackground(Color.BLACK);
		canvas.setIgnoreRepaint(true);
		canvas.setFocusTraversalKeysEnabled(false);
		canvas.setPreferredSize(windowedSize());

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override public void mouseMoved(MouseEvent e) { if (menuOpen) { menu.mouseMoved(e.getX(), e.getY()); } showCursorBriefly(); }
			@Override public void mousePressed(MouseEvent e) { if (menuOpen) { menu.mouseClicked(e.getX(), e.getY(), e.getButton()); } }
			@Override public void mouseWheelMoved(MouseWheelEvent e) { if (menuOpen) { menu.mouseWheel(e.getWheelRotation()); } }
		};
		canvas.addMouseListener(mouse);
		canvas.addMouseMotionListener(mouse);
		canvas.addMouseWheelListener(mouse);

		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			java.awt.Window w = e.getComponent() == null ? null : (e.getComponent() instanceof java.awt.Window ww ? ww : SwingUtilities.getWindowAncestor(e.getComponent()));
			if (w != frame) { return false; } // let dialogs handle their own keys
			if (e.getID() == KeyEvent.KEY_PRESSED) { keyDown(e); }
			else if (e.getID() == KeyEvent.KEY_RELEASED) { keyUp(e); }
			return true; // we handle every key (also stops Alt/F10 from stealing focus)
		});

		buildFrame();
	}

	private void buildFrame()
	{
		frame = new JFrame("Castle of Magic");
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override public void windowClosing(WindowEvent e) { quit(); }
			@Override public void windowDeactivated(WindowEvent e) { releaseAllKeys(); }
		});
		Image icon = loadIcon(settings);
		if (icon != null) { frame.setIconImage(icon); }
		frame.getContentPane().setBackground(Color.BLACK);
		frame.setUndecorated(settings.fullscreen);
		frame.add(canvas);
		if (settings.fullscreen)
		{
			frame.setBounds(screen().getDefaultConfiguration().getBounds());
		}
		else
		{
			canvas.setPreferredSize(windowedSize());
			frame.pack();
			frame.setLocationRelativeTo(null);
		}
		frame.setVisible(true);
		createStrategy();
		canvas.requestFocus();
	}

	private GraphicsDevice screen()
	{
		if (frame != null && frame.getGraphicsConfiguration() != null) { return frame.getGraphicsConfiguration().getDevice(); }
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
	}

	private Settings.Edition displayEdition() { return edition != null ? edition : settings.edition(); }

	@Override public int maxWindowScale()
	{
		Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		Settings.Edition e = displayEdition();
		return Math.max(1, Math.min((usable.width - 16) / e.width, (usable.height - 40) / e.height));
	}

	@Override public int autoWindowScale() { return maxWindowScale(); }

	private Dimension windowedSize()
	{
		Settings.Edition e = displayEdition();
		int k = settings.windowScale == 0 ? autoWindowScale() : Math.min(settings.windowScale, maxWindowScale());
		return new Dimension(e.width * k, e.height * k);
	}

	private void createStrategy()
	{
		strategy = null;
		if (settings.vsync)
		{
			try
			{
				// Java2D's own vsync request (Direct3D pipeline). Needs --add-exports java.desktop/sun.java2d.pipe.hw=ALL-UNNAMED.
				Class<?> ext = Class.forName("sun.java2d.pipe.hw.ExtendedBufferCapabilities");
				@SuppressWarnings({"unchecked", "rawtypes"})
				Class<Enum> vst = (Class<Enum>) Class.forName("sun.java2d.pipe.hw.ExtendedBufferCapabilities$VSyncType");
				@SuppressWarnings("unchecked")
				Object on = Enum.valueOf(vst, "VSYNC_ON");
				BufferCapabilities base = new BufferCapabilities(new ImageCapabilities(true), new ImageCapabilities(true), BufferCapabilities.FlipContents.UNDEFINED);
				BufferCapabilities caps = (BufferCapabilities) ext.getConstructor(BufferCapabilities.class, vst).newInstance(base, on);
				canvas.createBufferStrategy(2, caps);
				strategy = canvas.getBufferStrategy();
			}
			catch (Throwable t) { System.out.println("VSync not available, using normal buffering: " + t); }
		}
		if (strategy == null)
		{
			canvas.createBufferStrategy(2);
			strategy = canvas.getBufferStrategy();
		}
	}

	@Override public void displayChanged()
	{
		SwingUtilities.invokeLater(() -> {
			synchronized (renderLock)
			{
				frame.dispose();
				frame.remove(canvas);
				buildFrame();
			}
		});
	}

	private void toggleFullscreen()
	{
		settings.fullscreen = !settings.fullscreen;
		settings.save();
		menu.opened();
		displayChanged();
	}

	private long cursorUntil;

	private void showCursorBriefly()
	{
		cursorUntil = System.currentTimeMillis() + 2000;
		canvas.setCursor(Cursor.getDefaultCursor());
	}

	private static Cursor blank;

	private static Cursor blankCursor()
	{
		if (blank == null) { blank = Toolkit.getDefaultToolkit().createCustomCursor(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new java.awt.Point(0, 0), "blank"); }
		return blank;
	}

	// ------------------------------------------------------------------ rendering

	private void startRenderThread()
	{
		renderThread = new Thread(this::renderLoop, "Render");
		renderThread.setPriority(Thread.MAX_PRIORITY);
		renderThread.setDaemon(true);
		renderThread.start();
	}

	private void renderLoop()
	{
		while (running)
		{
			boolean got = false;
			synchronized (frameLock)
			{
				if (newFrame)
				{
					int[] t = showing; showing = pending; pending = t;
					newFrame = false;
					got = true;
				}
			}
			if (got) { scaler.setFrame(showing, frameW, frameH, settings.filter); lastFilter = settings.filter; }
			else if (lastFilter != settings.filter && scaler.hasFrame()) { scaler.setFrame(showing, frameW, frameH, settings.filter); lastFilter = settings.filter; }

			try
			{
				synchronized (renderLock)
				{
					if (strategy != null && canvas.isDisplayable()) { renderOnce(); }
				}
			}
			catch (Throwable e) { System.out.println("render: " + e); LockSupport.parkNanos(10_000_000); }

			if (!menuOpen && System.currentTimeMillis() > cursorUntil && canvas.getCursor() != blank) { SwingUtilities.invokeLater(() -> canvas.setCursor(blankCursor())); }

			// Wait for the next game frame. With vsync, show() already waited for the monitor.
			if (menuOpen || !started) { LockSupport.parkNanos(8_000_000); }
			else
			{
				long deadline = System.nanoTime() + 50_000_000;
				while (running && System.nanoTime() < deadline)
				{
					synchronized (frameLock) { if (newFrame) { break; } }
					LockSupport.parkNanos(deadline - System.nanoTime());
				}
			}
		}
	}

	private int lastFilter = -1;

	private void renderOnce()
	{
		GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
		int cw = canvas.getWidth(), ch = canvas.getHeight();
		if (cw <= 0 || ch <= 0 || gc == null) { return; }
		do
		{
			do
			{
				Graphics2D g = (Graphics2D) strategy.getDrawGraphics();
				try
				{
					g.setColor(Color.BLACK);
					g.fillRect(0, 0, cw, ch);
					Settings.Edition e = displayEdition();
					if (started && scaler.hasFrame()) { scaler.draw(g, gc, cw, ch, e.width, e.height, settings.filter); }
					if (menuOpen) { menu.draw(g, cw, ch, !started, System.currentTimeMillis()); }
					if (settings.showFps) { drawStats(g); }
				}
				finally { g.dispose(); }
			}
			while (strategy.contentsRestored());
			strategy.show();
		}
		while (strategy.contentsLost());
		Toolkit.getDefaultToolkit().sync();
		displayFrames++;
	}

	private void drawStats(Graphics2D g)
	{
		long now = System.nanoTime();
		if (now - statsStart >= 500_000_000L)
		{
			double secs = (now - statsStart) / 1e9;
			statsText = String.format("GAME %.0f FPS   DISPLAY %.0f FPS", gameFrames / secs, displayFrames / secs);
			gameFrames = 0; displayFrames = 0; statsStart = now;
		}
		g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
		int w = g.getFontMetrics().stringWidth(statsText);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillRect(6, 6, w + 12, 22);
		g.setColor(new Color(0x7CF7FF));
		g.drawString(statsText, 12, 22);
	}

	// ------------------------------------------------------------------ game

	@Override public boolean gameStarted() { return started; }

	@Override public void play()
	{
		if (!started && !startGame()) { return; }
		menuOpen = false;
		Mobile.isPaused = false;
		releaseAllKeys();
	}

	private void openMenu()
	{
		if (menuOpen) { return; }
		releaseAllKeys();
		Mobile.isPaused = true;
		menu.opened();
		menuOpen = true;
		showCursorBriefly();
	}

	private boolean startGame()
	{
		edition = settings.edition();
		File jar = new File(System.getProperty("user.dir"), edition.jar);
		if (!jar.isFile())
		{
			JOptionPane.showMessageDialog(frame, "Game file not found:\n" + jar, "Castle of Magic", JOptionPane.ERROR_MESSAGE);
			edition = null;
			return false;
		}

		Mobile.setPlatform(new MobilePlatform(edition.width, edition.height), () -> { });
		MobilePlatform platform = Mobile.getPlatform();
		platform.dataPath = "saves" + File.separator + edition.id + File.separator; // separate saves per version
		platform.setPainter(this::onGameFrame);
		if (!platform.load(jar.toURI().toString()))
		{
			JOptionPane.showMessageDialog(frame, "Could not load " + jar, "Castle of Magic", JOptionPane.ERROR_MESSAGE);
			edition = null;
			return false;
		}
		Mobile.config.settings.put("scrwidth", "" + edition.width);
		Mobile.config.settings.put("scrheight", "" + edition.height);
		Mobile.config.settings.put("phone", "Standard");
		Mobile.config.settings.put("rotate", "0");
		Mobile.config.settings.put("fps", "0");          // no extra limiter: the game paces itself, exactly as on the phone
		Mobile.config.settings.put("fpshack", "Disabled");
		Mobile.config.sysSettings.put("sound", settings.sound ? "on" : "off");
		Mobile.config.sysSettings.put("fpsCounterPosition", "Off");
		Mobile.updateSettings();
		platform.resizeLCD(edition.width, edition.height);
		started = true;
		platform.runJar();

		return true;
	}

	/** Called on the game thread after every finished frame. */
	private void onGameFrame()
	{
		if (MobilePlatform.appTerminated) { SwingUtilities.invokeLater(this::quit); return; }
		PlatformImage fb = Mobile.getPlatform().getLcdFrontbuffer();
		int w = fb.getWidth(), h = fb.getHeight();
		int[] data = fb.getDataBuffer();
		synchronized (frameLock)
		{
			if (pending.length != w * h) { pending = new int[w * h]; }
			System.arraycopy(data, 0, pending, 0, w * h);
			frameW = w; frameH = h;
			newFrame = true;
		}
		gameFrames++;
		Thread rt = renderThread;
		if (rt != null) { LockSupport.unpark(rt); }
	}

	@Override public void quit()
	{
		running = false;
		settings.save();
		System.exit(0);
	}

	@Override public void restart()
	{
		settings.save();
		try { new ProcessBuilder(relaunchCommand()).directory(new File(System.getProperty("user.dir"))).start(); }
		catch (Exception e) { JOptionPane.showMessageDialog(frame, "Please start the game again.\n" + e, "Castle of Magic", JOptionPane.INFORMATION_MESSAGE); }
		System.exit(0);
	}

	private static List<String> relaunchCommand()
	{
		String javaHome = System.getProperty("java.home");
		File javaw = new File(javaHome, "bin" + File.separator + "javaw.exe");
		File java = javaw.isFile() ? javaw : new File(javaHome, "bin" + File.separator + "java");
		List<String> cmd = new ArrayList<>();
		cmd.add(java.getPath());
		for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) { if (!a.startsWith("-Dfile.encoding")) { cmd.add(a); } }
		cmd.add("-Dfile.encoding=ISO_8859_1");
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add(CastleOfMagicPC.class.getName());
		return cmd;
	}

	// ------------------------------------------------------------------ input

	private void keyDown(KeyEvent e)
	{
		int code = e.getKeyCode();
		if ((code == KeyEvent.VK_ENTER && e.isAltDown()) || code == KeyEvent.VK_F11) { if (!isRepeat(e)) { toggleFullscreen(); } return; }
		if (code == KeyEvent.VK_F3) { if (!isRepeat(e)) { settings.showFps = !settings.showFps; settings.save(); } return; }
		if (code == KeyEvent.VK_F12) { if (!isRepeat(e)) { screenshot(); } return; }

		if (menuOpen)
		{
			switch (code)
			{
				case KeyEvent.VK_UP: case KeyEvent.VK_W: case KeyEvent.VK_NUMPAD8: menu.up(); break;
				case KeyEvent.VK_DOWN: case KeyEvent.VK_S: case KeyEvent.VK_NUMPAD2: menu.down(); break;
				case KeyEvent.VK_LEFT: case KeyEvent.VK_A: case KeyEvent.VK_NUMPAD4: menu.left(); break;
				case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: case KeyEvent.VK_NUMPAD6: menu.right(); break;
				case KeyEvent.VK_ENTER: case KeyEvent.VK_SPACE: case KeyEvent.VK_Z: case KeyEvent.VK_NUMPAD5: if (!isRepeat(e)) { menu.activate(); } break;
				case KeyEvent.VK_ESCAPE: case KeyEvent.VK_F1: case KeyEvent.VK_BACK_SPACE: if (!isRepeat(e)) { menu.back(); } break;
				default:
			}
			return;
		}

		if (code == KeyEvent.VK_F1) { openMenu(); return; }
		int k = phoneKeyFor(code);
		if (k >= 0) { press(k, true, e); }
	}

	private void keyUp(KeyEvent e)
	{
		int c = e.getKeyCode() & 1023;
		int k = phoneKeyFor(e.getKeyCode());
		if (menuOpen || k < 0) { physicalDown[c] = false; return; }
		press(k, false, e);
	}

	private final boolean[] physicalDown = new boolean[1024];

	private boolean isRepeat(KeyEvent e)
	{
		// Windows sends repeated KEY_PRESSED while held; keyUp clears the flag
		int c = e.getKeyCode() & 1023;
		if (e.getID() == KeyEvent.KEY_PRESSED) { boolean r = physicalDown[c]; physicalDown[c] = true; return r; }
		physicalDown[c] = false;
		return false;
	}

	private void press(int phoneKey, boolean down, KeyEvent e)
	{
		int c = e.getKeyCode() & 1023;
		if (down) { if (physicalDown[c]) { return; } physicalDown[c] = true; }
		else { if (!physicalDown[c]) { return; } physicalDown[c] = false; }
		phone(phoneKey, down);
	}

	private void phone(int k, boolean down)
	{
		if (!started || Mobile.getPlatform() == null) { return; }
		synchronized (holdCount)
		{
			if (down)
			{
				if (holdCount[k]++ == 0) { MobilePlatform.keyPressed(Mobile.getMobileKey(k)); }
			}
			else if (holdCount[k] > 0)
			{
				if (--holdCount[k] == 0) { MobilePlatform.keyReleased(Mobile.getMobileKey(k)); }
			}
		}
	}

	private void releaseAllKeys()
	{
		java.util.Arrays.fill(physicalDown, false);
		synchronized (holdCount)
		{
			for (int k = 0; k < holdCount.length; k++)
			{
				if (holdCount[k] > 0 && started) { MobilePlatform.keyReleased(Mobile.getMobileKey(k)); }
				holdCount[k] = 0;
			}
		}
	}

	private static int phoneKeyFor(int code)
	{
		switch (code)
		{
			case KeyEvent.VK_UP: case KeyEvent.VK_W: return K_UP;
			case KeyEvent.VK_DOWN: case KeyEvent.VK_S: return K_DOWN;
			case KeyEvent.VK_LEFT: case KeyEvent.VK_A: return K_LEFT;
			case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: return K_RIGHT;
			case KeyEvent.VK_ENTER: case KeyEvent.VK_SPACE: case KeyEvent.VK_Z: return K_FIRE;
			case KeyEvent.VK_ESCAPE: case KeyEvent.VK_Q: return K_LSOFT;
			case KeyEvent.VK_BACK_SPACE: case KeyEvent.VK_X: return K_RSOFT;
			case KeyEvent.VK_0: case KeyEvent.VK_NUMPAD0: return K_0;
			case KeyEvent.VK_1: case KeyEvent.VK_NUMPAD1: return K_1;
			case KeyEvent.VK_2: case KeyEvent.VK_NUMPAD2: return K_2;
			case KeyEvent.VK_3: case KeyEvent.VK_NUMPAD3: return K_3;
			case KeyEvent.VK_4: case KeyEvent.VK_NUMPAD4: return K_4;
			case KeyEvent.VK_5: case KeyEvent.VK_NUMPAD5: return K_5;
			case KeyEvent.VK_6: case KeyEvent.VK_NUMPAD6: return K_6;
			case KeyEvent.VK_7: case KeyEvent.VK_NUMPAD7: return K_7;
			case KeyEvent.VK_8: case KeyEvent.VK_NUMPAD8: return K_8;
			case KeyEvent.VK_9: case KeyEvent.VK_NUMPAD9: return K_9;
			case KeyEvent.VK_MULTIPLY: return K_STAR;
			case KeyEvent.VK_DIVIDE: return K_POUND;
			default: return -1;
		}
	}

	/** Controller input (from the gamepad thread). */
	private void padButton(int b, boolean down)
	{
		SwingUtilities.invokeLater(() -> {
			if (menuOpen)
			{
				if (!down) { return; }
				switch (b)
				{
					case WinNative.PAD_UP: menu.up(); break;
					case WinNative.PAD_DOWN: menu.down(); break;
					case WinNative.PAD_LEFT: menu.left(); break;
					case WinNative.PAD_RIGHT: menu.right(); break;
					case WinNative.PAD_A: case WinNative.PAD_START: menu.activate(); break;
					case WinNative.PAD_B: case WinNative.PAD_BACK: menu.back(); break;
					default:
				}
				return;
			}
			if (b == WinNative.PAD_BACK) { if (down) { openMenu(); } return; }
			int k;
			switch (b)
			{
				case WinNative.PAD_UP: k = K_UP; break;
				case WinNative.PAD_DOWN: k = K_DOWN; break;
				case WinNative.PAD_LEFT: k = K_LEFT; break;
				case WinNative.PAD_RIGHT: k = K_RIGHT; break;
				case WinNative.PAD_A: k = K_FIRE; break;
				case WinNative.PAD_B: k = K_RSOFT; break;
				case WinNative.PAD_START: k = K_LSOFT; break;
				case WinNative.PAD_X: k = K_1; break;
				case WinNative.PAD_Y: k = K_3; break;
				case WinNative.PAD_LB: k = K_STAR; break;
				case WinNative.PAD_RB: k = K_POUND; break;
				default: return;
			}
			phone(k, down);
		});
	}

	// ------------------------------------------------------------------ extras

	private void screenshot()
	{
		if (!scaler.hasFrame()) { return; }
		try
		{
			int w, h; int[] px;
			synchronized (frameLock) { w = frameW; h = frameH; px = showing.clone(); }
			BufferedImage img = new BufferedImage(w * 3, h * 3, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h * 3; y++) { for (int x = 0; x < w * 3; x++) { img.setRGB(x, y, px[(y / 3) * w + x / 3]); } }
			File dir = new File("screenshots");
			dir.mkdirs();
			File f = new File(dir, "castle-of-magic-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
			ImageIO.write(img, "png", f);
			System.out.println("Saved " + f);
		}
		catch (Exception e) { System.out.println("Screenshot failed: " + e); }
	}

}
