package com.lapissea.dfs.io;

import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentReader;

import java.io.EOFException;
import java.io.IOException;

public final class LimitedContentReader extends ContentInputStream{
	
	private final ContentReader source;
	private       long          remaining;
	
	public LimitedContentReader(ContentReader source, long limit){
		this.source = source;
		remaining = limit;
	}
	private static void endFail() throws EOFException{
		throw new EOFException("Out of bounds");
	}
	
	@Override
	public int read() throws IOException{
		if(remaining<=0) return -1;
		remaining--;
		return source.read();
	}
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		if(remaining<=0) return -1;
		var toRead = (int)Math.min(len, remaining);
		var read   = source.read(b, off, toRead);
		remaining -= read;
		return read;
	}
	@Override
	public long readWord(int len) throws IOException{
		if(remaining<len) endFail();
		remaining -= len;
		return source.readWord(len);
	}
	@Override
	public long skip(long toSkip) throws IOException{
		if(remaining<=0) return -1;
		var trySkip = Math.min(remaining, toSkip);
		var skipped = source.skip(trySkip);
		remaining -= skipped;
		return skipped;
	}
	@Override
	public void skipExact(long toSkip) throws IOException{
		if(remaining<toSkip) endFail();
		source.skipExact(toSkip);
		remaining -= toSkip;
	}
	@Override
	public byte[] readRemaining() throws IOException{
		byte[] result = new byte[Math.toIntExact(remaining)];
		int    pos    = 0;
		while(pos<result.length){
			var read = read(result, pos, result.length - pos);
			if(read == -1) break;
			pos += read;
		}
		return result;
	}
	
	@Override
	public void close() throws IOException{
		source.close();
	}
	@Override
	public int available(){
		return (int)Math.min(Integer.MAX_VALUE, remaining);
	}
}
