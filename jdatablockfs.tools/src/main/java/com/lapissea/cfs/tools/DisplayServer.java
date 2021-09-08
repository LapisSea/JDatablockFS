package com.lapissea.cfs.tools;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static com.lapissea.util.LogUtil.Init.*;

public class DisplayServer implements DataLogger{
	
	enum Action{
		LOG,
		RESET,
		FINISH,
		PING
	}
	
	private static synchronized DataLogger getLocalLoggerImpl(){
		try{
			return new DisplayLWJGL();
		}catch(Throwable e){
			return new Display2D();
		}
	}
	
	public static void main(String[] args) throws IOException{
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		var config=LoggedMemoryUtils.readConfig();
		int port  =(int)config.getOrDefault("port", 666);
		
		ServerSocket server=new ServerSocket(port);
		
		DataLogger display=Arrays.asList(args).contains("lazy")?null:getLocalLoggerImpl();
		
		
		LogUtil.println("started");
		int sesCounter=1;
		while(true){
			ServerSocket sessionServer;
			String       sessionName;
			try(Socket client=server.accept()){
				sessionServer=new ServerSocket(0);
				var out=new DataOutputStream(client.getOutputStream());
				out.writeInt(sessionServer.getLocalPort());
				out.flush();
				var in=new DataInputStream(client.getInputStream());
				sessionName=in.readUTF();
			}catch(Exception e){
				e.printStackTrace();
				continue;
			}
			
			if(display==null) display=getLocalLoggerImpl();
			
			DataLogger.Session session=display.getSession(sessionName);
			new Thread(()->{
				try(Socket client=sessionServer.accept()){
					session(session, client);
				}catch(Exception e){
					e.printStackTrace();
				}finally{
					UtilL.closeSilenty(sessionServer);
				}
				System.gc();
			}, "Session: "+sesCounter+"/"+sessionName).start();
		}
		
	}
	private static void session(DataLogger.Session display, Socket client) throws IOException{
		LogUtil.println("connected", client);
		var objInput=new ObjectInputStream(client.getInputStream());
		var out     =client.getOutputStream();
		
		run:
		while(true){
			try{
				switch(Action.values()[objInput.readByte()]){
				case LOG -> display.log((MemFrame)objInput.readObject());
				case RESET -> {
					display.reset();
					System.gc();
				}
				case FINISH -> {
					client.close();
					break run;
				}
				case PING -> {
					out.write(2);
					out.flush();
				}
				}
			}catch(SocketException e){
				if("Connection reset".equals(e.getMessage())){
					LogUtil.println("disconnected");
					break;
				}
				e.printStackTrace();
				break;
			}catch(Exception e){
				e.printStackTrace();
				break;
			}
		}
	}
	
	private static class ServerSession implements DataLogger.Session{
		
		private final Session proxy;
		
		public ServerSession(Info conn, String name, Map<String, Object> config) throws IOException{
			var socket=sessionConnection(conn, name, config);
			LogUtil.println("connected", socket);
			
			boolean threadedOutput=Boolean.parseBoolean(config.getOrDefault("threadedOutput", "false").toString());
			
			var is    =socket.getInputStream();
			var writer=new ObjectOutputStream(socket.getOutputStream());
			
			UnsafeConsumer<Action, IOException> sendAction=(Action a)->writer.writeByte(a.ordinal());
			
			UnsafeRunnable<IOException> flush=()->{
				if(threadedOutput) return;
				writer.flush();
			};
			
			
			DataLogger.Session proxy;
			
			proxy=new DataLogger.Session(){
				
				@Override
				public void log(MemFrame frame){
					try{
						sendAction.accept(Action.LOG);
						writer.writeObject(frame);
						flush.run();
					}catch(SocketException ignored){
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public void reset(){
					try{
						sendAction.accept(Action.RESET);
						flush.run();
					}catch(SocketException ignored){
					}catch(IOException e){
						e.printStackTrace();
					}
				}
				
				@Override
				public void finish(){
					try{
						sendAction.accept(Action.FINISH);
						writer.flush();
						is.read();
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
					@Override
					public void log(MemFrame frame){
						exec.execute(()->logger.log(frame));
					}
					
					@Override
					public void reset(){
						exec.execute(logger::reset);
					}
					
					@Override
					public void finish(){
						exec.execute(logger::finish);
						exec.shutdown();
					}
				};
			}
			proxy.reset();
			this.proxy=proxy;
			
		}
		
		public static record Info(InetAddress addr, int timeout){}
		
		private static Socket sessionConnection(Info con, String sessionName, Map<String, Object> config) throws IOException{
			int port=(int)config.getOrDefault("port", 666);
			
			var socketMake=new Socket();
			try{
				socketMake.connect(new InetSocketAddress(con.addr, port), con.timeout);
			}catch(SocketTimeoutException e){
				
				String jarPath=config.getOrDefault("jar", "").toString();
				socketMake.close();
				if(jarPath.isEmpty()) throw e;
				var     debugMode=GlobalConfig.DEBUG_VALIDATION;
				var     args     =Objects.requireNonNull((String)config.get("startArgs"));
				Process p        =Runtime.getRuntime().exec("java -jar "+(debugMode?"-ea ":"")+args+" \""+new File(jarPath).getAbsolutePath()+"\" lazy");
				p.getInputStream().read();
				
				socketMake=new Socket();
				socketMake.connect(new InetSocketAddress(con.addr, port), con.timeout);
			}
			
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
	}
	
	
	private Function<String, Session> sessionCreator;
	
	private final Map<String, Object>  config;
	private final Map<String, Session> sessions=new HashMap<>();
	
	public DisplayServer(Map<String, Object> config){
		this.config=config;
		
		sessionCreator=name->{
			try{
				return new ServerSession(new ServerSession.Info(InetAddress.getLocalHost(), 100), name, this.config);
			}catch(SocketTimeoutException e){
				LogUtil.printlnEr("Could not contact or start the server!");
			}catch(Throwable e){
				e.printStackTrace();
			}
			
			LogUtil.printlnEr("Switching to local server session.");
			
			sessionCreator=getLocalLoggerImpl()::getSession;
			
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
