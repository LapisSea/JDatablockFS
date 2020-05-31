package com.lapissea.fsf.headermodule;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public abstract class HeaderModule<Val, Identifier>{
	
	private final Consumer<Val> onRead;
	
	private         Val                            value;
	protected final Header<Identifier>             header;
	private         List<IOList.Ref<ChunkPointer>> owning;
	
	public HeaderModule(Header<Identifier> header, Consumer<Val> onRead){
		this.onRead=onRead;
		this.header=header;
	}
	
	public void read(UnsafeSupplier<IOList.Ref<ChunkPointer>, IOException> chunkIter) throws IOException{
		preRead();
		
		var owningArr=new ArrayList<IOList.Ref<ChunkPointer>>(getOwningChunkCount());
		for(int i=0, j=getOwningChunkCount();i<j;i++){
			owningArr.add(chunkIter.get());
		}
		
		owningArr.trimToSize();
		owning=Collections.unmodifiableList(owningArr);
		
		value=postRead();
		onRead.accept(getValue());
	}
	
	public final Stream<Stream<ChunkLink>> openChainStreamUnchecked(){
		try{
			return openChainStream();
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public final Stream<Stream<ChunkLink>> openChainStream() throws IOException{
		return Stream.concat(getOwning().map(Chunk::link), openReferenceStream())
		             .map(l->l.stream(header));
	}
	
	public Chunk getOwning(int index){
		try{
			return owning.get(index).get().dereference(header);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public Stream<Chunk> getOwning(){
		return IntStream.range(0, owning.size()).mapToObj(this::getOwning);
	}
	
	protected void setOwningChunk(int index, Chunk newVal) throws IOException{
		setOwningChunk(index, newVal.reference());
	}
	
	protected void setOwningChunk(int index, ChunkPointer newVal) throws IOException{
		if(DEBUG_VALIDATION){
			Assert(!owning.get(index).get().equals(newVal));
		}
		owning.get(index).set(newVal);
	}
	
	public abstract int getOwningChunkCount();
	
	public abstract Stream<ChunkLink> openReferenceStream() throws IOException;
	
	public List<ChunkLink> buildReferences() throws IOException{
		try(var refStream=openReferenceStream()){
			return refStream.collect(Collectors.toList());
		}
	}
	
	public abstract Color displayColor();
	
	public abstract boolean ownsBinaryOnly();
	
	protected void preRead() throws IOException  {}
	
	protected abstract Val postRead() throws IOException;
	
	protected void preWrite() throws IOException {}
	
	protected void postWrite() throws IOException{}
	
	public abstract List<Chunk> init() throws IOException;
	
	public abstract long capacityManager(Chunk chunk) throws IOException;
	
	protected Val getValue(){
		return value;
	}
}
