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
		private final Socket               managementSocket;
		private final DataInputStream      in;
		private final DataOutputStream     out;
		
		private Connection(Socket managementSocket) throws IOException{
			this.managementSocket = managementSocket;
			in = new DataInputStream(managementSocket.getInputStream());
			out = new DataOutputStream(managementSocket.getOutputStream());
		}
		
		private boolean processConnection() throws IOException{
			
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
						handleRequest();
					}finally{
						lock.unlock();
					}
				}
			}
			
			return false;
		}
		
		private void handleRequest() throws IOException{
			var name = IPC.readString(in);
			Log.info("SERVER: Session named {}#green requested", name);
			IPC.writeEnum(out, IPC.MSGSession.ACK, true);
			
			var sessionReceiver = new ServerSocket(0);
			IPC.writePortNum(out, sessionReceiver.getLocalPort());
			
			sessionReceiver.setSoTimeout(5000);
			
			CompletableFuture.runAsync(() -> {
				try(var socket = sessionReceiver.accept()){
					
					var session = new Session(name, socket);
					sessions.put(name, session);
					
					Log.info("SERVER: Session {}#green connected! Ready for commands.", name);
					
					while(true){
						var close = session.process();
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
					Log.info("SERVER: [{}#yellow] Session ended", name);
				}
			}, Thread.ofVirtual().name("Session: " + name)::start);
		}
		
		public Set<String> sessionNames(){
			return sessions.keySet();
		}
	}
	
	private static final class Session{
		
		public final String  name;
		public final FrameDB frameDB = new FrameDB(MemoryData.empty());
		
		private final Socket           socket;
		private final DataInputStream  in;
		private final DataOutputStream out;
		
		private Session(String name, Socket socket) throws IOException{
			this.name = name;
			this.socket = socket;
			in = new DataInputStream(socket.getInputStream());
			out = new DataOutputStream(socket.getOutputStream());
		}
		
		private boolean process() throws IOException{
			
			IPC.MSGSessionMessage cmd;
			try{
				cmd = IPC.readEnum(in, IPC.MSGSessionMessage.class);
			}catch(EOFException e){
				Log.info("SERVER: [{}#red] Ending session because the stream ended", name);
				return true;
			}
			
			switch(cmd){
				case FRAME_FULL -> {
					var frame = IPC.readFullFrame(in);
					Log.trace("SERVER: [{}#green] Frame received: {}#green", name, frame);
					frameDB.store(name, frame);
					ackNow(out);
				}
				case FRAME_DIFF -> {
					var frame = IPC.readDiffFrame(in);
					Log.trace("SERVER: [{}#green] Frame received: {}#green\n  as: {}#blue", name, frame, (Supplier<Object>)() -> {
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
					Log.info("SERVER: [{}#green] Ending session as requested", name);
					return true;
				}
				case CLEAR -> {
					Log.trace("SERVER: [{}#green] Clearing session", name);
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
					Log.warn("SERVER: [{}#green] Unrecognized frame type: {}", name, cmd);
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
				listenForConnection(soc);
			}
			for(Connection value : connections.values()){
				value.thread.interrupt();
			}
		}
	}
	
	private void listenForConnection(ServerSocket soc) throws IOException{
		ServerSocket ss = IPC.recieveHandshake(soc);
		if(ss == null) return;
		
		CompletableFuture.runAsync(() -> {
			try(ss; var managementSocket = ss.accept()){
				
				var con = new Connection(managementSocket);
				connections.put(ss.getLocalPort(), con);
				
				while(true){
					var toClose = con.processConnection();
					if(toClose) break;
				}
				
			}catch(Throwable e){
				e.printStackTrace();
			}finally{
				connections.remove(ss.getLocalPort());
				UtilL.closeSilently(ss);
			}
		}, Thread.ofVirtual()::start);
	}
	
}
