package com.lapissea.fsf.chunk;

import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public class ChunkChain extends AbstractList<Chunk>{
	
	private long[]  capacityOffsets=new long[2];
	private Chunk[] chunks         =new Chunk[2];
	private int     size;
	
	Set<Runnable> dependencyInvalidate=new HashSet<>(3);
	
	public ChunkChain(Chunk root){
		Objects.requireNonNull(root);
		addChunk(root);
	}
	
	private class InvRef implements Runnable{
		Chunk     c;
		Throwable t=new Throwable("local stack reference");
		
		public InvRef(Chunk ch){
			this.c=ch;
		}
		
		@Override
		public void run(){
			invalidate();
		}
		
		@Override
		public String toString(){
			return "\nREFERENCE\n"+Arrays.stream(t.getStackTrace()).map(Objects::toString).collect(Collectors.joining("\n"));
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			if(!(o instanceof InvRef)) return false;
			InvRef runnable=(InvRef)o;
			return Objects.equals(c, runnable.c);
		}
		
		@Override
		public int hashCode(){
			return Objects.hash(c);
		}
	}
	
	private void addChunk(Chunk chunk){
		
		if(size >= chunks.length){
			var newSiz=size<<1;
			chunks=Arrays.copyOf(chunks, newSiz);
			capacityOffsets=Arrays.copyOf(capacityOffsets, newSiz);
		}
		
		if(size==0) capacityOffsets[size]=0;
		else{
			var prev=lastIndex();
			capacityOffsets[size]=capacityOffsets[prev]+chunks[prev].getCapacity();
		}
		
		chunks[size]=chunk;
		size++;
		
		
		chunk.dependencyInvalidate.add(new InvRef(chunk));
	}
	
	private int lastIndex(){
		return size-1;
	}
	
	private void invalidate(){
		if(size==1) return;
		for(int i=1;i<size;i++){
			var chunk=chunks[i];
			chunk.dependencyInvalidate.remove(new InvRef(chunk));
			chunks[i]=null;
		}
		size=1;
		
		for(var r : dependencyInvalidate){
			r.run();
		}
	}
	
	private Chunk getRoot(){
		return chunks[0];
	}
	
	private Chunk getLast() throws IOException{
		readAll();
		return getLastUnsafe();
	}
	
	private Chunk getLastUnsafe(){
		return chunks[lastIndex()];
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
	
	public void readAll() throws IOException{
		var next=getLastUnsafe();
		
		while(true){
			next=next.nextChunk();
			if(next==null) return;
			addChunk(next);
		}
	}
	
	public long getChainSpaceSizeOffset(int index) throws IOException{
		readAll();
		Objects.checkIndex(index, size);
		
		return Arrays.stream(chunks)
		             .limit(index)
		             .mapToLong(Chunk::getSize)
		             .sum();
	}
	
	public long getChainSpaceCapacityOffset(int index){
		Objects.checkIndex(index, size);
		return capacityOffsets[index];
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
				var last=getLastUnsafe();
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
		
		long oldCapacity;
		if(DEBUG_VALIDATION){
			oldCapacity=getTotalCapacity();
		}
		
		Chunk root=getRoot(), last=getLast();
		last.header.requestMemory(root, last, amount);
		
		if(DEBUG_VALIDATION){
			var expectedCapacity=oldCapacity+amount;
			Assert(getTotalCapacity() >= expectedCapacity, "Failed to grow chain", oldCapacity+"+"+amount+"="+expectedCapacity+" > "+getTotalCapacity(), this);
		}
	}
	
	public long getTotalSize() throws IOException{
		return getChainSpaceSizeOffset(size-1)+getLastUnsafe().getSize();
	}
	
	public long getTotalCapacity() throws IOException{
		readAll();
		return capacityOffsets[lastIndex()]+getLastUnsafe().getCapacity();
	}
	
	@Override
	public String toString(){
		return "["+stream().map(Chunk::toShortString).collect(Collectors.joining(", "))+"]";
	}
}
