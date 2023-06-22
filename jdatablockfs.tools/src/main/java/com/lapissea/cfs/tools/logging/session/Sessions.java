package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class Sessions{
	
	
	private interface SessionFrames extends IOInstance.Def<SessionFrames>{
		IOList<Frame<?>> frames();
	}
	
	private interface SessionInfo{
		String ROOT_ID = "SESSION_INFO";
		
		IOMap<String, SessionFrames> sessions();
	}
	
	public static final class SessionWriter{
		private final SessionFrames frames;
		private SessionWriter(SessionFrames frames){ this.frames = frames; }
		
		
		public void snapshot(IOInterface data){
		
		}
		
	}
	
	public static final class Writer{
		private final Cluster                    cluster;
		private final SessionInfo                info;
		private final Map<String, SessionWriter> sessions = new HashMap<>();
		
		public Writer(IOInterface data) throws IOException{
			if(data.isReadOnly()) throw new IllegalArgumentException("data can not be read only");
			cluster = new Cluster(data);
			info = cluster.getRootProvider().request(SessionInfo.ROOT_ID, SessionInfo.class);
		}
		
		public synchronized SessionWriter getSession(String name){
			return sessions.computeIfAbsent(name, this::makeSession);
		}
		
		private SessionWriter makeSession(String name){
			SessionFrames frames;
			try{
				frames = info.sessions().computeIfAbsent(name, () -> {
					var fram = IOInstance.Def.of(SessionFrames.class);
					fram.allocateNulls(cluster);
					return fram;
				});
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			return new SessionWriter(frames);
		}
		
	}
	
	
	public static final class SessionView{
		private final SessionFrames frames;
		private SessionView(SessionFrames frames){ this.frames = frames; }
		
		public Frame<?> getFrame(){
			return null;
		}
		
	}
	
	public static final class Explorer{
		
		private final SessionInfo              info;
		private final Map<String, SessionView> sessions = new HashMap<>();
		
		public Explorer(IOInterface data) throws IOException{
			info = new Cluster(data.asReadOnly()).getRootProvider().require(SessionInfo.ROOT_ID, SessionInfo.class);
		}
		
		public synchronized SessionView getSession(String name){
			return sessions.computeIfAbsent(name, this::makeSession);
		}
		
		private SessionView makeSession(String name){
			SessionFrames frames;
			try{
				frames = info.sessions().get(name);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			return new SessionView(frames);
		}
	}
	
}
