package org.recompile.castle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Small Windows helpers through Java's foreign function API (no extra DLLs needed):
 *  - 1 ms system timer resolution, so frame pacing can sleep precisely instead of busy-waiting
 *  - XInput controllers (Xbox and compatible pads)
 * Everything fails quietly on other systems.
 */
final class WinNative
{
	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	private WinNative() { }

	static void raiseTimerResolution()
	{
		if (!WINDOWS) { return; }
		try
		{
			Linker linker = Linker.nativeLinker();
			SymbolLookup winmm = SymbolLookup.libraryLookup("winmm", Arena.global());
			MethodHandle begin = linker.downcallHandle(winmm.find("timeBeginPeriod").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			int r = (int) begin.invokeExact(1);
			System.out.println("timeBeginPeriod(1) = " + r);
		}
		catch (Throwable t) { System.out.println("Timer resolution unchanged: " + t); }
	}

	/** Receives controller button changes. */
	interface PadListener { void padButton(int button, boolean down); }

	// Button ids passed to PadListener
	static final int PAD_UP = 0, PAD_DOWN = 1, PAD_LEFT = 2, PAD_RIGHT = 3, PAD_A = 4, PAD_B = 5, PAD_X = 6, PAD_Y = 7,
		PAD_START = 8, PAD_BACK = 9, PAD_LB = 10, PAD_RB = 11, PAD_COUNT = 12;

	private static final int[] XI_BITS = { 0x0001, 0x0002, 0x0004, 0x0008, 0x1000, 0x2000, 0x4000, 0x8000, 0x0010, 0x0020, 0x0100, 0x0200 };

	static void startGamepadThread(PadListener listener)
	{
		if (!WINDOWS) { return; }
		MethodHandle getState;
		try
		{
			SymbolLookup xinput;
			try { xinput = SymbolLookup.libraryLookup("xinput1_4", Arena.global()); }
			catch (Throwable t) { xinput = SymbolLookup.libraryLookup("xinput9_1_0", Arena.global()); }
			getState = Linker.nativeLinker().downcallHandle(xinput.find("XInputGetState").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		}
		catch (Throwable t) { System.out.println("No XInput: " + t); return; }

		final MethodHandle xget = getState;
		Thread t = new Thread(() -> {
			MemorySegment state = Arena.global().allocate(16, 4);
			boolean[] down = new boolean[PAD_COUNT];
			int active = -1;
			long nextScan = 0;
			while (true)
			{
				try
				{
					// Looking for a pad on empty slots is slow in XInput, so only scan once a second
					if (active < 0)
					{
						if (System.nanoTime() >= nextScan)
						{
							for (int i = 0; i < 4 && active < 0; i++) { if ((int) xget.invokeExact(i, state) == 0) { active = i; System.out.println("Controller " + i + " connected"); } }
							nextScan = System.nanoTime() + 1_000_000_000L;
						}
						if (active < 0) { Thread.sleep(100); continue; }
					}
					if ((int) xget.invokeExact(active, state) != 0)
					{
						active = -1;
						for (int b = 0; b < PAD_COUNT; b++) { if (down[b]) { down[b] = false; listener.padButton(b, false); } }
						continue;
					}
					int buttons = state.get(ValueLayout.JAVA_SHORT, 4) & 0xFFFF;
					int lx = state.get(ValueLayout.JAVA_SHORT, 8);
					int ly = state.get(ValueLayout.JAVA_SHORT, 10);
					final int dead = 12000;
					for (int b = 0; b < PAD_COUNT; b++)
					{
						boolean now = (buttons & XI_BITS[b]) != 0;
						if (b == PAD_UP) { now |= ly > dead; }
						if (b == PAD_DOWN) { now |= ly < -dead; }
						if (b == PAD_LEFT) { now |= lx < -dead; }
						if (b == PAD_RIGHT) { now |= lx > dead; }
						if (now != down[b]) { down[b] = now; listener.padButton(b, now); }
					}
					Thread.sleep(4); // 250 Hz polling
				}
				catch (Throwable e) { try { Thread.sleep(500); } catch (InterruptedException ie) { return; } }
			}
		}, "Gamepad");
		t.setDaemon(true);
		t.start();
	}
}
