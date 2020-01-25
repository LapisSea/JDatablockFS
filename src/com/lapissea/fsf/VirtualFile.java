package com.lapissea.fsf;

import com.lapissea.util.NotNull;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static com.lapissea.util.UtilL.*;

public class VirtualFile{
	
	private final FilePointer source;
	private       ChunkIO     data;
	
	public VirtualFile(FilePointer pointer){
		this.source=pointer;
	}
	
	public String getPath(){
		return source.getLocalPath();
	}
	
	public String readAllString() throws IOException{
		return readAllString(StandardCharsets.UTF_8);
	}
	
	public String readAllString(Charset charset) throws IOException{
		return new String(readAll(), charset);
	}
	
	public byte[] readAll() throws IOException{
		byte[] bb=new byte[Math.toIntExact(getSize())];
		try(InputStream is=read()){
			is.readNBytes(bb, 0, bb.length);
		}
		return bb;
	}
	
	public InputStream read() throws IOException{
		return read(0);
	}
	
	public InputStream read(long offset) throws IOException{
		var data=getData();
		if(data==null) throw new FileNotFoundException();
		return data.read(offset);
	}
	
	public OutputStream write() throws IOException{
		return write(0);
	}
	
	private void createData(long initialSize) throws IOException{
		data=source.header.createFile(source.getLocalPath(), initialSize).io();
	}
	
	private ChunkIO getData() throws IOException{
		if(data==null){
			var c=source.dereference();
			if(c!=null) data=c.io();
		}
		return data;
	}
	
	/**
	 * crucial to close
	 */
	private class BufferingInit extends OutputStream{
		private OutputStream out=new ByteArrayOutputStream(){
			@Override
			public void close() throws IOException{
				Assert(getData()==null);
				createData(wrote);
				var directOut=getData().write(clipOnEnd);
				writeTo(directOut);
				out=directOut;
			}
		};
		
		private boolean usingDirect;
		private int     wrote;
		
		private final boolean clipOnEnd;
		
		private BufferingInit(boolean clipOnEnd){
			this.clipOnEnd=clipOnEnd;
		}
		
		private void logWrite(int moreData) throws IOException{
			if(usingDirect) return;
			wrote+=moreData;//ok to add moreData before as extra space will be filled right away
			if(wrote>source.header.config.maxBufferingInitSize) realFileMode();
		}
		
		private void realFileMode() throws IOException{
			if(usingDirect) return;
			usingDirect=true;
			out.close();
		}
		
		@Override
		public void write(int b) throws IOException{
			logWrite(1);
			out.write(b);
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			Objects.checkFromIndexSize(off, len, b.length);
			logWrite(len);
			out.write(b, off, len);
		}
		
		@Override
		public void close() throws IOException{
			realFileMode();
			out.close();
		}
	}
	
	public OutputStream write(long offset) throws IOException{
		if(getData()==null){
			if(offset!=0) createData(0);
			else return new BufferingInit(true);
		}
		return getData().write(offset, true);
		
	}
	
	public long getSize() throws IOException{
		var data=getData();
		return data==null?0:data.getSize();
	}
	
	public void delete() throws IOException{
		data=null;
		source.header.deleteFile(source.getLocalPath());
	}
	
	public void writeAll(byte[] bytes) throws IOException{
		if(getData()==null) createData(bytes.length);
		getData().write(true, bytes);
	}
}
