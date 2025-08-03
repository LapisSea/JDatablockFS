package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.frame.FrameUtils;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;

import static com.lapissea.dfs.tools.newlogger.IPC.DEFAULT_PORT;
import static com.lapissea.dfs.tools.newlogger.IPC.writeEnum;

public interface DBLogConnection extends Closeable{
	
	interface Session extends Closeable{
		
		record SessionStats(long frameCount, long lastFrameUid){ }
		
		IOHook getIOHook();
		byte[] readFrame(long uid) throws IOException;
		SessionStats readStats() throws IOException;
		void clear() throws IOException;
		
		default byte[] readLastFrame() throws IOException{
			var stats = readStats();
			if(stats.frameCount == 0) throw new IOException("No frame found");
			var last = stats.lastFrameUid();
			return readFrame(last);
		}
	}
	
	final class OfRemote implements DBLogConnection{
		
		private final Socket               sessionManagementSocket;
		private final Map<String, Session> sessions = new HashMap<>();
		
		public OfRemote() throws IOException{
			this(new InetSocketAddress(InetAddress.getLocalHost(), DEFAULT_PORT));
		}
		
		public OfRemote(InetSocketAddress serverAddress) throws IOException{
			Objects.requireNonNull(serverAddress);
			
			var handshake = IPC.clientHandshake(serverAddress);
			
			var communicationAddress = new InetSocketAddress(serverAddress.getAddress(), handshake.sessionManagementPort());
			
			sessionManagementSocket = new Socket();
			sessionManagementSocket.connect(communicationAddress);
			Log.trace("CLIENT: Opened session handler on {}#green", communicationAddress);
		}
		
		private final Lock sessionLock = new ReentrantLock();
		
		@Override
		public void close() throws IOException{
			sessionLock.lock();
			try{
				if(sessionManagementSocket.isClosed()) return;
				Log.info("CLIENT: Closing log connection on {}#yellow", sessionManagementSocket);
				
				for(Session value : sessions.values()){
					value.close();
				}
				sessions.clear();
				
				var out = new DataOutputStream(sessionManagementSocket.getOutputStream());
				IPC.writeEnum(out, IPC.MSGConnection.END, true);
				sessionManagementSocket.close();
			}finally{
				sessionLock.unlock();
			}
		}
		
		
		private final class IPCSession implements Session, IOHook{
			
			private final String name;
			private final Socket socket;
			
			private final DataInputStream  socketIn;
			private final DataOutputStream socketOut;
			
			private final Lock ioLock = new ReentrantLock();
			
			private long          uid;
			private IPC.FullFrame last;
			
			private IPCSession(String name, Socket socket) throws IOException{
				this.name = name;
				this.socket = Objects.requireNonNull(socket);
				socketOut = new DataOutputStream(socket.getOutputStream());
				socketIn = new DataInputStream(socket.getInputStream());
			}
			
			@Override
			public IOHook getIOHook(){ return this; }
			
			@Override
			public byte[] readFrame(long uid) throws IOException{
				ioLock.lock();
				try{
					if(socket.isClosed()){
						throw new IllegalStateException("Session closed");
					}
					
					writeEnum(socketOut, IPC.MSGSession.READ_FULL, false);
					socketOut.writeLong(uid);
					socketOut.flush();
					
					var msg = IPC.readEnum(socketIn, IPC.MSGSession.class);
					switch(msg){
						case FRAME_FULL -> {
							var frame = IPC.readFullFrame(socketIn);
							return frame.data();
						}
						case NACK -> throw new IOException("NACK on readFrame");
						default -> throw new IOException("Unexpected readFrame response: " + msg);
					}
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public SessionStats readStats() throws IOException{
				ioLock.lock();
				try{
					writeEnum(socketOut, IPC.MSGSession.READ_STATS, true);
					requireAckResponse("readStats");
					return IPC.readStats(socketIn);
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public void clear() throws IOException{
				ioLock.lock();
				try{
					writeEnum(socketOut, IPC.MSGSession.CLEAR, true);
					requireAckResponse("clear");
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public void close() throws IOException{
				ioLock.lock();
				try{
					if(socket.isClosed()) return;
					Log.trace("CLIENT: Closing log session {}#yellow", name);
					sessionLock.lock();
					try{
						sessions.remove(name, this);
					}finally{
						sessionLock.unlock();
					}
					writeEnum(socketOut, IPC.MSGSession.END, true);
					socket.close();
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public void writeEvent(IOInterface data, LongStream writeIds) throws IOException{
				if(socket.isClosed()){
					return;
				}
				
				var bytes    = data.readAll();
				var writeSet = IPC.RangeSet.from(writeIds);
				
				ioLock.lock();
				try{
					if(socket.isClosed()){
						return;
					}
					
					var uid = ++this.uid;
					
					var full = new IPC.FullFrame(uid, bytes, writeSet);
					if(last != null){
						var diff = FrameUtils.computeDiff(last.data(), bytes);
						
						IPC.writeEnum(socketOut, IPC.MSGSession.FRAME_DIFF, false);
						IPC.writeDiffFrame(socketOut, new IPC.DiffFrame(
							uid, last.uid(), diff.newSize().orElse(-1), diff.blocks(), writeSet
						));
					}else{
						IPC.writeEnum(socketOut, IPC.MSGSession.FRAME_FULL, false);
						IPC.writeFullFrame(socketOut, full);
					}
					last = full;
					
					socketOut.flush();
					try{
						requireAckResponse("writeEvent");
					}catch(IOException e){
						socket.close();
						Log.warn("Failed to send frame because: {}#red", e);
					}
					Log.trace("CLIENT: [{}#green] frame {}#green acknowledged", name, uid);
				}finally{
					ioLock.unlock();
				}
			}
			
			private void requireAckResponse(String actionName) throws IOException{
				var response = IPC.readEnum(socketIn, IPC.MSGSession.class);
				switch(response){
					case ACK -> { }//ok
					case NACK -> throw new IOException("Got NACK on " + actionName);
					default -> throw new IOException("Unexpected " + actionName + " response: " + response);
				}
			}
			
			@Override
			public String toString(){
				return "RemoteSession{" + name + "}";
			}
		}
		
		@Override
		public Session openSession(String name){
			sessionLock.lock();
			try{
				if(sessionManagementSocket.isClosed()){
					throw new IllegalStateException("Connection is closed");
				}
				var existing = sessions.get(name);
				if(existing != null){
					return existing;
				}
				
				Log.trace("CLIENT: Requesting session {}#yellow", name);
				var sessionSocket = IPC.requestSession(sessionManagementSocket, name);
				Log.info("CLIENT: Opened session {}#green on port: {}#green", name, sessionSocket.getPort());
				
				var ses = new IPCSession(name, sessionSocket);
				
				sessions.put(name, ses);
				return ses;
			}catch(IOException e){
				throw new RuntimeException("Failed to create session", e);
			}finally{
				sessionLock.unlock();
			}
		}
	}
	
	Session openSession(String name);
	
}
