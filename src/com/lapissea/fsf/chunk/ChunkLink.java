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
	
	public Chunk dereferenceSource(Header header) throws IOException{
		Assert(sourceValidChunk);
		return header.getByOffset(sourcePos);
	}
	
	public void setPointer(ChunkPointer pointer) throws IOException{
		Objects.requireNonNull(pointer);
		setter.accept(pointer);
		this.pointer=pointer;
	}
	
	public boolean hasPointer(){
		return pointer!=null;
	}
	
	public Iterator<ChunkLink> linkWalker(Header header) throws IOException{
		
		class ChainWalker implements Iterator<ChunkLink>{
			ChunkLink link;
			
			public ChainWalker(ChunkLink link){
				this.link=link;
			}
			
			@Override
			public boolean hasNext(){
				return link!=null;
			}
			
			@Override
			public ChunkLink next(){
				if(!hasNext()) throw new NoSuchElementException();
				
				var old=link;
				
				if(link.hasPointer()){
					try{
						link=new ChunkLink(link.getPointer().dereference(header));
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
					}
				}else link=null;
				
				return old;
			}
		}
		
		return new ChainWalker(this);
	}
}
