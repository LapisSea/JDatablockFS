package com.lapissea.dfs.core;

import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Arrays;
import java.util.Optional;

public final class MoveBuffer{
	
	private int    size;
	private long[] buff;
	
	public MoveBuffer(){ }
	public MoveBuffer(ChunkPointer from, ChunkPointer to){
		size = 1;
		buff = new long[]{from.getValue(), to.getValue()};
	}
	
	public int getSize()    { return size; }
	public boolean isEmpty(){ return size == 0; }
	public boolean hasAny() { return size != 0; }
	
	public void add(MoveBuffer other){
		var b = other.buff;
		if(b == null) return;
		for(int i = 0, s = other.size*2; i<s; i += 2){
			add(b[i], b[i + 1]);
		}
	}
	public void add(ChunkPointer from, ChunkPointer to){
		add(from.getValue(), to.getValue());
	}
	private void add(long from, long to){
		var i = (size++)*2;
		var b = buff;
		if(b == null) buff = b = new long[2];
		else if(b.length == i + 2){
			buff = b = Arrays.copyOf(b, b.length*2);
		}
		b[i] = from;
		b[i + 1] = to;
	}
	
	public boolean chainAffected(Chunk start){
		return start.walkNext().anyMatch(c -> moved(c.getPtr()));
	}
	
	public boolean moved(ChunkPointer from){
		var val = from.getValue();
		var b   = buff;
		for(int i = 0, s = size*2; i<s; i += 2){
			if(b[i] == val || b[i + 1] == val){
				return true;
			}
		}
		return false;
	}
	public Optional<ChunkPointer> toDest(ChunkPointer fromPtr){
		var val = fromPtr.getValue();
		
		var b = buff;
		for(int i = 0, s = size*2; i<s; i += 2){
			long from = b[i], to = b[i + 1];
			if(from == val){
				return Optional.of(ChunkPointer.of(to));
			}
		}
		return Optional.empty();
	}
	
	@Override
	public String toString(){
		return Iters.rangeMap(0, size, i -> i*2).map(p -> buff[p] + "->" + buff[p + 1])
		            .joinAsStr(", ", "[", "]");
	}
}
