package com.lapissea.fsf;

import com.lapissea.util.LogUtil;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.FunctionOI;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.lapissea.fsf.FileSystemInFile.*;

public class OffsetList<T extends FileObject&Comparable<T>> extends AbstractCollection<T> implements ShadowChunks{
	
	public static void init(ContentOutputStream out, long[] pos, int size) throws IOException{
		int ratio=5;
		int ss   =FILE_TABLE_PADDING/ratio;
		int fs   =FILE_TABLE_PADDING-ss;
		
		new Chunk(null, pos[0],NumberSize.SHORT ,fs).init(out);
		new Chunk(null, pos[0],NumberSize.SHORT, ss).init(out);
	}
	
	private static final int OFFSETS_PER_CHUNK=8;
	private static final int OFFSET_CHUNK_SIZE=OFFSETS_PER_CHUNK*(Long.BYTES);
	
	private final Supplier<T> tConstructor;
	
	private final ChunkIO objectsIo;
	private final ChunkIO offsetsIo;
	private       int     size;
	
	private final Map<Integer, long[]> offsetCache=new WeakValueHashMap<Integer, long[]>().defineStayAlivePolicy(3);
	private final Map<Long, T>         objectCache=new WeakValueHashMap<Long, T>().defineStayAlivePolicy(3);
	
	public OffsetList(Supplier<T> tConstructor, Chunk objects, Chunk offsets) throws IOException{
		this.tConstructor=tConstructor;
		objectsIo=objects.io();
		offsetsIo=offsets.io();
		
		try(var io=offsetsIo.read()){
			this.size=io.readInt();
		}catch(EOFException e){
			this.size=0;
		}
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		return List.of(objectsIo.getRoot(), offsetsIo.getRoot());
	}
	
	@SuppressWarnings({"unchecked", "AutoBoxing"})
	private void fullReadSortImpl(T obj) throws IOException{
		var    siz    =size();
		var    newSiz =siz+1;
		long[] offsets=new long[newSiz];
		T[]    objects=(T[])Array.newInstance(obj.getClass(), newSiz);
		
		for(int i=0;i<siz;i++){
			offsets[i]=getOffset(i);
		}
		for(int i=0;i<siz;i++){
			objects[i]=getByOffset(offsets[i]);
		}
		
		offsets[siz]=siz==0?0:offsets[siz-1]+objects[siz-1].length();
		objects[siz]=obj;
		
		int[] orderIndex=IntStream.range(0, objects.length)
		                          .boxed()
		                          .sorted(Comparator.comparing(i->objects[i]))
		                          .mapToInt(Integer::intValue)
		                          .toArray();
		
		
		size=newSiz;
		
		offsetCache.clear();
		try(var io=offsetsIo.write()){
			io.writeInt(newSiz);
			for(int index : orderIndex){
				io.writeLong(offsets[index]);
			}
		}
		
		objectCache.clear();
		try(var io=objectsIo.write()){
			for(int index : orderIndex){
				objects[index].write(io);
			}
		}
	}
	
	@Override
	public boolean add(T obj){
		try{
			addElement(obj);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		return true;
	}
	
	public void addElement(T obj) throws IOException{
		//todo make partial update implementation
		fullReadSortImpl(obj);
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
		}catch(EOFException e){
			LogUtil.println(offset, objectsIo.getRoot().header.source.size());
			throw e;
		}
		objectCache.put(offset, read);
		
		return read;
	}
	
	@SuppressWarnings("AutoBoxing")
	public T getByIndex(int index) throws IOException{
		Long offset=getOffset(index);
		return getByOffset(offset);
	}
	
	@SuppressWarnings("AutoBoxing")
	private long getOffset(Integer offsetIndex){
		
		var chunkIndex=offsetIndex/OFFSETS_PER_CHUNK;
		
		long[] chunk=offsetCache.computeIfAbsent(chunkIndex, ind->{
			
			int start=ind*OFFSET_CHUNK_SIZE+Integer.SIZE/Byte.SIZE;
			
			try(var io=offsetsIo.read(start)){
				
				long[] result=new long[(int)Math.min(OFFSETS_PER_CHUNK, (offsetsIo.size()-start)/Long.BYTES)];
				
				for(int j=0;j<result.length;j++){
					result[j]=io.readLong();
				}
				
				return result;
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		});
		
		return chunk[offsetIndex-chunkIndex*OFFSETS_PER_CHUNK];
	}
	
	public T findSingle(FunctionOI<T> comparator) throws IOException{
		int index=findSingleIndex(comparator);
		return index==-1?null:getByIndex(index);
	}
	
	public T findSingle(T toFind) throws IOException{
		return findSingle(toFind, null);
	}
	
	public T findSingle(T toFind, Comparator<T> comparator) throws IOException{
		int index=findSingleIndex(toFind, comparator);
		return index==-1?null:getByIndex(index);
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
			T   midVal=getByIndex(mid);
			
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
					return getByIndex(index++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
	
}
