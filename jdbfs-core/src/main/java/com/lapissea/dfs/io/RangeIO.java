package com.lapissea.dfs.io;

import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.EOFException;
import java.io.IOException;
import java.util.Collection;

public final class RangeIO implements RandomIO, Stringify{
	
	public static RandomIO of(RandomIO.Creator parent, long offset, long maxLength) throws IOException{
		return offset == 0 && maxLength == Long.MAX_VALUE? parent.io() : new RangeIO(parent, offset, maxLength);
	}
	public static RandomIO of(RandomIO parent, long offset, long maxLength) throws IOException{
		return offset == 0 && maxLength == Long.MAX_VALUE? parent : new RangeIO(parent, offset, maxLength);
	}
	
	private final RandomIO parent;
	private final long     offset;
	private final long     maxLength;
	
	public RangeIO(RandomIO parent, long offset, long maxLength) throws IOException{
		this.parent = parent;
		this.offset = offset;
		this.maxLength = maxLength;
		parent.setPos(0);
		parent.skipExact(offset);
	}
	
	public RangeIO(Creator parent, long offset, long maxLength) throws IOException{
		this.parent = parent.io();
		this.offset = offset;
		this.maxLength = maxLength;
		this.parent.skipExact(offset);
	}
	
	@Override
	public void setSize(long requestedSize) throws IOException{
		if(maxLength != Long.MAX_VALUE) throw new UnsupportedOperationException();
		parent.setSize(requestedSize + offset);
	}
	@Override
	public long getSize() throws IOException{
		return Math.min(maxLength, parent.getSize() - offset);
	}
	@Override
	public long getPos() throws IOException{
		return Math.min(maxLength, getLocalPos());
	}
	@Override
	public RandomIO setPos(long pos) throws IOException{
		parent.setPos(Math.min(maxLength, pos) + offset);
		return this;
	}
	@Override
	public long getCapacity() throws IOException{
		return Math.min(maxLength, parent.getCapacity() - offset);
	}
	@Override
	public RandomIO setCapacity(long newCapacity) throws IOException{
		if(maxLength != Long.MAX_VALUE) throw new UnsupportedOperationException();
		parent.setCapacity(newCapacity + offset);
		return this;
	}
	@Override
	public void close() throws IOException{
		parent.close();
	}
	@Override
	public void flush() throws IOException{
		parent.flush();
	}
	@Override
	public int read() throws IOException{
		if(remaining() == 0) return -1;
		return parent.read();
	}
	@Override
	public void write(int b) throws IOException{
		if(maxRemaining() == 0) endFail();
		parent.write(b);
	}
	private static void endFail() throws EOFException{
		throw new EOFException("Out of bounds");
	}
	@Override
	public void fillZero(long requestedMemory) throws IOException{
		var rem = maxRemaining();
		if(rem<requestedMemory) endFail();
		parent.fillZero(requestedMemory);
	}
	@Override
	public boolean isReadOnly(){
		return parent.isReadOnly();
	}
	
	@Override
	public boolean inTransaction(){
		return parent.inTransaction();
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		var rem = remaining();
		if(rem == 0) return -1;
		return parent.read(b, off, (int)Math.min(len, rem));
	}
	
	@Override
	public long readWord(int len) throws IOException{
		var rem = remaining();
		if(rem<len) endFail();
		return parent.readWord(len);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		var rem = maxRemaining();
		if(rem<len) endFail();
		parent.write(b, off, len);
	}
	@Override
	public void writeAtOffsets(Collection<WriteChunk> data) throws IOException{
		var max = Iters.from(data).mapToLong(WriteChunk::ioEnd).max(-1);
		if(maxLength<max) endFail();
		parent.writeAtOffsets(Iters.from(data).toModList(d -> d.withOffset(d.ioOffset() + offset)));
	}
	
	@Override
	public void writeWord(long v, int len) throws IOException{
		var rem = maxRemaining();
		if(rem<len) endFail();
		parent.writeWord(v, len);
	}
	
	@Override
	public RandomIO ensureCapacity(long capacity) throws IOException{
		if(maxLength<capacity) endFail();
		return parent.ensureCapacity(capacity + offset);
	}
	@Override
	public long remaining() throws IOException{
		var rem = parent.remaining();
		return Math.min(maxLength, rem);
	}
	private long maxRemaining() throws IOException{
		long localPos = getLocalPos();
		return maxLength - localPos;
	}
	private long getLocalPos() throws IOException{
		return parent.getPos() - offset;
	}
	@Override
	public long skip(long n) throws IOException{
		var rem = remaining();
		if(rem == 0) return -1;
		return parent.skip(Math.min(n, rem));
	}
	@Override
	public void trim() throws IOException{
		var localRemaining = parent.remaining() - offset;
		if(localRemaining>maxLength) throw new UnsupportedOperationException();
		parent.trim();
	}
	@Override
	public boolean isDirect(){
		return parent.isDirect();
	}
	@Override
	public String toString(){
		try{
			return "RangeIO{" +
			       getLocalPos() + "/" + getSize() +
			       " +" + offset + (maxLength != Long.MAX_VALUE? " <<" + maxLength : "") + '}';
		}catch(IOException e){
			return "CORRUPTED";
		}
	}
	
	@Override
	public String toShortString(){
		try{
			return "{" + getSize() + " +" + offset + (maxLength != Long.MAX_VALUE? " <<" + maxLength : "") + '}';
		}catch(IOException e){
			return "CORRUPTED";
		}
	}
	
}
