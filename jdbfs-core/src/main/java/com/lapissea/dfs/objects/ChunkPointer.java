package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalLong;

public final class ChunkPointer implements Comparable<ChunkPointer>{
	
	public static final ChunkPointer NULL = new ChunkPointer(0);
	
	public static final BasicSizeDescriptor<ChunkPointer, Void> DYN_SIZE_DESCRIPTOR = BasicSizeDescriptor.Unknown.of(
		WordSpace.BYTE, 1, OptionalLong.of(1 + NumberSize.LONG.bytes),
		(pool, prov, value) -> 1 + NumberSize.bySize(value).bytes
	);
	
	public static final ObjectPipe.NoPool<ChunkPointer> DYN_PIPE = new ObjectPipe.NoPool<>(){
		@Override
		public void write(DataProvider provider, ContentWriter dest, ChunkPointer instance) throws IOException{
			var size = NumberSize.bySize(instance);
			FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, size);
			size.write(dest, instance);
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			var size = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			size.skip(src);
		}
		@Override
		public ChunkPointer readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			var size = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			var val  = size.read(src);
			return of(val);
		}
		
		@Override
		public BasicSizeDescriptor<ChunkPointer, Void> getSizeDescriptor(){
			return DYN_SIZE_DESCRIPTOR;
		}
	};
	
	public static final Map<NumberSize, ObjectPipe.NoPool<ChunkPointer>> FIXED_PIPES;
	
	static{
		var pipes = new EnumMap<NumberSize, ObjectPipe.NoPool<ChunkPointer>>(NumberSize.class);
		
		for(var size : NumberSize.FLAG_INFO){
			pipes.put(size, new ObjectPipe.NoPool<>(){
				private final BasicSizeDescriptor<ChunkPointer, Void> desc = BasicSizeDescriptor.IFixed.Basic.of(size.bytes);
				@Override
				public void write(DataProvider provider, ContentWriter dest, ChunkPointer instance) throws IOException{
					size.write(dest, instance);
				}
				@Override
				public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					size.skip(src);
				}
				@Override
				public ChunkPointer readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					return ChunkPointer.read(size, src);
				}
				@Override
				public BasicSizeDescriptor<ChunkPointer, Void> getSizeDescriptor(){
					return desc;
				}
			});
		}
		
		pipes.put(NumberSize.VOID, new ObjectPipe.NoPool<>(){
			private final BasicSizeDescriptor<ChunkPointer, Void> desc = BasicSizeDescriptor.IFixed.Basic.of(0);
			@Override
			public void write(DataProvider provider, ContentWriter dest, ChunkPointer instance){ }
			@Override
			public void skip(DataProvider provider, ContentReader src, GenericContext genericContext){ }
			@Override
			public ChunkPointer readNew(DataProvider provider, ContentReader src, GenericContext genericContext){
				return ChunkPointer.NULL;
			}
			@Override
			public BasicSizeDescriptor<ChunkPointer, Void> getSizeDescriptor(){
				return desc;
			}
		});
		FIXED_PIPES = Collections.unmodifiableMap(pipes);
	}
	
	public static ObjectPipe.NoPool<ChunkPointer> varSizePipe(VaryingSize.Provider provider){
		var size = provider.provide(NumberSize.LARGEST, null, true);
		var desc = FIXED_PIPES.get(size.size).getSizeDescriptor();
		return new ObjectPipe.NoPool<>(){
			@Override
			public void write(DataProvider provider, ContentWriter dest, ChunkPointer instance) throws IOException{
				var siz = size.safeNumber(instance.getValue());
				siz.write(dest, instance);
			}
			@Override
			public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				size.size.skip(src);
			}
			@Override
			public ChunkPointer readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
				return ChunkPointer.read(size.size, src);
			}
			@Override
			public BasicSizeDescriptor<ChunkPointer, Void> getSizeDescriptor(){
				return desc;
			}
		};
	}
	
	@NotNull
	public static ChunkPointer read(NumberSize size, ContentReader src) throws IOException{
		return ChunkPointer.of(size.read(src));
	}
	
	@NotNull
	public static ChunkPointer of(long value){
		return value == 0? NULL : new ChunkPointer(value);
	}
	
	private final long value;
	
	private ChunkPointer(long value){
		if(value<0) throw new IllegalArgumentException();
		this.value = value;
	}
	
	public Chunk dereference(DataProvider provider) throws IOException{
		requireNonNull();
		return provider.getChunk(this);
	}
	
	@Override
	public String toString(){
		if(isNull()) return "NULL";
		return "*" + getValue();
	}
	
	public ChunkPointer addPtr(ChunkPointer value){
		requireNonNull();
		return addPtr(value.getValue());
	}
	
	public ChunkPointer addPtr(long value){
		requireNonNull();
		return new ChunkPointer(getValue() + value);
	}
	
	public long add(ChunkPointer value){
		requireNonNull();
		return add(value.getValue());
	}
	
	public long add(long value){
		requireNonNull();
		return getValue() + value;
	}
	
	@Override
	public boolean equals(Object o){
		return o instanceof ChunkPointer num &&
		       equals(num.getValue());
	}
	public boolean equals(ChunkPointer o){
		return equals(o.getValue());
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getValue());
	}
	
	public Reference makeReference(){
		return makeReference(0);
	}
	public Reference makeReference(long offset){
		return new Reference(this, offset);
	}
	
	public void requireNonNull(){
		if(isNull()) throw new NullPointerException("Pointer is null");
	}
	public boolean isNull(){
		return getValue() == 0;
	}
	
	@Override
	public int compareTo(ChunkPointer o){
		return compareTo(o.getValue());
	}
	
	public long getValue(){
		return value;
	}
	
	public boolean equals(long value){
		return getValue() == value;
	}
	
	public int compareTo(long o){
		return Long.compare(getValue(), o);
	}
}
