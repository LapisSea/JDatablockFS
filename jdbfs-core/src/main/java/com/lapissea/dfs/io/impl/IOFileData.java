package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.internal.WordIO;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.util.MathUtil;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;

public final class IOFileData extends CursorIOData implements Closeable{
	
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
	
	public IOFileData(File file) throws IOException{ this(file, false); }
	public IOFileData(File file, boolean readOnly) throws IOException{
		this(file,
		     readOnly?
		     Mode.READ_ONLY :
		     ConfigDefs.SYNCHRONOUS_FILE_IO.resolveVal()? Mode.READ_WRITE_SYNCHRONOUS : Mode.READ_WRITE
		);
	}
	public IOFileData(File file, Mode mode) throws IOException{
		super(null, mode == Mode.READ_ONLY);
		this.file = file;
		
		fileData = new RandomAccessFile(file, mode.str);
		
		this.used = getLength();
	}
	
	@Override
	public String toString(){
		return IOFileData.class.getSimpleName() + "{" + file + "}";
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof IOFileData that &&
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
	protected void readN(long fileOffset, byte[] dest, int off, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.readFully(dest, off, len);
	}
	@Override
	protected void writeN(byte[] src, int index, long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.write(src, index, len);
	}
	
	@Override
	protected long readWord(long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff = new byte[len];
		fileData.readFully(buff);
		return WordIO.getWord(buff, 0, len);
	}
	@Override
	protected void writeWord(long value, long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff = new byte[len];
		WordIO.setWord(value, buff, 0, len);
		fileData.write(buff);
	}
	
	@Override
	public IOFileData asReadOnly(){
		if(isReadOnly()) return this;
		try{
			close();
			return new IOFileData(file, true);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close() throws IOException{
		fileData.close();
	}
}
