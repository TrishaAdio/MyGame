import org.objectweb.asm.*; import org.objectweb.asm.tree.*; import org.objectweb.asm.util.*;
import java.io.*; import java.util.*; import java.util.zip.*;
/** Lists every write to a field (owner.name) with occurrence index per method and preceding instructions. */
public class FieldWrites { public static void main(String[] a) throws Exception {
  String target=a[1]; Map<String,byte[]> en=new LinkedHashMap<>(); try(ZipInputStream z=new ZipInputStream(new FileInputStream(a[0]))){ZipEntry e;while((e=z.getNextEntry())!=null) if(!e.isDirectory()) en.put(e.getName(),z.readAllBytes());}
  for(var e:en.entrySet()){ if(!e.getKey().endsWith(".class")) continue; ClassNode cn=new ClassNode(); new ClassReader(e.getValue()).accept(cn,0);
   for(MethodNode m:cn.methods){ AbstractInsnNode[] ins=m.instructions.toArray(); Map<String,Integer> occ=new HashMap<>();
    for(int i=0;i<ins.length;i++){ if(ins[i] instanceof FieldInsnNode f&&(f.getOpcode()==Opcodes.PUTSTATIC||f.getOpcode()==Opcodes.PUTFIELD)&&"I".equals(f.desc)){
      String key=f.owner+"."+f.name; int o=occ.merge(key,1,Integer::sum)-1; if(!key.equals(target)) continue;
      Textifier t=new Textifier(); TraceMethodVisitor tv=new TraceMethodVisitor(t); int from=i; int real=0; while(from>0&&real<9){from--; if(ins[from].getOpcode()>=0) real++;}
      for(int k=from;k<=i;k++) if(ins[k].getOpcode()>=0) ins[k].accept(tv);
      StringWriter sw=new StringWriter(); t.print(new PrintWriter(sw));
      System.out.println("=== "+cn.name+" "+m.name+" "+m.desc+" "+key+" "+o); System.out.print(sw.toString().replaceAll("(?m)^\\s+","  ")); } } } } } }
