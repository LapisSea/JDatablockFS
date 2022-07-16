package com.lapissea.cfs.run.world;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.run.Configuration;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.Rand;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class RandomLists{
	
	public static void main(String[] args) throws IOException{
		Configuration conf=new Configuration();
		conf.load(new Configuration.Loader.DashedNameValueArgs(args));
		main(conf.getView());
	}
	
	public static void main(Configuration.View conf) throws IOException{
		IntStream.range(conf.getInt("min", 1), conf.getInt("max", 10)).mapToObj(listCount->{
			try{
				var logger=LoggedMemoryUtils.createLoggerFromConfig();
				var mem   =LoggedMemoryUtils.newLoggedMemory("l"+listCount, logger);
				logger.get().getSession("l"+listCount);
				
				var task=CompletableFuture.runAsync(()->{
					try{
						try{
							var cl=Cluster.init(mem);
							var p =cl.getRootProvider();
							for(int i=0;i<400;i++){
								var m=p.request(Map.class, "map"+(int)Rand.f(listCount));
								m.entities.addNew(e->{
									e.inventory.add(new InventorySlot());
									e.inventory.add(new InventorySlot());
								});
							}
						}finally{
							logger.block();
							mem.onWrite.log(mem, LongStream.of());
						}
					}catch(IOException e){
						e.printStackTrace();
					}finally{
						logger.get().destroy();
					}
				});
				if(conf.getBoolean("async", true)) UtilL.sleep(50);
				else task.join();
				return task;
				
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}).toList().forEach(CompletableFuture::join);
		
	}
	
}
