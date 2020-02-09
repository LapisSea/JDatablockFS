package com.lapissea.fsf.collections;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.io.ContentInputStream;
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
@SuppressWarnings("AutoBoxing")
public class SparsePointerList<E extends FileObject> extends IOList.Abstract<E>{
	
	public static <H extends FileObject&FixedLenList.ElementHead<H, ?>> void init(ContentOutputStream out, H header, int initialCapacity) throws IOException{
		FixedLenList.init(NumberSize.BYTE, out, header, initialCapacity, false);
	}
	
	private final FixedLenList<SizedNumber, ChunkPointer> valuePointers;
	
	private final Map<Integer, E> cache;
	
	private final Supplier<E> constructor;
	private final Header      header;
	
	public SparsePointerList(@NotNull Supplier<E> constructor, @NotNull Chunk data) throws IOException{
		this.constructor=Objects.requireNonNull(constructor);
		header=data.header;
		cache=header.config.newCacheMap();
		this.valuePointers=new FixedLenList<>(()->new SizedNumber(header.source::getSize), data, null);
	}
	
	private void checkIntegrity() throws IOException{
		
		Map<Integer, E> disk=new LinkedHashMap<>();
		for(int i=0;i<size();i++){
			disk.put(i, readValue(getPtr(i)));
		}
		
		if(!Utils.isCacheValid(disk, cache)){
			throw new AssertionError("\n"+TextUtil.toTable("disk / cache", List.of(disk, cache)));
		}
	}
	
	private ChunkPointer getPtr(int index) throws IOException{
		return valuePointers.getElement(index);
	}
	
	private E readValue(int index) throws IOException{
		return readValue(getPtr(index));
	}
	
	private E readValue(ChunkPointer ptr) throws IOException{
		Chunk data=ptr.dereference(header);
		E     e   =constructor.get();
		try(var in=data.io().read()){
			e.read(in);
		}
		return e;
	}
	
	private E resolvePointer(int index) throws IOException{
		E cached=cache.get(index);
		if(!DEBUG_VALIDATION){
			if(cached!=null) return cached;
		}
		
		E read=readValue(index);
		
		if(DEBUG_VALIDATION){
			if(cached!=null){
				if(!cached.equals(read)){
					checkIntegrity();
					throw new AssertionError("cache mismatch:\n"+TextUtil.toTable("disk / cache", read, cached));
				}
				return cached;
			}
		}
		
		cache.put(index, read);
		return read;
	}
	
	@Override
	public E getElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		return Objects.requireNonNull(resolvePointer(index));
	}
	
	private void writeToChunk(E e, Chunk chunk) throws IOException{
		try(var out=chunk.io().write(true)){
			e.write(out);
		}
		
		if(DEBUG_VALIDATION){
			var read=readValue(chunk.reference());
			Assert(read.equals(e), read, e);
		}
	}
	
	@Override
	public void addElement(E e) throws IOException{
		Objects.requireNonNull(e);
		
		if(DEBUG_VALIDATION) checkIntegrity();
		valuePointers.ensureElementCapacity(valuePointers.size()+1);
		
		var chunk=header.aloc(e.length(), false);
		writeToChunk(e, chunk);
		
		if(DEBUG_VALIDATION){
			var read=readValue(chunk.reference());
			if(!read.equals(e)) throw new AssertionError("\n"+TextUtil.toTable("e / read", e, read));
		}
		
		cache.put(valuePointers.size(), e);
		valuePointers.addElement(new ChunkPointer(chunk));
		
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	
	@Override
	public void setElement(int index, E e) throws IOException{
		Objects.checkIndex(index, size());
		Objects.requireNonNull(e);
		
		if(DEBUG_VALIDATION) checkIntegrity();
		
		var oldPtr=getPtr(index);
		
		var old=getElement(index);
		if(old!=e){
			if(old.equals(e)){
				var cached=cache.get(index);
				
				byte[] bb =new byte[(int)e.length()];
				var    buf=new ContentOutputStream.BA(bb);
				cached.read(new ContentInputStream.BA(bb));
				return;
			}
		}else{
			E read=readValue(oldPtr);
			if(read.equals(e)) return;
		}
		
		var oldChunk=getPtr(index).dereference(header);
		
		if(oldChunk.getSize()==e.length()){
			writeToChunk(e, oldChunk);
			cache.put(index, e);
		}else{
			
			valuePointers.ensureElementCapacity(valuePointers.size()+1);
			
			var chunk=header.aloc(e.length(), false);
			writeToChunk(e, chunk);
			
			cache.remove(index);
			valuePointers.setElement(index, new ChunkPointer(chunk));
			cache.put(index, e);
			
			header.freeChunkChain(oldChunk);
		}
		
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		if(DEBUG_VALIDATION) checkIntegrity();
		var valuePtr=getPtr(index);
		cache.remove(index);
		valuePointers.removeElement(index);
		header.freeChunkChain(valuePtr.dereference(header));
		
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
