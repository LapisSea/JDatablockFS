package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class Sessions{
	
	
	public static final class SessionView{
		private final SessionsInfo.Frames frames;
		private SessionView(SessionsInfo.Frames frames){ this.frames = frames; }
		
		public Frame<?> getFrame(){
			return null;
		}
		
	}
	
	public static final class Explorer{
		
		private final SessionsInfo             info;
		private final Map<String, SessionView> sessions = new HashMap<>();
		
		public Explorer(IOInterface data) throws IOException{
			info = new Cluster(data.asReadOnly()).getRootProvider().require(SessionsInfo.ROOT_ID, SessionsInfo.class);
		}
		
		public synchronized SessionView getSession(String name){
			return sessions.computeIfAbsent(name, this::makeSession);
		}
		
		private SessionView makeSession(String name){
			SessionsInfo.Frames frames;
			try{
				frames = info.sessions().get(name);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			return new SessionView(frames);
		}
	}
	
}
