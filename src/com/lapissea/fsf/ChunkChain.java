package com.lapissea.fsf;

import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ChunkChain extends AbstractList<Chunk>{
	
	private long[]  offsets=new long[2];
	private Chunk[] chunks =new Chunk[2];
	private int     size;
	
	Set<Runnable> dependencyInvalidate=new HashSet<>(3);
	
	public ChunkChain(Chunk root){
		Objects.requireNonNull(root);
		addChunk(root);
	}
	
	private void addChunk(Chunk chunk){
		
		if(size >= chunks.length){
			var newSiz=size<<1;
			chunks=Arrays.copyOf(chunks, newSiz);
			offsets=Arrays.copyOf(offsets, newSiz);
		}
		
		if(size==0) offsets[size]=0;
		else{
			var prev=size-1;
			offsets[size]=offsets[prev]+chunks[prev].getDataSize();
		}
		
		chunks[size]=chunk;
		size++;
		
		chunk.dependencyInvalidate.add(this::invalidate);
	}
	
	private void invalidate(){
		if(size==1) return;
		for(int i=1;i<size;i++){
			chunks[i]=null;
		}
		size=1;
		
		for(var r : dependencyInvalidate){
			r.run();
		}
	}
	
	private Chunk getLast(){
		return chunks[size-1];
	}
	
	@Override
	public int size(){
		try{
			readAll();
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		return size;
	}
	
	private void readAll() throws IOException{
		var next=getLast();
		
		while(true){
			next=next.nextChunk();
			if(next==null) return;
			addChunk(next);
		}
	}
	
	public long getChainSpaceOffset(int index){
		Objects.checkIndex(index, size);
		return offsets[index];
	}
	
	@Override
	public Chunk get(int index){
		var c=getLoading(index);
		if(c==null) throw new IndexOutOfBoundsException("\n"+TextUtil.toTable(index+" >= "+size, ArrayViewList.create(chunks, null)));
		return c;
	}
	
	private Chunk getLoading(int index){
		
		while(index >= size){
			try{
				var last=getLast();
				var next=last.nextChunk();
				if(next==null) return null;
				addChunk(next);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		Objects.checkIndex(index, size);
		return chunks[index];
	}
	
	@NotNull
	@Override
	public Iterator<Chunk> iterator(){
		return new Iterator<>(){
			int i;
			
			@Override
			public boolean hasNext(){
				return getLoading(i)!=null;
			}
			
			@Override
			public Chunk next(){
				return getLoading(i++);
			}
		};
	}
	
	public void growBy(long amount) throws IOException{
		var last=getLast();
		last.header.requestMemory(chunks[0], last, amount);
	}
	
	public long getTotalSize() throws IOException{
		readAll();
		return offsets[size-1]+chunks[size-1].getUsed();
	}
	
	public long getTotalCapacity() throws IOException{
		readAll();
		return offsets[size-1]+chunks[size-1].getDataSize();
	}
	
	@Override
	public String toString(){
		return "["+stream().map(Chunk::toShortString).collect(Collectors.joining(", "))+"]";
	}
}
