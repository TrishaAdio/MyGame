package org.recompile.castle;

import org.recompile.mobile.Mobile;

import java.util.concurrent.locks.LockSupport;

/**
 * Frame timing for the patched Castle of Magic jars.
 *
 * The game engine advances animations by a per-frame "milliseconds" value and physics by a
 * per-frame 8.8 fixed-point "seconds" value, but Gameloft hard-coded both to one phone frame
 * (62 or 83 ms). The build-time patcher redirects those constants and the main-loop sleep here.
 *
 * Original mode returns the original constants and sleeps like the phone did.
 * Smooth mode runs physics in exact steps of {@code stepW}/256 s and paces frames to match,
 * so the game keeps its original speed while drawing many more frames per second.
 */
public final class Pacer
{
	/** Original per-frame animation milliseconds (62 for the adventure build, 83 for the platformer). */
	public static volatile int origMs = 62;
	/** Original per-frame physics step in 1/256 s (both builds use 66 ms -> 16). */
	public static volatile int origW = 16;
	/** Physics step used in smooth mode, in 1/256 s. Smaller = more frames per second. */
	public static volatile int stepW = 2;
	public static volatile boolean smooth = true;

	private static double msAccumulator;
	private static long nextDeadline;
	private static long spinThresholdNs = -1;

	private Pacer() { }

	/** Frames per second produced by a given physics step for the current build. */
	public static double fpsFor(int w) { return 1000.0 / periodMs(w); }

	private static double periodMs(int w) { return (double) origMs * w / origW; }

	public static double targetFps() { return smooth ? fpsFor(stepW) : 1000.0 / origMs; }

	/** Replaces the hard-coded per-frame animation time. Called once at the start of every game frame. */
	public static int stepMs()
	{
		if (!smooth) { tick = true; return origMs; }
		double period = periodMs(stepW);
		tickAccumulator += period;
		if (tickAccumulator >= origMs) { tickAccumulator -= origMs; tick = true; }
		else { tick = false; }
		msAccumulator += period;
		int whole = (int) msAccumulator;
		msAccumulator -= whole;
		return whole;
	}

	/** Replaces the hard-coded per-frame physics step. */
	public static int stepW() { return smooth ? stepW : origW; }

	// ---- Frame-counted logic ---------------------------------------------------------------
	// Some game code changes values by a fixed amount every frame (timers, text reveal, fades,
	// friction). The patcher routes those writes through gate() or scale() so they keep their
	// original real-time speed at any frame rate.

	private static double tickAccumulator;
	private static volatile boolean tick = true;
	private static final long[] siteRemainder = new long[256];

	/** Timestep for velocity helpers. They run once per original frame (see gate), so they use the original step. */
	public static int velW() { return origW; }

	/** Restart the original-frame clock, called right before a jump impulse. */
	public static void resync() { if (smooth) { tickAccumulator = 0; } }

	/** True on frames where one original phone frame has elapsed. */
	public static boolean tick() { return tick; }

	/** Instance-field timers: apply the change only once per original frame. */
	public static int gate(int newValue, int oldValue) { return (!smooth || tick) ? newValue : oldValue; }

	private static final java.util.IdentityHashMap<Object, double[]> holdTimers = new java.util.IdentityHashMap<>();

	/**
	 * Jump impulses start above the velocity cap and the game snaps them back on the next frame.
	 * On the phone that "next frame" came 83 ms later; keep the impulse that long here too.
	 */
	public static int capHold(Object entity, int newValue, int oldValue)
	{
		if (!smooth) { return newValue; }
		// The upward thrust call can only make an upward velocity smaller when it is clamping an impulse.
		boolean snapping = newValue < 0 && oldValue < newValue;
		synchronized (holdTimers)
		{
			if (!snapping) { holdTimers.remove(entity); return newValue; }
			// t[0] = elapsed ms, t[1] = impulse value. On the phone the impulse frame had no gravity applied,
			// so hold the value itself rather than letting gravity eat into it.
			double[] t = holdTimers.get(entity);
			if (t == null) { t = new double[] {0, oldValue}; holdTimers.put(entity, t); }
			t[0] += periodMs(stepW);
			if (t[0] >= origMs) { holdTimers.remove(entity); return newValue; }
			return (int) t[1];
		}
	}

	/** Static-field timers: apply the change proportionally to the elapsed time, keeping remainders. */
	public static int scale(int newValue, int oldValue, int site)
	{
		if (!smooth) { return newValue; }
		long acc = siteRemainder[site] + (long) (newValue - oldValue) * stepW;
		long out = Math.floorDiv(acc, origW);
		siteRemainder[site] = acc - out * origW;
		return (int) (oldValue + out);
	}

	/** Replaces Thread.sleep() in the game's main loop. */
	public static void frameSleep(long requestedMs) throws InterruptedException
	{
		while (Mobile.isPaused) { Thread.sleep(50); nextDeadline = 0; }

		if (!smooth)
		{
			Thread.sleep(requestedMs);
			return;
		}

		if (spinThresholdNs < 0) { calibrate(); }

		long period = (long) (periodMs(stepW) * 1_000_000.0);
		long now = System.nanoTime();
		if (nextDeadline == 0 || now - nextDeadline > 4 * period) { nextDeadline = now; } // first frame, or fell far behind
		nextDeadline += period;

		long remaining;
		while ((remaining = nextDeadline - System.nanoTime()) > spinThresholdNs)
		{
			LockSupport.parkNanos(remaining - spinThresholdNs);
		}
		while (nextDeadline - System.nanoTime() > 0) { Thread.onSpinWait(); }
	}

	/** Measures how precise parkNanos is on this machine so we only busy-wait the final stretch. */
	private static void calibrate()
	{
		long worst = 0;
		for (int i = 0; i < 12; i++)
		{
			long t = System.nanoTime();
			LockSupport.parkNanos(500_000);
			long over = System.nanoTime() - t - 500_000;
			if (i >= 2) { worst = Math.max(worst, over); }
		}
		// Keep some margin. If the OS timer is very coarse this simply means we spin more.
		spinThresholdNs = Math.max(300_000, worst + 300_000);
	}
}
