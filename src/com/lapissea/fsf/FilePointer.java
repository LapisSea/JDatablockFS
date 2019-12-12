package com.lapissea.fsf;

import java.io.IOException;

public class FilePointer extends FileObject implements Comparable<FilePointer>{
	
	public final Header header;
	
	private String localPath;
	private long   chunkOffset;
	
	public FilePointer(Header header, String localPath){
		this(header, localPath, -1);
	}
	
	public FilePointer(Header header, String localPath, long chunkOffset){
		this.header=header;
		this.localPath=localPath;
		this.chunkOffset=chunkOffset;
	}
	
	public FilePointer(Header header) {
		this(header, null);
	}
	
	public String getLocalPath(){
		return localPath;
	}
	
	public long getChunkOffset(){
		return chunkOffset;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		Content.NULL_TERMINATED_STRING.write(dest, localPath);
		dest.writeLong(chunkOffset);
	}
	
	@Override
	public void read(ContentInputStream stream) throws IOException{
		localPath=Content.NULL_TERMINATED_STRING.read(stream);
		chunkOffset=stream.readLong();
	}
	
	@Override
	public int length(){
		return Content.NULL_TERMINATED_STRING.length(localPath)+
		       Long.SIZE/Byte.SIZE;
	}
	
	@Override
	public int compareTo(FilePointer o){
		return localPath.compareTo(o.localPath);
	}
	
	public Chunk getChunk() throws IOException{
		var off=getChunkOffset();
		return off==-1?null:header.getByOffset(off);
	}
	
	@Override
	public String toString(){
		return "FilePointer{"+
		       "localPath='"+localPath+'\''+
		       ", chunkOffset="+chunkOffset+
		       '}';
	}
}
