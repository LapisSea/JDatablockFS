package com.lapissea.cfs.tools.logging;

import com.google.gson.GsonBuilder;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.DisplayManager;
import com.lapissea.cfs.tools.server.DisplayServer;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class LoggedMemoryUtils{
	
	private static WeakReference<Map<String, Object>> CONFIG=new WeakReference<>(null);
	
	public static Map<String, Object> readConfig(){
		Map<String, Object> config=CONFIG.get();
		if(config!=null) return config;
		
		Map<String, Object> newConf;
		
		try(var r=new FileReader("config.json")){
			newConf=new HashMap<>(new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class));
		}catch(Exception ignored){
			newConf=new HashMap<>();
		}
		
		CONFIG=new WeakReference<>(Collections.unmodifiableMap(newConf));
		return newConf;
	}
	
	public static LateInit<DataLogger> createLoggerFromConfig(){
		var config=readConfig();
		
		if(LogUtil.Init.OUT==System.out){
			var fancy=Boolean.parseBoolean(config.getOrDefault("fancyPrint", "true").toString());
			LogUtil.Init.attach(fancy?USE_CALL_POS|USE_TABULATED_HEADER:0);
		}
		
		var loggerConfig=(Map<String, Object>)config.getOrDefault("logger", Map.of());
		
		var type=loggerConfig.getOrDefault("type", "none").toString();
		
		return new LateInit<>(()->switch(type){
			case "none" -> DataLogger.Blank.INSTANCE;
			case "direct" -> new DisplayManager();
			case "server" -> new DisplayServer(loggerConfig);
			default -> throw new IllegalArgumentException("logger.type unknown value \""+type+"\"");
		});
	}
	
	public static MemoryData<?> newLoggedMemory(String sessionName, LateInit<DataLogger> display) throws IOException{
		MemoryData.EventLogger logger;
		if(display.isInited()){
			DataLogger disp=display.get();
			if(disp instanceof DataLogger.Blank){
				logger=(d, i)->{};
			}else{
				var ses=disp.getSession(sessionName);
				if(ses==DataLogger.Session.Blank.INSTANCE) logger=(d, i)->{};
				else logger=(data, ids)->ses.log(new MemFrame(data.readAll(), ids.toArray(), new Throwable()));
			}
		}else{
			var preBuf=new LinkedList<MemFrame>();
			new Thread(()->{
				UtilL.sleepUntil(display::isInited, 20);
				synchronized(preBuf){
					var ses=display.get().getSession(sessionName);
					while(!preBuf.isEmpty()){
						ses.log(preBuf.remove(0));
					}
				}
			}).start();
			
			logger=(data, ids)->{
				if(display.isInited()){
					if(display.get() instanceof DataLogger.Blank){
						return;
					}
				}
				var memFrame=new MemFrame(data.readAll(), ids.toArray(), new Throwable());
				synchronized(preBuf){
					if(display.isInited()){
						var ses=display.get().getSession(sessionName);
						while(!preBuf.isEmpty()){
							ses.log(preBuf.remove(0));
						}
						ses.log(memFrame);
					}else{
						preBuf.add(memFrame);
					}
				}
			};
		}
		
		MemoryData<?> mem=MemoryData.build().withOnWrite(logger).build();
		
		mem.onWrite.log(mem, LongStream.of());
		
		return mem;
	}
}
