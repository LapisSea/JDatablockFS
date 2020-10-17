package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.TypeParser;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;

//@ValueBased
public final class ChunkPointer implements INumber{
	
	public static final TypeParser TYPE_PARSER=new TypeParser(){
		@Override
		public boolean canParse(Cluster cluster, IOType type){
			if(!type.getGenericArgs().isEmpty()) return false;
			return UtilL.instanceOf(type.getType().instanceClass, PtrRef.class);
		}
		
		@Override
		public UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type){
			assert canParse(cluster, type);
			return type.getType()::newInstance;
		}
	};
	
	public abstract static class PtrRef extends IOInstance{
		
		public abstract ChunkPointer getValue();
		
		@Override
		public String toString(){
			return getValue()==null?"*null":getValue().toString();
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof PtrRef ptrFixed&&
			       Objects.equals(getValue(), ptrFixed.getValue());
		}
		
		public boolean equals(ChunkPointer o){
			return Objects.equals(getValue(), o);
		}
		
		@Override
		public int hashCode(){
			return Objects.hashCode(getValue());
		}
		
		public Chunk dereference(Cluster cluster) throws IOException{
			return cluster.getChunk(getValue());
		}
		
	}
	
	public static class PtrFixed extends PtrRef{
		
		@Value(index=1, rw=ChunkPointer.FixedIO.class, rwArgs="LONG")
		private ChunkPointer value;
		
		public PtrFixed(){}
		
		public PtrFixed(ChunkPointer value){
			this.value=value;
		}
		
		@Override
		public ChunkPointer getValue(){
			return value;
		}
		
	}
	
	public static class PtrSmart extends PtrRef{
		
		@Value(index=1, rw=ChunkPointer.AutoSizedIO.class)
		private ChunkPointer value;
		
		
		public PtrSmart(){}
		
		public PtrSmart(ChunkPointer value){
			this.value=value;
		}
		
		@Override
		public ChunkPointer getValue(){
			return value;
		}
		
	}
	
	
	public static class FixedIO implements ReaderWriter<ChunkPointer>{
		
		private final NumberSize fixedSize;
		
		public FixedIO(String sizeName){
			this(NumberSize.valueOf(sizeName));
		}
		
		public FixedIO(NumberSize fixedSize){
			this.fixedSize=fixedSize;
		}
		
		@Override
		public ChunkPointer read(Object targetObj, Cluster cluster, ContentReader source, ChunkPointer oldValue) throws IOException{
			return ChunkPointer.getNullable(fixedSize.read(source));
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ChunkPointer source) throws IOException{
			fixedSize.write(target, source);
		}
		
		@Override
		public long mapSize(Object targetObj, ChunkPointer source){
			return fixedSize.bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.of(fixedSize.bytes);
		}
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.of(NumberSize.LARGEST.bytes);
		}
	}
	
	public static class AutoSizedIO implements ReaderWriter<ChunkPointer>{
		
		final NumberSize minSize;
		
		public AutoSizedIO(String minSize){
			this(NumberSize.valueOf(minSize));
		}
		
		public AutoSizedIO(){
			this(NumberSize.VOID);
		}
		
		public AutoSizedIO(NumberSize minSize){
			this.minSize=minSize;
		}
		
		@Override
		public ChunkPointer read(Object targetObj, Cluster cluster, ContentReader source, ChunkPointer oldValue) throws IOException{
			NumberSize dataSize=FlagReader.readSingle(source, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO);
			return ChunkPointer.getNullable(dataSize.read(source));
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ChunkPointer source) throws IOException{
			NumberSize dataSize=NumberSize.bySizeVoidable(source);
			
			try(var flags=new FlagWriter.AutoPop(NumberSize.SMALEST_REAL, target)){
				flags.writeEnum(NumberSize.FLAG_INFO, dataSize);
			}
			
			dataSize.write(target, source);
		}
		
		@Override
		public long mapSize(Object targetObj, ChunkPointer source){
			return NumberSize.SMALEST_REAL.bytes+NumberSize.bySizeVoidable(source).bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){ return OptionalInt.empty();}
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.of(NumberSize.LARGEST.bytes);
		}
	}
	
	public static class WrittenSizeIO implements ReaderWriter<ChunkPointer>{
		
		private final Function<Object, NumberSize> sizeSource;
		private final String                       varName;
		
		public WrittenSizeIO(Class<?> owner, String varName) throws NoSuchFieldException{
			this.varName=varName;
			var siz=owner.getDeclaredField(varName);
			siz.setAccessible(true);
			if(siz.getType()!=NumberSize.class) throw new IllegalArgumentException(siz+" is not "+NumberSize.class);
			sizeSource=parent->{
				try{
					return (NumberSize)siz.get(parent);
				}catch(IllegalAccessException e){
					throw new RuntimeException(e);
				}
			};
		}
		
		private NumberSize getSize(Object targetObj){
			NumberSize siz=sizeSource.apply(targetObj);
			if(siz==null) throw new NullPointerException(targetObj.getClass().getName()+"."+varName+" can't be null!");
			return siz;
		}
		
		@Override
		public ChunkPointer read(Object targetObj, Cluster cluster, ContentReader source, ChunkPointer oldValue) throws IOException{
			return ChunkPointer.getNullable(getSize(targetObj).read(source));
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, ChunkPointer source) throws IOException{
			var siz=getSize(targetObj);
			if(source==null) siz.write(target, 0);
			else siz.write(target, source);
		}
		
		@Override
		public long mapSize(Object targetObj, ChunkPointer source){
			return getSize(targetObj).bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){ return OptionalInt.empty();}
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.of(NumberSize.LARGEST.bytes);
		}
	}
	
	
	public static ChunkPointer of(long value){
		return new ChunkPointer(value);
	}
	public static ChunkPointer of(INumber value){
		return new ChunkPointer(value.getValue());
	}
	
	public static long getValueNullable(ChunkPointer ptr){
		return ptr==null?0:ptr.getValue();
	}
	
	private final long value;
	
	public ChunkPointer(long value){
		this.value=value;
		assert value>0:
			this.toString();
	}
	
	public static ChunkPointer getNullable(long value){
		if(value==0) return null;
		return new ChunkPointer(value);
	}
	
	public ChunkPointer(INumber value){
		this(value.getValue());
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	public Chunk dereference(Cluster cluster) throws IOException{
		return cluster.getChunk(this);
	}
	
	@Override
	public String toString(){
		return "*"+getValue();
	}
	
	public ChunkPointer addPtr(INumber value){
		return addPtr(value.getValue());
	}
	public ChunkPointer addPtr(long value){
		return new ChunkPointer(getValue()+value);
	}
	public long add(INumber value){
		return add(value.getValue());
	}
	public long add(long value){
		return getValue()+value;
	}
	
	@Override
	public boolean equals(Object o){
		return o==this||
		       o instanceof INumber num&&
		       equals(num.getValue());
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getValue());
	}
}
