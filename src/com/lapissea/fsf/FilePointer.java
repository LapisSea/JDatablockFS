package com.lapissea.fsf;

import java.io.IOException;
import java.util.List;

public class FilePointer extends FileObject.FullLayout<FilePointer> implements Comparable<FilePointer>{
	
	private static final SequenceLayout<FilePointer> LAYOUT=
		FileObject.sequenceBuilder(List.of(
			new FlagDef<>(NumberSize.BYTE,
			              (flags, p)->flags.writeEnum(p.startSize),
			              (flags, p)->p.startSize=flags.readEnum(NumberSize.class)),
			new NumberDef<>(FilePointer::getStartSize,
			                FilePointer::getStart,
			                FilePointer::setStart),
			new ContentDef<>(Content.NULL_TERMINATED_STRING,
			                 FilePointer::getLocalPath,
			                 FilePointer::setLocalPath)
		                                  ));
	
	final Header header;
	
	private NumberSize startSize;
	private long       start;
	private String     localPath;
	
	public FilePointer(Header header){
		this(header, null);
	}
	
	public FilePointer(Header header, String localPath){
		this(header, localPath, null, -1);
	}
	
	public FilePointer(Header header, String localPath, long start){
		this(header, localPath, NumberSize.bySize(start), start);
	}
	
	public FilePointer(Header header, String localPath, NumberSize startSize, long start){
		super(LAYOUT);
		this.header=header;
		this.localPath=localPath;
		this.startSize=startSize;
		this.start=start;
	}
	
	public NumberSize getStartSize(){
		return startSize;
	}
	
	public long getStart(){
		return start;
	}
	
	private void setStart(long start){
		startSize=NumberSize.bySize(start);
		this.start=start;
	}
	
	public String getLocalPath(){
		return localPath;
	}
	
	private void setLocalPath(String localPath){
		this.localPath=localPath;
	}
	
	@Override
	public int compareTo(FilePointer o){
		return localPath.compareTo(o.localPath);
	}
	
	public Chunk dereference() throws IOException{
		var off=getStart();
		return off==-1?null:header.getByOffset(off);
	}
	
}
