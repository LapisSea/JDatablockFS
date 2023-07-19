package com.lapissea.cfs.run.fuzzing;

public record FuzzSequence(long startIndex, long index, long seed, int iterations){
	
	@Override
	public String toString(){
		
		var seedStr = new StringBuilder(4);
		for(int i = 0; i<4; i++) seedStr.append(toChar((int)((seed>>i)&0xFF)));
		
		int u  = 1, k = 1000, M = 1000_000;
		var us = "";
		if((startIndex%M|iterations%M) == 0){
			u = M;
			us = "M";
		}else if((startIndex%k|iterations%k) == 0){
			u = k;
			us = "k";
		}
		var start = startIndex/u;
		var end   = (startIndex + iterations)/u;
		return seedStr + "->" + index + "(" + start + us + "-" + end + us + ")";
	}
	
	private static char toChar(int b){
		var c = (char)(b%Byte.MAX_VALUE);
		if(c<'0') c += '0';
		if(c>'9' && c<'A') c += 'A' - '9';
		if(c>'Z' && c<'a') c += 'a' - 'Z';
		if(c>'z') c = (char)('a' + (c - 'z'));
		return c;
	}
	
	public FuzzSequence withIterations(int iterations){
		return new FuzzSequence(startIndex, index, seed, iterations);
	}
}
