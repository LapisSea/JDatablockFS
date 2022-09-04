package com.lapissea.cfs.tools.logging;

import com.google.gson.GsonBuilder;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.tools.DisplayManager;
import com.lapissea.cfs.tools.server.DisplayIpc;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class LoggedMemoryUtils{
	
	private static WeakReference<Map<String, Object>> CONFIG=new WeakReference<>(null);
	
	public static Map<String, Object> readConfig(){
		Map<String, Object> config=CONFIG.get();
		if(config!=null) return config;
		
		Map<String, Object> newConf;
		
		try(var r=new FileReader(new File("config.json").getAbsoluteFile())){
			newConf=new HashMap<>(new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class));
		}catch(Exception e){
			newConf=new HashMap<>();
			Log.warn("Unable to load config: "+e);
		}
		
		CONFIG=new WeakReference<>(Collections.unmodifiableMap(newConf));
		return newConf;
	}
	
	public static void simpleLoggedMemorySession(UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		simpleLoggedMemorySession("default", session);
	}
	public static void simpleLoggedMemorySession(String sessionName, UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		
		LateInit<DataLogger> logger=LoggedMemoryUtils.createLoggerFromConfig();
		
		try{
			var mem=LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			logger.ifInited(l->l.getSession(sessionName).reset());
			
			try{
				session.accept(mem);
			}finally{
				logger.block();
				mem.onWrite.log(mem, LongStream.of());
			}
		}finally{
			logger.get().destroy();
		}
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
			case "server" -> new DisplayIpc(loggerConfig);
			default -> throw new IllegalArgumentException("logger.type unknown value \""+type+"\"");
		});
	}
	
	public static MemoryData<?> newLoggedMemory(String sessionName, LateInit<DataLogger> logger) throws IOException{
		MemoryData.EventLogger proxyLogger;
		if(logger.isInited()){
			DataLogger disp=logger.get();
			if(disp instanceof DataLogger.Blank){
				proxyLogger=(d, i)->{};
			}else{
				var ses=disp.getSession(sessionName);
				if(ses==DataLogger.Session.Blank.INSTANCE) proxyLogger=(d, i)->{};
				else proxyLogger=new MemoryData.EventLogger(){
					private long frameId=0;
					@Override
					public void log(MemoryData<?> data, LongStream ids) throws IOException{
						long id;
						synchronized(this){
							id=frameId;
							frameId++;
						}
						ses.log(new MemFrame(id, data.readAll(), ids.toArray(), new Throwable()));
					}
				};
			}
		}else{
			var  preBuf=new LinkedList<MemFrame>();
			Lock lock  =new ReentrantLock();
			Thread.ofVirtual().start(()->{
				UtilL.sleepUntil(logger::isInited, 20);
				lock.lock();
				try{
					var ses=logger.get().getSession(sessionName);
					while(!preBuf.isEmpty()){
						ses.log(preBuf.remove(0));
					}
				}catch(DataLogger.Closed ignored){
				}finally{
					lock.unlock();
				}
			});
			
			long[] frameId={0};
			proxyLogger=(data, ids)->{
				if(logger.isInited()){
					var d=logger.get();
					if(!d.isActive()){
						return;
					}
				}
				long id;
				synchronized(frameId){
					id=frameId[0];
					frameId[0]++;
				}
				var memFrame=new MemFrame(id, data.readAll(), ids.toArray(), new Throwable());
				lock.lock();
				try{
					if(logger.isInited()){
						var ses=logger.get().getSession(sessionName);
						while(!preBuf.isEmpty()){
							ses.log(preBuf.remove(0));
						}
						ses.log(memFrame);
					}else{
						preBuf.add(memFrame);
					}
				}finally{
					lock.unlock();
				}
			};
		}
		
		MemoryData<?> mem=MemoryData.builder().withCapacity(0).withOnWrite(proxyLogger).build();
		
		mem.onWrite.log(mem, LongStream.of());
		
		return mem;
	}
}
