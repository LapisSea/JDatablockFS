package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FileID;
import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.util.NotImplementedException;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.fsf.NumberSize.*;

public class FileIDsModule<Identifier> extends HeaderModule<Identifier>{
	
	private final Supplier<SizedNumber<FileID>> headerSupplier=()->new SizedNumber<>(FileID.Mutable::new, header.source::getSize);
	
	private FixedLenList<SizedNumber<FileID>, FileID> list;
	
	public FileIDsModule(Header<Identifier> header){
		super(header);
	}
	
	@Override
	protected int getOwningChunkCount(){
		return 2;
	}
	
	public FixedLenList<SizedNumber<FileID>, FileID> getList(){
		return Objects.requireNonNull(list);
	}
	
	@Override
	protected void postRead() throws IOException{
		list=new FixedLenList<>(headerSupplier, getOwning().get(0), getOwning().get(1));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		FixedLenList.init(out, new SizedNumber<FileID>(FileID.Mutable::new, BYTE, ()->200), config.freeChunkCapacity, true);
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
		throw new NotImplementedException();//TODO
//		return getList().openLinkStream(e->new ChunkPointer(e.getValue()), (old, ptr)->new ChunkPointer(ptr));
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
