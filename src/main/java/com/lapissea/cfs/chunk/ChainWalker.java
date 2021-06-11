package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;

import java.util.Iterator;

public class ChainWalker implements IterablePP<Chunk>{
	
	private static class ChainIter implements Iterator<Chunk>{
		
		private Chunk chunk;
		public ChainIter(Chunk chunk){
			this.chunk=chunk;
		}
		
		@Override
		public boolean hasNext(){
			return chunk.hasNextPtr();
		}
		@Override
		public Chunk next(){
			Chunk c=chunk;
			if(c==null) return null;
			chunk=c.nextUnsafe();
			return c;
		}
	}
	
	private Chunk head;
	public ChainWalker(Chunk head){
		this.head=head;
	}
	
	@Override
	public Iterator<Chunk> iterator(){
		return new ChainIter(head);
	}
}
