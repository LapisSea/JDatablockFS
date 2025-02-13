package demo.photo;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.FilteredImageSource;
import java.awt.image.ImageFilter;
import java.awt.image.ImageProducer;
import java.awt.image.RGBImageFilter;

public final class ImageUitls{
	
	
	public static BufferedImage combineColorAlpha(BufferedImage color, BufferedImage alpha){
		return applyTransparency(color, transformGrayToTransparency(alpha));
	}
	private static BufferedImage applyTransparency(BufferedImage image, Image mask){
		BufferedImage dest = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D    g2   = dest.createGraphics();
		g2.drawImage(image, 0, 0, null);
		AlphaComposite ac = AlphaComposite.getInstance(AlphaComposite.DST_IN, 1.0F);
		g2.setComposite(ac);
		g2.drawImage(mask, 0, 0, null);
		g2.dispose();
		return dest;
	}
	
	private static Image transformGrayToTransparency(BufferedImage image){
		ImageFilter filter = new RGBImageFilter(){
			@Override
			public int filterRGB(int x, int y, int rgb){
				return (rgb<<8)&0xFF000000;
			}
		};
		
		ImageProducer ip = new FilteredImageSource(image.getSource(), filter);
		return Toolkit.getDefaultToolkit().createImage(ip);
	}
	
}
