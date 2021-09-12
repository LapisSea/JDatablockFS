package com.lapissea.cfs.tools.server;

import com.lapissea.cfs.tools.Display2D;
import com.lapissea.cfs.tools.DisplayLWJGL;
import com.lapissea.cfs.tools.logging.DataLogger;

class ServerCommons{
	
	enum Action{
		LOG,
		RESET,
		FINISH,
		PING
	}
	
	static synchronized DataLogger getLocalLoggerImpl(){
		try{
			return new DisplayLWJGL();
		}catch(Throwable e){
			e.printStackTrace();
			return new Display2D();
		}
	}
}
