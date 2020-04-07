package com.lapissea.fsf;

import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.io.serialization.Content;
import com.lapissea.fsf.io.serialization.FileObject;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class FileTag<Identifier> extends FileObject.FullLayout<FileTag<Identifier>>/*implements Comparable<FilePointer<Identifier>>*/{
	
	private static final IOList.PointerConverter<FileTag<?>> ID_CONVERTER=new IOList.PointerConverter<>(){
		@Override
		public <I> ChunkPointer get(Header<I> header, FileTag<?> value){
			var f=value.getFileID();
			if(f.isEmpty()) return null;
			return FileID.CONVERTER.get(header, f.get());
		}
		
		@Override
		public <I> FileTag<?> set(Header<I> header, FileTag<?> oldValue, ChunkPointer newPointer){
			throw new UnsupportedOperationException();
		}
	};
	
	@SuppressWarnings("unchecked")
	public static <Identifier> IOList.PointerConverter<FileTag<Identifier>> idConverter(){
		return (IOList.PointerConverter<FileTag<Identifier>>)(Object)ID_CONVERTER;
	}
	
	private static final ObjectDef<FileTag<Object>> LAYOUT=FileObject.sequenceBuilder(
		new ObjDef<>(v->v.fileId,
		             (v, f)->v.fileId=f,
		             (Supplier<SelfSizedNumber>)SelfSizedNumber::new),
		new ContentDef<>(Content.BYTE_ARRAY_SMALL,
		                 p->p.header.identifierIO.write(p.getPath()),
		                 (p, v)->p.setPath(p.header.identifierIO.read(v))){
			@Override
			public long length(FileTag<Object> p){
				var size=p.header.identifierIO.size(p.getPath());
				return SmallNumber.bytes(size)+size;
			}
		}
	                                                                                 );
	
	public final transient Header<Identifier> header;
	
	private SelfSizedNumber fileId;
	private Identifier      path;
	
	public FileTag(Header<Identifier> header){
		this(header, null);
	}
	
	public FileTag(Header<Identifier> header, Identifier path){
		this(header, path, null);
	}
	
	@SuppressWarnings("unchecked")
	public FileTag(Header<Identifier> header, Identifier path, FileID fileId){
		super((ObjectDef<FileTag<Identifier>>)((Object)LAYOUT));
		
		this.header=header;
		this.path=path;
		this.fileId=new SelfSizedNumber(fileId==null?new FileID(0):fileId);
	}
	
	public Identifier getPath(){
		return path;
	}
	
	private void setPath(Identifier path){
		this.path=path;
	}
	
	public Optional<FileID> getFileID(){
		return fileId.getValue()==0?Optional.empty():Optional.of(new FileID(fileId));
	}
	
	public FileTag<Identifier> withName(Identifier name){
		return new FileTag<>(header, name, getFileID().orElseThrow());
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof FileTag<?> that&&
		       fileId.equals(that.fileId)&&
		       Objects.equals(getPath(), that.getPath());
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+fileId.hashCode();
		result=31*result+(getPath()==null?0:getPath().hashCode());
		return result;
	}
	
	@Override
	public String toString(){
		return fileId+": "+path;
	}
	
	public FileTag<Identifier> withID(FileID fileID){
		return new FileTag<>(header, path, fileID);
	}
}
