package com.lapissea.fsf.headermodule;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.function.UnsafeSupplier;

import java.awt.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class HeaderModule<Val, Identifier>{
	
	private final Consumer<Val> onRead;
	
	private         Val                value;
	protected final Header<Identifier> header;
	private         List<Chunk>        owning;
	
	public HeaderModule(Header<Identifier> header, Consumer<Val> onRead){
		this.onRead=onRead;
		this.header=header;
	}
	
	public void read(UnsafeSupplier<Chunk, IOException> chunkIter) throws IOException{
		preRead();
		
		var owningArr=new Chunk[getOwningChunkCount()];
		for(int i=0;i<owningArr.length;i++){
			owningArr[i]=chunkIter.get();
		}
		
		owning=ArrayViewList.create(owningArr, null);
		
		value=postRead();
		onRead.accept(getValue());
	}
	
	public void write(Consumer<Chunk> dest) throws IOException{
		preWrite();
		for(Chunk chunk : owning){
			dest.accept(chunk);
		}
		postWrite();
	}
	
	public final Stream<Iterator<ChunkLink>> openChainStream() throws IOException{
		return Stream.concat(getOwning().stream().map(Chunk::link), openReferenceStream())
		             .map(l->l.iterator(header));
	}
	
	public Chunk getOwning(int index){
		return getOwning().get(index);
	}
	
	public List<Chunk> getOwning(){
		return Objects.requireNonNull(owning);
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
