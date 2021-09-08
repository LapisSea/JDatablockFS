package com.lapissea.cfs.tools.logging;

import java.io.Serializable;

public record MemFrame(byte[] data, long[] ids, Throwable e) implements Serializable{
	
	public void printStackTrace(){
		e.printStackTrace();
	}
	
}
