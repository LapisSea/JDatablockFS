package com.lapissea.fsf;

import com.lapissea.util.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static com.lapissea.util.UtilL.*;

public class FilePointer extends FileObject implements Comparable<FilePointer>{
	
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	final Header header;
	
	private String     localPath;
	private NumberSize startSize;
	private long       start;
	
	public FilePointer(Header header, String localPath){
		this(header, localPath, null, -1);
	}
	
	public FilePointer(Header header, String localPath, long start){
		this(header, localPath, NumberSize.getBySize(start), start);
	}
	
	public FilePointer(Header header, String localPath, NumberSize startSize, long start){
		this.header=header;
		this.localPath=localPath;
		this.startSize=startSize;
		this.start=start;
	}
	
	public FilePointer(Header header){
		this(header, null);
	}
	
	public String getLocalPath(){
		return localPath;
	}
	
	public NumberSize getStartSize(){
		return startSize;
	}
	
	public long getStart(){
		return start;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		
		ByteArrayOutputStream os =new ByteArrayOutputStream(length());
		var                   buf=new ContentOutputStream.Wrapp(os);
		
		var flags=new FlagWriter(FLAGS_SIZE);
		flags.writeEnum(startSize);
		flags.export(buf);
		
		startSize.write(buf, start);
		
		Content.NULL_TERMINATED_STRING.write(buf, localPath);
		
		Assert(length()==os.size());
		os.writeTo(dest);
	}
	
	@Override
	public void read(ContentInputStream stream) throws IOException{
		
		var flags=FlagReader.read(stream, FLAGS_SIZE);
		startSize=flags.readEnum(NumberSize.class);
		
		start=startSize.read(stream);
		
		localPath=Content.NULL_TERMINATED_STRING.read(stream);
		
		LogUtil.println("read", this);
	}
	
	@Override
	public int length(){
		return FLAGS_SIZE.bytes+
		       startSize.bytes+
		       Content.NULL_TERMINATED_STRING.length(localPath);
	}
	
	@Override
	public int compareTo(FilePointer o){
		return localPath.compareTo(o.localPath);
	}
	
	public Chunk loadChunk() throws IOException{
		var off=getStart();
		return off==-1?null:header.getByOffset(off);
	}
	
}
