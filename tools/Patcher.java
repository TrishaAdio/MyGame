import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Build-time patcher for Castle of Magic J2ME builds.
 * Redirects the hard-coded frame timing to org.recompile.castle.Pacer so the game can run
 * at high frame rates while keeping its original speed.
 *
 * Usage: java -cp asm.jar:asm-tree.jar:. Patcher in.jar out.jar
 * Fails (exit 1) unless all three patch sites are found exactly once.
 */
public class Patcher
{
	static final String PACER = "org/recompile/castle/Pacer";
	static int siteCounter = 0;

	public static void main(String[] args) throws Exception
	{
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0])))
		{
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) { if (!e.isDirectory()) { entries.put(e.getName(), zin.readAllBytes()); } }
		}

		int sleepSites = 0, msSites = 0, wSites = 0;
		int frameMs = -1;

		// Gate file lines: "<class> <method> <desc> <owner.field> <occurrence> <gate|scale>"
		Map<String, String> gates = new LinkedHashMap<>();
		Map<String, String> holds = new LinkedHashMap<>();
		Map<String, String> helpers = new LinkedHashMap<>();
		Set<String> resyncs = new LinkedHashSet<>();
		Map<String, String> dtSwaps = new LinkedHashMap<>();
		if (args.length > 2)
		{
			for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[2])))
			{
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) { continue; }
				String[] p = line.split("\\s+");
				if ("HELPER".equals(p[3])) { helpers.put(p[0] + " " + p[1] + " " + p[2], p[4]); }        // <cls> <m> <desc> HELPER <timestepField>
				else if ("RESYNC".equals(p[3])) { resyncs.add(p[0] + " " + p[1] + " " + p[2] + " " + p[4] + " " + p[5]); } // <cls> <m> <desc> RESYNC <owner.name(desc)> <occ>
				else if ("CALL".equals(p[3])) { holds.put(p[0] + " " + p[1] + " " + p[2] + " " + p[4] + " " + p[5], p[7]); } // <cls> <m> <desc> CALL <owner.name(desc)> <occ> hold <field>
				else
				{
					gates.put(p[0] + " " + p[1] + " " + p[2] + " " + p[3] + " " + p[4], p[5]);
					// "gatew <dtHelper(I)I> <mul(II)I>": inside this method, use the original timestep for dtHelper(x) = mul(x, dt)
					if ("gatew".equals(p[5])) { dtSwaps.put(p[0] + " " + p[1] + " " + p[2], p[6] + " " + p[7]); }
				}
			}
		}

		for (Map.Entry<String, byte[]> en : entries.entrySet())
		{
			if (!en.getKey().endsWith(".class")) { continue; }
			ClassNode cn = new ClassNode();
			new ClassReader(en.getValue()).accept(cn, 0);
			boolean changed = false;

			boolean isCanvas = "com/nokia/mid/ui/FullCanvas".equals(cn.superName) || "javax/microedition/lcdui/Canvas".equals(cn.superName);

			for (MethodNode m : cn.methods)
			{
				// 1) Main loop: first Thread.sleep(J) inside the canvas' run()
				if (isCanvas && "run".equals(m.name) && "()V".equals(m.desc))
				{
					for (AbstractInsnNode in : m.instructions.toArray())
					{
						if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
							&& "java/lang/Thread".equals(mi.owner) && "sleep".equals(mi.name) && "(J)V".equals(mi.desc))
						{
							mi.owner = PACER; mi.name = "frameSleep";
							sleepSites++; changed = true;
							break;
						}
					}
				}

				AbstractInsnNode[] ins = m.instructions.toArray();
				for (int i = 0; i + 3 < ins.length; i++)
				{
					// 2) Per-frame animation time: "<canvas>.f = K; this.x = K;" (K = 62 or 83)
					if (ins[i] instanceof IntInsnNode a && a.getOpcode() == Opcodes.BIPUSH
						&& ins[i + 1] instanceof FieldInsnNode f1 && f1.getOpcode() == Opcodes.PUTSTATIC && "I".equals(f1.desc) && !f1.owner.equals(cn.name)
						&& ins[i + 2] instanceof IntInsnNode b && b.getOpcode() == Opcodes.BIPUSH && b.operand == a.operand
						&& ins[i + 3] instanceof FieldInsnNode f2 && f2.getOpcode() == Opcodes.PUTSTATIC && "I".equals(f2.desc) && f2.owner.equals(cn.name)
						&& (a.operand == 62 || a.operand == 83))
					{
						m.instructions.set(b, new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "stepMs", "()I", false));
						frameMs = a.operand;
						msSites++; changed = true;
					}
				}

				ins = m.instructions.toArray();
				for (int i = 0; i + 4 < ins.length; i++)
				{
					// 3) Per-frame physics step: "this.w = toFixed(66) / 1000;"
					if (ins[i] instanceof IntInsnNode a && a.getOpcode() == Opcodes.BIPUSH && a.operand == 66
						&& ins[i + 1] instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESTATIC && "(I)I".equals(c.desc)
						&& ins[i + 2] instanceof IntInsnNode d && d.getOpcode() == Opcodes.SIPUSH && d.operand == 1000
						&& ins[i + 3].getOpcode() == Opcodes.IDIV
						&& ins[i + 4] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.owner.equals(cn.name))
					{
						InsnList repl = new InsnList();
						repl.add(new InsnNode(Opcodes.POP));
						repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "stepW", "()I", false));
						m.instructions.insertBefore(f, repl);
						wSites++; changed = true;
					}
				}
			}

			// 4) Frame-counted logic sites listed in the gate file
			for (MethodNode m : cn.methods)
			{
				Map<String, Integer> occ = new HashMap<>();
				for (AbstractInsnNode in : m.instructions.toArray())
				{
					if (!(in instanceof FieldInsnNode f) || !(f.getOpcode() == Opcodes.PUTSTATIC || f.getOpcode() == Opcodes.PUTFIELD) || !"I".equals(f.desc)) { continue; }
					String fkey = f.owner + "." + f.name;
					int o = occ.merge(fkey, 1, Integer::sum) - 1;
					String key = cn.name + " " + m.name + " " + m.desc + " " + fkey + " " + o;
					String mode = gates.remove(key);
					if (mode == null) { continue; }

					boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
					AbstractInsnNode at = f;
					AbstractInsnNode prev = f.getPrevious();
					while (prev != null && prev.getOpcode() < 0) { prev = prev.getPrevious(); }
					if (prev != null && prev.getOpcode() == (isStatic ? Opcodes.DUP : Opcodes.DUP_X1)) { at = prev; } // value is also used in an expression

					InsnList l = new InsnList();
					if (!isStatic)
					{
						// [obj, new] -> [obj, new, old]
						l.add(new InsnNode(Opcodes.SWAP));
						l.add(new InsnNode(Opcodes.DUP_X1));
						l.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, f.name, "I"));
					}
					else
					{
						l.add(new FieldInsnNode(Opcodes.GETSTATIC, f.owner, f.name, "I"));
					}
					if ("gate".equals(mode) || "gatew".equals(mode)) { l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "gate", "(II)I", false)); }
					else
					{
						l.add(new LdcInsnNode(siteCounter++));
						l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "scale", "(III)I", false));
					}
					m.instructions.insertBefore(at, l);
					changed = true;
					System.out.println("  " + mode + ": " + key);
				}
			}

			// 4b) Timestep swaps for "gatew" methods
			for (MethodNode m : cn.methods)
			{
				String swap = dtSwaps.remove(cn.name + " " + m.name + " " + m.desc);
				if (swap == null) { continue; }
				if (applyDtSwap(m, swap)) { changed = true; System.out.println("  dt swap in " + cn.name + "." + m.name + m.desc + ": " + swap); }
			}

			// 6) Velocity helpers: step velocity with the original timestep, once per original frame.
			//    Positions still integrate every frame, so motion is smooth but trajectories match the phone.
			for (MethodNode m : cn.methods)
			{
				String field = helpers.remove(cn.name + " " + m.name + " " + m.desc);
				if (field == null) { continue; }
				if (field.contains("=")) { applyDtSwap(m, field.replace("=", " ")); field = "-.-"; } // dt comes from a helper call
				String[] of = field.split("\\.");
				int saved = m.maxLocals;
				m.maxLocals += 1;
				for (AbstractInsnNode in : m.instructions.toArray())
				{
					if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(of[0]) && f.name.equals(of[1]))
					{
						m.instructions.set(in, new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "velW", "()I", false));
					}
					else if (in.getOpcode() == Opcodes.IRETURN)
					{
						InsnList l = new InsnList();
						l.add(new VarInsnNode(Opcodes.ILOAD, saved));
						l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "gate", "(II)I", false));
						m.instructions.insertBefore(in, l);
					}
				}
				InsnList head = new InsnList();
				head.add(new VarInsnNode(Opcodes.ILOAD, 0)); // static helper: first argument is the current velocity
				head.add(new VarInsnNode(Opcodes.ISTORE, saved));
				m.instructions.insert(head);
				changed = true;
				System.out.println("  helper: " + cn.name + "." + m.name + m.desc);
			}

			// 7) Tick resync before impulses, so the first velocity update lands one original frame later.
			for (MethodNode m : cn.methods)
			{
				Map<String, Integer> occ = new HashMap<>();
				for (AbstractInsnNode in : m.instructions.toArray())
				{
					if (!(in instanceof MethodInsnNode mi)) { continue; }
					String target = mi.owner + "." + mi.name + mi.desc;
					int o = occ.merge(target, 1, Integer::sum) - 1;
					String key = cn.name + " " + m.name + " " + m.desc + " " + target + " " + o;
					if (!resyncs.remove(key)) { continue; }
					m.instructions.insertBefore(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "resync", "()V", false));
					changed = true;
					System.out.println("  resync: " + key);
				}
			}

			// 5) Over-cap hold: a setter call that snaps an impulse back to its cap. Keep the impulse for one original frame.
			for (MethodNode m : cn.methods)
			{
				Map<String, Integer> occ = new HashMap<>();
				for (AbstractInsnNode in : m.instructions.toArray())
				{
					if (!(in instanceof MethodInsnNode mi) || !"(I)V".equals(mi.desc)) { continue; }
					String target = mi.owner + "." + mi.name + mi.desc;
					int o = occ.merge(target, 1, Integer::sum) - 1;
					String key = cn.name + " " + m.name + " " + m.desc + " " + target + " " + o;
					String field = holds.remove(key);
					if (field == null) { continue; }
					String[] of = field.split("\\.");
					InsnList l = new InsnList();
					// [obj, new] -> [obj, new, obj, new, old] -> capHold(obj, new, old) -> [obj, res]
					l.add(new InsnNode(Opcodes.DUP2));
					l.add(new InsnNode(Opcodes.SWAP));
					l.add(new InsnNode(Opcodes.DUP_X1));
					l.add(new FieldInsnNode(Opcodes.GETFIELD, of[0], of[1], "I"));
					l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "capHold", "(Ljava/lang/Object;II)I", false));
					l.add(new InsnNode(Opcodes.SWAP));
					l.add(new InsnNode(Opcodes.POP));
					m.instructions.insertBefore(mi, l);
					changed = true;
					System.out.println("  hold: " + key + " (" + field + ")");
				}
			}

			if (changed)
			{
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); // class files are version 45: no stack map frames
				cn.accept(cw);
				en.setValue(cw.toByteArray());
				System.out.println("patched " + en.getKey());
			}
		}

		System.out.printf("sites: sleep=%d ms=%d w=%d frameMs=%d%n", sleepSites, msSites, wSites, frameMs);
		if (!helpers.isEmpty() || !resyncs.isEmpty()) { System.err.println("Helper/resync sites not found: " + helpers.keySet() + resyncs); System.exit(1); }
		if (!holds.isEmpty()) { System.err.println("Hold sites not found: " + holds.keySet()); System.exit(1); }
		if (!gates.isEmpty()) { System.err.println("Gate sites not found: " + gates.keySet()); System.exit(1); }
		if (sleepSites != 1 || msSites != 1 || wSites != 1) { System.err.println("Patch sites not found exactly once, refusing to write."); System.exit(1); }

		try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1])))
		{
			// Manifest first, like the original
			List<String> order = new ArrayList<>(entries.keySet());
			order.sort(Comparator.comparing((String n) -> !n.equals("META-INF/MANIFEST.MF")));
			for (String n : order)
			{
				zout.putNextEntry(new ZipEntry(n));
				zout.write(entries.get(n));
				zout.closeEntry();
			}
		}
		// Tell the build which original frame time this build uses
		try (PrintWriter pw = new PrintWriter(args[1] + ".framems")) { pw.println(frameMs); }
	}

	/** Inside m, replace calls dtHelper(x) with mul(x, Pacer.velW()). swap = "<owner.name(I)I> <owner.name(II)I>" */
	static boolean applyDtSwap(MethodNode m, String swap)
	{
		String[] sw = swap.split(" ");
		boolean any = false;
		for (AbstractInsnNode in : m.instructions.toArray())
		{
			if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && (mi.owner + "." + mi.name + mi.desc).equals(sw[0]))
			{
				String mul = sw[1];
				String owner = mul.substring(0, mul.indexOf('.'));
				String name = mul.substring(mul.indexOf('.') + 1, mul.indexOf('('));
				InsnList l = new InsnList();
				l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACER, "velW", "()I", false));
				l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, "(II)I", false));
				m.instructions.insertBefore(mi, l);
				m.instructions.remove(mi);
				any = true;
			}
		}
		if (!any) { throw new IllegalStateException("dt helper call not found: " + swap + " in " + m.name + m.desc); }
		return true;
	}
}
