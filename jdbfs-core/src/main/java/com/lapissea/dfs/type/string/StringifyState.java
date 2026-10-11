package com.lapissea.dfs.type.string;

/**
 * Guards the recursive stringification walk against cyclic object graphs and pathologically deep nesting.
 * Recursion stops expanding once {@link #MAX_DEPTH} is reached.
 */
public final class StringifyState{
	
	public static final int MAX_DEPTH = 64;
	
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	
	private StringifyState(){ }
	
	/** @return false when the depth budget is exhausted, in which case {@link #exit} must not be called */
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
