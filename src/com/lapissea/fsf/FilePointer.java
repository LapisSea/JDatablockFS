package com.lapissea.fsf;

import com.lapissea.fsf.io.serialization.Content;
import com.lapissea.fsf.io.serialization.FileObject;

import java.util.Objects;
import java.util.function.Supplier;

public class FilePointer<Identifier> extends FileObject.FullLayout<FilePointer<Identifier>>/*implements Comparable<FilePointer<Identifier>>*/{
	
	private static final ObjectDef<FilePointer<Object>> LAYOUT=FileObject.sequenceBuilder(
		new ObjDef<>(v->v.fileId,
		             (v, f)->v.fileId=f,
		             (Supplier<SelfSizedNumber>)SelfSizedNumber::new),
		new ContentDef<>(Content.BYTE_ARRAY_SMALL,
		                 p->p.header.identifierIO.write(p.getLocalPath()),
		                 (p, v)->p.setLocalPath(p.header.identifierIO.read(v))){
			@Override
			public long length(FilePointer<Object> p){
				var size=p.header.identifierIO.size(p.getLocalPath());
				return SmallNumber.bytes(size)+size;
			}
		}
	                                                                                     );
	
	private static final SelfSizedNumber NO_ID=new SelfSizedNumber(0);
	
	public final transient Header<Identifier> header;
	
	private SelfSizedNumber fileId;
	private Identifier      localPath;
	
	public FilePointer(Header<Identifier> header){
		this(header, null);
	}
	
	public FilePointer(Header<Identifier> header, Identifier path){
		this(header, path, null);
	}
	
	public FilePointer(Header<Identifier> header, Identifier path, SelfSizedNumber fileId){
		super((ObjectDef<FilePointer<Identifier>>)((Object)LAYOUT));
		
		this.header=header;
		this.localPath=path;
		if(fileId==null) this.fileId=NO_ID;
		else this.fileId=fileId;
	}
	
	public Identifier getLocalPath(){
		return localPath;
	}
	
	private void setLocalPath(Identifier localPath){
		this.localPath=localPath;
	}
	
	public FileID getFile(){
		return fileId.getValue()==0?null:new FileID(fileId);
	}
	
	public FilePointer<Identifier> withPath(Identifier path){
		return new FilePointer<>(header, path, fileId);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof FilePointer)) return false;
		var that=(FilePointer<?>)o;
		return fileId.equals(that.fileId)&&
		       Objects.equals(getLocalPath(), that.getLocalPath());
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+fileId.hashCode();
		result=31*result+(getLocalPath()==null?0:getLocalPath().hashCode());
		return result;
	}
	
	public String toTableString(){
		return localPath+" -> "+fileId;
	}
	
}
