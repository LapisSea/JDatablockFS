package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;

import java.util.Optional;
import java.util.Set;

public abstract class SessionSetView{
	
	public record FrameData(IOInterface contents, IPC.RangeSet writes){
		public static final FrameData EMPTY = new SessionSetView.FrameData(MemoryData.viewOf(new byte[0]), IPC.RangeSet.EMPTY);
	}
	
	public interface SessionView{
		String name();
		int frameCount();
		FrameData getFrameData(int id);
	}
	
	public abstract boolean isDirty();
	public abstract void clearDirty();
	
	public abstract Set<String> getSessionNames();
	
	public abstract Optional<SessionView> getAnySession();
	public abstract Optional<SessionView> getSession(String name);
	
}
