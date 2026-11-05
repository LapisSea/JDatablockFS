package com.lapissea.dfs.type.string;

/**
 * Guards toString against cyclic object graphs and very deep nesting
 */
public final class StringifyState{
	
	public static final int MAX_DEPTH = 64;
	
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	
	private StringifyState(){ }
	
	public static boolean enter(){
		var depth = DEPTH.get();
		if(depth[0]>=MAX_DEPTH) return false;
		depth[0]++;
		return true;
	}
	
	public static void exit(){
		DEPTH.get()[0]--;
	}
}
