package com.lapissea.fsf.endpoint.data;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.RandomIO;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.stream.LongStream;

import static com.lapissea.util.UtilL.*;

public class FileData implements IOInterface{
	public UnsafeConsumer<long[], IOException> onWrite;
	
	private       RandomAccessFile ra;
	private final String           path;
	
	public FileData(File file, FileSystemInFile.Config config) throws IOException{
		File enforcedFile=config.shouldEnforceExtension?config.enforceExtension(file):file;
		
		if(!enforcedFile.isFile()){
			enforcedFile=config.enforceExtension(enforcedFile);
			
			//noinspection ResultOfMethodCallIgnored
			enforcedFile.createNewFile();
		}
		
		path=enforcedFile.getPath();
		ra=new RandomAccessFile(enforcedFile, "rw");
	}
	
	@Override
	public String toString(){
		return path;
	}
	
	private void pushOnWrite(long[] ids){
		try{
			onWrite.accept(ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
	}
	
	@Override
	public RandomIO doRandom(){
		return new RandomIO(){
			private long pos;
			
			private final byte[] buf=new byte[8];
			
			@Override
			public byte[] contentBuf(){
				return buf;
			}
			
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
				return ra.length();
			}
			
			@Override
			public RandomIO setSize(long targetSize){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public long getCapacity() throws IOException{
				return ra.length();
			}
			
			@Override
			public RandomIO setCapacity(long newCapacity) throws IOException{
				ra.setLength(newCapacity);
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
				var p0 =ra.getFilePointer();
				if(p0!=pos) ra.seek(pos);
				var p1=ra.getFilePointer();
				Assert(pos==p1);
			}
			
			@Override
			public int read() throws IOException{
				snapPos();
				var i=ra.read();
				if(i!=-1) pos++;
				return i;
			}
			
			
			@Override
			public int read(byte[] b, int off, int len) throws IOException{
				snapPos();
				var read=ra.read(b, off, len);
				if(read>0) pos+=read;
				return read;
			}
			
			@Override
			public void write(int b) throws IOException{
				snapPos();
				ra.write(b);
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
				ra.write(b, off, len);
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
		};
	}
	
	@Override
	public void setSize(long requestedSize){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public long getSize() throws IOException{
		return ra.length();
	}
	
	@Override
	public long getCapacity() throws IOException{
		return ra.length();
	}
	
	@Override
	public void setCapacity(long newCapacity) throws IOException{
		ra.setLength(newCapacity);
	}
}
