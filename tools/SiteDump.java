import org.objectweb.asm.*; import org.objectweb.asm.tree.*; import org.objectweb.asm.util.*;
import java.io.*; import java.util.*; import java.util.zip.*;
/** Prints the instructions leading to given int field writes (same numbering as ProbePatcher). */
public class SiteDump { public static void main(String[] a) throws Exception {
  Set<Integer> want=new HashSet<>(); for(int i=1;i<a.length;i++) want.add(Integer.parseInt(a[i]));
  Map<String,byte[]> en=new LinkedHashMap<>(); try(ZipInputStream z=new ZipInputStream(new FileInputStream(a[0]))){ZipEntry e;while((e=z.getNextEntry())!=null) if(!e.isDirectory()) en.put(e.getName(),z.readAllBytes());}
  int id=0;
  for(var e:en.entrySet()){ if(!e.getKey().endsWith(".class")) continue; ClassNode cn=new ClassNode(); new ClassReader(e.getValue()).accept(cn,0);
   for(MethodNode m:cn.methods){ AbstractInsnNode[] ins=m.instructions.toArray();
    for(int i=0;i<ins.length;i++){ if(ins[i] instanceof FieldInsnNode f&&(f.getOpcode()==Opcodes.PUTSTATIC||f.getOpcode()==Opcodes.PUTFIELD)&&"I".equals(f.desc)){
      if(want.contains(id)){ Textifier t=new Textifier(); TraceMethodVisitor tv=new TraceMethodVisitor(t); StringBuilder sb=new StringBuilder();
        int from=i; int real=0; while(from>0&&real<10){from--; if(ins[from].getOpcode()>=0) real++;}
        for(int k=from;k<=i;k++){ if(ins[k].getOpcode()<0) continue; ins[k].accept(tv);} 
        StringWriter sw=new StringWriter(); PrintWriter pw=new PrintWriter(sw); t.print(pw);
        System.out.println("=== "+id+" "+cn.name+"."+m.name+" -> "+f.owner+"."+f.name); System.out.print(sw.toString().replaceAll("(?m)^\\s+","  ")); }
      id++; } } } } } }
