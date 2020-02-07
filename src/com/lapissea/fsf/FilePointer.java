package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.io.serialization.Content;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.util.UtilL.*;

public class FilePointer extends FileObject.FullLayout<FilePointer> implements Comparable<FilePointer>{
	
	private static final SequenceLayout<FilePointer> LAYOUT=
		FileObject.sequenceBuilder(
			new FlagDef<>((flags, p)->flags.writeEnum(p.startSize),
			              (flags, p)->p.startSize=flags.readEnum(NumberSize.class)),
			new NumberDef<>(FilePointer::getStartSize,
			                pointer->{
				                Assert(pointer.getStart() >= 0, pointer);
				                return pointer.getStart();
			                },
			                FilePointer::setStart),
			new ContentDef<>(Content.NULL_TERMINATED_STRING,
			                 FilePointer::getLocalPath,
			                 FilePointer::setLocalPath)
		                          );
	
	public final transient Header header;
	
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
		this(header, localPath, start==-1?null:NumberSize.bySize(start), start);
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
		return off<=0?null:header.getByOffset(off);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof FilePointer)) return false;
		FilePointer that=(FilePointer)o;
		return getStart()==that.getStart()&&
		       getStartSize()==that.getStartSize()&&
		       Objects.equals(getLocalPath(), that.getLocalPath());
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+getStartSize().hashCode();
		result=31*result+Long.hashCode(getStart());
		result=31*result+(getLocalPath()==null?0:getLocalPath().hashCode());
		return result;
	}
	
	public String toTableString(){
		return localPath+(" @"+start)+startSize.shortName;
	}
}
