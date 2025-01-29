package com.lapissea.dfs.internal;

public enum HashCommons{
	;
	
	public static final byte HASH_GENERATIONS = 3;
	
	public static <K> int toHash(K key){
		if(key == null){
			return 0;
		}
		var h = key.hashCode();
		return h2h(h);
	}
	
	public static int h2h(int x){
		return mixMurmur32(x);
	}
	
	/// Source: jdk.internal.util.random.RandomSupport
	public static long mixStafford13(long z){
		z = (z^(z >>> 30))*0xbf58476d1ce4e5b9L;
		z = (z^(z >>> 27))*0x94d049bb133111ebL;
		return z^(z >>> 31);
	}
	/// Source: jdk.internal.util.random.RandomSupport
	public static int mixMurmur32(int z){
		z = (z^(z >>> 16))*0x85ebca6b;
		z = (z^(z >>> 13))*0xc2b2ae35;
		return z^(z >>> 16);
	}
}
