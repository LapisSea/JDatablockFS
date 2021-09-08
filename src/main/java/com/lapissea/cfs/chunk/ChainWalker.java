package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class ChainWalker implements IterablePP<Chunk>{
	
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
			var c=chunk;
			if(c==null) throw new NoSuchElementException();
			chunk=c.nextUnsafe();
			return c;
		}
	}
	
	private final Chunk head;
	public ChainWalker(Chunk head){
		this.head=head;
	}
	
	@Override
	public Iterator<Chunk> iterator(){
		return new ChainIter(head);
	}
}
