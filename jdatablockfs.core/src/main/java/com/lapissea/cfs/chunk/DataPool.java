package com.lapissea.cfs.chunk;

import com.lapissea.util.NotImplementedException;

public final class DataPool{
	
	public <T> int toId(Class<T> type, T val, boolean write){
		throw new NotImplementedException();
	}
	public <T> T fromId(Class<T> type, int id){
		throw new NotImplementedException();
	}
}
