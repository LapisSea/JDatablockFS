package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.*;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.MutableChunkPointer;
import com.lapissea.fsf.collections.SparsePointerList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

public class DataMappedModule<Identifier> extends HeaderModule<Identifier>{
	
	private SparsePointerList<FilePointer<Identifier>> mappings;
	
	public DataMappedModule(Header<Identifier> header){
		super(header);
	}
	
	@Override
	protected int getOwningChunkCount(){
		return 1;
	}
	
	@Override
	protected void postRead() throws IOException{
		mappings=new SparsePointerList<>(()->new FilePointer<>(header), getOwning().get(0));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		SparsePointerList.init(out, new SizedNumber<>(MutableChunkPointer::new, NumberSize.BYTE, ()->0), config.fileTablePadding);
	}
	
	@Override
	public long capacityManager(Chunk chunk) throws IOException{
		long[] siz={0}, cap={0};
		chunk.calculateWholeChainSizes(siz, cap);
		return siz[0]*3/2;
	}
	
	public SparsePointerList<FilePointer<Identifier>> getMappings(){
		return Objects.requireNonNull(mappings);
	}
	
	@Override
	public Stream<ChunkLink> openReferenceStream() throws IOException{
		return Stream.concat(
			mappings.openLinkStream(),
			mappings.openValueLinkStream((int valueIndex, ChunkPointer valuePtr, FilePointer<Identifier> value)->{
				FileID fileId=value.getFile();
				if(fileId==null) return null;
				
				var chunk=valuePtr.dereference(header);
				
				var filePtr=fileId.getFilePtr(header);
				var source =chunk.getDataStart();
				
				return new ChunkLink(false, filePtr, source, p->{
					var newVal=new FilePointer<>(value.header, value.getLocalPath(), new SelfSizedNumber(p.getValue()));
					mappings.setElement(valueIndex, newVal);
				});
			}).filter(Objects::nonNull)
		                    );
	}
	
	@Override
	public Color displayColor(){
		return Color.GREEN.darker();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return false;
	}
}
