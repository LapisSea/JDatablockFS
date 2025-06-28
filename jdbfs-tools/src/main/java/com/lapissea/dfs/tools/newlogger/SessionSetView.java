package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOInterface;

import java.util.Optional;
import java.util.Set;

public abstract class SessionSetView{
	
	public interface SessionView{
		String name();
		int frameCount();
		IOInterface getFrameData(int id);
	}
	
	public abstract boolean isDirty();
	public abstract void clearDirty();
	
	public abstract Set<String> getSessionNames();
	
	public abstract Optional<SessionView> getAnySession();
	public abstract Optional<SessionView> getSession(String name);
	
}
