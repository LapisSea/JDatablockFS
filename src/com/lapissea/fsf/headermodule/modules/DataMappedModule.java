package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FilePointer;
import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.SourcedChunkPointer;
import com.lapissea.fsf.collections.SparsePointerList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.util.LogUtil;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

public class DataMappedModule extends HeaderModule{
	private SparsePointerList<FilePointer> mappings;
	
	
	public DataMappedModule(Header header){
		super(header);
	}
	
	@Override
	protected int getChunkCount(){
		return 1;
	}
	
	@Override
	protected void postRead() throws IOException{
		mappings=new SparsePointerList<>(()->new FilePointer(header), getOwning().get(0));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		SparsePointerList.init(out, new SizedNumber(NumberSize.SHORT, ()->0), config.fileTablePadding);
	}
	
	public SparsePointerList<FilePointer> getMappings(){
		return Objects.requireNonNull(mappings);
	}
	
	@Override
	public Iterable<SourcedChunkPointer> getReferences() throws IOException{
		var ptrs =mappings.getReferences();
		var files=new ArrayList<SourcedChunkPointer>(ptrs.size()*2);
		
		for(int i=0;i<mappings.size();i++){
			var mapping=mappings.getElement(i);
			var ch     =ptrs.get(i).pointer.dereference(header);
			
			if(mapping==null) LogUtil.println(mappings);
			
			var ptr   =new ChunkPointer(mapping.getStart());
			var source=ch.getDataStart();
			ptrs.add(new SourcedChunkPointer(ptr, source));
		}
		
		files.addAll(ptrs);
		return files;
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
