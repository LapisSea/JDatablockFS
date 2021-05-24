package com.lapissea.cfs;

import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.util.Objects;

public class Chunk implements RandomIO.Creator{
	
	private static final NumberSize FLAGS_SIZE=NumberSize.BYTE;
	
	public static Chunk readChunk(Cluster cluster, IOInterface io, ChunkPointer pointer) throws IOException{
		Chunk chunk=new Chunk(cluster, pointer);
		chunk.readHeader(io);
		return chunk;
	}
	
	private final ChunkPointer ptr;
	
	private NumberSize bodySize;
	
	private long capacity;
	private long size;
	
	private int headerSize;
	
	private final Cluster cluster;
	
	private Chunk(Cluster cluster, ChunkPointer ptr){
		this.ptr=ptr;
		this.cluster=cluster;
	}
	
	public Chunk(Cluster cluster, ChunkPointer ptr, long capacity, long size){
		this.ptr=ptr;
		this.capacity=capacity;
		this.size=size;
		this.cluster=cluster;
		
		bodySize=NumberSize.bySize(capacity);
		headerSize=FLAGS_SIZE.bytes+
		           bodySize.bytes*2;
	}
	
	public void writeHeader(ContentWriter dest) throws IOException{
		try(var buff=dest.writeTicket(headerSize).requireExact().submit()){
			FlagWriter.writeSingle(buff, NumberSize.FLAG_INFO, false, bodySize);
			
			bodySize.write(buff, capacity);
			bodySize.write(buff, size);
		}
	}
	
	public void readHeader(IOInterface src) throws IOException{
		try(var io=src.read(getPtr().getValue())){
			readHeader(io);
		}
	}
	public void readHeader(ContentReader src) throws IOException{
		bodySize=FlagReader.readSingle(src, FLAGS_SIZE, NumberSize.FLAG_INFO, false);
		
		capacity=bodySize.read(src);
		size=bodySize.read(src);
	}
	
	@Override
	public RandomIO io() throws IOException{
		return cluster.getSource().ioAt(bodyStart());
	}
	
	public long bodyStart(){
		return ptr.add(headerSize);
	}
	
	public ChunkPointer getPtr(){
		return ptr;
	}
	public long getSize(){
		return size;
	}
	public long getCapacity(){
		return capacity;
	}
	public long totalSize(){
		return headerSize+capacity;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Chunk chunk)) return false;
		return getCapacity()==chunk.getCapacity()&&
		       getSize()==chunk.getSize()&&
		       getPtr().equals(chunk.getPtr())&&
		       bodySize==chunk.bodySize&&
		       Objects.equals(cluster, chunk.cluster);
	}
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+getPtr().hashCode();
		result=31*result+bodySize.hashCode();
		result=31*result+Long.hashCode(getCapacity());
		result=31*result+Long.hashCode(getSize());
		
		return result;
	}
}
