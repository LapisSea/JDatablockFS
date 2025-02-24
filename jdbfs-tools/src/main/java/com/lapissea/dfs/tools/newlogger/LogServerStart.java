package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOInterfaces;

import java.io.IOException;

public final class LogServerStart{
	
	public static void main(String[] args) throws IOException{
		
		try(var dbFile = IOInterfaces.ofFile("frames.dfs")){
			var server = new DBLogIngestServer(() -> new FrameDB(dbFile));
			server.start();
		}
		
	}
	
}
