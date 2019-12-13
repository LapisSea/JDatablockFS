package com.lapissea.fsf;

import java.io.IOException;

public class FilePointer extends FileObject implements Comparable<FilePointer>{
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	public final Header header;
	
	private String     localPath;
	private NumberSize offsetType;
	private long       chunkOffset;
	
	public FilePointer(Header header, String localPath){
		this(header, localPath, null, -1);
	}
	
	public FilePointer(Header header, String localPath, long chunkOffset){
		this(header, localPath, NumberSize.getBySize(chunkOffset), chunkOffset);
	}
	
	public FilePointer(Header header, String localPath, NumberSize offsetType, long chunkOffset){
		this.header=header;
		this.localPath=localPath;
		this.offsetType=offsetType;
		this.chunkOffset=chunkOffset;
	}
	
	public FilePointer(Header header){
		this(header, null);
	}
	
	public String getLocalPath(){
		return localPath;
	}
	
	public NumberSize getOffsetType(){
		return offsetType;
	}
	
	public long getChunkOffset(){
		return chunkOffset;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		
		var flags=new FlagWriter(FLAGS_SIZE);
		flags.writeEnum(offsetType);
		flags.export(dest);
		
		offsetType.write(dest, chunkOffset);
		
		Content.NULL_TERMINATED_STRING.write(dest, localPath);
	}
	
	@Override
	public void read(ContentInputStream stream) throws IOException{
		
		var flags=FlagReader.read(stream, FLAGS_SIZE);
		offsetType=flags.readEnum(NumberSize.class);
		
		chunkOffset=offsetType.read(stream);
		
		localPath=Content.NULL_TERMINATED_STRING.read(stream);
	}
	
	@Override
	public int length(){
		return FLAGS_SIZE.bytes+
		       offsetType.bytes+
		       Content.NULL_TERMINATED_STRING.length(localPath);
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
