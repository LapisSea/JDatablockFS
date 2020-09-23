package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Supplier;

public abstract class ObjectPointer<T>{
	
	public static class FixedIO implements ReaderWriter<ObjectPointer<?>>{
		
		private static final OptionalInt SIZ=OptionalInt.of(NumberSize.LARGEST.bytes*2);
		
		@Override
		public ObjectPointer<?> read(Object targetObj, ContentReader source, ObjectPointer<?> oldValue) throws IOException{
			oldValue.dataBlock=new ChunkPointer(NumberSize.LARGEST.read(source));
			oldValue.offset=NumberSize.LARGEST.read(source);
			return oldValue;
		}
		
		@Override
		public void write(Object targetObj, ContentWriter target, ObjectPointer<?> source) throws IOException{
			NumberSize.LARGEST.write(target, source.dataBlock);
			NumberSize.LARGEST.write(target, source.offset);
		}
		
		@Override
		public long mapSize(Object targetObj, ObjectPointer<?> source){
			return NumberSize.LARGEST.bytes*2;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return SIZ;
		}
		@Override
		public OptionalInt getMaxSize(){
			return getFixedSize();
		}
	}
	
	public static class Struct<T extends IOInstance> extends ObjectPointer<T>{
		
		private final Supplier<T> constructor;
		
		public Struct(ChunkPointer dataBlock, long offset, Supplier<T> constructor){
			super(dataBlock, offset);
			this.constructor=constructor;
		}
		
		public Struct(Supplier<T> constructor){
			this.constructor=constructor;
		}
		
		@Override
		protected void write(Cluster cluster, T value, RandomIO io) throws IOException{
			value.writeStruct(cluster, io);
		}
		
		@Override
		protected T read(Cluster cluster, RandomIO io) throws IOException{
			T t=constructor.get();
			t.readStruct(cluster, io);
			return t;
		}
	}
	
	private ChunkPointer dataBlock;
	private long         offset;
	
	
	public ObjectPointer(){ }
	public ObjectPointer(ChunkPointer dataBlock, long offset){
		set(dataBlock, offset);
	}
	
	public void unset(){ set(null, 0); }
	public void set(ChunkPointer dataBlock, long offset){
		this.dataBlock=dataBlock;
		this.offset=offset;
	}
	
	protected abstract void write(Cluster cluster, T value, RandomIO io) throws IOException;
	protected abstract T read(Cluster cluster, RandomIO io) throws IOException;
	
	public void write(Cluster cluster, T value) throws IOException{
		try(RandomIO io=getBlock(cluster).ioAt(offset)){
			write(cluster, value, io);
		}
	}
	
	public T read(Cluster cluster) throws IOException{
		if(dataBlock==null) return null;
		try(RandomIO io=getBlock(cluster).ioAt(offset)){
			return read(cluster, io);
		}
	}
	
	public Chunk getBlock(Cluster cluster) throws IOException{
		return dataBlock.dereference(cluster);
	}
	
	@Override
	public boolean equals(Object o){
		return o==this||
		       o instanceof ObjectPointer<?> p&&
		       equals(p);
	}
	public boolean equals(ObjectPointer<?> o){
		if(o==null) return false;
		return o==this||
		       Objects.equals(dataBlock, o.dataBlock)&&
		       offset==o.offset;
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+(dataBlock==null?0:dataBlock.hashCode());
		result=31*result+Long.hashCode(offset);
		return result;
	}
	
	public ChunkPointer getDataBlock(){
		return dataBlock;
	}
	
}
