package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.logging.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
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
				
				for(Session value : sessions.values()){
					value.close();
				}
				sessions.clear();
				
				var out = new DataOutputStream(sessionManagementSocket.getOutputStream());
				IPC.writeEnum(out, IPC.MSGSession.END, true);
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
					
					writeEnum(socketOut, IPC.MSGSessionMessage.READ_FULL, false);
					socketOut.writeLong(uid);
					socketOut.flush();
					
					var msg = IPC.readEnum(socketIn, IPC.MSGSessionMessage.class);
					switch(msg){
						case FRAME_FULL -> {
							var frame = IPC.readFullFrame(socketIn);
							return frame.data();
						}
						case NACK -> throw new IOException("NACK");
						default -> throw new IOException("Unexpected response: " + msg);
					}
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public SessionStats readStats() throws IOException{
				ioLock.lock();
				try{
					writeEnum(socketOut, IPC.MSGSessionMessage.READ_STATS, true);
					var msg = IPC.readEnum(socketIn, IPC.MSGSessionMessage.class);
					switch(msg){
						case ACK -> {
							return IPC.readStats(socketIn);
						}
						default -> throw new IOException("Unexpected response: " + msg);
					}
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public void close() throws IOException{
				ioLock.lock();
				try{
					if(socket.isClosed()) return;
					sessionLock.lock();
					try{
						var removed = sessions.remove(name);
						assert removed == this;
					}finally{
						sessionLock.unlock();
					}
					socket.close();
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public void writeEvent(IOInterface data, LongStream changeIds) throws IOException{
				if(socket.isClosed()){
					return;
				}
				
				var bytes = data.readAll();
				var ids   = changeIds.toArray();
				
				ioLock.lock();
				try{
					if(socket.isClosed()){
						return;
					}
					
					var uid = ++this.uid;
					
					var full = new IPC.FullFrame(uid, bytes, ids);
					if(last != null){
						var diff = makeDiff(last.data(), bytes, last.uid(), uid, ids);
						IPC.writeEnum(socketOut, IPC.MSGSessionMessage.FRAME_DIFF, false);
						IPC.writeDiffFrame(socketOut, diff);
					}else{
						IPC.writeEnum(socketOut, IPC.MSGSessionMessage.FRAME_FULL, false);
						IPC.writeFullFrame(socketOut, full);
					}
					last = full;
					
					socketOut.flush();
					
					var b = IPC.readEnum(socketIn, IPC.MSGSessionMessage.class);
					switch(b){
						case ACK -> Log.trace("CLIENT: frame {} acknowledged", uid);
						case NACK -> throw new IOException("FRAME_NACK");
						default -> throw new IOException("Unexpected response: " + b);
					}
					
				}finally{
					ioLock.unlock();
				}
			}
			
			@Override
			public String toString(){
				return "RemoteSession{" + name + "}";
			}
		}
		
		private static IPC.DiffFrame makeDiff(byte[] last, byte[] current, long lastUid, long uid, long[] ids){
			
			final class Range{
				private int from, to;
				Range(int from, int to){
					this.from = from;
					this.to = to;
				}
			}
			var   ranges    = new ArrayList<Range>();
			Range lastRange = null;
			for(int i = 0, len = Math.min(last.length, current.length); i<len; i++){
				if(last[i] == current[i]) continue;
				if(lastRange == null){
					lastRange = new Range(i, i + 1);
					ranges.add(lastRange);
				}else if(lastRange.to == i){
					lastRange.to++;
				}else{
					lastRange = new Range(i, i + 1);
					ranges.add(lastRange);
				}
			}
			var parts = new IPC.DiffPart[ranges.size() + (current.length>last.length? 1 : 0)];
			for(int i = 0; i<ranges.size(); i++){
				var range = ranges.get(i);
				parts[i] = new IPC.DiffPart(range.from, Arrays.copyOfRange(current, range.from, range.to));
			}
			if(current.length>last.length){
				parts[ranges.size()] = new IPC.DiffPart(last.length, Arrays.copyOfRange(current, last.length, current.length));
			}
			
			
			return new IPC.DiffFrame(uid, lastUid, last.length != current.length? current.length : -1, parts, ids);
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
