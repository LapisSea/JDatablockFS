package com.lapissea.dfs.inspect;

import com.lapissea.dfs.inspect.display.VulkanDisplay;
import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD|LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws InterruptedException{
		new LogServerStart().start(Arrays.asList(args).contains("nodata"));
	}
	
	private DBLogIngestServer server;
	
	private void start(boolean nodata) throws InterruptedException{
		var lazyView = new SessionSetView.Lazy();
		var sem      = new Semaphore(1);
		sem.acquire();
		var ingestThread = Thread.ofPlatform().name("Ingest").start(() -> {
			try{
				sem.tryAcquire(1, TimeUnit.SECONDS);
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
			var logger = LoggedMemoryUtils.createLoggerFromConfig();
			logger.block();
			var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
			
			server = new DBLogIngestServer(() -> new FrameDB(dbFile));
			lazyView.init(server.view);
			if(!nodata) loadDummyData();
			try{
				server.start();
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		});
		
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay(lazyView)){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			sem.release();
			display.run();
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			do{
				if(server != null){
					server.stop();
					ingestThread.join();
				}
			}while(!ingestThread.join(Duration.ofMillis(10)));
		}
		System.exit(0);
	}
	
	private void loadDummyData(){
		Thread.ofVirtual().start(() -> {
			try{
				LogServerDummyData.mapAdd();
			}catch(Throwable e){
				new RuntimeException("Failed to send dummy data", e).printStackTrace();
				System.exit(1);
			}
		});
	}
	
}
