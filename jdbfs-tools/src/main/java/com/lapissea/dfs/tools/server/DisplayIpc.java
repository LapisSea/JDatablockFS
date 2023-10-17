package com.lapissea.dfs.tools.server;

import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.dfs.tools.logging.MemoryLogConfig;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.dfs.logging.Log.info;
import static com.lapissea.dfs.logging.Log.trace;
import static com.lapissea.dfs.logging.Log.warn;
import static com.lapissea.util.ConsoleColors.RESET;
import static com.lapissea.util.ConsoleColors.YELLOW_BRIGHT;

public class DisplayIpc implements DataLogger{
	
	private static class IpcSession implements DataLogger.Session{
		
		private final Session proxy;
		
		public IpcSession(Info conn, String name, MemoryLogConfig config) throws IOException{
			var socket = sessionConnection(conn, name, config);
			info(YELLOW_BRIGHT + "Server session({}) established" + RESET, name);
			
			record Event(boolean compressed, byte[] b, int off, int len){ }
			
			var socketIn  = socket.getInputStream();
			var socketOut = socket.getOutputStream();
			
			var io = ServerCommons.makeIO();
			
			UnsafeRunnable<IOException> ping;
			
			DataLogger.Session proxy;
			
			if(!config.threadedOutput){
				var sendActionLock = ClosableLock.reentrant();
				UnsafeBiConsumer<ServerCommons.Action, UnsafeConsumer<DataOutputStream, IOException>, IOException> sendAction = (a, data) -> {
					try(var ignored = sendActionLock.open()){
						socketOut.write(a.ordinal());
						ServerCommons.writeSafe(socketOut, data);
						socketOut.flush();
					}
				};
				
				ping = () -> sendAction.accept(ServerCommons.Action.PING, __ -> { });
				
				proxy = new DataLogger.Session(){
					@Override
					public String getName(){
						return name;
					}
					@Override
					public void log(MemFrame frame){
						try{
							sendAction.accept(ServerCommons.Action.LOG, buff -> io.writeFrame(buff, frame));
						}catch(SocketException ignored){
						}catch(IOException e){
							throw new RuntimeException(e);
						}
					}
					
					@Override
					public void reset(){
						try{
							sendAction.accept(ServerCommons.Action.RESET, buff -> { });
						}catch(SocketException ignored){
						}catch(IOException e){
							e.printStackTrace();
						}
					}
					@Override
					public void delete(){
						terminating(ServerCommons.Action.DELETE);
					}
					
					@Override
					public void finish(){
						terminating(ServerCommons.Action.FINISH);
					}
					public void terminating(ServerCommons.Action action){
						try{
							sendAction.accept(action, buff -> { });
							info(YELLOW_BRIGHT + "Closing connection to: {}" + RESET, name);
							
							try{
								socketIn.read();
							}catch(IOException e){ }
							socketOut.flush();
							socket.close();
						}catch(SocketException ignored){
						}catch(IOException e){
							e.printStackTrace();
						}
					}
				};
			}else{
				
				var asyncWriter = new Object(){
					
					private boolean running = true;
					private final List<Event> queue = new LinkedList<>();
					private final Thread t;
					private boolean fullFlag;
					
					{
						t = Thread.ofVirtual().name("socket writer").start(() -> {
//							long lastTimeMs  =0;
//							long writtenBytes=0;
							
							while(running){
								if(queue.isEmpty()){
									UtilL.sleep(1);
									continue;
								}
								Event e;
								synchronized(queue){
									e = queue.remove(0);
								}

//								writtenBytes+=e.len;
								try{
									socketOut.write(e.b, e.off, e.len);
								}catch(IOException ex){
									ex.printStackTrace();
									return;
								}

//								var timeMs=System.currentTimeMillis();
//								var passed=timeMs-lastTimeMs;
//								if(passed>100){
//									lastTimeMs=timeMs;
//									var mb       =writtenBytes/(1024*1024D);
//									var secPassed=passed/1000D;
//									Log.trace(queue.size()+"\t", (mb/secPassed)+" Mb/s");
//									writtenBytes=0;
//								}
								
							}
							info(YELLOW_BRIGHT + "Closing async connection to: {}" + RESET, name);
							try{
								socketOut.flush();
								socket.close();
							}catch(IOException ex){
								ex.printStackTrace();
							}
						});
					}
					
					
					public void write(Event e){
						if(!fullFlag && queue.size()>16){
							synchronized(queue){
								if(queue.stream().mapToLong(e1 -> e1.len).sum()<4*1024*1024){
									fullFlag = true;
								}
							}
						}
						UtilL.sleepWhile(() -> {
							if(queue.size()<16) return false;
							
							synchronized(queue){
								if(queue.stream().mapToLong(e1 -> e1.len).sum()<8*1024*1024) return false;
							}
							return true;
						});
						synchronized(queue){
							queue.add(e);
						}
					}
					
					public void close(){
						running = false;
						try{
							t.join();
						}catch(InterruptedException e){
							throw new RuntimeException(e);
						}
					}
				};
				
				var queue     = new LinkedList<CompletableFuture<List<Event>>>();
				var queueLock = ClosableLock.reentrant();
				var hasTasks  = queueLock.newCondition();
				var worker = new Thread("Socket data sender"){
					private boolean run = true;
					@Override
					public void run(){
						while(run || !queue.isEmpty()){
							CompletableFuture<List<Event>> r;
							
							try(var ignored = queueLock.open()){
								if(queue.isEmpty()){
									try{
										hasTasks.await();
									}catch(InterruptedException e){
										throw new RuntimeException(e);
									}
									continue;
								}
								
								r = queue.remove(0);
							}
							
							var d = r.join();
							for(Event event : d){
								asyncWriter.write(event);
							}
						}
						asyncWriter.close();
					}
					
					private void end(){
						run = false;
						try(var ignored = queueLock.open()){
							hasTasks.signalAll();
						}
						try{
							join();
						}catch(InterruptedException e){
							throw new RuntimeException(e);
						}
					}
				};
				worker.start();
				
				BiFunction<ServerCommons.Action, UnsafeConsumer<DataOutputStream, IOException>, List<Event>> sendAction = (a, data) -> {
					var buff = new ByteArrayOutputStream(){
						Event event(){
							return new Event(false, this.buf, 0, count);
						}
					};
					buff.write(a.ordinal());
					try{
						ServerCommons.writeSafe(buff, data);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					return List.of(buff.event());
				};
				
				var tmpProxy = new Session(){
					
					private boolean deleting;
					@Override
					public String getName(){
						return name;
					}
					
					private final int max = Runtime.getRuntime().availableProcessors();
					
					private void exec(Supplier<List<Event>> e){
						if(!worker.run) throw new IllegalStateException();
						while(queue.size()>max){
							UtilL.sleep(1);
						}
						try(var ignored = queueLock.open()){
							queue.add(CompletableFuture.supplyAsync(e));
							hasTasks.signalAll();
						}
					}
					private void stop(){
						worker.end();
					}
					
					@Override
					public void log(MemFrame frame){
						exec(() -> {
							if(deleting) return List.of();
							return sendAction.apply(ServerCommons.Action.LOG, buff -> {
								if(asyncWriter.fullFlag){
									asyncWriter.fullFlag = false;
									frame.askForCompress = true;
								}
								io.writeFrame(buff, frame);
							});
						});
					}
					
					@Override
					public void reset(){
						exec(() -> {
							if(deleting) return List.of();
							return sendAction.apply(ServerCommons.Action.RESET, buff -> { });
						});
					}
					@Override
					public void delete(){
						deleting = true;
						exec(() -> sendAction.apply(ServerCommons.Action.DELETE, buff -> { }));
						stop();
					}
					
					@Override
					public void finish(){
						exec(() -> sendAction.apply(ServerCommons.Action.FINISH, buff -> { }));
						try{
							socketIn.read();
						}catch(IOException e){ }
						stop();
					}
				};
				proxy = tmpProxy;
				ping = () -> tmpProxy.exec(() -> sendAction.apply(ServerCommons.Action.PING, buff -> { }));
			}
			
			Thread.ofVirtual().name(name + " poke machine").start(() -> {
				try{
					while(!socket.isClosed()){
						ping.run();
						socketIn.read();
						UtilL.sleep(1000);
					}
				}catch(Throwable ignored){ }
				trace("Stopped poking {}", name);
			});
			
			proxy.reset();
			this.proxy = proxy;
			
		}
		
		public record Info(InetAddress addr, int timeout){ }
		
		private static Socket sessionConnection(Info con, String sessionName, MemoryLogConfig config) throws IOException{
			
			var socketMake = new Socket();
			socketMake.connect(new InetSocketAddress(con.addr, config.negotiationPort), con.timeout);
			
			int realPort;
			try(var preSocket = socketMake){
				var in = new DataInputStream(preSocket.getInputStream());
				realPort = in.readInt();
				var out = new DataOutputStream(preSocket.getOutputStream());
				out.writeUTF(sessionName);
				out.flush();
			}
			
			return new Socket(con.addr, realPort);
		}
		
		@Override
		public void log(MemFrame frame){
			proxy.log(frame);
		}
		@Override
		public void finish(){
			proxy.finish();
		}
		@Override
		public void reset(){
			proxy.reset();
		}
		@Override
		public void delete(){
			proxy.delete();
		}
		@Override
		public String getName(){
			return proxy.getName();
		}
	}
	
	
	private Function<String, Session> sessionCreator;
	private boolean                   active = true;
	
	private final MemoryLogConfig       config;
	private final Map<String, Session>  sessions     = new HashMap<>();
	private final ReadWriteClosableLock sessionsLock = ReadWriteClosableLock.reentrant();
	
	private static final Map<InetAddress, Long> FAILS = new ConcurrentHashMap<>();
	
	public DisplayIpc(MemoryLogConfig config){
		this.config = config;
		
		initSession();
	}
	
	private void initSession(){
		sessionCreator = name -> {
			InetAddress address;
			try{
				address = InetAddress.getLocalHost();
			}catch(UnknownHostException e){
				throw new RuntimeException(e);
			}
			
			String  msg;
			boolean tryConnect = true;
			try{
				var t = FAILS.get(address);
				if(t != null){
					if(System.currentTimeMillis()>t + 5000){
						FAILS.remove(address);
					}else{
						tryConnect = false;
					}
				}
				
				if(tryConnect){
					return new IpcSession(new IpcSession.Info(address, 20), name, config);
				}else msg = null;
			}catch(SocketTimeoutException e){
				msg = "Could not contact the server for \"" + name + "\"";
			}catch(Throwable e){
				msg = "Unexpected error: " + e;
			}
			FAILS.computeIfAbsent(address, c -> {
				warn("Giving up on connecting to {} for 5s", address);
				return System.currentTimeMillis();
			});
			
			
			sessionCreator = switch(config.loggerFallbackType){
				case LOCAL -> ServerCommons.getLocalLoggerImpl(true)::getSession;
				case NONE, SERVER -> {
					active = false;
					yield s -> Session.Blank.INSTANCE;
				}
			};
			
			if(msg != null) warn(switch(config.loggerFallbackType){
				case SERVER -> "{}, Fallback is server. Already tried that...";
				case LOCAL -> "{}, switching to local server session.";
				case NONE -> "{}, switching to no output.";
			}, msg);
			
			return sessionCreator.apply(name);
		};
	}
	
	@Override
	public Session getSession(String name){
		if(sessionCreator == null) throw new Closed("This server has been closed");
		try(var ignored = sessionsLock.read()){
			var ses = sessions.get(name);
			if(ses != null) return ses;
		}
		try(var ignored = sessionsLock.write()){
			return sessions.computeIfAbsent(name, sessionCreator);
		}
	}
	
	@Override
	public void destroy(){
		sessionCreator = null;
		
		try(var ignored = sessionsLock.write()){
			for(Session session : sessions.values()){
				session.finish();
			}
			sessions.clear();
		}
	}
	@Override
	public boolean isActive(){
		return active;
	}
}
