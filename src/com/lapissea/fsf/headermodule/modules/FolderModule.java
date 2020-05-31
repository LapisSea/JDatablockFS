package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.Folder;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.util.NotImplementedException;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FolderModule<Identifier> extends HeaderModule<Folder<Identifier>, Identifier>{
	
	
	public FolderModule(Header<Identifier> header, Consumer<Folder<Identifier>> onRead){
		super(header, onRead);
	}
	
	@Override
	public int getOwningChunkCount(){
		return 1;
	}
	
	@Override
	protected Folder<Identifier> postRead() throws IOException{
		return getOwning(0).io().readAsObject(new Folder<>(header, newF->{
			var old=getOwning(0);
			var c  =header.aloc(newF, true);
			setOwningChunk(0, c);
			header.freeChunkChain(old);
		}));
	}
	
	@Override
	public List<Chunk> init() throws IOException{
		return Folder.init(header);
	}
	
	@Override
	public long capacityManager(Chunk chunk) throws IOException{
		throw new NotImplementedException();//TODO
	}
	
	/**
	 * MUST CALL CLOSE ON STREAM
	 */
	@Override
	public Stream<ChunkLink> openReferenceStream() throws IOException{
		return getValue().openReferenceStream(getOwning(0).getDataStart());
	}
	
	@Override
	public Color displayColor(){
		return Color.ORANGE;
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return false;
	}
}
