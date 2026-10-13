package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.util.MathUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;

public final class FileRandomAccessData extends FileIOData{
	
	public enum Mode{
		READ_ONLY("r"),
		READ_WRITE("rw"),
		READ_WRITE_SYNCHRONOUS("rwd");
		
		final String str;
		Mode(String str){ this.str = str; }
	}
	
	public static void readInto(File file, IOInterface dest) throws IOException{
		try(var in = new FileInputStream(file); var out = dest.io()){
			var buff = new byte[MathUtil.snap((int)Math.min(file.length(), (long)BATCH_BYTES), 16, BATCH_BYTES)];
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
	
	public FileRandomAccessData(String fileName) throws IOException{ this(new File(fileName)); }
	public FileRandomAccessData(File file) throws IOException      { this(file, false); }
	public FileRandomAccessData(File file, boolean readOnly) throws IOException{
		this(null, file, readOnly);
	}
	public FileRandomAccessData(IOHook hook, File file, boolean readOnly) throws IOException{
		this(hook, file,
		     readOnly?
		     Mode.READ_ONLY :
		     ConfigDefs.SYNCHRONOUS_FILE_IO.resolveVal()? Mode.READ_WRITE_SYNCHRONOUS : Mode.READ_WRITE
		);
	}
	public FileRandomAccessData(File file, Mode mode) throws IOException{
		this(null, file, mode);
	}
	public FileRandomAccessData(File file, boolean readOnly, boolean durable) throws IOException{
		this(null, file, readOnly? Mode.READ_ONLY :
		                 ConfigDefs.SYNCHRONOUS_FILE_IO.resolveVal()? Mode.READ_WRITE_SYNCHRONOUS : Mode.READ_WRITE, durable);
	}
	public FileRandomAccessData(IOHook hook, File file, Mode mode) throws IOException{
		this(hook, file, mode, ConfigDefs.DURABLE_FILE_IO.resolveVal());
	}
	public FileRandomAccessData(IOHook hook, File file, Mode mode, boolean durable) throws IOException{
		super(hook, file, mode == Mode.READ_ONLY, durable);
		this.file = file;
		try{
			fileData = new RandomAccessFile(file, durable && mode != Mode.READ_ONLY? "rw" : mode.str);
		}catch(FileNotFoundException e){
			releaseFileOwnership();
			if(mode == Mode.READ_ONLY){
				throw new FileNotFoundException("File must exist if in read only mode: " + e.getMessage());
			}
			throw e;
		}catch(Throwable e){
			releaseFileOwnership();
			throw e;
		}
		try{
			fileLock = fileData.getChannel().tryLock(0, Long.MAX_VALUE, mode == Mode.READ_ONLY);
			if(fileLock == null) throw new IOException("File is already in use: " + file);
		}catch(IOException|OverlappingFileLockException e){
			try{ fileData.close(); }finally{ releaseFileOwnership(); }
			throw new IOException("Unable to acquire file access: " + file, e);
		}catch(Throwable e){
			try{ fileData.close(); }finally{ releaseFileOwnership(); }
			throw e;
		}
		
		try{
			this.used = getLength();
			initializeJournal(file);
			
			if(!isReadOnly()) bindCloseOnShutdown(this);
		}catch(Throwable e){
			try{ fileData.close(); }finally{ releaseFileOwnership(); }
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
	protected void forceFile() throws IOException{
		fileData.getChannel().force(true);
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
		try(var ignored = closeAccess()){
			try{
				closeJournal();
			}finally{
				markClosed();
				try{
					if(!isReadOnly()){
						resize(used);
					}
				}finally{
					try{
						fileData.close();
					}finally{
						releaseFileOwnership();
						if(!isReadOnly()){
							unbindCloseOnShutdown(this);
						}
					}
				}
			}
		}
	}
}
