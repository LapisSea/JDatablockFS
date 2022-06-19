package com.lapissea.cfs.tools.server;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.lapissea.cfs.tools.server.ServerCommons.Action;
import static com.lapissea.cfs.tools.server.ServerCommons.getLocalLoggerImpl;

public class DisplayServer implements DataLogger{
	
	private static class ServerSession implements DataLogger.Session{
		
		private final Session proxy;
		
		public ServerSession(Info conn, String name, Map<String, Object> config) throws IOException{
			var socket=sessionConnection(conn, name, config);
			LogUtil.println("Server session("+name+") established");
			
			boolean threadedOutput=Boolean.parseBoolean(config.getOrDefault("threadedOutput", "false").toString());
			
			var writer=new DataOutputStream(socket.getOutputStream());
			
			var io=ServerCommons.makeIO();
			
			UnsafeBiConsumer<Action, UnsafeConsumer<DataOutputStream, IOException>, IOException> sendAction=(a, data)->{
				synchronized(writer){
					writer.writeByte(a.ordinal());
					ServerCommons.writeSafe(writer, data);
					
					if(threadedOutput) return;
					writer.flush();
				}
				
			};
			
			
			DataLogger.Session proxy;
			
			proxy=new DataLogger.Session(){
				@Override
				public String getName(){
					return name;
				}
				@Override
				public void log(MemFrame frame){
					try{
						sendAction.accept(Action.LOG, buff->io.writeFrame(buff, frame));
					}catch(SocketException ignored){
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public void reset(){
					try{
						sendAction.accept(Action.RESET, buff->{});
					}catch(SocketException ignored){
					}catch(IOException e){
						e.printStackTrace();
					}
				}
				@Override
				public void delete(){
					terminating(Action.DELETE);
				}
				
				@Override
				public void finish(){
					terminating(Action.FINISH);
				}
				public void terminating(Action action){
					try{
						sendAction.accept(action, buff->{});
						writer.flush();
						socket.close();
					}catch(SocketException ignored){
					}catch(IOException e){
						e.printStackTrace();
					}
				}
			};
			
			if(threadedOutput){
				ExecutorService    exec  =Executors.newSingleThreadExecutor();
				DataLogger.Session logger=proxy;
				proxy=new Session(){
					
					private boolean deleting;
					@Override
					public String getName(){
						return logger.getName();
					}
					
					private void stop(){
						exec.shutdown();
						try{
							exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
						}catch(InterruptedException ignored){}
					}
					
					@Override
					public void log(MemFrame frame){
						exec.execute(()->{
							if(deleting) return;
							logger.log(frame);
						});
					}
					
					@Override
					public void reset(){
						exec.execute(()->{
							if(deleting) return;
							logger.reset();
						});
					}
					@Override
					public void delete(){
						deleting=true;
						exec.execute(logger::delete);
						stop();
					}
					
					@Override
					public void finish(){
						exec.execute(()->{
							if(deleting) return;
							logger.finish();
						});
						stop();
					}
				};
			}
			proxy.reset();
			this.proxy=proxy;
			
		}
		
		public record Info(InetAddress addr, int timeout){}
		
		private static Socket sessionConnection(Info con, String sessionName, Map<String, Object> config) throws IOException{
			int port=((Number)config.getOrDefault("port", 666)).intValue();
			
			var socketMake=new Socket();
			socketMake.connect(new InetSocketAddress(con.addr, port), con.timeout);
			
			int realPort;
			try(var preSocket=socketMake){
				var in=new DataInputStream(preSocket.getInputStream());
				realPort=in.readInt();
				var out=new DataOutputStream(preSocket.getOutputStream());
				out.writeUTF(sessionName);
				out.flush();
			}
			
			return new Socket(InetAddress.getLocalHost(), realPort);
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
	
	private final Map<String, Object>  config;
	private final Map<String, Session> sessions=new HashMap<>();
	
	public DisplayServer(Map<String, Object> config){
		this.config=config;
		
		initSession();
	}
	
	private void initSession(){
		sessionCreator=name->{
			try{
				return new ServerSession(new ServerSession.Info(InetAddress.getLocalHost(), 20), name, config);
			}catch(SocketTimeoutException e){
				LogUtil.printlnEr("Could not contact the server!");
			}catch(Throwable e){
				e.printStackTrace();
			}
			
			var type=config.getOrDefault("server-fallback", "local").toString();
			
			sessionCreator=switch(type){
				case "local" -> {
					LogUtil.printlnEr("Switching to local server session.");
					yield getLocalLoggerImpl()::getSession;
				}
				case "none" -> {
					LogUtil.printlnEr("Switching to no output.");
					yield s->Session.Blank.INSTANCE;
				}
				default -> {
					LogUtil.printlnEr("Unknown type \""+type+"\", defaulting to no output.");
					yield s->Session.Blank.INSTANCE;
				}
			};
			
			return sessionCreator.apply(name);
		};
	}
	
	@Override
	public Session getSession(String name){
		if(sessionCreator==null) throw new IllegalStateException("This server has been closed");
		return sessions.computeIfAbsent(name, sessionCreator);
	}
	
	@Override
	public void destroy(){
		sessionCreator=null;
		sessions.values().forEach(Session::finish);
		sessions.clear();
	}
}
