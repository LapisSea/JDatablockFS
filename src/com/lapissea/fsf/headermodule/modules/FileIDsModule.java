package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FileEntry;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.headermodule.HeaderModule;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FileIDsModule<Identifier> extends HeaderModule<IOList<FileEntry>, Identifier>{
	
	public FileIDsModule(Header<Identifier> header, Consumer<IOList<FileEntry>> onRead){
		super(header, onRead);
	}
	
	@Override
	public int getOwningChunkCount(){
		return 1;
	}
	
	@Override
	protected IOList<FileEntry> postRead() throws IOException{
		return new FixedLenList<>(()->new FileEntry.Head(header), getOwning(0));
	}
	
	@Override
	public List<Chunk> init() throws IOException{
		return FixedLenList.init(header, new FileEntry.Head(header), header.config.freeChunkCapacity, false);
	}
	
	@Override
	public long capacityManager(Chunk chunk) throws IOException{
		long[] siz={0}, cap={0};
		chunk.calculateWholeChainSizes(siz, cap);
		return siz[0]*2;
	}
	
	/**
	 * MUST CALL CLOSE ON STREAM
	 */
	@Override
	public Stream<ChunkLink> openReferenceStream() throws IOException{
		return getValue().openLinkStream(FileEntry.CONVERTER);
	}
	
	@Override
	public Color displayColor(){
		return Color.GREEN.brighter();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
}
