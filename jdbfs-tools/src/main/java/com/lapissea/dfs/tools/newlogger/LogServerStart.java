package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.util.LogUtil;

import java.io.IOException;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD);
	}
	
	public static void main(String[] args) throws IOException, LockedFlagSet{
//		ConfigDefs.LOG_LEVEL.set(Log.LogLevel.TRACE);
		
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay()){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			display.run();
		}
		System.exit(0);
		
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
		
		var server = new DBLogIngestServer(() -> new FrameDB(dbFile));
		server.start();
		
	}
	
}
