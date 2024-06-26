package com.lapissea.dfs.tools.logging;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.DisplayManager;
import com.lapissea.dfs.tools.server.DisplayIpc;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.stream.LongStream;

public final class LoggedMemoryUtils{
	
	private static MemoryLogConfig CONFIG;
	
	public static synchronized MemoryLogConfig readConfig(){
		var c = CONFIG;
		if(c != null) return c;
		
		Map<String, Object> data;
		var                 f = new File("config.json").getAbsoluteFile();
		try(var r = new FileReader(f)){
			data = new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class);
			Log.info("Loaded config from {}", f);
		}catch(FileNotFoundException e){
			data = Map.of();
			Log.trace("Config does not exist! {}", e);
		}catch(Exception e){
			data = Map.of();
			Log.warn("Unable to load config: {}", e);
		}
		
		return CONFIG = new MemoryLogConfig(data);
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
			var config = readConfig();
			return switch(config.loggerType){
				case NONE -> DataLogger.Blank.INSTANCE;
				case LOCAL -> new DisplayManager(true);
				case SERVER -> new DisplayIpc(config);
			};
		}, Thread::startVirtualThread);
	}
	
	public static MemoryData newLoggedMemory(String sessionName, LateInit<DataLogger, RuntimeException> logger){
		var    hasFallback = readConfig().loggerFallbackType != MemoryLogConfig.LoggerType.NONE;
		IOHook proxyLogger = adaptToHook(sessionName, hasFallback, logger);
		
		return MemoryData.builder().withCapacity(0).withOnWrite(proxyLogger).build();
	}
	
	private static IOHook adaptToHook(String sessionName, boolean asyncLoad, LateInit<DataLogger, RuntimeException> logger){
		long[] frameId = {0};
		
		if(logger.isInitialized()){
			var l = logger.get();
			if(!l.isActive()){
				return (data, changeIds) -> { };
			}else{
				return (data, changeIds) -> {
					if(logger.isInitialized() && !logger.get().isActive()) return;
					var memFrame = makeFrame(frameId, data, changeIds);
					logger.get().getSession(sessionName).log(memFrame);
				};
			}
		}
		
		var preBuf = new LinkedList<MemFrame>();
		var lock   = ClosableLock.reentrant();
		
		Thread.startVirtualThread(() -> {
			logger.block();
			try(var ignored = lock.open()){
				var ses = logger.get().getSession(sessionName);
				while(!preBuf.isEmpty()){
					ses.log(preBuf.removeFirst());
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
				if(asyncLoad || logger.isInitialized()){
					var ses = logger.get().getSession(sessionName);
					while(!preBuf.isEmpty()){
						ses.log(preBuf.removeFirst());
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
