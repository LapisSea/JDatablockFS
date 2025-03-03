package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.logging.Log;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class DBLogIngestServer{
	
	private final class Connection implements Closeable{
		private final Map<String, Session> sessions = new HashMap<>();
		private final Lock                 lock     = new ReentrantLock();
		
		private Thread thread;
		
		private final Socket           managementSocket;
		private final DataInputStream  in;
		private final DataOutputStream out;
		
		private Connection(Socket managementSocket) throws IOException{
			this.managementSocket = managementSocket;
			in = new DataInputStream(managementSocket.getInputStream());
			out = new DataOutputStream(managementSocket.getOutputStream());
		}
		
		private boolean processConnection() throws IOException{
			thread = Thread.currentThread();
			
			IPC.MSGConnection command;
			try{
				command = IPC.readEnum(in, IPC.MSGConnection.class);
			}catch(EOFException|SocketException e){
				Log.info("SERVER: Ending connection with port {}#yellow because the stream ended", managementSocket.getPort());
				return true;
			}
			
			switch(command){
				case END -> {
					Log.info("SERVER: Ending connection with port {}#green as requested", managementSocket.getPort());
					return true;
				}
				case REQUEST_SESSION -> {
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
			IPC.writeEnum(out, IPC.MSGConnection.ACK, true);
			
			//noinspection resource
			var sessionReceiver = new ServerSocket(0);
			IPC.writePortNum(out, sessionReceiver.getLocalPort(), true);
			
			CompletableFuture.runAsync(() -> {
				try(var socket = sessionReceiver.accept();
				    var session = new Session(name, getDB(), socket)){
					
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
		
		@Override
		public void close(){
			thread.interrupt();
			lock.lock();
			try{
				for(Session value : sessions.values()){
					value.close();
				}
				sessions.clear();
			}finally{
				lock.unlock();
			}
			UtilL.closeSilently(managementSocket);
			
		}
	}
	
	private static final class Session implements Closeable{
		
		public final String  name;
		public final FrameDB frameDB;
		
		private final Socket           socket;
		private final DataInputStream  in;
		private final DataOutputStream out;
		
		private Thread processThread;
		
		private Session(String name, FrameDB frameDB, Socket socket) throws IOException{
			this.name = name;
			this.frameDB = frameDB;
			this.socket = socket;
			in = new DataInputStream(socket.getInputStream());
			out = new DataOutputStream(socket.getOutputStream());
		}
		
		private boolean process() throws IOException{
			processThread = Thread.currentThread();
			if(processThread.isInterrupted()){
				return true;
			}
			
			IPC.MSGSession cmd;
			try{
				cmd = IPC.readEnum(in, IPC.MSGSession.class);
			}catch(EOFException|SocketException e){
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
						Log.info("SERVER: [{}#green] Requested to read frame of ID: {}#red", name, uid);
						IPC.writeEnum(out, IPC.MSGSession.NACK, true);
						return true;
					}
					Log.trace("SERVER: [{}#green] Sending full frame of ID: {}#green", name, uid);
					
					IPC.writeEnum(out, IPC.MSGSession.FRAME_FULL, false);
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
					IPC.writeEnum(out, IPC.MSGSession.NACK, true);
					return true;
				}
			}
			return false;
		}
		private static void ackNow(DataOutputStream out) throws IOException{
			IPC.writeEnum(out, IPC.MSGSession.ACK, true);
		}
		
		public void close(){
			processThread.interrupt();
			UtilL.closeSilently(socket);
		}
	}
	
	private       boolean                  run         = true;
	private final Map<Integer, Connection> connections = Collections.synchronizedMap(new LinkedHashMap<>());
	private       Thread                   runThread;
	
	private final    UnsafeSupplier<FrameDB, IOException> frameDBInit;
	private volatile FrameDB                              db;
	
	public DBLogIngestServer(UnsafeSupplier<FrameDB, IOException> frameDBInit){
		this.frameDBInit = frameDBInit;
		
		//lazy init db
		Thread.ofVirtual().start(() -> {
			UtilL.sleep(100);
			try{
				getDB();
			}catch(IOException e){
				e.printStackTrace();
			}
		});
	}
	
	private FrameDB getDB() throws IOException{
		if(db == null) loadDB();
		return db;
	}
	private void loadDB() throws IOException{
		synchronized(this){
			if(db == null){
				var db = frameDBInit.get();
				Objects.requireNonNull(db);
				this.db = db;
			}
		}
	}
	
	public void stop(){
		run = false;
		if(runThread != null){
			runThread.interrupt();
		}
	}
	
	public void start() throws IOException{
		runThread = Thread.currentThread();
		try(var soc = new ServerSocket(IPC.DEFAULT_PORT)){
			while(run){
				listenForConnection(soc);
			}
			for(Connection value : connections.values()){
				value.close();
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
