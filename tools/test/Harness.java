import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless test harness: boots a MIDlet, presses scripted keys, saves screenshots, counts frames.
 * Usage: Harness <jar> <w> <h> <outPrefix> <script>
 * script: comma list of  "w<ms>" wait, "k<idx>" tap key index (Mobile.getMobileKey index), "h<idx>:<ms>" hold, "s<name>" screenshot
 */
public class Harness {
    static final AtomicLong frames = new AtomicLong();
    static final boolean PROBE = Boolean.getBoolean("probe");
    static java.io.PrintWriter log; static long logT0;
    static java.lang.reflect.Field fPlayer; static java.lang.reflect.Field[] fVals;
    static void logState() {
        try {
            if (fPlayer == null) {
                Class<?> c = Class.forName(System.getProperty("pcls","f"), false, Mobile.getPlatform().loader);
                for (java.lang.reflect.Field f : c.getDeclaredFields()) if (f.getName().equals(System.getProperty("pfield","a")) && f.getType() == c && java.lang.reflect.Modifier.isStatic(f.getModifiers())) { fPlayer = f; }
                String[] names = System.getProperty("pvals","e,f,t,u").split(",");
                fVals = new java.lang.reflect.Field[names.length];
                for (int i = 0; i < names.length; i++) for (java.lang.reflect.Field f : c.getDeclaredFields()) if (f.getName().equals(names[i]) && f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) fVals[i] = f;
                fPlayer.setAccessible(true); for (java.lang.reflect.Field f : fVals) f.setAccessible(true);
            }
            Object p = fPlayer.get(null); if (p == null) return;
            StringBuilder sb = new StringBuilder().append(System.currentTimeMillis() - logT0);
            for (java.lang.reflect.Field f : fVals) sb.append(',').append(f.getInt(p));
            log.println(sb);
        } catch (Throwable t) { log.println("ERR " + t); log.close(); log = null; }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String jar = a[0];
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        String out = a[3];
        String script = a[4];

        Mobile.setPlatform(new MobilePlatform(w, h), new Runnable() { public void run() {} });
        Mobile.getPlatform().setPainter(new Runnable() { public void run() { frames.incrementAndGet(); if (PROBE) org.recompile.castle.Probe.frame(); if (log != null) logState(); org.recompile.castle.Probe.traceFrame(); } });
        if (!Mobile.getPlatform().load(new File(jar).toURI().toString())) { System.out.println("LOAD FAILED"); System.exit(2); }
        Mobile.config.settings.put("scrwidth", "" + w);
        Mobile.config.settings.put("scrheight", "" + h);
        Mobile.config.settings.put("fps", System.getProperty("fps", "0"));
        Mobile.config.sysSettings.put("sound", "off");
        Mobile.updateSettings();
        Mobile.getPlatform().resizeLCD(w, h);
        org.recompile.castle.Pacer.smooth = Boolean.parseBoolean(System.getProperty("smooth","false"));
        org.recompile.castle.Pacer.stepW = Integer.parseInt(System.getProperty("stepW","2"));
        org.recompile.castle.Pacer.origMs = Integer.parseInt(System.getProperty("origMs","62"));
        Mobile.getPlatform().runJar();

        long t0 = System.currentTimeMillis();
        for (String step : script.split(",")) {
            char c = step.charAt(0);
            String arg = step.substring(1);
            if (c == 'w') {
                long f0 = frames.get(); long s = System.nanoTime();
                Thread.sleep(Long.parseLong(arg));
                double secs = (System.nanoTime() - s) / 1e9;
                System.out.printf("wait %sms: %.1f fps%n", arg, (frames.get() - f0) / secs);
            } else if (c == 'k') {
                int k = Mobile.getMobileKey(Integer.parseInt(arg));
                MobilePlatform.keyPressed(k); Thread.sleep(120); MobilePlatform.keyReleased(k); Thread.sleep(80);
            } else if (c == 'h') {
                String[] p = arg.split(":");
                int k = Mobile.getMobileKey(Integer.parseInt(p[0]));
                MobilePlatform.keyPressed(k); Thread.sleep(Long.parseLong(p[1])); MobilePlatform.keyReleased(k);
            } else if (c == 'L') { logT0 = System.currentTimeMillis(); log = new java.io.PrintWriter(out + "_" + arg + ".csv");
            } else if (c == 'E') { java.io.PrintWriter l = log; log = null; l.close();
            } else if (c == 'T') { org.recompile.castle.Probe.traceStart(out + "_" + arg + ".trace");
            } else if (c == 'U') { org.recompile.castle.Probe.traceStop();
            } else if (c == 'P') { org.recompile.castle.Probe.start();
            } else if (c == 'D') { org.recompile.castle.Probe.dump(out + "_" + arg + ".probe");
            } else if (c == 's') {
                BufferedImage img = Mobile.getPlatform().getLcdFrontbuffer().getCanvas();
                BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                copy.getGraphics().drawImage(img, 0, 0, null);
                ImageIO.write(copy, "png", new File(out + "_" + arg + ".png"));
                System.out.println("shot " + arg + " at " + (System.currentTimeMillis() - t0) + "ms");
            }
        }
        System.exit(0);
    }
}
