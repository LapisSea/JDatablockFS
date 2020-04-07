package com.lapissea.fsf.chunk;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.*;

public class ChunkLink{
	
	public final boolean sourceValidChunk;
	public final long    sourcePos;
	
	private final UnsafeConsumer<ChunkPointer, IOException> setter;
	
	private ChunkPointer pointer;
	
	public ChunkLink(Chunk source){
		this(source.hasNext()?new ChunkPointer(source.getNext()):null, source, ptr->{
			try{
				source.setNext(ptr);
			}catch(BitDepthOutOfSpaceException e){
				throw new NotImplementedException(e);//TODO: how to handle this??
			}
			source.syncHeader();
		});
	}
	
	public ChunkLink(ChunkPointer pointer, Chunk source, UnsafeConsumer<ChunkPointer, IOException> setter){
		this(true, pointer, source.getOffset(), setter);
	}
	
	public ChunkLink(boolean sourceValidChunk, ChunkPointer pointer, long sourcePos){
		this(sourceValidChunk, pointer, sourcePos, o->{throw new UnsupportedOperationException();});
	}
	
	public ChunkLink(boolean sourceValidChunk, ChunkPointer pointer, long sourcePos, UnsafeConsumer<ChunkPointer, IOException> setter){
		this.sourceValidChunk=sourceValidChunk;
		Assert(sourcePos>0);
		
		this.pointer=pointer;
		this.sourcePos=sourcePos;
		this.setter=setter;
	}
	
	public ChunkPointer getPointer(){
		return pointer;
	}
	
	public ChunkPointer sourceReference(){
		Assert(sourceValidChunk);
		return new ChunkPointer(sourcePos);
	}
	
	public Chunk dereferenceSource(Header<?> header) throws IOException{
		return header.getChunk(sourceReference());
	}
	
	public void setPointer(ChunkPointer pointer) throws IOException{
		Objects.requireNonNull(pointer);
		setter.accept(pointer);
		this.pointer=pointer;
	}
	
	public boolean hasPointer(){
		return pointer!=null;
	}
	
	private ChunkLink next(Header<?> header){
		if(hasPointer()){
			try{
				return new ChunkLink(getPointer().dereference(header));
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}else return null;
	}
	
	public Stream<ChunkLink> stream(Header<?> header){
		return Stream.generate(new Supplier<ChunkLink>(){
			ChunkLink link=ChunkLink.this;
			
			@Override
			public ChunkLink get(){
				synchronized(this){
					if(link==null) return null;
					try{
						return link;
					}finally{
						link=link.next(header);
					}
				}
			}
		}).takeWhile(Objects::nonNull);
	}
	
	public Iterator<ChunkLink> iterator(Header<?> header){
		return new Iterator<>(){
			ChunkLink link=ChunkLink.this;
			
			@Override
			public boolean hasNext(){
				return link!=null;
			}
			
			@Override
			public ChunkLink next(){
				if(!hasNext()) throw new NoSuchElementException();
				try{
					return link;
				}finally{
					link=link.next(header);
				}
			}
		};
	}
}
