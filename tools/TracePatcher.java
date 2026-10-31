import org.objectweb.asm.*; import org.objectweb.asm.tree.*; import java.io.*; import java.util.*; import java.util.zip.*;
/** Analysis: logs (site,value) of every write to the given int fields. Site numbering: "class method desc field occ". */
public class TracePatcher { public static void main(String[] a) throws Exception {
  Set<String> fields=new HashSet<>(Arrays.asList(a[2].split(","))); Map<String,byte[]> en=new LinkedHashMap<>();
  try(ZipInputStream z=new ZipInputStream(new FileInputStream(a[0]))){ZipEntry e;while((e=z.getNextEntry())!=null) if(!e.isDirectory()) en.put(e.getName(),z.readAllBytes());}
  List<String> names=new ArrayList<>();
  for(var e:en.entrySet()){ if(!e.getKey().endsWith(".class")) continue; ClassNode cn=new ClassNode(); new ClassReader(e.getValue()).accept(cn,0);
   for(MethodNode m:cn.methods){ Map<String,Integer> occ=new HashMap<>(); for(AbstractInsnNode in:m.instructions.toArray()){
     if(in instanceof FieldInsnNode f&&(f.getOpcode()==Opcodes.PUTSTATIC||f.getOpcode()==Opcodes.PUTFIELD)&&"I".equals(f.desc)){ String k=f.owner+"."+f.name; int o=occ.merge(k,1,Integer::sum)-1;
       if(!fields.contains(k)) continue; int id=names.size(); names.add(cn.name+" "+m.name+" "+m.desc+" "+k+" "+o);
       InsnList l=new InsnList(); l.add(new InsnNode(Opcodes.DUP)); l.add(new LdcInsnNode(id)); l.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/recompile/castle/Probe","val","(II)V",false)); m.instructions.insertBefore(f,l);} } }
   ClassWriter cw=new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(cw); e.setValue(cw.toByteArray()); }
  try(ZipOutputStream zo=new ZipOutputStream(new FileOutputStream(a[1]))){ for(var e:en.entrySet()){ zo.putNextEntry(new ZipEntry(e.getKey())); zo.write(e.getValue()); zo.closeEntry(); } }
  try(PrintWriter pw=new PrintWriter(a[1]+".sites")){ for(String s:names) pw.println(s);} System.out.println("trace sites "+names.size()); } }
