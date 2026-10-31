import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Analysis tool: instruments every int field write in game classes with a hit counter. */
public class ProbePatcher
{
	public static void main(String[] args) throws Exception
	{
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0])))
		{
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) { if (!e.isDirectory()) { entries.put(e.getName(), zin.readAllBytes()); } }
		}
		List<String> sites = new ArrayList<>();
		for (Map.Entry<String, byte[]> en : entries.entrySet())
		{
			if (!en.getKey().endsWith(".class")) { continue; }
			ClassNode cn = new ClassNode();
			new ClassReader(en.getValue()).accept(cn, 0);
			for (MethodNode m : cn.methods)
			{
				AbstractInsnNode[] ins = m.instructions.toArray();
				for (int i = 0; i < ins.length; i++)
				{
					if (ins[i] instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.PUTSTATIC || f.getOpcode() == Opcodes.PUTFIELD) && "I".equals(f.desc))
					{
						int id = sites.size();
						sites.add(cn.name + "." + m.name + m.desc + " -> " + f.owner + "." + f.name + " @" + i);
						InsnList l = new InsnList();
						l.add(new LdcInsnNode(id));
						l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/recompile/castle/Probe", "hit", "(I)V", false));
						m.instructions.insertBefore(f, l);
					}
				}
			}
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);
			en.setValue(cw.toByteArray());
		}
		try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1])))
		{
			for (Map.Entry<String, byte[]> en : entries.entrySet()) { zout.putNextEntry(new ZipEntry(en.getKey())); zout.write(en.getValue()); zout.closeEntry(); }
		}
		try (PrintWriter pw = new PrintWriter(args[1] + ".sites")) { for (String s : sites) { pw.println(s); } }
		System.out.println("sites: " + sites.size());
	}
}
