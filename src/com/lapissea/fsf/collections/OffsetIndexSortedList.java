package com.lapissea.fsf.collections;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkIO;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.ByteBufferBackedInputStream;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.FunctionOI;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.lapissea.fsf.FileSystemInFile.*;

public class OffsetIndexSortedList<T extends FileObject&Comparable<T>> extends IOList.Abstract<T>{
	
	private static final FileObject.SequenceLayout<OffsetIndexSortedList<?>> HEADER=
		FileObject.sequenceBuilder(List.of(
			new FileObject.NumberDef<>(NumberSize.INT,
			                           OffsetIndexSortedList::size,
			                           (l, val)->l.size=(int)val),
			new FileObject.FlagDef<>(NumberSize.BYTE,
			                         (flags, l)->flags.writeEnum(l.sizePerOffset),
			                         (flags, l)->l.sizePerOffset=flags.readEnum(NumberSize.class))
		                                  ));
	
	private static final int OFFSETS_PER_CHUNK=8;
	private static final int OFFSET_CHUNK_SIZE=OFFSETS_PER_CHUNK*(Long.BYTES);
	
	public static void init(ContentOutputStream out, int size, Config config) throws IOException{
		int ratio=4;
		int ss   =config.fileTablePadding/ratio;
		int fs   =config.fileTablePadding-ss;
		
		Chunk.init(out, NumberSize.BYTE, fs);
		Chunk.init(out, NumberSize.BYTE, ss);
	}
	
	private final Supplier<T> tConstructor;
	
	private final ChunkIO objectsIo;
	private final ChunkIO offsetsIo;
	private       int     size;
	
	private NumberSize sizePerOffset;
	
	private final Map<Integer, long[]> offsetCache;
	private final Map<Long, T>         objectCache;
	
	public OffsetIndexSortedList(Supplier<T> tConstructor, Chunk objects, Chunk offsets) throws IOException{
		offsetCache=objects.header.config.newCacheMap();
		objectCache=objects.header.config.newCacheMap();
		
		this.tConstructor=tConstructor;
		objectsIo=objects.io();
		offsetsIo=offsets.io();
		
		try(var io=offsetsIo.read()){
			HEADER.read(io, this);
		}catch(EOFException e){
			this.size=0;
			this.sizePerOffset=NumberSize.BYTE;
		}
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		return List.of(objectsIo.getRoot(), offsetsIo.getRoot());
	}
	
	
	@SuppressWarnings({"AutoBoxing"})
	private void fullReadUnknownModify(int expectedSizeChange, Consumer<List<T>> modifier) throws IOException{
		
		List<T> objects=new ArrayList<>(size()+expectedSizeChange);
		{
			long[] offsets=new long[size];
			
			for(int i=0;i<size();i++){
				offsets[i]=getOffset(i);
			}
			
			for(long offset : offsets){
				objects.add(getByOffset(offset));
			}
		}
		
		modifier.accept(objects);
		objects.sort(Comparable::compareTo);
		
		var newSize   =objects.size();
		var newOffsets=new long[newSize];
		
		long off=0;
		for(int i=0;i<newSize;i++){
			T o=objects.get(i);
			newOffsets[i]=off;
			off+=o.length();
		}
		
		sizePerOffset=NumberSize.bySize(off);
		size=newSize;
		
		offsetCache.clear();
		objectCache.clear();
		
		for(int i=0;i<newSize;i++){
			objectCache.put(newOffsets[i], objects.get(i));
		}
		
		var offCounter=0;
		var ind       =0;
		while(offCounter<newSize){
			int numCount=Math.min(OFFSETS_PER_CHUNK, newSize-offCounter);
			offsetCache.put(ind, Arrays.copyOfRange(newOffsets, offCounter, offCounter+numCount));
			offCounter+=numCount;
			ind++;
		}
		
		try(var out=offsetsIo.write(true)){
			HEADER.write(out, this);
			sizePerOffset.write(out, newOffsets);
		}
		
		try(var io=objectsIo.write(true)){
			for(var o : objects){
				o.write(io);
			}
		}
	}
	
	@Override
	public void addElement(T obj) throws IOException{
		//TODO Performance - make partial update implementation
		fullReadUnknownModify(1, l->l.add(obj));
	}
	
	@Override
	public void setElement(int index, T element) throws IOException{
		Objects.checkIndex(index, size());
		
		T old=getElement(index);
		
		if(old==element||!element.equals(old)){
			
			fullReadUnknownModify(0, l->l.set(index, element));
			
			//noinspection AutoBoxing
			try(var io=objectsIo.read(getOffset(index))){
				old.read(io);
			}
		}
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		T old=getElement(index);
		fullReadUnknownModify(0, l->l.remove(index));
	}
	
	@Override
	public int size(){
		return size;
	}
	
	private T getByOffset(Long offset) throws IOException{
		var cached=objectCache.get(offset);
		if(cached!=null) return cached;
		
		T read=tConstructor.get();
		try(var io=objectsIo.read(offset)){
			read.read(io);
		}
		objectCache.put(offset, read);
		
		return read;
	}
	
	@Override
	@SuppressWarnings("AutoBoxing")
	public T getElement(int index) throws IOException{
		long offset=getOffset(index);
		return getByOffset(offset);
	}
	
	private long[] loadOffsetChunk(long ind) throws IOException{
		long byteStart     =ind*OFFSET_CHUNK_SIZE+HEADER.length(this);
		long remainingBytes=offsetsIo.getSize()-byteStart;
		
		int numCount=(int)Math.min(OFFSETS_PER_CHUNK, remainingBytes/sizePerOffset.bytes);
		
		var buff=ByteBuffer.allocate(numCount*sizePerOffset.bytes);
		
		offsetsIo.read(byteStart, buff.array());
		
		long[] result=new long[numCount];
		try(var in=new ContentInputStream.Wrapp(new ByteBufferBackedInputStream(buff))){
			for(int i=0;i<result.length;i++){
				result[i]=sizePerOffset.read(in);
			}
		}
		
		return result;
	}
	
	@SuppressWarnings("AutoBoxing")
	public long getOffset(Integer index) throws IOException{
		Objects.checkIndex(index, size());
		int chunkIndex=index/OFFSETS_PER_CHUNK;
		
		long[] chunk=offsetCache.get(chunkIndex);
		if(chunk==null){
			offsetCache.put(chunkIndex, chunk=loadOffsetChunk(chunkIndex));
		}
		
		return chunk[index-chunkIndex*OFFSETS_PER_CHUNK];
	}
	
	public T findSingle(FunctionOI<T> comparator) throws IOException{
		int index=findSingleIndex(comparator);
		return index==-1?null:getElement(index);
	}
	
	public T findSingle(T toFind) throws IOException{
		return findSingle(toFind, null);
	}
	
	public T findSingle(T toFind, Comparator<T> comparator) throws IOException{
		int index=findSingleIndex(toFind, comparator);
		return index==-1?null:getElement(index);
	}
	
	public int findSingleIndex(T toFind, Comparator<T> comparator) throws IOException{
		Comparator<T> comp=comparator==null?Comparable::compareTo:comparator;
		return findSingleIndex(midVal->comp.compare(midVal, toFind));
	}
	
	public int findSingleIndex(FunctionOI<T> comparator) throws IOException{
		
		int fromIndex=0;
		int toIndex  =size();
		
		
		int low =fromIndex;
		int high=toIndex-1;
		
		while(low<=high){
			int mid   =(low+high) >>> 1;
			T   midVal=getElement(mid);
			
			int cmp=comparator.apply(midVal);
			if(cmp<0) low=mid+1;
			else if(cmp>0) high=mid-1;
			else return mid; // key found
		}
		return -1;//-(low + 1);  // key not found.
	}
	
	@NotNull
	@Override
	public Iterator<T> iterator(){
		return new Iterator<>(){
			int index;
			
			@Override
			public boolean hasNext(){
				return index<size();
			}
			
			@Override
			public T next(){
				try{
					return getElement(index++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
	
	public long capacity() throws IOException{
		return objectsIo.getCapacity();
	}
	
	public boolean ensureCapacity(long capacity) throws IOException{
		var cap=capacity();
		if(cap >= capacity) return false;
		objectsIo.setCapacity(capacity);
		return true;
	}
	
	@Override
	public void clearElements() throws IOException{
		objectCache.clear();
		offsetCache.clear();
		
		sizePerOffset=NumberSize.BYTE;
		size=0;
		
		try(var out=offsetsIo.write(true)){
			HEADER.write(out, this);
		}
		objectsIo.setCapacity(0);
	}
}
