package com.lapissea.fsf;

import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

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
			
			if(!file.isFile()) file.createNewFile();
			path=file.getPath();
			ra=new RandomAccessFile(file, "rw");
			
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
		public long size() throws IOException{
			return ra.length();
		}
		
		@Override
		public void setSize(long newSize) throws IOException{
			ra.setLength(newSize);
		}
	}
	
	default ContentOutputStream write() throws IOException{
		return write(0);
	}
	
	RandomIO doRandom() throws IOException;
	
	default ContentOutputStream write(long fileOffset) throws IOException{
		return new ContentOutputStream(){
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				super.write(b, off, len);
			}
			
			@Override
			public void flush() throws IOException{
				super.flush();
			}
			
			@Override
			public void close() throws IOException{
				super.close();
			}
			
			@Override
			public void write(int b) throws IOException{
				throw NotImplementedException.infer();//TODO: implement .write()
			}
		};
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
	
	ContentInputStream read(long fileOffset) throws IOException;
	
	default void read(long fileOffset, byte[] dest) throws IOException{
		read(fileOffset, dest.length, dest);
	}
	
	default void read(long fileOffset, int length, byte[] dest) throws IOException{
		try(var stream=read(fileOffset)){
			stream.readNBytes(dest, 0, length);
		}
	}
	
	long size() throws IOException;
	
	void setSize(long newSize) throws IOException;
}
