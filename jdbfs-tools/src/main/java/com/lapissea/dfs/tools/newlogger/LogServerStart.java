package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.time.Duration;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD);
	}
	
	public static void main(String[] args) throws InterruptedException{
		new LogServerStart().start();
	}
	
	private DBLogIngestServer server;
	
	private void start() throws InterruptedException{
		var ingestThread = Thread.ofPlatform().name("Ingest").unstarted(() -> {
			UtilL.sleep(1000);
			var logger = LoggedMemoryUtils.createLoggerFromConfig();
			logger.block();
			var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
			
			server = new DBLogIngestServer(() -> new FrameDB(dbFile));
			try{
				server.start();
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		});
		boolean ingestStarted = false;
		
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay()){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			ingestThread.start();
			ingestStarted = true;
			display.run();
		}catch(VulkanCodeException e){
			e.printStackTrace();
		}finally{
			if(ingestStarted) do{
				if(server != null){
					server.stop();
					ingestThread.join();
				}
			}while(!ingestThread.join(Duration.ofMillis(10)));
		}
	}
}
