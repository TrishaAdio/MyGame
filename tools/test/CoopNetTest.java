package org.recompile.castle;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;
import org.recompile.mobile.PlatformImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * End-to-end multiplayer test without a window: one process per player.
 *   CoopNetTest host|guest <jar> <serverUrl> <serverKey> <codeFile> <outPrefix> <script>
 * Script: w<ms> wait, k<idx> tap phone key, h<idx>:<ms> hold, s<name> screenshot (with the friend drawn), p print state
 */
public class CoopNetTest
{
	static volatile boolean started;
	static MultiSession mp;
	static final Coop coop = new Coop();
	static volatile PlatformImage lastShown;
	static volatile int[] lastPos = { -9999, -9999 };
	static volatile Coop.State lastMine = new Coop.State();

	public static void main(String[] a) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		boolean host = a[0].equals("host");
		String jar = a[1], server = a[2], key = a[3], codeFile = a[4], out = a[5], script = a[6];

		Settings s = new Settings(new File(out + ".properties"));
		s.playerName = host ? "Anirban" : "Friend";
		s.roomKey = System.getProperty(host ? "hostKey" : "guestKey", "castle-4321");
		s.serverUrl = server;
		s.serverKey = key;
		mp = new MultiSession(s, new File("."), () -> started);

		if (host)
		{
			mp.create();
			waitFor(() -> mp.inRoom() || !mp.error().isEmpty(), 30000);
			System.out.println("HOST room=" + mp.roomCode() + " error=" + mp.error());
			Files.writeString(new File(codeFile).toPath(), mp.roomCode());
		}
		else
		{
			waitFor(() -> new File(codeFile).isFile(), 60000);
			s.roomCode = System.getProperty("codeMangle", "").replace("CODE", Files.readString(new File(codeFile).toPath()).trim().toLowerCase());
			if (s.roomCode.isEmpty()) { s.roomCode = Files.readString(new File(codeFile).toPath()).trim(); }
			mp.join();
			waitFor(() -> mp.inRoom() || !mp.error().isEmpty(), 30000);
			System.out.println("GUEST joined room=" + mp.roomCode() + " error=" + mp.error());
			waitFor(() -> mp.saveSynced(), 20000);
			System.out.println("GUEST save: " + mp.saveNote());
		}

		int w = 240, h = 320;
		Mobile.setPlatform(new MobilePlatform(w, h), () -> { });
		Mobile.getPlatform().setPainter(() -> {
			PlatformImage fb = Mobile.getPlatform().getLcdFrontbuffer();
			Coop.State mine = coop.capture();
			lastMine = mine;
			mp.publish(mine);
			int[] pos = new int[2];
			lastShown = coop.drawFriend(fb, mine, mp.friend(), pos);
			lastPos = pos;
		});
		if (!Mobile.getPlatform().load(new File(jar).toURI().toString())) { System.out.println("LOAD FAILED"); System.exit(2); }
		Mobile.config.settings.put("scrwidth", "" + w);
		Mobile.config.settings.put("scrheight", "" + h);
		Mobile.config.settings.put("fps", "0");
		Mobile.config.sysSettings.put("sound", "off");
		Mobile.updateSettings();
		Mobile.getPlatform().resizeLCD(w, h);
		started = true;
		Mobile.getPlatform().runJar();

		for (String step : script.split(","))
		{
			char c = step.charAt(0);
			String arg = step.substring(1);
			switch (c)
			{
				case 'w': Thread.sleep(Long.parseLong(arg)); break;
				case 'k': { int k = Mobile.getMobileKey(Integer.parseInt(arg)); MobilePlatform.keyPressed(k); Thread.sleep(120); MobilePlatform.keyReleased(k); Thread.sleep(80); break; }
				case 'h': { String[] p = arg.split(":"); int k = Mobile.getMobileKey(Integer.parseInt(p[0])); MobilePlatform.keyPressed(k); Thread.sleep(Long.parseLong(p[1])); MobilePlatform.keyReleased(k); break; }
				case 'p': print(a[0], arg); break;
				case 'B': // wait until the friend is in the same place as me
				{
					long end = System.currentTimeMillis() + Long.parseLong(arg);
					while (System.currentTimeMillis() < end) { Coop.State f = mp.friend(); Coop.State m = lastMine; if (f != null && m.samePlace(f)) { break; } Thread.sleep(100); }
					break;
				}
				case 's':
				{
					print(a[0], arg);
					BufferedImage src = lastShown.getCanvas();
					BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
					img.getGraphics().drawImage(src, 0, 0, null);
					ImageIO.write(img, "png", new File(out + "_" + arg + ".png"));
					break;
				}
				default:
			}
		}
		print(a[0], "end");
		System.out.println(a[0].toUpperCase() + " messages: " + mp.toasts());
		mp.leave();
		Thread.sleep(500);
		System.exit(0);
	}

	static void print(String who, String tag)
	{
		Coop.State f = mp.friend();
		System.out.println(who.toUpperCase() + " [" + tag + "] status=\"" + mp.status() + "\" friend=" + mp.friendName()
			+ " friendPlace=" + (f == null ? "-" : f.place) + " friendDrawnAt=" + lastPos[0] + "," + lastPos[1]);
	}

	interface Cond { boolean ok(); }

	static void waitFor(Cond c, long ms) throws InterruptedException
	{
		long end = System.currentTimeMillis() + ms;
		while (!c.ok() && System.currentTimeMillis() < end) { Thread.sleep(100); }
	}
}
