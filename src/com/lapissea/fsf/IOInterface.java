package com.lapissea.fsf;

import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.stream.LongStream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public interface IOInterface{
	
	class FileRA implements IOInterface{
		public UnsafeConsumer<long[], IOException> onWrite=writes->{};
		
		private       RandomAccessFile ra;
		private final String           path;
		
		@Override
		public String toString(){
			return path;
		}
		
		public FileRA(File file) throws IOException{
			var extp="."+EXTENSION;
			if(!file.getPath().endsWith(extp)) file=new File(file.getPath()+extp);
			
			if(!file.isFile()){
				//noinspection ResultOfMethodCallIgnored
				file.createNewFile();
			}
			path=file.getPath();
			ra=new RandomAccessFile(file, "rw");
			
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
					try{
						onWrite.accept(new long[]{pos});
					}catch(Exception ignored){}
					pos++;
				}
				
				@Override
				public void write(byte[] b, int off, int len) throws IOException{
					snapPos();
					ra.write(b, off, len);
					try{
						onWrite.accept(LongStream.range(pos, pos+len).toArray());
					}catch(Exception ignored){}
					pos+=len;
				}
			};
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
	
	
	class RandomInputStream extends ContentInputStream{
		
		private final RandomIO io;
		private       Long     mark;
		
		RandomInputStream(RandomIO io){this.io=io;}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			return io.read(b, off, len);
		}
		
		@Override
		public int read() throws IOException{
			return io.read();
		}
		
		@Override
		public long skip(long n) throws IOException{
			return io.skip(n);
		}
		
		@Override
		public void close() throws IOException{
			io.close();
		}
		
		@SuppressWarnings("AutoBoxing")
		@Override
		public synchronized void mark(int readLimit){
			try{
				mark=io.getPos();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public synchronized void reset() throws IOException{
			io.setPos(mark);
		}
		
		@Override
		public boolean markSupported(){
			return true;
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+"{"+io+'}';
		}
	}
	
	class RandomIOOutputStream extends ContentOutputStream{
		
		private final RandomIO io;
		private final boolean  clipOnEnd;
		
		RandomIOOutputStream(RandomIO io, boolean clipOnEnd){
			this.io=io;
			this.clipOnEnd=clipOnEnd;
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			io.write(b, off, len);
		}
		
		@Override
		public void flush() throws IOException{
			io.flush();
		}
		
		@Override
		public void close() throws IOException{
			if(clipOnEnd){
				io.trim();
			}
			io.close();
		}
		
		@Override
		public void write(int b) throws IOException{
			io.write(b);
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+'{'+io+'}';
		}
	}
	
	
	default ContentOutputStream write(boolean clipOnEnd) throws IOException{
		return write(0, clipOnEnd);
	}
	
	/**
	 * <p>Creates a new random read and write interface.</p>
	 * <p>Writing <b>will not</b> implicitly truncate the underlying contents when closed.</p>
	 *
	 * @return RandomIO instance
	 */
	RandomIO doRandom() throws IOException;
	
	/**
	 * <p>Creates a new sequential write interface.</p>
	 * <p>Writing <b>will</b> implicitly truncate the underlying contents when closed.</p>
	 */
	default ContentOutputStream write(long fileOffset, boolean clipOnEnd) throws IOException{
		return new RandomIOOutputStream(doRandom().setPos(fileOffset), clipOnEnd);
	}
	
	default void write(boolean clipOnEnd, byte[] data) throws IOException{
		write(0, clipOnEnd, data.length, data);
	}
	
	default void write(long fileOffset, boolean clipOnEnd, byte[] data) throws IOException{
		write(fileOffset, clipOnEnd, data.length, data);
	}
	
	default void write(long fileOffset, boolean clipOnEnd, int length, byte[] data) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.write(data, 0, length);
			if(clipOnEnd) io.trim();
		}
	}
	
	default ContentInputStream read() throws IOException{
		return read(0);
	}
	
	default ContentInputStream read(long fileOffset) throws IOException{
		return new RandomInputStream(doRandom().setPos(fileOffset));
	}
	
	default void read(long fileOffset, byte[] dest) throws IOException{
		read(fileOffset, dest.length, dest);
	}
	
	default void read(long fileOffset, int length, byte[] dest) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.readFully(dest, 0, length);
		}
	}
	
	default byte[] readAll() throws IOException{
		try(var stream=read()){
			byte[] data=new byte[Math.toIntExact(getSize())];
			int    read=stream.readNBytes(data, 0, data.length);
			if(read!=data.length) data=Arrays.copyOf(data, read);
			return data;
		}
	}
	
	long getSize() throws IOException;
	
	long getCapacity() throws IOException;
	
	void setCapacity(long newCapacity) throws IOException;
}
