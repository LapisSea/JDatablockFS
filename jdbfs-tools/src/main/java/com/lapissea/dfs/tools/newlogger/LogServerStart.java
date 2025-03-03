package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;

import java.io.IOException;

public final class LogServerStart{
	
	public static void main(String[] args) throws IOException, LockedFlagSet{
//		ConfigDefs.LOG_LEVEL.set(Log.LogLevel.TRACE);
		
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
		
		var server = new DBLogIngestServer(() -> new FrameDB(dbFile));
		server.start();
		
		
	}
	
}
