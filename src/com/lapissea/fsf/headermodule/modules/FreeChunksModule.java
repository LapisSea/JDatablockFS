package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.MutableChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.fsf.NumberSize.*;

public class FreeChunksModule<Identifier> extends HeaderModule<FixedLenList<SizedNumber<ChunkPointer>, ChunkPointer>, Identifier>{
	
	private final Supplier<SizedNumber<ChunkPointer>> headerSupplier=()->new SizedNumber<>(MutableChunkPointer::new, header.source::getSize);
	
	public FreeChunksModule(Header<Identifier> header, Consumer<FixedLenList<SizedNumber<ChunkPointer>, ChunkPointer>> onRead){
		super(header, onRead);
	}
	
	@Override
	public int getOwningChunkCount(){
		return 2;
	}
	
	@Override
	protected FixedLenList<SizedNumber<ChunkPointer>, ChunkPointer> postRead() throws IOException{
		return new FixedLenList<>(headerSupplier, getOwning(0), getOwning(1));
	}
	
	@Override
	public List<Chunk> init() throws IOException{
		return FixedLenList.init(header, new SizedNumber<>(MutableChunkPointer::new, BYTE, ()->200), header.config.freeChunkCapacity, true);
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
		return getValue().openLinkStream(ChunkPointer.CONVERTER);
	}
	
	@Override
	public Color displayColor(){
		return Color.BLUE.brighter();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
}
