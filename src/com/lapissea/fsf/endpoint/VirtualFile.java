package com.lapissea.fsf.endpoint;

import com.lapissea.fsf.FileTag;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.io.FileData;
import com.lapissea.fsf.io.RandomIO;

import java.io.IOException;
import java.util.Optional;

public class VirtualFile<Identifier> implements IFile<Identifier>{
	
	private FileTag<Identifier> source;
	
	public VirtualFile(FileTag<Identifier> pointer){
		this.source=pointer;
	}
	
	private Header<Identifier> header(){
		return source.header;
	}
	
	private FileData getData() throws IOException{
		ensureId();
		return header().getFileData(source)
		               .orElseThrow(()->new IllegalStateException("File "+source.getPath()+" does not exist"));
	}
	
	private void ensureId() throws IOException{
		if(source.getFileID().isPresent()) return;
		
		Optional<IOList.Ref<FileTag<Identifier>>> opt=header().getFilePtrByPath(source.getPath());
		var                                       ref=opt.orElseThrow(()->new IllegalStateException("File "+source.getPath()+" does not exist"));
		
		if(!ref.get().equals(source)){
			source=ref.get();
			ensureId();
			return;
		}
		
		source=header().alocIDToTag(ref, 0);
	}
	
	@Override
	public Identifier getPath(){
		return source.getPath();
	}
	
	@Override
	public long getSize() throws IOException{
		return getData().getSize();
	}
	
	@Override
	public boolean delete() throws IOException{
		if(!exists()) return false;
		header().deleteFile(source.getPath());
		return true;
	}
	
	@Override
	public RandomIO randomIO(RandomIO.Mode mode) throws IOException{
		var io=getData().doRandom();
		return mode.canWrite?io:RandomIO.readOnly(io);
	}
	
	@Override
	public boolean rename(Identifier newId) throws IOException{
		var renamed=header().rename(getPath(), newId);
		if(!renamed) return false;
		source=header().getFilePtrByPath(newId).orElseThrow().get();
		return true;
	}
	
	@Override
	public boolean exists() throws IOException{
		return header().getFilePtrByPath(source.getPath()).isPresent();
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof VirtualFile<?> f&&
		       f.source.equals(source);
	}
	
	@Override
	public int hashCode(){
		return source.hashCode();
	}
}
