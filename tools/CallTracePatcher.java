import org.objectweb.asm.*; import org.objectweb.asm.tree.*; import java.io.*; import java.util.*; import java.util.zip.*;
/** Analysis: logs (site,arg) for every call to owner.name(I)V. Site: "class method desc call owner.name occ". */
public class CallTracePatcher { public static void main(String[] a) throws Exception {
  String target=a[2]; Map<String,byte[]> en=new LinkedHashMap<>();
  try(ZipInputStream z=new ZipInputStream(new FileInputStream(a[0]))){ZipEntry e;while((e=z.getNextEntry())!=null) if(!e.isDirectory()) en.put(e.getName(),z.readAllBytes());}
  List<String> names=new ArrayList<>();
  for(var e:en.entrySet()){ if(!e.getKey().endsWith(".class")) continue; ClassNode cn=new ClassNode(); new ClassReader(e.getValue()).accept(cn,0);
   for(MethodNode m:cn.methods){ int o=0; for(AbstractInsnNode in:m.instructions.toArray()){
     if(in instanceof MethodInsnNode mi&&(mi.owner+"."+mi.name+mi.desc).equals(target)){ int id=names.size(); names.add(cn.name+" "+m.name+" "+m.desc+" call#"+(o++));
       InsnList l=new InsnList(); l.add(new InsnNode(Opcodes.DUP)); l.add(new LdcInsnNode(id)); l.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/recompile/castle/Probe","val","(II)V",false)); m.instructions.insertBefore(mi,l);} } }
   ClassWriter cw=new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(cw); e.setValue(cw.toByteArray()); }
  try(ZipOutputStream zo=new ZipOutputStream(new FileOutputStream(a[1]))){ for(var e:en.entrySet()){ zo.putNextEntry(new ZipEntry(e.getKey())); zo.write(e.getValue()); zo.closeEntry(); } }
  try(PrintWriter pw=new PrintWriter(a[1]+".sites")){ for(String s:names) pw.println(s);} System.out.println("call sites "+names.size()); } }
