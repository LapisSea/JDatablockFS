package com.lapissea.fsf;

import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.io.FileData;
import com.lapissea.util.NotImplementedException;

public class FileID implements INumber{
	
	public static final IOList.PointerConverter<FileID> CONVERTER=new IOList.PointerConverter<>(){
		@Override
		public <Identifier> ChunkPointer get(Header<Identifier> header, FileID value){
			return header.getFileData(value).map(FileData::getPointer).orElse(null);
		}
		
		@Override
		public <Identifier> FileID set(Header<Identifier> header, FileID oldValue, ChunkPointer newPointer){
			throw NotImplementedException.infer();//TODO: implement .set()
		}
	};
	
	public static class Mutable extends FileID implements INumber.Mutable{
		
		public Mutable(){
			this(0);
		}
		
		public Mutable(long value){
			super(value);
		}
		
		@Override
		public void setValue(long value){
			this.value=value;
		}
	}
	
	protected long value;
	
	public FileID(INumber value){
		this(value.getValue());
	}
	
	public FileID(long value){
		this.value=value;
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof FileID fileID&&
		       getValue()==fileID.getValue();
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getValue());
	}
	
	@Override
	public String toString(){
		return "FileID("+value+')';
	}
}
