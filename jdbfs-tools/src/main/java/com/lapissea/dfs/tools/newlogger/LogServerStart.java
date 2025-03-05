package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;

import java.io.IOException;

public final class LogServerStart{
	
	public static void main(String[] args) throws IOException, LockedFlagSet{
//		ConfigDefs.LOG_LEVEL.set(Log.LogLevel.TRACE);
		
		try(var display = new VulkanDisplay()){
			display.init();
			display.run();
		}
		System.exit(0);
		
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
		
		var server = new DBLogIngestServer(() -> new FrameDB(dbFile));
		server.start();
		
	}
	
}
