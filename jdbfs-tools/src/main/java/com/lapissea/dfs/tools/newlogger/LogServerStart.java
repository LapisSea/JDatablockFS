package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class LogServerStart{
	static{
		//Eager init to reduce startup time
		Thread.ofVirtual().start(VulkanCore::preload);
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD|LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args) throws InterruptedException{
		new LogServerStart().start();
	}
	
	private DBLogIngestServer server;
	
	private void start() throws InterruptedException{
		var lazyView = new SessionSetView(){
			
			private SessionSetView data;
			private boolean        dirty;
			
			private void init(SessionSetView data){
				this.data = data;
				dirty = true;
			}
			
			@Override
			public Optional<SessionView> getSession(String name){
				if(data == null) return Optional.empty();
				return data.getSession(name);
			}
			@Override
			public Optional<SessionView> getAnySession(){
				if(data == null) return Optional.empty();
				return data.getAnySession();
			}
			@Override
			public boolean isDirty(){
				if(data == null || dirty) return dirty;
				return data.isDirty();
			}
			@Override
			public void clearDirty(){
				dirty = false;
				if(data == null) return;
				data.clearDirty();
			}
			@Override
			public Set<String> getSessionNames(){
				if(data == null) return Set.of();
				return data.getSessionNames();
			}
		};
		var sem = new Semaphore(1);
		sem.acquire();
		var ingestThread = Thread.ofPlatform().name("Ingest").start(() -> {
			try{
				sem.tryAcquire(5, TimeUnit.SECONDS);
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
			var logger = LoggedMemoryUtils.createLoggerFromConfig();
			logger.block();
			var dbFile = LoggedMemoryUtils.newLoggedMemory("DBLogIngestServer", logger);
			
			server = new DBLogIngestServer(() -> new FrameDB(dbFile));
			lazyView.init(server.view);
			loadDummyData();
			try{
				server.start();
			}catch(Throwable e){
				e.printStackTrace();
				System.exit(1);
			}
		});
		
		var t = System.currentTimeMillis();
		try(var display = new VulkanDisplay(lazyView)){
			LogUtil.println("Initialized window in ", System.currentTimeMillis() - t, "ms");
			sem.release();
			display.run();
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			do{
				if(server != null){
					server.stop();
					ingestThread.join();
				}
			}while(!ingestThread.join(Duration.ofMillis(10)));
		}
	}
	
	private void loadDummyData(){
		Thread.ofVirtual().start(() -> {
			try{
				var mem = Cluster.emptyMem();
				mem.roots().provide(69, "This is a test!!! :D");
				UtilL.sleepWhile(() -> server == null);
				
				try(var remote = new DBLogConnection.OfRemote()){
//					try(var ses = remote.openSession("test")){
//						var dst = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
//						mem.getSource().transferTo(dst, true);
//					}
//					LogUtil.println("======= Sent frame =======");
//
//					remote.openSession("empty").close();
//					LogUtil.println("======= Sent empty frame =======");
					
					try(var ses = remote.openSession("bigMap")){
						var dst = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
						
						var                    cluster = Cluster.init(dst);
						IOMap<Integer, String> map     = cluster.roots().request(0, IOMap.class, Integer.class, String.class);
						while(dst.getIOSize()<Character.MAX_VALUE - 4000){
							var val = ("int(" + map.size() + ")").repeat(new RawRandom(dst.getIOSize() + map.size()).nextInt(20));
							map.put((int)map.size(), val);
						}
						LogUtil.println("======= Sent frame =======");
					}
				}
			}catch(Throwable e){
				new RuntimeException("Failed to send dummy data", e).printStackTrace();
				System.exit(1);
			}
		});
	}
}
