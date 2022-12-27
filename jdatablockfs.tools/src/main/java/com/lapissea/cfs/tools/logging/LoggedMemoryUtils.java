package com.lapissea.cfs.tools.logging;

import com.google.gson.GsonBuilder;
import com.lapissea.cfs.io.IOHook;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.tools.DisplayManager;
import com.lapissea.cfs.tools.server.DisplayIpc;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.stream.LongStream;

public class LoggedMemoryUtils{
	
	private static WeakReference<Map<String, Object>> CONFIG = new WeakReference<>(null);
	
	public static Map<String, Object> readConfig(){
		Map<String, Object> config = CONFIG.get();
		if(config != null) return config;
		
		Map<String, Object> newConf;
		
		try(var r = new FileReader(new File("config.json").getAbsoluteFile())){
			newConf = new HashMap<>(new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class));
		}catch(Exception e){
			newConf = new HashMap<>();
			Log.warn("Unable to load config: " + e);
		}
		
		CONFIG = new WeakReference<>(Collections.unmodifiableMap(newConf));
		return newConf;
	}
	
	public static void simpleLoggedMemorySession(UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		simpleLoggedMemorySession("default", session);
	}
	public static void simpleLoggedMemorySession(String sessionName, UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		
		try{
			var mem = LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			logger.ifInited(l -> l.getSession(sessionName).reset());
			
			try{
				session.accept(mem);
			}finally{
				logger.block();
				mem.getHook().writeEvent(mem, LongStream.of());
			}
		}finally{
			logger.get().destroy();
		}
	}
	
	public static LateInit.Safe<DataLogger> createLoggerFromConfig(){
		return new LateInit.Safe<>(() -> {
			var config       = readConfig();
			var loggerConfig = (Map<String, Object>)config.getOrDefault("logger", Map.of());
			
			var type = loggerConfig.getOrDefault("type", "none").toString();
			return switch(type){
				case "none" -> DataLogger.Blank.INSTANCE;
				case "direct" -> new DisplayManager();
				case "server" -> new DisplayIpc(loggerConfig);
				default -> throw new IllegalArgumentException("logger.type unknown value \"" + type + "\"");
			};
		}, Thread.ofVirtual()::start);
	}
	
	public static MemoryData<?> newLoggedMemory(String sessionName, LateInit<DataLogger, RuntimeException> logger) throws IOException{
		
		IOHook proxyLogger = adaptToHook(sessionName, logger);
		
		MemoryData<?> mem = MemoryData.builder().withCapacity(0).withOnWrite(proxyLogger).build();
		
		mem.getHook().writeEvent(mem, LongStream.of());
		
		return mem;
	}
	
	private static IOHook adaptToHook(String sessionName, LateInit<DataLogger, RuntimeException> logger){
		long[] frameId = {0};
		
		if(logger.isInitialized()){
			var l = logger.get();
			if(!l.isActive()){
				return (data, changeIds) -> { };
			}else{
				return (data, changeIds) -> {
					var memFrame = makeFrame(frameId, data, changeIds);
					logger.get().getSession(sessionName).log(memFrame);
				};
			}
		}
		
		var preBuf = new LinkedList<MemFrame>();
		var lock   = ClosableLock.reentrant();
		
		Thread.ofVirtual().start(() -> {
			logger.block();
			try(var ignored = lock.open()){
				var ses = logger.get().getSession(sessionName);
				while(!preBuf.isEmpty()){
					ses.log(preBuf.remove(0));
				}
			}catch(DataLogger.Closed ignored){
			}
		});
		
		return (data, ids) -> {
			if(logger.isInitialized()){
				var d = logger.get();
				if(!d.isActive()){
					return;
				}
			}
			var memFrame = makeFrame(frameId, data, ids);
			try(var ignored = lock.open()){
				if(logger.isInitialized()){
					var ses = logger.get().getSession(sessionName);
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
	
	private static MemFrame makeFrame(long[] frameId, IOInterface data, LongStream ids) throws IOException{
		long id;
		synchronized(frameId){
			id = frameId[0];
			frameId[0]++;
		}
		return new MemFrame(id, System.nanoTime(), data.readAll(), ids.toArray(), new Throwable());
	}
}
