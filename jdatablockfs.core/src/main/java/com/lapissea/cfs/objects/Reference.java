package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.OffsetIO;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class Reference extends IOInstance<Reference>{
	
	@SuppressWarnings("unchecked")
	public static final Struct<Reference> STRUCT=(Struct<Reference>)Struct.thisClass();
	
	static{
		boolean useOptimized=!UtilL.sysPropertyByClass(Chunk.class, "DO_NOT_USE_OPTIMIZED_PIPE", false, Boolean::parseBoolean);
		if(useOptimized){
			ContiguousStructPipe.registerSpecialImpl(STRUCT, ()->new ContiguousStructPipe<>(STRUCT, true){
				@Override
				protected List<IOField<Reference, ?>> initFields(){
					var f=super.initFields();
					if(
						f.get(0) instanceof BitFieldMerger<?> m&&m.fieldGroup().stream().map(IOField::getName).toList().equals(List.of("offsetSize", "ptrSize"))&&
						f.get(1).getName().equals("offset")&&
						f.get(2).getName().equals("ptr")
					){
						return f;
					}
					
					throw new ShouldNeverHappenError(f.toString());
				}
				@Override
				protected void doWrite(DataProvider provider, ContentWriter dest, Struct.Pool<Reference> ioPool, Reference instance) throws IOException{
					
					var off=instance.getOffset();
					var ptr=instance.getPtr();
					
					var offsetSize=NumberSize.bySize(off);
					var ptrSize   =NumberSize.bySize(ptr);
					
					var flags=offsetSize.ordinal()|(ptrSize.ordinal()<<3)|(0b11<<6);
					
					dest.writeInt1(flags);
					offsetSize.write(dest, off);
					ptrSize.write(dest, ptr);
				}
				@Override
				protected Reference doRead(Struct.Pool<Reference> ioPool, DataProvider provider, ContentReader src, Reference instance, GenericContext genericContext) throws IOException{
					int flags     =src.readInt1()&0xFF;
					var offsetSize=NumberSize.ordinal(flags&0b111);
					var ptrSize   =NumberSize.ordinal(flags&(0b111<<3));
					if((flags&(0b11<<6))!=(0b11<<6)){
						throw new IOException();
					}
					
					var off=offsetSize.read(src);
					var ptr=ChunkPointer.of(ptrSize.read(src));
					
					instance.offset=off;
					instance.ptr=ptr;
					return instance;
				}
			});
			FixedContiguousStructPipe.registerSpecialImpl(STRUCT, ()->new FixedContiguousStructPipe<>(STRUCT, true){
				@Override
				protected List<IOField<Reference, ?>> initFields(){
					var f=super.initFields();
					if(
						f.get(0).getName().equals("offset")&&
						f.get(1).getName().equals("ptr")
					){
						return f;
					}
					
					throw new ShouldNeverHappenError(f.toString());
				}
				@Override
				protected void doWrite(DataProvider provider, ContentWriter dest, Struct.Pool<Reference> ioPool, Reference instance) throws IOException{
					
					var off=instance.getOffset();
					var ptr=instance.getPtr();
					
					dest.writeInt8(off);
					dest.writeInt8(ptr.getValue());
				}
				@Override
				protected Reference doRead(Struct.Pool<Reference> ioPool, DataProvider provider, ContentReader src, Reference instance, GenericContext genericContext) throws IOException{
					var off=src.readInt8();
					var ptr=ChunkPointer.of(src.readInt8());
					
					instance.offset=off;
					instance.ptr=ptr;
					
					return instance;
				}
			});
		}
	}
	
	public static final FixedContiguousStructPipe<Reference> FIXED_PIPE=FixedContiguousStructPipe.of(STRUCT);
	
	private static final class IOContext implements RandomIO.Creator{
		private final Reference    ref;
		private final DataProvider provider;
		
		public IOContext(Reference ref, DataProvider provider){
			this.ref=ref;
			this.provider=provider;
		}
		
		@Override
		public RandomIO io() throws IOException{
			return ref.io(provider);
		}
		
		@Override
		public String toString(){
			return "{"+provider+" @ "+ref+"}";
		}
	}
	
	@IOValue
	@IODependency.VirtualNumSize(name="ptrSize")
	private ChunkPointer ptr;
	@IOValue
	@IODependency.VirtualNumSize(name="offsetSize")
	private long         offset;
	
	public Reference(){
		this(ChunkPointer.NULL, 0);
	}
	
	public Reference(ChunkPointer ptr, long offset){
		super(STRUCT);
		this.ptr=Objects.requireNonNull(ptr);
		this.offset=offset;
		if(offset<0) throw new IllegalArgumentException("Offset can not be negative");
	}
	
	public RandomIO.Creator withContext(DataProvider provider){
		return new IOContext(this, provider);
	}
	public RandomIO.Creator withContext(DataProvider.Holder holder){
		return new IOContext(this, holder.getDataProvider());
	}
	
	public RandomIO io(DataProvider.Holder holder) throws IOException{
		return io(holder.getDataProvider());
	}
	public RandomIO io(DataProvider provider) throws IOException{
		return OffsetIO.of(ptr.dereference(provider), offset);
	}
	
	public ChunkPointer getPtr(){return ptr;}
	public long getOffset()     {return offset;}
	
	@Override
	public boolean equals(Object obj){
		if(obj==this) return true;
		if(obj==null||obj.getClass()!=this.getClass()) return false;
		var that=(Reference)obj;
		return Objects.equals(this.ptr, that.ptr)&&
		       this.offset==that.offset;
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
		return ptr+"+"+offset;
	}
	
	public boolean isNull(){
		return ptr.isNull();
	}
	
	public Reference requireNonNull(){
		if(isNull()) throw new NullPointerException("Reference is null");
		return this;
	}
	
	public Reference addOffset(long offset){
		return new Reference(getPtr(), getOffset()+offset);
	}
	public long calcGlobalOffset(DataProvider provider) throws IOException{
		try(var io=new ChunkChainIO(getPtr().dereference(provider))){
			io.setPos(getOffset());
			return io.calcGlobalPos();
		}
	}
	public String infoString(DataProvider provider) throws IOException{
		return this+" / "+calcGlobalOffset(provider);
	}
}
