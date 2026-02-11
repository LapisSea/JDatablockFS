package com.lapissea.dfs.inspect;

import com.lapissea.dfs.inspect.display.VulkanDisplay;
import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD|LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws InterruptedException{
		
		var ingestServer = CompletableFuture.supplyAsync(() -> {
			var logger = LoggedMemoryUtils.createLoggerFromConfig();
			return new DBLogIngestServer(() -> {
				var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
				return new FrameDB(dbFile);
			});
		});
		
		var ingestProcess = ingestServer.thenAcceptAsync(server -> {
			if(!Arrays.asList(args).contains("nodata")) Thread.ofVirtual().start(() -> {
				try{
					LogServerDummyData.mapAdd();
				}catch(Throwable e){
					new RuntimeException("Failed to send dummy data", e).printStackTrace();
					System.exit(1);
				}
			});
			try{
				server.start();
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		}, Thread.ofPlatform().name("Ingest")::start);
		
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay()){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			ingestServer.thenAccept(e -> display.setSessionSetView(e.view));
			display.run();
		}catch(Throwable e){
			e.printStackTrace();
		}
		
		ingestProcess.whenComplete((server, e) -> System.exit(0));
		ingestServer.join().stop();
	}
}
