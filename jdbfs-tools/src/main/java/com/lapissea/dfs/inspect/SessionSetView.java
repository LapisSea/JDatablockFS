package com.lapissea.dfs.inspect;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public abstract class SessionSetView{
	
	public static class Lazy extends SessionSetView{
		
		private SessionSetView data;
		private boolean        dirty;
		
		public synchronized void init(SessionSetView data){
			Objects.requireNonNull(data);
			if(this.data != null) throw new IllegalStateException();
			this.data = data;
			dirty = true;
		}
		
		@Override
		public Optional<SessionView> getSession(String name){
			if(data == null) return Optional.empty();
			return data.getSession(name);
		}
		@Override
		public Optional<SessionView> getAnySession(){
			if(data == null) return Optional.empty();
			return data.getAnySession();
		}
		@Override
		public boolean checkPopDirty(){
			var d = dirty;
			if(data == null || d){
				dirty = false;
				return d;
			}
			return data.checkPopDirty();
		}
		@Override
		public Set<String> getSessionNames(){
			if(data == null) return Set.of();
			return data.getSessionNames();
		}
		
		@Override
		public String toString(){
			return data == null? "Lazy<Uninitialized>" : data.toString();
		}
	}
	
	public static class Empty extends SessionSetView{
		@Override
		public Optional<SessionView> getSession(String name){ return Optional.empty(); }
		@Override
		public Optional<SessionView> getAnySession(){ return Optional.empty(); }
		@Override
		public boolean checkPopDirty(){ return false; }
		@Override
		public Set<String> getSessionNames(){ return Set.of(); }
		@Override
		public String toString(){ return "EmptySessionSet"; }
	}
	
	public record FrameData(IOInterface contents, IPC.RangeSet writes, String stacktrace){
		public static final FrameData EMPTY = new SessionSetView.FrameData(MemoryData.viewOf(new byte[0]), IPC.RangeSet.EMPTY, "");
	}
	
	public interface SessionView{
		String name();
		int frameCount();
		FrameData getFrameData(int id);
	}
	
	public abstract boolean checkPopDirty();
	
	public abstract Set<String> getSessionNames();
	
	public abstract Optional<SessionView> getAnySession();
	public abstract Optional<SessionView> getSession(String name);
	
}
