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
    static java.io.PrintWriter log; static long logT0;
    static java.lang.reflect.Field fPlayer; static java.lang.reflect.Field[] fVals;
    /** -Dvars="g.i,g.j,f.aa" (static ints) and -Dhero="f.h:l,m,a.d,a.e" */
    static String dumpVars() {
        StringBuilder sb = new StringBuilder();
        try {
            ClassLoader cl = Mobile.getPlatform().loader;
            for (String v : System.getProperty("vars", "").split(",")) {
                if (v.isEmpty()) continue;
                String[] p = v.split("\\.");
                Class<?> c = Class.forName(p[0], false, cl);
                java.lang.reflect.Field f = null;
                for (java.lang.reflect.Field x : c.getDeclaredFields()) if (x.getName().equals(p[1]) && java.lang.reflect.Modifier.isStatic(x.getModifiers()) && x.getType() == int.class) f = x;
                f.setAccessible(true);
                sb.append(v).append('=').append(f.get(null)).append(' ');
            }
            String hero = System.getProperty("hero", "");
            if (!hero.isEmpty()) {
                String[] hp = hero.split(":");
                String[] cf = hp[0].split("\\.");
                Class<?> c = Class.forName(cf[0], false, cl);
                java.lang.reflect.Field hf = null;
                for (java.lang.reflect.Field x : c.getDeclaredFields()) if (x.getName().equals(cf[1]) && x.getType() == c && java.lang.reflect.Modifier.isStatic(x.getModifiers())) hf = x;
                hf.setAccessible(true);
                Object h = hf.get(null);
                if (h == null) { sb.append("hero=null"); return sb.toString(); }
                for (String n : hp[1].split(",")) {
                    Object target = h; String name = n;
                    if (n.contains(".")) { // sub-object field of type (class) e.g. a.d = hero's 'a' typed field, then its int 'd'
                        String[] q = n.split("\\.");
                        Class<?> sub = Class.forName(q[0], false, cl);
                        for (java.lang.reflect.Field x : c.getDeclaredFields()) if (x.getType() == sub && !java.lang.reflect.Modifier.isStatic(x.getModifiers())) { x.setAccessible(true); target = x.get(h); }
                        name = q[1];
                    }
                    for (java.lang.reflect.Field x : target.getClass().getDeclaredFields()) if (x.getName().equals(name) && x.getType() == int.class && !java.lang.reflect.Modifier.isStatic(x.getModifiers())) { x.setAccessible(true); sb.append(n).append('=').append(x.getInt(target)).append(' '); }
                }
            }
        } catch (Throwable t) { sb.append("ERR ").append(t); }
        return sb.toString();
    }

    static org.recompile.castle.Coop coop = new org.recompile.castle.Coop();
    static volatile org.recompile.castle.Coop.State coopGhost; static volatile boolean coopGrab;
    static volatile java.awt.image.BufferedImage coopShot; static volatile String coopShotName;
    static void coopFrame() {
        if (coopGrab) { coopGhost = coop.capture(); coopGrab = false; System.out.println("GHOST " + coopGhost.toJson("me")); }
        if (coopShotName != null) {
            org.recompile.castle.Coop.State me = coop.capture();
            int[] pos = new int[2];
            org.recompile.mobile.PlatformImage img = coop.drawFriend(Mobile.getPlatform().getLcdFrontbuffer(), me, coopGhost, pos);
            BufferedImage src = img.getCanvas();
            BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            copy.getGraphics().drawImage(src, 0, 0, null);
            System.out.println("COOP me=" + me.toJson("me") + " friendAt=" + pos[0] + "," + pos[1] + " drawn=" + (img != Mobile.getPlatform().getLcdFrontbuffer()));
            coopShot = copy; String n = coopShotName; coopShotName = null;
            try { ImageIO.write(copy, "png", new File(n)); } catch (Exception e) { e.printStackTrace(); }
        }
    }

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
        Mobile.getPlatform().setPainter(new Runnable() { public void run() { frames.incrementAndGet(); if (log != null) logState(); coopFrame(); } });
        if (!Mobile.getPlatform().load(new File(jar).toURI().toString())) { System.out.println("LOAD FAILED"); System.exit(2); }
        Mobile.config.settings.put("scrwidth", "" + w);
        Mobile.config.settings.put("scrheight", "" + h);
        Mobile.config.settings.put("fps", System.getProperty("fps", "0"));
        Mobile.config.sysSettings.put("sound", "off");
        Mobile.updateSettings();
        Mobile.getPlatform().resizeLCD(w, h);
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
            } else if (c == 'V') { System.out.println("STATE " + arg + " " + dumpVars());
            } else if (c == 'X') {
                java.lang.reflect.Method m = null;
                for (java.lang.reflect.Method x : Class.forName("i", false, Mobile.getPlatform().loader).getDeclaredMethods()) if (x.getName().equals("b") && x.getReturnType() == String.class && x.getParameterCount() == 1 && x.getParameterTypes()[0] == int.class) m = x;
                m.setAccessible(true);
                for (int k = 0; k < 400; k++) { try { Object t = m.invoke(null, k); if (t != null) System.out.println("TXT " + k + " " + String.valueOf(t).replace('\n', ' ')); } catch (Throwable e) { break; } }
            } else if (c == 'G') { coopGrab = true; Thread.sleep(150);
            } else if (c == 'C') { coopShotName = out + "_" + arg + ".png"; Thread.sleep(200);
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
