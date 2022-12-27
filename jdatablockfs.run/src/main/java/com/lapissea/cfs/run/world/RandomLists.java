package com.lapissea.cfs.run.world;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.run.Configuration;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class RandomLists{
	
	public static void main(String[] args){
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER|LogUtil.Init.USE_CALL_THREAD);
		Configuration conf = new Configuration();
		conf.load(new Configuration.Loader.DashedNameValueArgs(args));
		main(conf.getView());
	}
	
	public static void main(Configuration.View conf){
		IntStream.range(conf.getInt("min", 1), conf.getInt("max", 30)).mapToObj(listCount -> {
			try{
				var logger = LoggedMemoryUtils.createLoggerFromConfig();
				var mem    = LoggedMemoryUtils.newLoggedMemory("l" + listCount, logger);
				logger.get().getSession("l" + listCount);
				
				var task = CompletableFuture.runAsync(() -> {
					Log.info(ConsoleColors.PURPLE + "Starting: {} lists" + ConsoleColors.RESET, listCount);
					try{
						var rand = new Random((long)listCount<<4);
						try{
							var cl = Cluster.init(mem);
							var p  = cl.getRootProvider();
							for(int i = 0; i<400; i++){
								var m = p.request("map" + rand.nextInt(listCount), Map.class);
								m.entities.addNew(e -> {
									e.inventory.add(new InventorySlot());
								});
							}
							cl.defragment();
						}finally{
							logger.block();
							mem.getHook().writeEvent(mem, LongStream.of());
						}
					}catch(Throwable e){
						throw new RuntimeException(e);
					}finally{
						logger.get().destroy();
					}
				}, Thread.ofVirtual()::start);
				if(conf.getBoolean("async", true)) UtilL.sleep(10);
				else task.join();
				return task;
				
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}).toList().forEach(CompletableFuture::join);
		
	}
	
}
