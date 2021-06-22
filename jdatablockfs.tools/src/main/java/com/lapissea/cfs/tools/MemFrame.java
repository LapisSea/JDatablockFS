package com.lapissea.cfs.tools;

import java.io.Serializable;

public record MemFrame(byte[] data, long[] ids, Throwable e) implements Serializable{
	
	public void printStackTrace(){
		e.printStackTrace();
	}
	
}
