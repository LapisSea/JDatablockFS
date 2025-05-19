package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.util.LogUtil;

import java.io.IOException;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD);
	}
	
	public static void main(String[] args) throws IOException, LockedFlagSet, VulkanCodeException, InterruptedException{
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
		
		var server = new DBLogIngestServer(() -> new FrameDB(dbFile));
		
		var ingestThread = Thread.ofPlatform().name("Ingest").start(() -> {
			try{
				server.start();
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		});
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay()){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			display.run();
		}catch(VulkanCodeException e){
			e.printStackTrace();
		}finally{
			server.stop();
		}
		ingestThread.join();
	}
	
}
