import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Analysis tool. Given an original jar, its ProbePatcher site numbering and one or more probe dumps,
 * prints int field writes that ran on most frames, read the same field (read-modify-write),
 * and do not involve the engine's timestep fields. These are frame-counted logic candidates.
 *
 * Usage: RmwFinder jar minRun timeFields(comma, owner.name) probe1 [probe2...]
 */
public class RmwFinder
{
	public static void main(String[] a) throws Exception
	{
		double minRun = Double.parseDouble(a[1]);
		Set<String> timeFields = new HashSet<>(Arrays.asList(a[2].split(",")));
		Map<Integer, double[]> stats = new HashMap<>();
		for (int p = 3; p < a.length; p++)
		{
			List<String> lines = Files.readAllLines(Path.of(a[p]));
			int frames = Integer.parseInt(lines.get(0).split(" ")[1]);
			for (String l : lines.subList(1, lines.size()))
			{
				String[] s = l.split(" ");
				int id = Integer.parseInt(s[0]);
				double frac = Integer.parseInt(s[1]) / (double) frames;
				double per = Long.parseLong(s[3]) / Double.parseDouble(s[1]);
				int mr = Integer.parseInt(s[4]);
				double[] cur = stats.computeIfAbsent(id, k -> new double[] {0, 0, 0});
				if (frac > cur[0]) { cur[0] = frac; }
				cur[1] = Math.max(cur[1], per);
				cur[2] = Math.max(cur[2], mr);
			}
		}

		List<String> gates = new ArrayList<>();
		Map<String, byte[]> en = new LinkedHashMap<>();
		try (ZipInputStream z = new ZipInputStream(new FileInputStream(a[0])))
		{
			ZipEntry e;
			while ((e = z.getNextEntry()) != null) { if (!e.isDirectory()) { en.put(e.getName(), z.readAllBytes()); } }
		}
		int id = 0;
		for (var e : en.entrySet())
		{
			if (!e.getKey().endsWith(".class")) { continue; }
			ClassNode cn = new ClassNode();
			new ClassReader(e.getValue()).accept(cn, 0);
			for (MethodNode m : cn.methods)
			{
				AbstractInsnNode[] ins = m.instructions.toArray();
				Map<String, Integer> occ = new HashMap<>();
				for (int i = 0; i < ins.length; i++)
				{
					if (!(ins[i] instanceof FieldInsnNode f) || !(f.getOpcode() == Opcodes.PUTSTATIC || f.getOpcode() == Opcodes.PUTFIELD) || !"I".equals(f.desc)) { continue; }
					String key = f.owner + "." + f.name;
					int occurrence = occ.merge(key, 1, Integer::sum) - 1;
					double[] st = stats.get(id);
					int myId = id++;
					if (st == null || st[2] < minRun || st[1] > 1.5) { continue; }

					// Walk back over the expression (stop at previous field write / local store / jump)
					boolean readsSelf = false, usesTime = false;
					int from = i;
					for (int k = i - 1, real = 0; k >= 0 && real < 14; k--)
					{
						AbstractInsnNode n = ins[k];
						int op = n.getOpcode();
						if (op < 0) { continue; }
						real++;
						if (n instanceof FieldInsnNode g)
						{
							if (g.getOpcode() == Opcodes.PUTSTATIC || g.getOpcode() == Opcodes.PUTFIELD) { break; }
							String gk = g.owner + "." + g.name;
							if (gk.equals(key)) { readsSelf = true; }
							if (timeFields.contains(gk)) { usesTime = true; }
						}
						if (n instanceof MethodInsnNode mi && mi.owner.contains("Pacer")) { usesTime = true; }
						if (n instanceof JumpInsnNode && op == Opcodes.GOTO) { break; }
						from = k;
					}
					if (!readsSelf || usesTime) { continue; }

					Textifier t = new Textifier();
					TraceMethodVisitor tv = new TraceMethodVisitor(t);
					for (int k = from; k <= i; k++) { if (ins[k].getOpcode() >= 0) { ins[k].accept(tv); } }
					StringWriter sw = new StringWriter();
					t.print(new PrintWriter(sw));
					System.out.printf("=== site %d  %s.%s%s  writes %s #%d  (frames %.2f, per %.2f, run %.0f)%n", myId, cn.name, m.name, m.desc, key, occurrence, st[0], st[1], st[2]);
					gates.add(cn.name + " " + m.name + " " + m.desc + " " + key + " " + occurrence + " " + myId);
					System.out.print(sw.toString().replaceAll("(?m)^\\s+", "    "));
				}
			}
		}
			if (System.getProperty("out") != null) { Files.write(Path.of(System.getProperty("out")), gates); }
	}
}
