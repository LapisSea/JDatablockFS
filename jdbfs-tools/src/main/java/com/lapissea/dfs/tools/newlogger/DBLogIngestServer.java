package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.lapissea.dfs.tools.newlogger.IPC.SERVER;

public final class DBLogIngestServer{
	
	private static final class StoredSession{
		
		final String  name;
		final FrameDB frameDB;
		
		final SessionSetView.SessionView view = new SessionSetView.SessionView(){
			@Override
			public String name(){
				return name;
			}
			@Override
			public int frameCount(){
				try{
					var count = frameDB.sequenceInfo(name).frameCount();
					return Math.toIntExact(count);//TODO
				}catch(IOException e){
					throw new RuntimeException(e);//TODO
				}
			}
			@Override
			public IOInterface getFrameData(int id){
				try{
					var frame = frameDB.resolve(name, id);
					if(frame == null){
						LogUtil.println("INVALID FRAME DATA:", name, id);
						return MemoryData.empty();
					}
					return MemoryData.viewOf(frame.data());
				}catch(IOException e){
					throw new RuntimeException(e);//TODO
				}
			}
		};
		
		private StoredSession(String name, FrameDB frameDB){
			this.name = name;
			this.frameDB = frameDB;
		}
		
	}
	
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
				SERVER.info("Ending connection with port {}#yellow because the stream ended", managementSocket.getPort());
				return true;
			}
			
			switch(command){
				case END -> {
					SERVER.info("Ending connection with port {}#green as requested", managementSocket.getPort());
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
			if(listSessionNames().contains(name)){
				SERVER.warn("Session named {}#red requested but already exists!", name);
				IPC.writeEnum(out, IPC.MSGConnection.NACK, true);
				return;
			}
			SERVER.info("Session named {}#green requested", name);
			IPC.writeEnum(out, IPC.MSGConnection.ACK, true);
			
			//noinspection resource
			var sessionReceiver = new ServerSocket(0);
			IPC.writePortNum(out, sessionReceiver.getLocalPort(), true);
			
			CompletableFuture.runAsync(() -> {
				StoredSession storedSession;
				
				try(var socket = sessionReceiver.accept();
				    var session = new Session(storedSession = new StoredSession(name, getDB()), socket)){
					
					lock.lock();
					try{
						sessions.put(name, session);
					}finally{
						lock.unlock();
					}
					synchronized(storedSessions){
						storedSessions.put(name, storedSession);
					}
					modId.incrementAndGet();
					
					SERVER.info("Session {}#green connected! Ready for commands.", name);
					
					while(true){
						var close = session.process();
						modId.incrementAndGet();
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
					modId.incrementAndGet();
					
					SERVER.info("[{}#yellow] Session ended", name);
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
		
		public final StoredSession storedSession;
		
		private final Socket           socket;
		private final DataInputStream  in;
		private final DataOutputStream out;
		
		private Thread processThread;
		
		private final SessionSetView.SessionView view;
		
		private Session(StoredSession storedSession, Socket socket) throws IOException{
			this.storedSession = storedSession;
			this.socket = socket;
			in = new DataInputStream(socket.getInputStream());
			out = new DataOutputStream(socket.getOutputStream());
			
			view = new SessionSetView.SessionView(){
				@Override
				public String name(){ return storedSession.name; }
				@Override
				public int frameCount(){
					throw NotImplementedException.infer();//TODO: implement .frameCount()
				}
				@Override
				public IOInterface getFrameData(int id){
					throw NotImplementedException.infer();//TODO: implement .getFrameData()
				}
			};
		}
		
		private boolean process() throws IOException{
			processThread = Thread.currentThread();
			if(processThread.isInterrupted()){
				return true;
			}
			
			var name    = storedSession.name;
			var frameDB = storedSession.frameDB;
			
			IPC.MSGSession cmd;
			try{
				cmd = IPC.readEnum(in, IPC.MSGSession.class);
			}catch(EOFException|SocketException e){
				SERVER.info("[{}#red] Ending session because the stream ended", name);
				return true;
			}
			
			switch(cmd){
				case FRAME_FULL -> {
					var frame = IPC.readFullFrame(in);
					ackNow(out);
					SERVER.trace("[{}#green] Frame received: {}#green", name, frame);
					frameDB.store(name, frame);
				}
				case FRAME_DIFF -> {
					var frame = IPC.readDiffFrame(in);
					ackNow(out);
					SERVER.trace("[{}#green] Frame received: {}#green\n  as: {}#blue", name, frame, (Supplier<Object>)() -> {
						try{
							return frameDB.resolve(name, frame.uid());
						}catch(IOException e){
							throw new RuntimeException(e);
						}
					});
					frameDB.store(name, frame);
				}
				case END -> {
					SERVER.info("[{}#green] Ending session as requested", name);
					return true;
				}
				case CLEAR -> {
					SERVER.trace("[{}#green] Clearing session", name);
					frameDB.clear(name);
					ackNow(out);
				}
				case READ_FULL -> {
					long uid = in.readLong();
					var  sf  = frameDB.resolve(name, uid);
					if(sf == null){
						SERVER.info("[{}#green] Requested to read frame of ID: {}#red", name, uid);
						IPC.writeEnum(out, IPC.MSGSession.NACK, true);
						return true;
					}
					SERVER.trace("[{}#green] Sending full frame of ID: {}#green", name, uid);
					
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
					SERVER.warn("[{}#green] Unrecognized frame type: {}", name, cmd);
					IPC.writeEnum(out, IPC.MSGSession.NACK, true);
					return true;
				}
			}
			return false;
		}
		private static void ackNow(DataOutputStream out) throws IOException{
			try{
				IPC.writeEnum(out, IPC.MSGSession.ACK, true);
			}catch(IOException e){
				throw new IOException("Failed to ack", e);
			}
		}
		
		public void close(){
			processThread.interrupt();
			UtilL.closeSilently(socket);
		}
	}
	
	private       boolean                  run         = true;
	private final Map<Integer, Connection> connections = new LinkedHashMap<>();
	
	private final    UnsafeSupplier<FrameDB, IOException> frameDBInit;
	private volatile FrameDB                              db;
	private          ServerSocket                         serverSocket;
	
	private final Map<String, StoredSession> storedSessions = new HashMap<>();
	
	private final AtomicLong     modId = new AtomicLong();
	public final  SessionSetView view;
	
	public DBLogIngestServer(UnsafeSupplier<FrameDB, IOException> frameDBInit){
		this.frameDBInit = frameDBInit;
		
		view = makeView();
		
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
	
	private SessionSetView makeView(){
		return new SessionSetView(){
			
			private long seenId;
			
			@Override
			public boolean isDirty(){ return seenId != modId.get(); }
			@Override
			public void clearDirty(){ seenId = modId.get(); }
			
			@Override
			public Set<String> getSessionNames(){ return listSessionNames(); }
			
			@Override
			public Optional<SessionView> getAnySession(){
				synchronized(storedSessions){
					var name = Iters.keys(storedSessions).findFirst();
					if(name.isEmpty()) return Optional.empty();
					return getSession(name.get());
				}
			}
			
			@Override
			public Optional<SessionView> getSession(String name){
				synchronized(storedSessions){
					var session = storedSessions.get(name);
					if(session == null) return Optional.empty();
					return Optional.of(session.view);
				}
			}
			
			@Override
			public String toString(){
				return "IngestDBView" + listSessionNames();
			}
		};
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
		if(serverSocket != null){
			try{
				serverSocket.close();
			}catch(IOException e){
				throw new UncheckedIOException(e);
			}
		}
	}
	
	public void start() throws IOException{
		try(var soc = new ServerSocket(IPC.DEFAULT_PORT)){
			this.serverSocket = soc;
			while(run){
				listenForConnection(soc);
			}
			synchronized(connections){
				for(var value : connections.values()){
					value.close();
				}
			}
		}
	}
	
	private void listenForConnection(ServerSocket soc) throws IOException{
		ServerSocket ss = IPC.recieveHandshake(soc);
		if(ss == null) return;
		
		CompletableFuture.runAsync(() -> {
			try(ss; var managementSocket = ss.accept()){
				
				var con = new Connection(managementSocket);
				synchronized(connections){
					connections.put(ss.getLocalPort(), con);
				}
				
				modId.incrementAndGet();
				while(true){
					var toClose = con.processConnection();
					if(toClose) break;
				}
				
			}catch(Throwable e){
				e.printStackTrace();
			}finally{
				synchronized(connections){
					connections.remove(ss.getLocalPort());
				}
				UtilL.closeSilently(ss);
			}
		}, Thread.ofVirtual()::start);
	}
	
	public Set<String> listSessionNames(){
		synchronized(storedSessions){
			return Set.copyOf(storedSessions.keySet());
		}
	}
	
}
