package com.lapissea.dfs.tools.newlogger;

import java.io.IOException;

public class LogServerStart{
	public static void main(String[] args) throws IOException{
		var server = new DBLogServer();
		server.start();
	}
}
