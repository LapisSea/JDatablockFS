package com.lapissea.cfs.io;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;
import java.util.Collection;

public final class OffsetIO implements RandomIO{
	private final RandomIO parent;
	private final long     offset;
	
	public OffsetIO(RandomIO parent, long offset) throws IOException{
		this.parent=parent;
		this.offset=offset;
		parent.setPos(0);
		parent.skipExact(offset);
	}
	
	public OffsetIO(RandomIO.Creator parent, long offset) throws IOException{
		this.parent=parent.io();
		this.offset=offset;
		this.parent.skipExact(offset);
	}
	
	@Override
	public void setSize(long requestedSize) throws IOException{
		parent.setSize(requestedSize+offset);
	}
	@Override
	public long getSize() throws IOException{
		return parent.getSize()-offset;
	}
	@Override
	public long getPos() throws IOException{
		return parent.getPos()-offset;
	}
	@Override
	public RandomIO setPos(long pos) throws IOException{
		parent.setPos(pos+offset);
		return this;
	}
	@Override
	public long getCapacity() throws IOException{
		return parent.getCapacity()-offset;
	}
	@Override
	public RandomIO setCapacity(long newCapacity) throws IOException{
		parent.setCapacity(newCapacity+offset);
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
		return parent.read();
	}
	@Override
	public void write(int b) throws IOException{
		parent.write(b);
	}
	@Override
	public void fillZero(long requestedMemory) throws IOException{
		parent.fillZero(requestedMemory);
	}
	@Override
	public boolean isReadOnly(){
		return parent.isReadOnly();
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		return parent.read(b, off, len);
	}
	
	@Override
	public long readWord(int len) throws IOException{
		return parent.readWord(len);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		parent.write(b, off, len);
	}
	@Override
	public void writeAtOffsets(Collection<WriteChunk> data) throws IOException{
		parent.writeAtOffsets(data.stream().map(d->d.withOffset(d.ioOffset()+offset)).toList());
	}
	
	@Override
	public void write8(long v, int len) throws IOException{
		parent.write8(v, len);
	}
	
	@Override
	public RandomIO ensureCapacity(long capacity) throws IOException{
		return parent.ensureCapacity(capacity+offset);
	}
	@Override
	public long remaining() throws IOException{
		return parent.remaining();
	}
	@Override
	public void skipExact(long toSkip) throws IOException{
		parent.skipExact(toSkip);
	}
	@Override
	public long skip(long n) throws IOException{
		return parent.skip(n);
	}
	@Override
	public void trim() throws IOException{
		parent.trim();
	}
	@Override
	public byte[] readRemaining() throws IOException{
		return parent.readRemaining();
	}
	@Override
	public long transferTo(ContentWriter out, int buffSize) throws IOException{
		return parent.transferTo(out, buffSize);
	}
	@Override
	public ContentReader.BufferTicket readTicket(long amount){
		return parent.readTicket(amount);
	}
	@Override
	public ContentReader.BufferTicket readTicket(int amount){
		return parent.readTicket(amount);
	}
	@Override
	public ContentWriter.BufferTicket writeTicket(long amount){
		return parent.writeTicket(amount);
	}
	@Override
	public ContentWriter.BufferTicket writeTicket(int amount){
		return parent.writeTicket(amount);
	}
	@Override
	public boolean isDirect(){
		return parent.isDirect();
	}
	@Override
	public String toString(){
		return "OffsetIO{"+
		       "parent="+parent+
		       ", offset="+offset+
		       '}';
	}
	
	public String toShortString(){
		return "{"+parent+" +"+offset+'}';
	}
	
}
