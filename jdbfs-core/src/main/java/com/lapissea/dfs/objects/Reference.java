package com.lapissea.dfs.objects;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.internal.Preload;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@StructPipe.Special
@IOValue
public final class Reference extends IOInstance.Managed<Reference>{
	
	public static final Struct<Reference> STRUCT = Struct.of(Reference.class);
	
	static{
		if(ConfigDefs.OPTIMIZED_PIPE_USE_REFERENCE.resolveValLocking()){
			StandardStructPipe.registerSpecialImpl(STRUCT, () -> new StandardStructPipe<>(STRUCT, (t, structFields, testRun) -> {
				var res = StandardStructPipe.<Reference>compiler().compile(t, structFields);
				var f   = res.fields();
				if(
					f.get(0) instanceof BitFieldMerger<?> m && Iters.from(m.fieldGroup()).toModList(IOField::getName).equals(List.of("offsetSize", "ptrSize")) &&
					f.get(1).getName().equals("offset") &&
					f.get(2).getName().equals("ptr")
				){
					return res;
				}
				
				throw new ShouldNeverHappenError(f.toString());
			}, StagedInit.STATE_NOT_STARTED){
				
				private static final int NULL_HEADER = (int)BitFieldMerger.calcIntegrityBits(0, 2, 6);
				
				@Override
				protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<Reference> ioPool, Reference instance) throws IOException{
					var off  = instance.getOffset();
					var ptr1 = instance.getPtr();
					
					if(off == 0 && ptr1.isNull()){
						dest.writeInt1(NULL_HEADER);
						return;
					}
					
					var offsetSize = NumberSize.bySize(off);
					var ptrSize    = NumberSize.bySize(ptr1);
					
					var header = offsetSize.ordinal()|(ptrSize.ordinal()<<3);
					
					dest.writeInt1(header|(int)BitFieldMerger.calcIntegrityBits(header, 2, 6));
					offsetSize.write(dest, off);
					ptrSize.write(dest, ptr1);
				}
				@Override
				public Reference readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					int flags = src.readUnsignedInt1();
					if(flags == NULL_HEADER) return Reference.NULL;
					
					var offsetSize = NumberSize.ordinal(flags&0b111);
					var ptrSize    = NumberSize.ordinal((flags >>> 3)&0b111);
					BitFieldMerger.readIntegrityBits(flags, 8, 6);
					
					var off = offsetSize.read(src);
					var ptr = ChunkPointer.of(ptrSize.read(src));
					
					return Reference.of(ptr, off);
				}
				@Override
				protected Reference doRead(VarPool<Reference> ioPool, DataProvider provider, ContentReader src, Reference instance, GenericContext genericContext) throws IOException{
					return readNew(provider, src, genericContext);
				}
			});
			FixedStructPipe.registerSpecialImpl(STRUCT, () -> new FixedStructPipe<>(STRUCT, (t, structFields, testRun) -> {
				var res = FixedStructPipe.<Reference>compiler().compile(t, structFields);
				var f   = res.fields();
				if(
					f.get(0).getName().equals("offset") &&
					f.get(1).getName().equals("ptr")
				){
					return res;
				}
				
				throw new ShouldNeverHappenError(f.toString());
			}, StagedInit.STATE_NOT_STARTED){
				@Override
				protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<Reference> ioPool, Reference instance) throws IOException{
					
					var off = instance.getOffset();
					var ptr = instance.getPtr();
					
					dest.writeInt8(off);
					dest.writeInt8(ptr.getValue());
				}
				@Override
				public Reference readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					var off = src.readInt8();
					var ptr = ChunkPointer.of(src.readInt8());
					
					return Reference.of(ptr, off);
				}
				@Override
				protected Reference doRead(VarPool<Reference> ioPool, DataProvider provider, ContentReader src, Reference instance, GenericContext genericContext) throws IOException{
					return readNew(provider, src, genericContext);
				}
			});
		}
	}
	
	
	private static FixedStructPipe<Reference> FIXED_PIPE;
	private static StructPipe<Reference>      STANDARD_PIPE;
	
	static{
		Preload.preloadFn(Reference.class, "ensureFixed");
		Preload.preloadFn(Reference.class, "ensureStandard");
	}
	
	public static FixedStructPipe<Reference> fixedPipe(){
		if(FIXED_PIPE == null) ensureFixed();
		return FIXED_PIPE;
	}
	
	public static StructPipe<Reference> standardPipe(){
		if(STANDARD_PIPE == null) ensureStandard();
		return STANDARD_PIPE;
	}
	
	private static void ensureFixed(){
		if(FIXED_PIPE != null) return;
		FIXED_PIPE = FixedStructPipe.of(STRUCT);
	}
	private static void ensureStandard(){
		if(STANDARD_PIPE != null) return;
		STANDARD_PIPE = StandardStructPipe.of(STRUCT);
	}
	
	public static final Reference NULL = new Reference(ChunkPointer.NULL, 0);
	
	public static Reference of(){
		return NULL;
	}
	public static Reference of(ChunkPointer ptr, long offset){
		return new Reference(ptr, offset);
	}
	
	@IODependency.VirtualNumSize(name = "ptrSize")
	private final ChunkPointer ptr;
	@IODependency.VirtualNumSize(name = "offsetSize")
	@IOValue.Unsigned
	private final long         offset;
	
	public Reference(ChunkPointer ptr, long offset){
		super(STRUCT);
		this.ptr = Objects.requireNonNull(ptr);
		this.offset = offset;
		if(offset<0) throw new IllegalArgumentException("Offset can not be negative");
	}
	
	public RandomIO io(DataProvider.Holder holder) throws IOException{
		return io(holder.getDataProvider());
	}
	public RandomIO io(DataProvider provider) throws IOException{
		var chunk = ptr.dereference(provider);
		var io    = chunk.io();
		try{
			io.skipExact(offset);
		}catch(Throwable e){
			io.close();
			throw e;
		}
		return io;
	}
	
	public void io(DataProvider provider, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
		try(var io = io(provider)){
			session.accept(io);
		}
	}
	public <T> T ioMap(DataProvider provider, UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
		try(var io = io(provider)){
			return session.apply(io);
		}
	}
	
	public <T> void writeAtomic(DataProvider provider, boolean trim, ObjectPipe<T, ?> pipe, T val) throws IOException{
		try(var ignored = provider.getSource().openIOTransaction()){
			write(provider, trim, pipe, val);
		}
	}
	public <T> void write(DataProvider provider, boolean trim, ObjectPipe<T, ?> pipe, T val) throws IOException{
		try(var io = io(provider)){
			pipe.write(provider, io, val);
			if(trim) io.trim();
		}
	}
	public <T> T readNew(DataProvider provider, ObjectPipe<T, ?> pipe, GenericContext genericContext) throws IOException{
		try(var io = io(provider)){
			return pipe.readNew(provider, io, genericContext);
		}
	}
	public <T> T read(DataProvider provider, ObjectPipe<T, ?> pipe, GenericContext genericContext) throws IOException{
		try(var io = io(provider)){
			return pipe.readNew(provider, io, genericContext);
		}
	}
	
	
	public ChunkPointer asPtr(){
		if(offset != 0) throw new UnsupportedOperationException();
		return ptr;
	}
	
	public ChunkPointer getPtr(){ return ptr; }
	public long getOffset()     { return offset; }
	
	@Override
	public boolean equals(Object obj){
		if(obj == this) return true;
		if(obj == null || obj.getClass() != this.getClass()) return false;
		var that = (Reference)obj;
		return Objects.equals(this.ptr, that.ptr) &&
		       this.offset == that.offset;
	}
	@Override
	public int hashCode(){
		return Objects.hash(ptr, offset);
	}
	@Override
	public String toShortString(){
		return toString();
	}
	@Override
	public String toString(){
		if(ptr.isNull()) return ptr.toString();
		return ptr + "+" + offset;
	}
	
	public boolean isNull(){
		return ptr.isNull();
	}
	
	public Reference requireNonNull(){
		if(isNull()) throw new NullPointerException("Reference is null");
		return this;
	}
	
	public Reference addOffset(long offset){
		return new Reference(getPtr(), getOffset() + offset);
	}
	public long calcGlobalOffset(DataProvider provider) throws IOException{
		Chunk cursor      = getPtr().dereference(provider);
		long  cursorStart = 0, localPos;
		setPos:
		{
			var pos = getOffset();
			while(true){
				var cursorEffectiveCapacity = cursor.hasNextPtr()? cursor.getCapacity() : cursor.getSize();
				
				long curserEnd = cursorStart + cursorEffectiveCapacity;
				if(curserEnd>pos){
					localPos = pos;
					break setPos;
				}
				
				boolean result;
				tryAdvanceCursor:
				{
					var last = cursor;
					var next = cursor.next();
					if(next == null){
						result = false;
						break tryAdvanceCursor;
					}
					cursor = next;
					cursorStart += last.getSize();
					result = true;
				}
				
				if(!result){//end reached
					localPos = curserEnd;
					break setPos;
				}
			}
		}
		
		var cursorOffset = localPos - cursorStart;
		return cursor.dataStart() + cursorOffset;
	}
	
	public ChunkPointer asJustPointer(){
		if(offset != 0){
			throw new IllegalStateException("Reference " + this + " has an offset");
		}
		return ptr;
	}
	public Chunk asJustChunk(DataProvider provider) throws IOException{
		return provider.getChunk(asJustPointer());
	}
	
	public String infoString(DataProvider provider) throws IOException{
		return this + " / " + calcGlobalOffset(provider);
	}
}
