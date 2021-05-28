package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.stream.LongStream;

import static com.lapissea.util.UtilL.*;

public class IOFileData implements IOInterface, AutoCloseable{
	
	private class FIleRandomIO implements RandomIO{
		private long pos;
		
		@Override
		public RandomIO setPos(long pos){
			if(pos<0) throw new IndexOutOfBoundsException();
			this.pos=pos;
			return this;
		}
		
		@Override
		public long getPos() throws IOException{
			return Math.min(this.pos, getSize());
		}
		
		@Override
		public long getSize() throws IOException{
			return source.length();
		}
		
		@Override
		public void setSize(long targetSize){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public long getCapacity() throws IOException{
			return source.length();
		}
		
		@Override
		public RandomIO setCapacity(long newCapacity) throws IOException{
			source.setLength(newCapacity);
			return this;
		}
		
		@Override
		public void close(){
//					ra.close();
		}
		
		@Override
		public void flush(){ }
		
		private void snapPos() throws IOException{
			var pos=getPos();
			var p0 =source.getFilePointer();
			if(p0!=pos) source.seek(pos);
			var p1=source.getFilePointer();
			Assert(pos==p1);
		}
		
		@Override
		public int read() throws IOException{
			snapPos();
			var i=source.read();
			if(i!=-1) pos++;
			return i;
		}
		
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			snapPos();
			var read=source.read(b, off, len);
			if(read>0) pos+=read;
			return read;
		}
		
		@Override
		public void write(int b) throws IOException{
			snapPos();
			source.write(b);
			if(onWrite!=null){
				pushOnWrite(new long[]{pos});
			}
			pos++;
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			write(b, off, len, true);
		}
		
		public void write(byte[] b, int off, int len, boolean pushPos) throws IOException{
			snapPos();
			source.write(b, off, len);
			if(onWrite!=null){
				pushOnWrite(LongStream.range(pos, pos+len).toArray());
			}
			if(pushPos) pos+=len;
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			Utils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
		}
		
		@Override
		public long getGlobalPos() throws IOException{
			return getPos();
		}
	}
	
	
	//////////////////////////////////////////////////////////
	
	
	public UnsafeConsumer<long[], IOException> onWrite;
	
	private RandomAccessFile source;
	
	private final String  path;
	private final boolean readOnly;
	
	public IOFileData(File file) throws IOException{
		this.readOnly=!file.canWrite();
		path=file.getPath();
	}
	
	public IOFileData(File file, boolean readOnly) throws IOException{
		if(!readOnly&&!file.canWrite()) throw new IOException("can not write to "+file.getPath());
		this.readOnly=readOnly;
		path=file.getPath();
	}
	
	@Override
	public String toString(){ return path; }
	
	private void pushOnWrite(long[] ids){
		try{
			onWrite.accept(ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
	}
	
	@Override
	@NotNull
	public RandomIO io(){ return new FIleRandomIO(); }
	
	@Override
	public void setSize(long requestedSize){ throw new UnsupportedOperationException(); }
	
	@Override
	public long getSize() throws IOException{ return getSource().length(); }
	
	@Override
	public long getCapacity() throws IOException{ return getSource().length(); }
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	@Override
	public void setCapacity(long newCapacity) throws IOException{ getSource().setLength(newCapacity); }
	
	@Override
	public void close() throws Exception{
		if(source!=null){
			source.close();
			source=null;
		}
	}
	
	private RandomAccessFile getSource() throws FileNotFoundException{
		if(source==null) source=new RandomAccessFile(path, "rw");
		return source;
	}
}
