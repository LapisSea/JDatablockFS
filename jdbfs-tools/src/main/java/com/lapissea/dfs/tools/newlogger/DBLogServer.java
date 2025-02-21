package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.logging.Log;
import com.lapissea.util.UtilL;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class DBLogServer{
	
	private static final class Connection{
		private final Map<String, Session> sessions = new HashMap<>();
		private final Lock                 lock     = new ReentrantLock();
		private final Thread               thread   = Thread.currentThread();
		
		private boolean manageSessions(Socket managementSocket) throws IOException{
			var in  = new DataInputStream(managementSocket.getInputStream());
			var out = new DataOutputStream(managementSocket.getOutputStream());
			
			IPC.MSGSession command;
			try{
				command = IPC.readEnum(in, IPC.MSGSession.class);
			}catch(EOFException e){
				Log.info("SERVER: Ending connection with port {}#yellow because the stream ended", managementSocket.getPort());
				return true;
			}
			
			switch(command){
				case END -> {
					Log.info("SERVER: Ending connection with port {}#green as requested", managementSocket.getPort());
					return true;
				}
				case REQ -> {
					lock.lock();
					try{
						var name = IPC.readString(in);
						Log.info("SERVER: Session named {}#green requested", name);
						IPC.writeEnum(out, IPC.MSGSession.ACK, true);
						
						var session         = new Session(name);
						var sessionReceiver = new ServerSocket(0);
						IPC.writePortNum(out, sessionReceiver.getLocalPort());
						
						sessions.put(name, session);
						sessionReceiver.setSoTimeout(5000);
						
						CompletableFuture.runAsync(() -> {
							try(var socket = sessionReceiver.accept()){
								Log.info("SERVER: Session {}#green fully connected! Ready for commands.", name);
								var sin  = new DataInputStream(socket.getInputStream());
								var sout = new DataOutputStream(socket.getOutputStream());
								while(true){
									var close = session.run(sin, sout);
									if(close) break;
								}
							}catch(Throwable e){
								e.printStackTrace();
							}finally{
								UtilL.closeSilently(sessionReceiver);
								lock.lock();
								try{
									sessions.remove(name);
								}finally{
									lock.unlock();
								}
								Log.info("SERVER: Session {}#yellow ended", name);
							}
						}, Thread.ofVirtual().name("Session: " + name)::start);
					}finally{
						lock.unlock();
					}
				}
			}
			
			return false;
		}
		
		public Set<String> sessionNames(){
			return sessions.keySet();
		}
	}
	
	private static final class Session{
		
		public final String  name;
		public final FrameDB frameDB = new FrameDB(MemoryData.empty());
		private Session(String name) throws IOException{ this.name = name; }
		
		private boolean run(DataInputStream in, DataOutputStream out) throws IOException{
			
			IPC.MSGSessionMessage cmd;
			try{
				cmd = IPC.readEnum(in, IPC.MSGSessionMessage.class);
			}catch(EOFException e){
				Log.info("SERVER: Ending session {}#yellow because the stream ended", name);
				return true;
			}
			
			switch(cmd){
				case FRAME_FULL -> {
					var frame = IPC.readFullFrame(in);
					Log.trace("SERVER: Frame received: {}#green", frame);
					frameDB.store(name, frame);
					ackNow(out);
				}
				case FRAME_DIFF -> {
					var frame = IPC.readDiffFrame(in);
					Log.trace("SERVER: Frame received: {}#green\n  as: {}#blue", frame, (Supplier<Object>)() -> {
						try{
							return frameDB.resolve(name, frame.uid());
						}catch(IOException e){
							throw new RuntimeException(e);
						}
					});
					frameDB.store(name, frame);
					ackNow(out);
				}
				case END -> {
					Log.info("SERVER: Ending session {}#yellow as requested", name);
					return true;
				}
				case CLEAR -> {
					Log.trace("SERVER: Clearing session {}#yellow", name);
					frameDB.clear(name);
					ackNow(out);
				}
				case READ_FULL -> {
					long uid = in.readLong();
					var  sf  = frameDB.resolve(name, uid);
					if(sf == null){
						IPC.writeEnum(out, IPC.MSGSessionMessage.NACK, true);
						return true;
					}
					
					IPC.writeEnum(out, IPC.MSGSessionMessage.FRAME_FULL, false);
					IPC.writeFullFrame(out, sf);
					out.flush();
				}
				case READ_STATS -> {
					ackNow(out);
					var info = frameDB.sequenceInfo(name);
					IPC.writeStats(out, info);
					out.flush();
				}
				default -> {
					Log.warn("SERVER: Unrecognized frame type: {}", cmd);
					IPC.writeEnum(out, IPC.MSGSessionMessage.NACK, true);
					return true;
				}
			}
			return false;
		}
		private static void ackNow(DataOutputStream out) throws IOException{
			IPC.writeEnum(out, IPC.MSGSessionMessage.ACK, true);
		}
	}
	
	public        boolean                  run         = true;
	private final Map<Integer, Connection> connections = Collections.synchronizedMap(new LinkedHashMap<>());
	private       Thread                   runThread;
	
	public void stop(){
		run = false;
		runThread.interrupt();
	}
	
	public void start() throws IOException{
		runThread = Thread.currentThread();
		try(var soc = new ServerSocket(IPC.DEFAULT_PORT)){
			while(run){
				var ss = IPC.recieveHandshake(soc);
				if(ss == null) continue;
				
				CompletableFuture.runAsync(() -> {
					try(ss; var managementSocket = ss.accept()){
						Connection con = new Connection();
						connections.put(ss.getLocalPort(), con);
						while(true){
							var toClose = con.manageSessions(managementSocket);
							if(toClose) break;
						}
					}catch(Throwable e){
						e.printStackTrace();
					}finally{
						connections.remove(ss.getLocalPort());
					}
				}, Thread.ofVirtual()::start);
			}
			for(Connection value : connections.values()){
				value.thread.interrupt();
			}
		}
	}
	
}
