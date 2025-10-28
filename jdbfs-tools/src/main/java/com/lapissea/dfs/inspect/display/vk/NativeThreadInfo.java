package com.lapissea.dfs.inspect.display.vk;

import com.lapissea.dfs.logging.Log;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeThreadInfo{
	
	private static final class ThreadInfo{
		private long threadId;
	}
	
	private static final ConcurrentHashMap<Thread, ThreadInfo> threadInfo = new ConcurrentHashMap<>();
	
	public static void fetchCurrentThreadInfo(){
		long nativeId = NJA.getNativeThreadId();
		var  info     = getInfo();
		info.threadId = nativeId;
	}
	
	private static ThreadInfo getInfo(){
		var th = Thread.currentThread();
		if(th.isVirtual()){
			Log.warn("Warning: Getting virtual thread info: {}#red", th);
		}
		return threadInfo.computeIfAbsent(th, t -> new ThreadInfo());
	}
	
	public static Optional<Thread> getThreadById(long nativeId){
		return threadInfo.entrySet().stream().filter(e -> e.getValue().threadId == nativeId).findAny().map(Map.Entry::getKey);
	}
	
}
