package demo.photo;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class PUtils{
	
	static Photo photoFromFile(File file) throws IOException{
//		return new Photo(file.getName(), new byte[2]);
		return new Photo(file.getAbsolutePath(), Files.readAllBytes(file.toPath()));
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
