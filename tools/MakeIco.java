import javax.imageio.ImageIO; import java.awt.*; import java.awt.image.BufferedImage; import java.io.*; import java.util.*;
/** Builds a multi-size .ico (PNG entries) from the game's icon, with the white background made transparent. */
public class MakeIco { public static void main(String[] a) throws Exception {
  BufferedImage src = ImageIO.read(new File(a[0])); int w = src.getWidth(), h = src.getHeight();
  BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); im.getGraphics().drawImage(src, 0, 0, null);
  // flood fill near-white from the borders
  boolean[] seen = new boolean[w*h]; ArrayDeque<int[]> q = new ArrayDeque<>();
  for (int x=0;x<w;x++){q.add(new int[]{x,0});q.add(new int[]{x,h-1});} for (int y=0;y<h;y++){q.add(new int[]{0,y});q.add(new int[]{w-1,y});}
  while(!q.isEmpty()){ int[] p=q.poll(); int x=p[0],y=p[1]; if(x<0||y<0||x>=w||y>=h||seen[y*w+x]) continue; seen[y*w+x]=true; int c=im.getRGB(x,y);
    int r=(c>>16)&255,g=(c>>8)&255,b=c&255; if(r<235||g<235||b<235) continue; im.setRGB(x,y,0); q.add(new int[]{x+1,y});q.add(new int[]{x-1,y});q.add(new int[]{x,y+1});q.add(new int[]{x,y-1}); }
  int[] sizes = {16, 24, 32, 48, 64, 128, 256}; java.util.List<byte[]> pngs = new ArrayList<>();
  for (int s : sizes) { BufferedImage o = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB); Graphics2D g = o.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s >= w ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    if (s >= w) { int k = s / w; int d = w * k; g.drawImage(im, (s-d)/2, (s-d)/2, d, d, null); } else { g.drawImage(im, 0, 0, s, s, null); }
    g.dispose(); ByteArrayOutputStream bo = new ByteArrayOutputStream(); ImageIO.write(o, "png", bo); pngs.add(bo.toByteArray());
    if (s == 256) ImageIO.write(o, "png", new File(a[1].replace(".ico", "-256.png"))); }
  try (DataOutputStream out = new DataOutputStream(new FileOutputStream(a[1]))) {
    out.write(le16(0)); out.write(le16(1)); out.write(le16(sizes.length)); int off = 6 + 16 * sizes.length;
    for (int i=0;i<sizes.length;i++){ int s=sizes[i]; out.write(s>=256?0:s); out.write(s>=256?0:s); out.write(0); out.write(0); out.write(le16(1)); out.write(le16(32)); out.write(le32(pngs.get(i).length)); out.write(le32(off)); off += pngs.get(i).length; }
    for (byte[] p : pngs) out.write(p); } }
  static byte[] le16(int v){return new byte[]{(byte)v,(byte)(v>>8)};} static byte[] le32(int v){return new byte[]{(byte)v,(byte)(v>>8),(byte)(v>>16),(byte)(v>>24)};} }
