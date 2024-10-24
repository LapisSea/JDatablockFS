package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ChainWalker implements IterablePP<Chunk>{
	
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
			var c = chunk;
			if(c == null) throw new NoSuchElementException();
			try{
				chunk = c.next();
			}catch(IOException e){
				this.e = e;
			}
			return c;
		}
	}
	
	private final Chunk head;
	public ChainWalker(Chunk head){
		this.head = head;
	}
	
	@Override
	public Iterator<Chunk> iterator(){
		return new ChainIter(head);
	}
}
