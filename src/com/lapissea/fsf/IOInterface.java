package com.lapissea.fsf;

import com.lapissea.util.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import static com.lapissea.fsf.FileSystemInFile.*;

public interface IOInterface{
	
	class FileRA implements IOInterface{
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
				public RandomIO setSize(long newSize) throws IOException{
					ra.setLength(newSize);
					return this;
				}
				
				@Override
				public void close() throws IOException{
					ra.close();
				}
				
				@Override
				public void flush(){ }
				
				private void snapPos() throws IOException{
					var pos=getPos();
					if(ra.getFilePointer()!=pos) ra.seek(pos);
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
					pos++;
				}
				
				@Override
				public void write(byte[] b, int off, int len) throws IOException{
					snapPos();
					ra.write(b, off, len);
					pos+=len;
				}
			};
		}
		
		@Override
		public ContentOutputStream write(long fileOffset) throws IOException{
			return new ContentOutputStream(){
				long pos=fileOffset;
				
				@Override
				public void write(int b) throws IOException{
					if(ra.getFilePointer()!=pos) ra.seek(pos);
					pos++;
					ra.write(b);
				}
				
				@Override
				public void write(@NotNull byte[] b, int off, int len) throws IOException{
					if(ra.getFilePointer()!=pos) ra.seek(pos);
					pos+=len;
					ra.write(b, off, len);
				}
				
			};
		}
		
		@Override
		public ContentInputStream read(long fileOffset) throws IOException{
			return new ContentInputStream(){
				long pos=fileOffset;
				
				@Override
				public long skip(long n) throws IOException{
					var skipped=ra.skipBytes((int)Math.min(n, Integer.MAX_VALUE));
					pos+=skipped;
					return skipped;
				}
				
				@Override
				public int read() throws IOException{
					if(ra.getFilePointer()!=pos) ra.seek(pos);
					pos++;
					return ra.read();
				}
				
				@Override
				public int read(@NotNull byte[] b, int off, int len) throws IOException{
					if(ra.getFilePointer()!=pos) ra.seek(pos);
					int read=ra.read(b, off, len);
					pos+=read;
					return read;
				}
				
				@Override
				public boolean markSupported(){
					try{
						ra.getFilePointer();
						return true;
					}catch(IOException ignored){}
					return false;
				}
				
				long mark;
				
				@Override
				public synchronized void mark(int readlimit){
					try{
						mark=ra.getFilePointer();
					}catch(IOException ignored){}
				}
				
				@Override
				public synchronized void reset() throws IOException{
					pos+=mark;
					ra.seek(mark);
				}
			};
		}
		
		@Override
		public long getSize() throws IOException{
			return ra.length();
		}
		
		@Override
		public void setSize(long newSize) throws IOException{
			ra.setLength(newSize);
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
	}
	
	class RandomIOOutputStream extends ContentOutputStream{
		
		private final RandomIO io;
		
		RandomIOOutputStream(RandomIO io){this.io=io;}
		
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
			io.trim();
			io.close();
		}
		
		@Override
		public void write(int b) throws IOException{
			io.write(b);
		}
	}
	
	
	default ContentOutputStream write() throws IOException{
		return write(0);
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
	default ContentOutputStream write(long fileOffset) throws IOException{
		
		return new RandomIOOutputStream(doRandom().setPos(fileOffset));
	}
	
	default void write(long fileOffset, byte[] data) throws IOException{
		write(fileOffset, data.length, data);
	}
	
	default void write(long fileOffset, int length, byte[] data) throws IOException{
		try(var stream=write(fileOffset)){
			stream.write(data, 0, length);
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
		try(var stream=read(fileOffset)){
			stream.readNBytes(dest, 0, length);
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
	
	void setSize(long newSize) throws IOException;
}
