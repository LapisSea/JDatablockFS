import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.IOInterface;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.Codec;
import org.jcodec.common.Format;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Rational;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.Supplier;

class FSFTest{
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws Exception{
		
		for(File file : new File(".").listFiles()){
			file.delete();
		}
		
		var encoderArr=new SequenceEncoder[1];
		
		Supplier<SequenceEncoder> encoder=()->{
			if(encoderArr[0]==null){
				try{
					encoderArr[0]=new SequenceEncoder(NIOUtils.writableChannel(new File("output.mp4")), Rational.R(60, 1), Format.MOV, Codec.H264, null);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			return encoderArr[0];
		};
		
		int[] counter={0};
		var   pool   =(ThreadPoolExecutor)Executors.newFixedThreadPool(1);
		
		Function<BufferedImage, BufferedImage> noAlpha=img->{
			BufferedImage copy=new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D    g2d =copy.createGraphics();
			g2d.setColor(Color.DARK_GRAY);
			g2d.fillRect(0, 0, copy.getWidth(), copy.getHeight());
			g2d.drawImage(img, 0, 0, null);
			g2d.dispose();
			return copy;
		};
		UnsafeConsumer<BufferedImage, IOException> doMp4=img->encoder.get().encodeNativeFrame(AWTUtil.fromBufferedImageRGB(noAlpha.apply(img)));
		UnsafeConsumer<BufferedImage, IOException> doPng=img->ImageIO.write(img, "png", new File("snap"+(counter[0]++)+".png"));
		UnsafeConsumer<BufferedImage, IOException> doJpg=img->ImageIO.write(noAlpha.apply(img), "jpg", new File("snap"+(counter[0]++)+".jpg"));
		
		UnsafeConsumer<BufferedImage, IOException> writeImg=img->{
			pool.submit(()->{
				try{
					doPng.accept(img);
				}catch(Exception ex){
					ex.printStackTrace();
				}
			});
		};
		
		try{
			var source=new IOInterface.FileRA(new File("testFileSystem.fsf"));
			var fil   =new FileSystemInFile(source);
			
			
			UnsafeConsumer<long[], IOException> snapshotIds=ids->{
				var img=fil.renderFile(20, 20, ids, 10);
				writeImg.accept(img);
			};
			UnsafeRunnable<IOException> snapshot=()->{};

//			boolean moreLog=false;
//
//			if(moreLog) source.onWrite=snapshotIds;
//			else snapshot=()->snapshotIds.accept(new long[0]);
			
			
			snapshot.run();
			
			var testFile=fil.createFile("test", 8);
			try(OutputStream os=testFile.write()){
				os.write("THIS WORKS!!!!".getBytes());
			}
			LogUtil.println(fil.getFile("test").readAllString());
			
			snapshot.run();
			
			try(OutputStream os=fil.getFile("aaaaaaa").write()){
				os.write("THIS REALLY WORKS!!!!".getBytes());
			}
			snapshot.run();
			LogUtil.println(fil.getFile("aaaaaaa").readAllString());
			
			testFile.writeAll("THIS W".getBytes());
			snapshot.run();
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("THIS WORKS!!!!".getBytes());
			testFile.writeAll("THIS W".getBytes());
			testFile.writeAll("this works????".getBytes());
			snapshot.run();
			fil.createFile("test3", 8).writeAll("12345678B".getBytes());
			snapshot.run();
			fil.createFile("test4", 8).writeAll("123456789".getBytes());
			snapshot.run();
			fil.createFile("5", 8).writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test4").writeAll("12345678".getBytes());
			snapshot.run();
			fil.getFile("test3").writeAll("Ur mum very gay. Like very GAY. Ur mum so gay she make the gay not gay.".getBytes());
			LogUtil.println(fil.getFile("test3").readAllString());
			snapshot.run();
			
			
			fil.defragment();
			LogUtil.println(fil.getFile("test3").readAllString());
			if(true) return;
			snapshot.run();
			
			LogUtil.println(testFile.readAllString());
			
			fil.delete("");
			
			LogUtil.println(fil.getFile("test2").readAllString());
			
			fil.getFile("test").writeAll("This is a very very very fucking long text that will force the file to grow.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is a bit smaller text that will force the file to grow shrink.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.getFile("test").writeAll("This is small.".getBytes());
			LogUtil.println(fil.getFile("test").readAllString());
			
			fil.delete("");
			System.exit(0);
			
			fil.delete("test");
			
			fil.getFile("test");
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			UtilL.sleepWhile(()->pool.getTaskCount()>pool.getCompletedTaskCount());
			if(encoderArr[0]!=null) encoderArr[0].finish();
			System.exit(0);
		}
	}
	
}
