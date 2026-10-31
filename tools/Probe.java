package org.recompile.castle;

import java.io.PrintWriter;

/** Analysis-only hit counter used by ProbePatcher builds. */
public final class Probe
{
	static final int MAX = 20000;
	static final int[] thisFrame = new int[MAX];
	static final int[] framesHit = new int[MAX];
	static final int[] framesHitOnce = new int[MAX];
	static final long[] total = new long[MAX];
	static final int[] run = new int[MAX];
	static final int[] maxRun = new int[MAX];
	static int frames;
	static volatile boolean recording;

	public static void hit(int id) { thisFrame[id]++; }

	static java.io.PrintWriter trace; static int traceFrame;
	public static synchronized void val(int value, int id) { if (trace != null) { trace.println(traceFrame + " " + id + " " + value); } }
	public static synchronized void traceStart(String path) throws Exception { trace = new java.io.PrintWriter(path); traceFrame = 0; }
	public static synchronized void traceStop() { if (trace != null) { trace.close(); trace = null; } }
	public static synchronized void traceFrame() { traceFrame++; }

	public static synchronized void frame()
	{
		if (recording)
		{
			frames++;
			for (int i = 0; i < MAX; i++)
			{
				int c = thisFrame[i];
				if (c > 0) { framesHit[i]++; total[i] += c; if (c == 1) { framesHitOnce[i]++; } run[i]++; if (run[i] > maxRun[i]) { maxRun[i] = run[i]; } } else { run[i] = 0; }
			}
		}
		java.util.Arrays.fill(thisFrame, 0);
	}

	public static synchronized void start() { recording = true; frames = 0; java.util.Arrays.fill(framesHit, 0); java.util.Arrays.fill(framesHitOnce, 0); java.util.Arrays.fill(total, 0); java.util.Arrays.fill(run, 0); java.util.Arrays.fill(maxRun, 0); }

	public static synchronized void dump(String path) throws Exception
	{
		recording = false;
		try (PrintWriter pw = new PrintWriter(path))
		{
			pw.println("frames " + frames);
			for (int i = 0; i < MAX; i++)
			{
				if (framesHit[i] > 0) { pw.println(i + " " + framesHit[i] + " " + framesHitOnce[i] + " " + total[i] + " " + maxRun[i]); }
			}
		}
	}
}
