package com.lapissea.cfs.chunk;

import java.util.function.Supplier;

public class ChainSupplier implements Supplier<Chunk>{
	private Chunk chunk;
	public ChainSupplier(Chunk chunk){
		this.chunk=chunk;
	}
	
	@Override
	public Chunk get(){
		Chunk c=chunk;
		if(c==null) return null;
		chunk=c.nextUnsafe();
		return c;
	}
}
