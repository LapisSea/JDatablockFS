package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class Sessions{
	
	public static final class FrameView{
		
		private final String      trace;
		private final IOInterface data;
		public FrameView(String trace, IOInterface data){
			this.trace = trace;
			this.data = data;
		}
		
		public String getTrace(){
			return trace;
		}
		public IOInterface getData(){
			return data;
		}
		
		@Override
		public String toString(){
			try{
				return "FrameView{" + data.getIOSize() + " bytes}";
			}catch(Throwable e){
				return "corrupted: " + e;
			}
		}
	}
	
	
	public static final class SessionView{
		private final SessionsInfo.Frames frames;
		private SessionView(SessionsInfo.Frames frames){ this.frames = frames; }
		
		public long getFrameCount(){
			return frames.frames().size();
		}
		
		private byte[] getData(Frame<?> frame) throws IOException{
			return switch(frame){
				case Frame.Full full -> full.getData().readAll();
				case Frame.Incremental inc -> {
					var parent     = frames.frames().get(inc.getParentFrame());
					var parentData = getData(parent);
					var newSize    = inc.getNewSize();
					if(parentData.length != newSize){
						parentData = Arrays.copyOf(parentData, Math.toIntExact(newSize));
					}
					for(var block : inc.getData()){
						var data  = block.data();
						var start = (int)block.start();
						System.arraycopy(data, 0, parentData, start, data.length);
					}
					yield parentData;
				}
			};
		}
		
		private String buildTrace(Frame<?> frame) throws IOException{
			var strings = frames.strings();
			var trace   = new StringBuilder();
			var start   = 0;
			var doHead  = true;
			
			while(true){
				var st = frame.getStacktrace();
				start += st.toString(trace, doHead, start, strings);
				
				var diffBottomCount = st.getDiffBottomCount();
				if(diffBottomCount == 0) break;
				
				doHead = false;
				var parentId = ((Frame.Incremental)frame).getParentFrame();
				frame = frames.frames().get(parentId);
			}
			return trace.toString();
		}
		
		public FrameView getFrame(long index) throws IOException{
			var frame = frames.frames().get(index);
			
			var trace = buildTrace(frame);
			var data  = getData(frame);
			
			return new FrameView(trace, MemoryData.viewOf(data));
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
