package com.lapissea.fsf;

import com.lapissea.util.*;
import com.lapissea.util.function.FunctionOI;

import java.io.EOFException;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.lapissea.fsf.FileSystemInFile.*;

public class OffsetIndexSortedList<T extends FileObject&Comparable<T>> extends AbstractList<T> implements ShadowChunks{
	
	public static void init(ContentOutputStream out, long[] pos, int size) throws IOException{
		int ratio=5;
		int ss   =FILE_TABLE_PADDING/ratio;
		int fs   =FILE_TABLE_PADDING-ss;
		
		Chunk.init(out, pos[0], NumberSize.SHORT, fs);
		Chunk.init(out, pos[0], NumberSize.SHORT, ss);
	}
	
	private static final int OFFSETS_PER_CHUNK=8;
	private static final int OFFSET_CHUNK_SIZE=OFFSETS_PER_CHUNK*(Long.BYTES);
	
	private final Supplier<T> tConstructor;
	
	private final ChunkIO objectsIo;
	private final ChunkIO offsetsIo;
	private       int     size;
	
	private NumberSize sizePerOffset;
	
	private final Map<Integer, long[]> offsetCache=new WeakValueHashMap<Integer, long[]>().defineStayAlivePolicy(3);
	private final Map<Long, T>         objectCache=new WeakValueHashMap<Long, T>().defineStayAlivePolicy(3);
	
	public OffsetIndexSortedList(Supplier<T> tConstructor, Chunk objects, Chunk offsets) throws IOException{
		this.tConstructor=tConstructor;
		objectsIo=objects.io();
		offsetsIo=offsets.io();
		
		try(var io=offsetsIo.read()){
			this.size=io.readInt();
			this.sizePerOffset=NumberSize.ordinal(io.readByte());
		}catch(EOFException e){
			this.size=0;
//			this.sizePerOffset=NumberSize.BYTE;
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
		var newSize=objects.size();
		
		int[] orderIndex=IntStream.range(0, newSize)
		                          .boxed()
		                          .sorted(Comparator.comparing(objects::get))
		                          .mapToInt(Integer::intValue)
		                          .toArray();
		
		long[] computedOffsets=new long[newSize];
		
		long off=0;
		for(int i=0;i<newSize;i++){
			T o=objects.get(i);
			computedOffsets[i]=off;
			off+=o.length();
		}
		
		sizePerOffset=NumberSize.getBySize(off);
		
		
		LogUtil.println(newSize);
		LogUtil.println(offsetsIo.readAll());
		
		offsetCache.clear();
		try(var io=offsetsIo.write(true)){
//			io.writeInt(newSize);
//			LogUtil.println(offsetsIo.readAll());
			io.writeByte(0xFF);
			LogUtil.println(offsetsIo.readAll());
			io.writeByte(100);
			LogUtil.println(offsetsIo.readAll());
			byte[] b=new byte[8];
			for(int i=0;i<b.length;i++){
				b[i]=(byte)(i);
			}
			io.write(b);
			LogUtil.println(offsetsIo.readAll());
			offsetsIo.setSize(7);
			LogUtil.println(offsetsIo.readAll());
			offsetsIo.setSize(15);
			LogUtil.println(offsetsIo.readAll());
			
			System.exit(0);
			
			for(int index : orderIndex){
				sizePerOffset.write(io, computedOffsets[index]);
			}
		}
		
		objectCache.clear();
		try(var io=objectsIo.write(true)){
			for(int index : orderIndex){
				objects.get(index).write(io);
			}
		}
		size=objects.size();
	}
	
	
	public void addElement(T obj) throws IOException{
		//todo make partial update implementation
		fullReadUnknownModify(1, l->l.add(obj));
	}
	
	@Override
	@Deprecated
	public boolean add(T obj){
		try{
			addElement(obj);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		return true;
	}
	
	@Override
	@Deprecated
	public T get(int index){
		try{
			return getByIndex(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	@Deprecated
	public T set(int index, T element){
		try{
			return setByIndex(index, element);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public T setByIndex(int index, T element) throws IOException{
		Objects.checkIndex(index, size());
		
		T old=getByIndex(index);
		
		if(old==element||!element.equals(old)){
			
			fullReadUnknownModify(0, l->l.set(index, element));
			
			try(var io=objectsIo.read(getOffset(index))){
				old.read(io);
			}
		}
		
		return old;
	}
	
	@Override
	public T remove(int index){
		try{
			return removeElement(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public T removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		T old=getByIndex(index);
		fullReadUnknownModify(0, l->l.remove(index));
		return old;
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
	
	@SuppressWarnings("AutoBoxing")
	public T getByIndex(int index) throws IOException{
		Objects.checkIndex(index, size());
		Long offset=getOffset(index);
		return getByOffset(offset);
	}
	
	@SuppressWarnings("AutoBoxing")
	private long getOffset(Integer offsetIndex){
		var chunkIndex=offsetIndex/OFFSETS_PER_CHUNK;
		
		long[] chunk=offsetCache.computeIfAbsent(chunkIndex, ind->{
			
			int start=ind*OFFSET_CHUNK_SIZE+Integer.BYTES+Byte.BYTES;
			
			try(var io=offsetsIo.read(start)){
				long   remaining=offsetsIo.getSize()-start;
				long[] result   =new long[(int)Math.min(OFFSETS_PER_CHUNK, remaining/sizePerOffset.bytes)];
				
				for(int j=0;j<result.length;j++){
					result[j]=sizePerOffset.read(io);
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
