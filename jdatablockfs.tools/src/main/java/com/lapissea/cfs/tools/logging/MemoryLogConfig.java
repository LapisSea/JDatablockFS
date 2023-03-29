package com.lapissea.cfs.tools.logging;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.logging.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
		
		negotiationPort = ((Number)data.getOrDefault("port", 6666)).intValue();
		loggerType = Utils.findFuzzyEnum(Optional.ofNullable(data.get("loggerType")).map(Object::toString), LoggerType.NONE)
		                  .warn("Logger type");
		loggerFallbackType = Utils.findFuzzyEnum(Optional.ofNullable(data.get("loggerFallback")).map(Object::toString), LoggerType.NONE)
		                          .warn("Logger fallback type");
		
		var to = Objects.toString(data.getOrDefault("threadedOutput", "false"));
		threadedOutput = switch(to.toLowerCase()){
			case "true" -> true;
			case "false" -> false;
			default -> {
				Log.warn("threadedOutput can only be true or false but is \"{}\"", to);
				yield false;
			}
		};
		
		var check = new HashSet<>(data.keySet());
		List.of("port", "loggerType", "loggerFallback", "threadedOutput").forEach(check::remove);
		if(!check.isEmpty()) Log.warn("Unknown config properties were found: {}", check);
	}
}
