package com.lapissea.cfs.tools;

import com.google.gson.GsonBuilder;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.ZeroArrays;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class Common{
	
	public static LateInit<DataLogger> initAndLogger(){
		
		Map<String, Object> config=new HashMap<>();
		try(var r=new FileReader(new File("config.json"))){
			new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class).forEach(config::put);
		}catch(Exception ignored){ }
		
		LogUtil.Init.attach(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "true").toString())?LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER:0);
		
		Map<String, Object> loggerConfig=(Map<String, Object>)config.getOrDefault("logger", Map.of());
		
		var type=loggerConfig.getOrDefault("type", "").toString();
		
		return new LateInit<>(()->switch(type){
			case "none" -> new DataLogger.Blank();
			case "direct" -> {
				try{
					yield new DisplayLWJGL();
				}catch(Throwable e){
					LogUtil.printEr("Failed to use LWJGL display, reason:", e);
					yield new Display2D();
				}
			}
			case "lwjgl" -> new DisplayLWJGL();
			case "swing" -> new Display2D();
			case "server" -> new DisplayServer(loggerConfig);
			default -> throw new IllegalArgumentException("logger.type unknown value "+type);
		});
	}
	
	public static MemoryData newLoggedMemory(LateInit<DataLogger> display) throws IOException{
		
		var mem=new MemoryData();
		
		var preBuf=new LinkedList<MemFrame>();
		mem.onWrite=ids->{
			preBuf.add(new MemFrame(mem.readAll(), ids, new Throwable()));
			display.ifInited(d->{
				while(!preBuf.isEmpty()){
					d.log(preBuf.remove(0));
				}
			});
		};
		mem.onWrite.accept(ZeroArrays.ZERO_LONG);
		
		return mem;
	}
}
