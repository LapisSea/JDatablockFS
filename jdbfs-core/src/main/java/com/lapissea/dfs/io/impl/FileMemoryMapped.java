package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.content.WordIO;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

public final class FileMemoryMapped extends CursorIOData implements Closeable{
	
	private final File         file;
	private final FileMappings mappedFileData;
	
	public FileMemoryMapped(File file) throws IOException{ this(file, false); }
	public FileMemoryMapped(File file, boolean readOnly) throws IOException{
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
		var fileChannel = (FileChannel)Files.newByteChannel(file.toPath(), ioOptions);
		if(!readOnly){
			try{
				//noinspection ResultOfMethodCallIgnored
				fileChannel.lock();
			}catch(IOException|OverlappingFileLockException e){
				fileChannel.close();
				throw new IOException("Unable to acquire exclusive access to: " + file, e);
			}
		}
		
		mappedFileData = new FileMappings(fileChannel, readOnly);
		
		this.used = getLength();
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
	public FileMemoryMapped asReadOnly(){
		if(isReadOnly()) return this;
		try{
			close();
			return new FileMemoryMapped(file, true);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close() throws IOException{
		mappedFileData.close();
	}
}
