package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.content.WordIO;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

public final class FileMemoryMappedData extends CursorIOData implements Closeable{
	
	private final File         file;
	private final FileMappings mappedFileData;
	
	public FileMemoryMappedData(String fileName) throws IOException{ this(new File(fileName), false); }
	public FileMemoryMappedData(File file) throws IOException      { this(file, false); }
	public FileMemoryMappedData(File file, boolean readOnly) throws IOException{
		super(null, readOnly);
		this.file = file;
		
		var ioOptions = EnumSet.of(
			StandardOpenOption.CREATE,
			StandardOpenOption.READ
		);
		if(!readOnly){
			ioOptions.add(StandardOpenOption.WRITE);
			if(ConfigDefs.SYNCHRONOUS_FILE_IO.resolveVal()){
				ioOptions.add(StandardOpenOption.SYNC);
			}
		}
		FileChannel fileChannel;
		
		try{
			var bc = Files.newByteChannel(file.toPath(), ioOptions);
			if(bc instanceof FileChannel fc) fileChannel = fc;
			else{
				bc.close();
				throw new IOException(file + " is not a valid file!");
			}
		}catch(NoSuchFileException e){
			if(readOnly){
				throw new NoSuchFileException("File must exist if in read only mode: " + e.getMessage());
			}
			throw e;
		}
		try{
			if(!readOnly){
				try{
					//noinspection ResultOfMethodCallIgnored
					fileChannel.lock();
				}catch(IOException|OverlappingFileLockException e){
					throw new IOException("Unable to acquire exclusive access to: " + file, e);
				}
			}
			
			mappedFileData = new FileMappings(fileChannel, readOnly);
			
			this.used = getLength();
			
			if(!isReadOnly()) bindCloseOnShutdown(this);
		}catch(Throwable e){
			fileChannel.close();
			throw e;
		}
	}
	
	@Override
	public String toString(){
		return getClass().getSimpleName() + "{" + file + "}";
	}
	
	@Override
	public boolean equals(Object o){
		return this == o;
	}
	
	@Override
	public int hashCode(){
		return file.hashCode();
	}
	
	@Override
	protected long getLength(){
		return mappedFileData.fileSize();
	}
	@Override
	protected void resize(long newSize) throws IOException{
		mappedFileData.resize(newSize);
	}
	
	@Override
	protected byte read1(long fileOffset) throws IOException{
		long chunkIndex = fileOffset/FileMappings.MAX_CHUNK_SIZE;
		long chunkStart = chunkIndex*FileMappings.MAX_CHUNK_SIZE;
		var  chunkOff   = (int)(fileOffset - chunkStart);
		return mappedFileData.onMapping(chunkIndex, m -> m.get(chunkOff));
	}
	@Override
	protected void write1(long fileOffset, byte b) throws IOException{
		long chunkIndex = fileOffset/FileMappings.MAX_CHUNK_SIZE;
		long chunkStart = chunkIndex*FileMappings.MAX_CHUNK_SIZE;
		var  chunkOff   = (int)(fileOffset - chunkStart);
		mappedFileData.onMapping(chunkIndex, m -> m.put(chunkOff, b));
	}
	
	@Override
	protected void readN(long fileOffset, byte[] dest, int destOff, int len) throws IOException{
		io(fileOffset, dest, destOff, len, true);
	}
	@Override
	protected void writeN(long fileOffset, byte[] src, int srcOff, int len) throws IOException{
		io(fileOffset, src, srcOff, len, false);
	}
	private void io(long fileOffset, byte[] dest, int destOff, int len, boolean read) throws IOException{
		while(len>0){
			long chunkIndex     = fileOffset/FileMappings.MAX_CHUNK_SIZE;
			long chunkStart     = chunkIndex*FileMappings.MAX_CHUNK_SIZE;
			var  chunkOff       = (int)(fileOffset - chunkStart);
			var  chunkRemaining = FileMappings.MAX_CHUNK_SIZE - chunkOff;
			
			var chunkToExec = Math.min(len, chunkRemaining);
			var off         = destOff;
			mappedFileData.onMapping(chunkIndex, mapping -> {
				if(read){
					mapping.get(chunkOff, dest, off, chunkToExec);
				}else{
					mapping.put(chunkOff, dest, off, chunkToExec);
				}
				return null;
			});
			
			len -= chunkToExec;
			fileOffset += chunkToExec;
			destOff += chunkToExec;
		}
	}
	
	@Override
	protected long readWord(long fileOffset, int len) throws IOException{
		byte[] buff = new byte[len];//TODO: write 0 allocation impl
		readN(fileOffset, buff, 0, len);
		return WordIO.getWord(buff, 0, len);
	}
	@Override
	protected void writeWord(long fileOffset, long value, int len) throws IOException{
		byte[] buff = new byte[len];//TODO: write 0 allocation impl
		WordIO.setWord(value, buff, 0, len);
		writeN(fileOffset, buff, 0, len);
	}
	
	@Override
	public FileMemoryMappedData asReadOnly(){
		if(isReadOnly()) return this;
		try{
			close();
			return new FileMemoryMappedData(file, true);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close() throws IOException{
		resize(getIOSize());
		mappedFileData.close();
		if(!isReadOnly()) unbindCloseOnShutdown(this);
	}
}
