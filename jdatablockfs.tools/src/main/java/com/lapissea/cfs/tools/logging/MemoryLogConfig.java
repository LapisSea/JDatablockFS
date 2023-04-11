package com.lapissea.cfs.tools.logging;

import com.lapissea.cfs.config.ConfigUtils;
import com.lapissea.cfs.logging.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class MemoryLogConfig{
	
	public enum LoggerType{
		NONE,
		LOCAL,
		SERVER
	}
	
	public final int        negotiationPort;
	public final LoggerType loggerType;
	public final LoggerType loggerFallbackType;
	public final boolean    threadedOutput;
	
	public MemoryLogConfig(Map<String, Object> data){
		negotiationPort = ConfigUtils.configInt("port", data, 6969);
		loggerType = ConfigUtils.configEnum("loggerType", data, LoggerType.NONE);
		loggerFallbackType = ConfigUtils.configEnum("loggerFallback", data, LoggerType.NONE);
		threadedOutput = ConfigUtils.configBoolean("threadedOutput", data, false);
		
		var check = new HashSet<>(data.keySet());
		List.of("port", "loggerType", "loggerFallback", "threadedOutput").forEach(check::remove);
		if(!check.isEmpty()) Log.warn("Unknown config properties were found: {}", check);
	}
}
