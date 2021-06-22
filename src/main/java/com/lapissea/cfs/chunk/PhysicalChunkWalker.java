package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;

import java.io.IOException;
import java.util.Iterator;

public class PhysicalChunkWalker implements IterablePP<Chunk>{
	
	private static class ChainIter implements Iterator<Chunk>{
		
		private Chunk chunk;
		public ChainIter(Chunk chunk){
			this.chunk=chunk;
		}
		
		@Override
		public boolean hasNext(){
			return chunk!=null;
		}
		@Override
		public Chunk next(){
			Chunk c=chunk;
			if(c==null) return null;
			try{
				chunk=c.nextPhysical();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			return c;
		}
	}
	
	private Chunk start;
	public PhysicalChunkWalker(Chunk start){
		this.start=start;
	}
	
	@Override
	public Iterator<Chunk> iterator(){
		return new ChainIter(start);
	}
}
