import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.awt.*;
public class Sheet { public static void main(String[] a) throws Exception {
 int n=a.length-1; BufferedImage[] im=new BufferedImage[n]; int w=0,h=0;
 for(int i=0;i<n;i++){im[i]=ImageIO.read(new File(a[i+1])); w=Math.max(w,im[i].getWidth()); h=Math.max(h,im[i].getHeight());}
 int cols=Math.min(4,n), rows=(n+cols-1)/cols; BufferedImage s=new BufferedImage(cols*(w+4),rows*(h+4),BufferedImage.TYPE_INT_RGB);
 Graphics2D g=s.createGraphics(); g.setColor(Color.MAGENTA); g.fillRect(0,0,s.getWidth(),s.getHeight());
 for(int i=0;i<n;i++) g.drawImage(im[i],(i%cols)*(w+4),(i/cols)*(h+4),null);
 ImageIO.write(s,"png",new File(a[0])); } }
