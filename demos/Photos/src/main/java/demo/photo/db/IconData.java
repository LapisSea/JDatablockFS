package demo.photo.db;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import demo.photo.ImageUitls;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static java.awt.image.BufferedImage.TYPE_BYTE_GRAY;
import static java.awt.image.BufferedImage.TYPE_INT_RGB;

public sealed interface IconData{
	
	private static byte[] toJpg(BufferedImage image, float quality){
		var bb = new ByteArrayOutputStream();
		
		ImageWriter     jpgWriter  = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam writeParam = jpgWriter.getDefaultWriteParam();
		
		writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		writeParam.setCompressionQuality(quality);
		
		try(var out = new MemoryCacheImageOutputStream(bb)){
			jpgWriter.setOutput(out);
			jpgWriter.write(null, new IIOImage(image, null, null), writeParam);
		}catch(IOException e){
			throw new UncheckedIOException("Failed to write JPG to byte buffer", e);
		}finally{
			jpgWriter.dispose();
		}
		
		return bb.toByteArray();
	}
	
	@IOValue
	final class Solid extends IOInstance.Managed<Solid> implements IconData{
		private byte[] data;
		
		private Solid(){ }
		
		private Solid(BufferedImage image){
			data = IconData.toJpg(image, 0.6F);
		}
		
		@Override
		public BufferedImage parse(){
			try{
				return ImageIO.read(new ByteArrayInputStream(data));
			}catch(IOException e){
				throw new RuntimeException("Invalid icon", e);
			}
		}
	}
	
	@IOValue
	final class Transparent extends IOInstance.Managed<Transparent> implements IconData{
		private byte[] color;
		private byte[] alpha;
		
		private Transparent(){ }
		
		private Transparent(BufferedImage color, BufferedImage alpha){
			this.color = IconData.toJpg(color, 0.6F);
			this.alpha = IconData.toJpg(alpha, 0.9F);
		}
		
		@Override
		public BufferedImage parse(){
			try{
				var color = ImageIO.read(new ByteArrayInputStream(this.color));
				var alpha = ImageIO.read(new ByteArrayInputStream(this.alpha));
				return ImageUitls.combineColorAlpha(color, alpha);
			}catch(IOException e){
				throw new RuntimeException("Invalid icon", e);
			}
		}
	}
	
	static IconData from(BufferedImage img){
		if(img.getColorModel().hasAlpha()){
			var hasAlpha  = false;
			var threshold = 0xff;
			
			var color = new BufferedImage(img.getWidth(), img.getHeight(), TYPE_INT_RGB);
			var alpha = new BufferedImage(img.getWidth(), img.getHeight(), TYPE_BYTE_GRAY);
			
			for(int x = 0; x<img.getWidth(); x++){
				for(int y = 0; y<img.getHeight(); y++){
					int pixel = img.getRGB(x, y);
					
					var a = ((pixel>>24)&0xff);
					
					color.setRGB(x, y, pixel == 255? 0xFFFFFF : pixel);
					alpha.setRGB(x, y, a|(a<<8)|(a<<16));
					
					if(a<threshold){
						hasAlpha = true;
					}
				}
			}
			
			if(hasAlpha){
				return new Transparent(color, alpha);
			}
			return new Solid(color);
		}
		
		if(Iters.ofInts(img.getSampleModel().getSampleSize()).anyMatch(i -> i>8)){
			var resample = new BufferedImage(img.getWidth(), img.getHeight(), img.getSampleModel().getNumBands() == 1? TYPE_BYTE_GRAY : TYPE_INT_RGB);
			
			var g = resample.getGraphics();
			g.drawImage(img, 0, 0, null);
			g.dispose();
			
			img = resample;
			
		}
		
		return new Solid(img);
	}
	
	BufferedImage parse();
}
