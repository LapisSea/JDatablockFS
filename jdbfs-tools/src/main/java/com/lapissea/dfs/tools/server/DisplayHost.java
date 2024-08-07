package com.lapissea.dfs.tools.server;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import static com.lapissea.dfs.logging.Log.info;
import static com.lapissea.dfs.logging.Log.nonFatal;
import static com.lapissea.dfs.tools.server.ServerCommons.Action;
import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;
import static com.lapissea.util.UtilL.async;

public class DisplayHost{
	static{
		Thread.startVirtualThread(() -> {
			UtilL.sleep(1000*5);
			while(true){
				System.gc();
				UtilL.sleep(1000*60*2);
			}
		});
	}
	
	public static void main(String[] args) throws IOException{
		if(Log.DEBUG) LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		new DisplayHost().start(Arrays.asList(args).contains("lazy"));
	}
	
	private final class Sess{
		
		private final String name;
		private final Socket client;
		
		
		private final Map<Long, UnsafeRunnable<IOException>> readyTasks = Collections.synchronizedMap(new HashMap<>());
		
		private long doneCounter;
		private long taskCounter;
		
		private boolean running = true;
		
		private Sess(String name, Socket client){
			this.name = name;
			this.client = client;
		}
		
		private boolean hasNext(){
			return readyTasks.containsKey(doneCounter);
		}
		private DataLogger.Session getSession(){
			return getDisplay().join().getSession(name);
		}
		private void doTasks() throws IOException{
			while(hasNext()){
				var task = readyTasks.get(doneCounter);
				if(task == null) continue;
				readyTasks.remove(doneCounter++);
				task.run();
			}
		}
		
		private void run() throws IOException{
			info("connected {} {}", name, client);
			
			var objInput = new DataInputStream(new BufferedInputStream(client.getInputStream()));
			var io       = ServerCommons.makeIO();
			
			var out = client.getOutputStream();
			
			var runner = async(() -> {
				try{
					while(running){
						if(readyTasks.isEmpty()){
							UtilL.sleep(1);
							continue;
						}
						UtilL.sleep(0.1);
						doTasks();
					}
					
					while(doneCounter != taskCounter){
						info("finishing up {} / {}", doneCounter, taskCounter);
						while(!hasNext()) UtilL.sleep(1);
						doTasks();
					}
					
					client.close();
					
				}catch(Throwable e){
					e.printStackTrace();
				}
			}, Thread::startVirtualThread);
			
			var workerPool = Executors.newFixedThreadPool(ForkJoinPool.getCommonPoolParallelism());
			
			MemFrame[] lastFrame = {null};
			while(running){
				try{
					
					Action action;
					byte[] data;
					try{
						action = Action.values()[objInput.readByte()];
						data = ServerCommons.readSafe(objInput);
					}catch(EOFException e1){
						doTasks();
						continue;
					}
					
					while(doneCounter + 1<<12<taskCounter){
						UtilL.sleep(1);
					}
					
					var id = taskCounter++;
					
					workerPool.submit(() -> {
						UnsafeRunnable<IOException> readyTask = switch(action){
							case LOG -> {
								MemFrame frame;
								try{
									frame = io.readFrame(new DataInputStream(new ByteArrayInputStream(data)));
								}catch(IOException e){
									throw new RuntimeException(e);
								}
								var s = getSession();
								yield () -> {
									var l    = lastFrame[0];
									var diff = MemFrame.diff(l, frame);
									if(diff != null) s.log(diff);
									else{
										lastFrame[0] = frame;
										s.log(frame);
									}
								};
							}
							case RESET -> () -> {
								getSession().reset();
								System.gc();
							};
							case FINISH -> () -> {
								try{
									out.write(2);
									out.flush();
								}catch(SocketException ignored){ }
								getSession().finish();
								running = false;
							};
							case PING -> () -> {
								out.write(2);
								out.flush();
							};
							case DELETE -> () -> {
//								Log.debug("DELETE ORDER {}", name);
								getSession().delete();
								running = false;
							};
						};
						readyTasks.put(id, readyTask);
					});
					
				}catch(SocketException e){
					if("Connection reset".equals(e.getMessage())){
						break;
					}
					if("Socket closed".equals(e.getMessage())){
						break;
					}
					if(e.getMessage().contains("connection was aborted")){
						break;
					}
					e.printStackTrace();
					break;
				}catch(Exception e){
					e.printStackTrace();
					break;
				}
			}
			
			workerPool.close();
			runner.join();
			
			info("disconnected {}", client);
		}
	}
	
	private volatile CompletableFuture<DataLogger> display;
	
	private record NegotiatedSession(ServerSocket sessionServer, String sessionName) implements AutoCloseable{
		static NegotiatedSession negotiate(ServerSocket source) throws IOException{
			try(Socket client = source.accept()){
				var sessionServer = new ServerSocket(0);
				var out           = new DataOutputStream(client.getOutputStream());
				out.writeInt(sessionServer.getLocalPort());
				out.flush();
				var in          = new DataInputStream(client.getInputStream());
				var sessionName = in.readUTF();
				return new NegotiatedSession(sessionServer, sessionName);
			}
		}
		
		Socket open() throws IOException{
			return sessionServer.accept();
		}
		
		@Override
		public void close(){
			UtilL.closeSilently(sessionServer);
		}
	}
	
	public void start(boolean lazyStart) throws IOException{
		if(!lazyStart) getDisplay();
		
		var config = LoggedMemoryUtils.readConfig();
		
		ServerSocket server = new ServerSocket(config.negotiationPort);
		info("Started on port {}", config.negotiationPort);
		
		var initialData = System.getProperty("initialData");
		if(initialData != null){
			Thread.startVirtualThread(() -> {
				var file    = new File(initialData);
				var modTime = Long.MIN_VALUE;
				int count   = 0;
				
				byte[] start;
				try{
					start = Cluster.emptyMem().getSource().readAll();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				while(true){
					try{
						var tim = file.lastModified();
						if(modTime<tim){
							modTime = tim;
						}else{
							UtilL.sleep(10);
							continue;
						}
						
						info("Loading initialData... {}", count);
						var data = Files.readAllBytes(file.toPath());
						var ses  = getDisplay().join().getSession("initialData");
						if(Arrays.equals(data, start)){
							count = 0;
							ses.reset();
							info("Reset initialData");
						}
						ses.log(new MemFrame(count++, System.nanoTime(), data, new long[0], ""));
					}catch(Throwable e){
						nonFatal(e, "Failed to load initialData");
					}
				}
			});
		}
		
		int sesCounter = 1;
		while(true){
			NegotiatedSession ses;
			try{
				ses = NegotiatedSession.negotiate(server);
			}catch(Throwable e){
				e.printStackTrace();
				continue;
			}
			Thread.ofVirtual().name("Session: " + sesCounter + "/" + ses.sessionName).start(() -> {
				try(ses; var client = ses.open()){
					new Sess(ses.sessionName, client).run();
				}catch(Exception e){
					e.printStackTrace();
				}
				System.gc();
			});
		}
	}
	
	public CompletableFuture<DataLogger> getDisplay(){
		if(display == null){
			synchronized(this){
				if(display == null){
					display = UtilL.async(() -> ServerCommons.getLocalLoggerImpl(false));
				}
			}
		}
		return display;
	}
}
