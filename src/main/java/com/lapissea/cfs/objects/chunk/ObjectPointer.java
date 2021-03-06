package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class ObjectPointer<T extends IOInstance>{
	
	public static class FixedIO implements ReaderWriter<ObjectPointer<?>>{
		
		private static final OptionalInt SIZ=OptionalInt.of(NumberSize.LARGEST.bytes*2);
		
		@Override
		public ObjectPointer<?> read(Object targetObj, Cluster cluster, ContentReader source, ObjectPointer<?> oldValue) throws IOException{
			oldValue.set(ChunkPointer.ofNullable(NumberSize.LARGEST.read(source)), NumberSize.LARGEST.read(source));
			return oldValue;
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ObjectPointer<?> source) throws IOException{
			NumberSize.LARGEST.write(target, source==null?null:source.dataBlock);
			NumberSize.LARGEST.write(target, source==null?0:source.offset);
		}
		
		@Override
		public long mapSize(Object targetObj, ObjectPointer<?> source){ return SIZ.orElseThrow(); }
		
		@Override
		public OptionalInt getFixedSize(){ return SIZ; }
		
		@Override
		public OptionalInt getMaxSize(){ return SIZ; }
	}
	
	public static class FixedNoOffsetIO implements ReaderWriter<ObjectPointer<?>>{
		
		private static final OptionalInt SIZ=OptionalInt.of(NumberSize.LARGEST.bytes);
		
		@Override
		public ObjectPointer<?> read(Object targetObj, Cluster cluster, ContentReader source, ObjectPointer<?> oldValue) throws IOException{
			oldValue.set(ChunkPointer.ofNullable(NumberSize.LARGEST.read(source)), 0);
			return oldValue;
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ObjectPointer<?> source) throws IOException{
			NumberSize.LARGEST.write(target, source==null?null:source.dataBlock);
			assert source==null||source.offset==0:source.offset;
			
		}
		
		@Override
		public long mapSize(Object targetObj, ObjectPointer<?> source){ return SIZ.orElseThrow(); }
		
		@Override
		public OptionalInt getFixedSize(){ return SIZ; }
		
		@Override
		public OptionalInt getMaxSize(){ return SIZ; }
	}
	
	public static class AutoSizedIO implements ReaderWriter<ObjectPointer<?>>{
		
		private final NumberSize minSize;
		
		public AutoSizedIO()                  {this(NumberSize.VOID);}
		
		public AutoSizedIO(NumberSize minSize){this.minSize=minSize;}
		
		@Override
		public ObjectPointer<?> read(Object targetObj, Cluster cluster, ContentReader source, ObjectPointer<?> oldValue) throws IOException{
			NumberSize ptrSiz;
			NumberSize offSiz;
			
			try(var flags=FlagReader.read(source, NumberSize.SMALEST_REAL)){
				ptrSiz=NumberSize.FLAG_INFO.read(flags);
				offSiz=NumberSize.FLAG_INFO.read(flags);
			}
			
			var ptr=ptrSiz.read(source);
			var off=offSiz.read(source);
			
			oldValue.set(ChunkPointer.ofNullable(ptr), off);
			
			return oldValue;
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ObjectPointer<?> source) throws IOException{
			var ptr=source==null?0:ChunkPointer.getValueNullable(source.getDataBlock());
			var off=source==null?0:source.getOffset();
			
			NumberSize ptrSiz=NumberSize.bySize(ptr).max(minSize);
			NumberSize offSiz=NumberSize.bySize(off).max(minSize);
			
			try(var flags=new FlagWriter.AutoPop(NumberSize.SMALEST_REAL, target)){
				flags.writeEnum(NumberSize.FLAG_INFO, ptrSiz, false);
				flags.writeEnum(NumberSize.FLAG_INFO, offSiz, false);
			}
			
			ptrSiz.write(target, ptr);
			offSiz.write(target, off);
		}
		
		@Override
		public long mapSize(Object targetObj, ObjectPointer<?> source){
			var ptr=source==null?0:ChunkPointer.getValueNullable(source.getDataBlock());
			var off=source==null?0:source.getOffset();
			
			NumberSize ptrSiz=NumberSize.bySize(ptr).max(minSize);
			NumberSize offSiz=NumberSize.bySize(off).max(minSize);
			
			return 1+
			       ptrSiz.bytes+
			       offSiz.bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.empty();
		}
		
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.of(1+NumberSize.LARGEST.bytes*2);
		}
	}
	
	public static class AutoSizedNoOffsetIO implements ReaderWriter<ObjectPointer<?>>{
		
		private final NumberSize minSize;
		
		public AutoSizedNoOffsetIO()                  {this(NumberSize.VOID);}
		
		public AutoSizedNoOffsetIO(String minSize)    {this(NumberSize.valueOf(minSize));}
		
		public AutoSizedNoOffsetIO(NumberSize minSize){this.minSize=minSize;}
		
		@Override
		public ObjectPointer<?> read(Object targetObj, Cluster cluster, ContentReader source, ObjectPointer<?> oldValue) throws IOException{
			NumberSize ptrSiz;
			
			try(var flags=FlagReader.read(source, NumberSize.SMALEST_REAL)){
				ptrSiz=NumberSize.FLAG_INFO.read(flags);
				NumberSize.FLAG_INFO.read(flags);
			}
			
			var ptr=ptrSiz.read(source);
			
			oldValue.set(ChunkPointer.ofNullable(ptr), 0);
			
			return oldValue;
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ObjectPointer<?> source) throws IOException{
			var ptr=source==null?0:ChunkPointer.getValueNullable(source.getDataBlock());
			
			NumberSize ptrSiz=NumberSize.bySize(ptr).max(minSize);
			
			try(var flags=new FlagWriter.AutoPop(NumberSize.SMALEST_REAL, target)){
				NumberSize.FLAG_INFO.write(ptrSiz, flags);
				NumberSize.FLAG_INFO.write(NumberSize.VOID, flags);
			}
			
			ptrSiz.write(target, ptr);
		}
		
		@Override
		public long mapSize(Object targetObj, ObjectPointer<?> source){
			var ptr=source==null?0:ChunkPointer.getValueNullable(source.getDataBlock());
			
			NumberSize ptrSiz=NumberSize.bySize(ptr).max(minSize);
			
			return 1+
			       ptrSiz.bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.empty();
		}
		
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.of(1+NumberSize.LARGEST.bytes);
		}
	}
	
	public static class StructCached<T extends IOInstance> extends Struct<T>{
		private final Function<Chunk, T> cacheFetch;
		
		public StructCached(ChunkPointer dataBlock, long offset, UnsafeFunction<Chunk, T, IOException> constructor, Function<Chunk, T> cacheFetch){
			super(dataBlock, offset, constructor);
			this.cacheFetch=cacheFetch;
		}
		
		public StructCached(UnsafeFunction<Chunk, T, IOException> constructor, Function<Chunk, T> cacheFetch){
			super(constructor);
			this.cacheFetch=cacheFetch;
		}
		
		@Override
		public T read(Cluster cluster) throws IOException{
			T cached=cacheFetch.apply(getBlock(cluster));
			if(cached!=null) return cached;
			return super.read(cluster);
		}
	}
	
	public static class Struct<T extends IOInstance> extends ObjectPointer<T>{
		
		private final UnsafeFunction<Chunk, T, IOException> constructor;
		
		public Struct(ChunkPointer dataBlock, long offset, UnsafeFunction<Chunk, T, IOException> constructor){
			super(dataBlock, offset);
			this.constructor=constructor;
		}
		
		public Struct(UnsafeFunction<Chunk, T, IOException> constructor){
			this.constructor=constructor;
		}
		
		@Override
		protected T construct(Chunk chunk) throws IOException{
			return constructor.apply(chunk);
		}
		
		@Override
		protected void write(Cluster cluster, T value, RandomIO target) throws IOException{
			value.writeStruct(cluster, target);
		}
		
		@Override
		protected T read(Cluster cluster, RandomIO io, T value) throws IOException{
			value.readStruct(cluster, io);
			return value;
		}
	}
	
	public static class Raw extends ObjectPointer<IOInstance>{
		
		public Raw(ChunkPointer dataBlock, long offset){
			super(dataBlock, offset);
		}
		
		public Raw(){ }
		
		@Override
		protected void write(Cluster cluster, IOInstance value, RandomIO io) throws IOException{
			throw new UnsupportedOperationException();
		}
		
		@Override
		protected IOInstance read(Cluster cluster, RandomIO io, IOInstance value) throws IOException{
			throw new UnsupportedOperationException();
		}
	}
	
	private ChunkPointer dataBlock;
	private long         offset;
	
	
	public ObjectPointer(){
	
	}
	
	public ObjectPointer(ChunkPointer dataBlock, long offset){
		set(dataBlock, offset);
	}
	
	public void unset()                                 { set(null, 0); }
	
	public ObjectPointer<T> set(ObjectPointer<?> offset){ return set(offset.dataBlock, offset.offset); }
	
	public ObjectPointer<T> set(ChunkPointer dataBlock, long offset){
		this.dataBlock=dataBlock;
		setOffset(offset);
		return this;
	}
	
	public void setOffset(long offset){
		this.offset=offset;
	}
	
	protected abstract void write(Cluster cluster, T value, RandomIO io) throws IOException;
	
	protected abstract T read(Cluster cluster, RandomIO io, T value) throws IOException;
	
	protected T construct(Chunk chunk) throws IOException{
		throw new UnsupportedOperationException();
	}
	
	public void write(Cluster cluster, T value) throws IOException{
		try(RandomIO io=io(cluster)){
			write(cluster, value, io);
		}
	}
	
	public T read(Cluster cluster) throws IOException{
		if(dataBlock==null) return null;
		T value=construct(getBlock(cluster));
		try(RandomIO io=io(cluster)){
			if(io.isEmpty()) return value;
			return read(cluster, io, value);
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
	
	public long getOffset(){
		return offset;
	}
	
	public RandomIO io(Cluster cluster) throws IOException{
		return getBlock(cluster).ioAt(getOffset());
	}
	
	public void io(Cluster cluster, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
		try(var io=io(cluster)){
			session.accept(io);
		}
	}
	
	public <L> L io(Cluster cluster, UnsafeFunction<RandomIO, L, IOException> session) throws IOException{
		try(var io=io(cluster)){
			return session.apply(io);
		}
	}
	
	public long globalOffset(Cluster cluster) throws IOException{
		try(var io=io(cluster)){
			return io.getGlobalPos();
		}
	}
	
	public ObjectPointer<T> addOffset(long offset){
		setOffset(getOffset()+offset);
		return this;
	}
	
	@Override
	public String toString(){
		return getDataBlock()==null?"null":getDataBlock()+" >> "+getOffset();
	}
	
	public boolean hasPtr(){
		return getDataBlock()!=null;
	}
}
