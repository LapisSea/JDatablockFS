package com.lapissea.cfs.internal;

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
//		Inlined:
//		return new Random(x).nextInt();
		
		long mul = 0x5DEECE66DL, mask = 0xFFFFFFFFFFFFL;
		return (int)(((((x^mul)&mask)*mul + 0xBL)&mask) >>> 17);
	}
}
