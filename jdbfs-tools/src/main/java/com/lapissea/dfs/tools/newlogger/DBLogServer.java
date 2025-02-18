package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.logging.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
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
						
						Thread.ofVirtual().name("Session: " + name).start(() -> {
							try(sessionReceiver; var socket = sessionReceiver.accept()){
								Log.info("SERVER: Session {}#green fully connected! Ready for commands.", name);
								var sin  = new DataInputStream(socket.getInputStream());
								var sout = new DataOutputStream(socket.getOutputStream());
								while(true){
									var close = session.run(sin, sout);
									if(close) break;
								}
							}catch(IOException e){
								e.printStackTrace();
							}finally{
								lock.lock();
								try{
									sessions.remove(name);
								}finally{
									lock.unlock();
								}
								Log.info("SERVER: Session {}#yellow ended", name);
							}
						});
					}finally{
						lock.unlock();
					}
				}
			}
			
			return false;
		}
	}
	
	private static final class Session{
		
		public final String                            name;
		public final SequencedMap<Long, IPC.SendFrame> frames = new LinkedHashMap<>();
		private Session(String name){ this.name = name; }
		
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
					frames.put(frame.uid(), frame);
					ackNow(out);
				}
				case FRAME_DIFF -> {
					var frame = IPC.readDiffFrame(in);
					Log.trace("SERVER: Frame received: {}#green\n  as: {}#blue", frame, (Supplier<Object>)() -> resolve(frame));
					frames.put(frame.uid(), frame);
					ackNow(out);
				}
				case END -> {
					Log.info("SERVER: Ending session {}#yellow as requested", name);
					return true;
				}
				case CLEAR -> {
					Log.trace("SERVER: Clearing session {}#yellow", name);
					frames.clear();
					ackNow(out);
				}
				case READ_FULL -> {
					long uid = in.readLong();
					var  sf  = frames.get(uid);
					if(sf == null){
						IPC.writeEnum(out, IPC.MSGSessionMessage.NACK, true);
						return true;
					}
					
					IPC.writeEnum(out, IPC.MSGSessionMessage.FRAME_FULL, false);
					IPC.writeFullFrame(out, resolve(sf));
					out.flush();
				}
				case READ_STATS -> {
					ackNow(out);
					var last = frames.lastEntry();
					IPC.writeStats(out, new DBLogConnection.Session.SessionStats(frames.size(), last == null? -1 : last.getKey()));
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
		private IPC.FullFrame resolve(IPC.SendFrame frame){
			return switch(frame){
				case IPC.DiffFrame f -> resolve(f);
				case IPC.FullFrame f -> f;
			};
		}
		private IPC.FullFrame resolve(IPC.DiffFrame frame){
			var full = switch(frames.get(frame.prevUid())){
				case IPC.DiffFrame prev -> resolve(prev);
				case IPC.FullFrame prev -> prev;
			};
			byte[] buff;
			if(frame.newSize() != -1){
				buff = Arrays.copyOf(full.data(), frame.newSize());
			}else{
				buff = full.data().clone();
			}
			for(var part : frame.parts()){
				System.arraycopy(part.data(), 0, buff, part.offset(), part.data().length);
			}
			return new IPC.FullFrame(frame.uid(), buff, frame.ids());
		}
		
	}
	
	public        boolean                  run         = true;
	private final Map<Integer, Connection> connections = Collections.synchronizedMap(new LinkedHashMap<>());
	private       Thread                   runThread;
	
	public void stop(){
		run = false;
		runThread.interrupt();
	}
	
	public void run() throws IOException{
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
