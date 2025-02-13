package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.util.MathUtil;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;

public final class FileRandomAccessData extends CursorIOData implements Closeable{
	
	public enum Mode{
		READ_ONLY("r"),
		READ_WRITE("rw"),
		READ_WRITE_SYNCHRONOUS("rwd");
		
		final String str;
		Mode(String str){ this.str = str; }
	}
	
	public static void readInto(String path, IOInterface dest) throws IOException{
		readInto(new File(path), dest);
	}
	public static void readInto(File file, IOInterface dest) throws IOException{
		try(var in = new FileInputStream(file); var out = dest.io()){
			var buff = new byte[MathUtil.snap((int)file.length(), 16, BATCH_BYTES)];
			int read;
			while((read = in.read(buff))>=0){
				out.write(buff, 0, read);
			}
			out.trim();
		}
	}
	
	private final File             file;
	private final RandomAccessFile fileData;
	private final FileLock         fileLock;
	
	public FileRandomAccessData(String fileName) throws IOException{ this(new File(fileName), false); }
	public FileRandomAccessData(File file) throws IOException      { this(file, false); }
	public FileRandomAccessData(File file, boolean readOnly) throws IOException{
		this(file,
		     readOnly?
		     Mode.READ_ONLY :
		     ConfigDefs.SYNCHRONOUS_FILE_IO.resolveVal()? Mode.READ_WRITE_SYNCHRONOUS : Mode.READ_WRITE
		);
	}
	public FileRandomAccessData(File file, Mode mode) throws IOException{
		super(null, mode == Mode.READ_ONLY);
		this.file = file;
		try{
			fileData = new RandomAccessFile(file, mode.str);
		}catch(FileNotFoundException e){
			if(mode == Mode.READ_ONLY){
				throw new FileNotFoundException("File must exist if in read only mode: " + e.getMessage());
			}
			throw e;
		}
		if(mode != Mode.READ_ONLY){
			try{
				fileLock = fileData.getChannel().lock();
			}catch(IOException|OverlappingFileLockException e){
				fileData.close();
				throw new IOException("Unable to acquire exclusive access to: " + file, e);
			}catch(Throwable e){
				fileData.close();
				throw e;
			}
		}else{
			fileLock = null;
		}
		try{
			this.used = getLength();
			
			if(!isReadOnly()) bindCloseOnShutdown(this);
		}catch(Throwable e){
			fileData.close();
			throw e;
		}
	}
	
	@Override
	public String toString(){
		return getClass().getSimpleName() + "{" + file + "}";
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof FileRandomAccessData that &&
		       fileData.equals(that.fileData);
	}
	
	@Override
	public int hashCode(){
		return file.hashCode();
	}
	
	@Override
	protected long getLength() throws IOException{
		return fileData.length();
	}
	@Override
	protected void resize(long newSize) throws IOException{
		fileData.setLength(newSize);
	}
	
	@Override
	protected byte read1(long fileOffset) throws IOException{
		fileData.seek(fileOffset);
		return fileData.readByte();
	}
	@Override
	protected void write1(long fileOffset, byte b) throws IOException{
		fileData.seek(fileOffset);
		fileData.writeByte(b);
	}
	
	@Override
	protected void readN(long fileOffset, byte[] dest, int destOff, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.readFully(dest, destOff, len);
	}
	@Override
	protected void writeN(long fileOffset, byte[] src, int srcOff, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.write(src, srcOff, len);
	}
	
	@Override
	protected long readWord(long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff = new byte[len];
		fileData.readFully(buff);
		return WordIO.getWord(buff, 0, len);
	}
	@Override
	protected void writeWord(long fileOffset, long value, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff = new byte[len];
		WordIO.setWord(value, buff, 0, len);
		fileData.write(buff);
	}
	
	@Override
	public FileRandomAccessData asReadOnly(){
		if(isReadOnly()) return this;
		try{
			close();
			return new FileRandomAccessData(file, true);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close() throws IOException{
		markClosed();
		resize(getIOSize());
		if(fileLock != null) fileLock.close();
		fileData.close();
		if(!isReadOnly()) unbindCloseOnShutdown(this);
	}
}
