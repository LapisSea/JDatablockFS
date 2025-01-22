package demo.photo;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class PUtils{
	
	private static final Semaphore READ_SEMAPHORE = new Semaphore(5);
	private static final Executor  GC_RUNNER      = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
	
	static Photo photoFromFile(File file) throws IOException{
//		return new Photo(file.getName(), new byte[2]);
		
		BufferedImage image;
		try{
			READ_SEMAPHORE.acquire();
			image = ImageIO.read(file);
		}catch(InterruptedException e){
			throw new RuntimeException(e);
		}finally{
			READ_SEMAPHORE.release();
		}
		
		LogUtil.println("Read photo " + file.getAbsolutePath());
		
		var limit = 300;
		while(Math.max(image.getWidth(), image.getHeight())>limit){
			var ns = Math.max(0.5, limit/(double)Math.max(image.getWidth(), image.getHeight()));
			
			var newW = (int)(image.getWidth()*ns);
			var newH = (int)(image.getHeight()*ns);
			image = resize(image, newW, newH);
		}
		
		var data = toJpg(image, 0.6F);
		
		//Encourage the JVM to release memory used processing image
		GC_RUNNER.execute(System::gc);
		return new Photo(file.getAbsolutePath(), data);
	}
	
	private static BufferedImage resize(BufferedImage originalImage, int targetWidth, int targetHeight){
		BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D    graphics     = resizedImage.createGraphics();
		graphics.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
		graphics.dispose();
		return resizedImage;
	}
	public static byte[] toJpg(BufferedImage image, float quality) throws IOException{
		var bb = new ByteArrayOutputStream();
		
		ImageWriter     jpgWriter  = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam writeParam = jpgWriter.getDefaultWriteParam();
		
		writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		writeParam.setCompressionQuality(quality);
		
		try(var out = new MemoryCacheImageOutputStream(bb)){
			jpgWriter.setOutput(out);
			jpgWriter.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
		}finally{
			jpgWriter.dispose();
		}
		
		return bb.toByteArray();
	}
	
	static void threadedFolderScan(File folder, UnsafeConsumer<File, Exception> consumer){
		Stream.of(Objects.requireNonNull(folder.listFiles())).map(file -> CompletableFuture.runAsync(() -> {
			      try{
				      if(file.isDirectory()){
					      threadedFolderScan(file, consumer);
				      }else{
					      consumer.accept(file);
				      }
			      }catch(Exception e){
				      throw new RuntimeException("Failed to process: " + file, e);
			      }
		      }, Thread.ofVirtual()::start)).toList()
		      .forEach(CompletableFuture::join);
	}
	
	static void loggedRAMMemory(UnsafeConsumer<IOInterface, IOException> run) throws IOException{
		String sessionName = "photos";
		var    logger      = LoggedMemoryUtils.createLoggerFromConfig();
		var    data        = LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
		try{
			run.accept(data);
		}finally{
			logger.block();
			data.getHook().writeEvent(data, LongStream.of());
			logger.get().getSession(sessionName).finish();
		}
	}
	
	static void fileMemory(UnsafeConsumer<IOInterface, IOException> run) throws IOException{
		var file = new File("images.bin");
		LogUtil.println("Database at:", file.getAbsolutePath());
		try(var data = new FileMemoryMappedData(file)){
			run.accept(data);
		}
	}
}
