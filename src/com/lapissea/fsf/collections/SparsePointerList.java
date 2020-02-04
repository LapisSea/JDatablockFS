package com.lapissea.fsf.collections;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.ShadowChunks;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

/**
 * Serves as a wrapper for {@link FixedLenList} to convert it from a flat memory layout to a
 * pointer driven one what is greatly beneficiary for values of variable sizes but at the cost
 * of memory footprint and more random access.<br>
 * (TODO) It may be worth while exploring the idea of separating a pointer driven layout as this
 * in to a virtual sub-file contained within a chunk to concentrate the random access in to
 * a smaller area to improve seek time or/and perfecting.
 * Another solution may be to create a localised and limited chunk system that would aim to allow
 * for localised data fragmentation with minimal extra footprint optimized for smaller data sets.
 * (flags byte + size + next index instead of next offset?)
 */
public class SparsePointerList<E extends FileObject> extends IOList.Abstract<E> implements ShadowChunks{
	
	public static <H extends FileObject&FixedLenList.ElementHead<H, ?>> void init(ContentOutputStream out, H header, int initialCapacity) throws IOException{
		FixedLenList.init(NumberSize.BYTE, out, header, initialCapacity, false);
	}
	
	private final FixedLenList<SizedNumber, ChunkPointer> valuePointers;
	
	private final Map<ChunkPointer, E> cache;
	
	private final Supplier<E> constructor;
	private final Header      header;
	
	public SparsePointerList(@NotNull Supplier<E> constructor, @NotNull Chunk data) throws IOException{
		this.constructor=Objects.requireNonNull(constructor);
		header=data.header;
		cache=header.config.newCacheMap();
		this.valuePointers=new FixedLenList<>(()->new SizedNumber(header.source::getSize), data, null);
	}
	
	private void checkIntegrity() throws IOException{
		
		Map<ChunkPointer, E> disk=new LinkedHashMap<>();
		for(int i=0;i<size();i++){
			var ptr=getPtr(i);
			disk.put(ptr, readValue(ptr));
		}
		
		if(!Utils.isCacheValid(disk, cache)){
			throw new AssertionError("\n"+TextUtil.toTable("disk / cache", List.of(disk, cache)));
		}
	}
	
	private ChunkPointer getPtr(int index) throws IOException{
		return valuePointers.getElement(index);
	}
	
	private E readValue(ChunkPointer ptr) throws IOException{
		Chunk data=ptr.dereference(header);
		E     e   =constructor.get();
		try(var in=data.io().read()){
			e.read(in);
		}
		return e;
	}
	
	private E resolvePointer(ChunkPointer ptr) throws IOException{
		E cached=cache.get(ptr);
		if(!DEBUG_VALIDATION){
			if(cached!=null) return cached;
		}
		
		E read=readValue(ptr);
		
		if(DEBUG_VALIDATION){
			if(cached!=null){
				Assert(cached.equals(read), cached, read);
				return cached;
			}
		}
		
		cache.put(ptr, read);
		return read;
	}
	
	@Override
	public E getElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		return Objects.requireNonNull(resolvePointer(getPtr(index)));
	}
	
	@Override
	public void addElement(E e) throws IOException{
		Objects.requireNonNull(e);
		
		if(DEBUG_VALIDATION) checkIntegrity();
		
		valuePointers.ensureElementCapacity(valuePointers.size()+1);
		
		var chunk=header.aloc(e.length(), false);
		try(var out=chunk.io().write(true)){
			e.write(out);
		}
		
		cache.put(new ChunkPointer(chunk), e);
		valuePointers.addElement(new ChunkPointer(chunk));
		
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	@Override
	public void setElement(int index, E e) throws IOException{
		Objects.checkIndex(index, size());
		Objects.requireNonNull(e);
		
		if(DEBUG_VALIDATION) checkIntegrity();
		
		var chunk=header.aloc(e.length(), false);
		try(var out=chunk.io().write(true)){
			e.write(out);
		}
		cache.remove(getPtr(index));
		valuePointers.setElement(index, new ChunkPointer(chunk));
		
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		if(DEBUG_VALIDATION) checkIntegrity();
		var valuePtr=getPtr(index);
		cache.remove(valuePtr);
		valuePointers.removeElement(index);
		header.freeChunk(valuePtr.dereference(header));
		
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	@Override
	public void clearElements() throws IOException{
		if(DEBUG_VALIDATION) checkIntegrity();
		cache.clear();
		valuePointers.clearElements();
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	public interface ValueMapper<E>{
		ChunkLink map(int valueIndex, ChunkPointer valuePtr, E value) throws IOException;
	}
	
	public Stream<ChunkLink> openValueLinkStream(ValueMapper<E> valueMapper){
		return IntStream.range(0, size()).mapToObj(i->{
			try{
				var valPtr=valuePointers.getElement(i);
				var val   =getElement(i);
				return valueMapper.map(i, valPtr, val);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}).limit(size());
	}
	
	public Stream<ChunkLink> openLinkStream() throws IOException{
		return valuePointers.openLinkStream(e->e, (old, val)->val);
	}
	
	@Override
	public int size(){
		return valuePointers.size();
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		return valuePointers.getShadowChunks();
	}
}
