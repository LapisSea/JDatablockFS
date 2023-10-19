package com.lapissea.dfs.chunk;

import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Iterator;

public class PhysicalChunkWalker implements IterablePP<Chunk>{
	
	private static class ChainIter implements Iterator<Chunk>{
		
		private Chunk       chunk;
		private IOException e;
		
		public ChainIter(Chunk chunk){
			this.chunk = chunk;
		}
		
		@Override
		public boolean hasNext(){
			return chunk != null;
		}
		@Override
		public Chunk next(){
			if(e != null){
				throw UtilL.uncheckedThrow(e);
			}
			
			Chunk c = chunk;
			if(c == null) return null;
			try{
				chunk = c.nextPhysical();
			}catch(IOException e){
				this.e = e;
			}
			return c;
		}
	}
	
	private final Chunk start;
	public PhysicalChunkWalker(Chunk start){
		this.start = start;
	}
	
	@Override
	public Iterator<Chunk> iterator(){
		return new ChainIter(start);
	}
}
