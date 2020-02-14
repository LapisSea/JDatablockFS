package com.lapissea.fsf;

import com.lapissea.fsf.chunk.ChunkPointer;

public class FileID implements INumber{
	
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
	
	public ChunkPointer getFilePtr(Header<?> header){
		return header.getFile(this);
	}
}
