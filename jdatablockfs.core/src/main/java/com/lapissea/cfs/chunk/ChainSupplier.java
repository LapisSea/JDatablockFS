package com.lapissea.cfs.chunk;

import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.function.Supplier;

public class ChainSupplier implements Supplier<Chunk>{
	private Chunk       chunk;
	private IOException e;
	
	public ChainSupplier(Chunk chunk){
		this.chunk = chunk;
	}
	
	@Override
	public Chunk get(){
		if(e != null){
			throw UtilL.uncheckedThrow(e);
		}
		
		Chunk c = chunk;
		if(c == null) return null;
		try{
			chunk = c.next();
		}catch(IOException e){
			this.e = e;
		}
		return c;
	}
}
