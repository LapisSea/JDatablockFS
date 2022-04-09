package com.lapissea.cfs.io;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;

public sealed interface ValueStorage<T>{
	
	final class FixedInstance<T extends IOInstance<T>> implements ValueStorage<T>{
		
		private final GenericContext               ctx;
		private final DataProvider                 provider;
		private final FixedContiguousStructPipe<T> pipe;
		private final long                         size;
		
		public FixedInstance(GenericContext ctx, DataProvider provider, FixedContiguousStructPipe<T> pipe){
			this.ctx=ctx;
			this.provider=provider;
			this.pipe=pipe;
			size=pipe.getFixedDescriptor().get(WordSpace.BYTE);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			return pipe.readNew(provider, src, ctx);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			pipe.write(provider, dest, src);
		}
		
		@Override
		public long inlineSize(){
			return size;
		}
		
		public FixedContiguousStructPipe<T> getPipe(){
			return pipe;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new IOField.NoIO<>(accessor, SizeDescriptor.Fixed.of(size));
		}
		
		@Override
		public RuntimeType<T> getType(){
			return pipe.getType();
		}
	}
	
	final class ReferencedInstance<T extends IOInstance<T>> implements ValueStorage<T>{
		
		private static final FixedContiguousStructPipe<Reference> REF_PIPE=FixedContiguousStructPipe.of(Reference.STRUCT);
		private static final long                                 SIZE    =REF_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		public ReferencedInstance(GenericContext ctx, DataProvider provider, StructPipe<T> pipe){
			this.ctx=ctx;
			this.provider=provider;
			this.pipe=pipe;
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref=REF_PIPE.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			try(var io=ref.io(provider)){
				return pipe.readNew(provider, io, ctx);
			}
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			var ref=REF_PIPE.readNew(provider, dest, null);
			if(ref.isNull()){
				var ch=AllocateTicket.withData(pipe, provider, src).submit(provider);
				REF_PIPE.write(provider, dest, ch.getPtr().makeReference());
				return;
			}
			
			try(var io=ref.io(provider)){
				pipe.write(provider, io, src);
			}
		}
		
		@Override
		public long inlineSize(){
			return SIZE;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new IOField.Ref.NoIO<>(accessor, REF_PIPE.getFixedDescriptor()){
				@Override
				public void setReference(I instance, Reference newRef){
					try(var io=ioAt.get()){
						REF_PIPE.write(provider, io, newRef);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public Reference getReference(I instance){
					try(var io=ioAt.get()){
						return REF_PIPE.readNew(provider, io, null);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public StructPipe<T> getReferencedPipe(I instance){
					return pipe;
				}
			};
		}
		
		@Override
		public RuntimeType<T> getType(){
			return pipe.getType();
		}
	}
	
	final class UnmanagedInstance<T extends IOInstance.Unmanaged<T>> implements ValueStorage<T>{
		
		private static final FixedContiguousStructPipe<Reference> REF_PIPE=FixedContiguousStructPipe.of(Reference.STRUCT);
		private static final long                                 SIZE    =REF_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		
		private final TypeLink            type;
		private final DataProvider        provider;
		private final Struct.Unmanaged<T> struct;
		
		@SuppressWarnings("unchecked")
		public UnmanagedInstance(TypeLink type, DataProvider provider){
			this.type=type;
			this.provider=provider;
			struct=(Struct.Unmanaged<T>)Struct.Unmanaged.ofUnknown(type.getTypeClass(provider.getTypeDb()));
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref=REF_PIPE.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			
			return struct.requireUnmanagedConstructor().create(provider, ref, type);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			REF_PIPE.write(provider, dest, src.getReference());
		}
		
		@Override
		public long inlineSize(){
			return SIZE;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public RuntimeType<T> getType(){
			return struct;
		}
	}
	
	final class Primitive<T> implements ValueStorage<T>{
		
		private final SupportedPrimitive type;
		private final long               size;
		
		public Primitive(SupportedPrimitive type){
			this.type=type;
			size=type.maxSize.requireFixed(WordSpace.BYTE);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			return (T)switch(type){
				case DOUBLE -> src.readFloat8();
				case FLOAT -> src.readFloat4();
				case LONG -> src.readInt8();
				case INT -> src.readInt4();
				case SHORT -> src.readInt2();
				case BYTE -> src.readInt1();
				case BOOLEAN -> src.readBoolean();
			};
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			switch(type){
				case DOUBLE -> dest.writeFloat8((Double)src);
				case FLOAT -> dest.writeFloat4((Float)src);
				case LONG -> dest.writeInt8((Long)src);
				case INT -> dest.writeInt4((Integer)src);
				case SHORT -> dest.writeInt2((Short)src);
				case BYTE -> dest.writeInt1((Byte)src);
				case BOOLEAN -> dest.writeBoolean((Boolean)src);
			}
		}
		
		@Override
		public long inlineSize(){
			return size;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new IOField.NoIO<>(accessor, SizeDescriptor.Fixed.of(size));
		}
		@SuppressWarnings("unchecked")
		@Override
		public RuntimeType<T> getType(){
			return (RuntimeType<T>)type;
		}
	}
	
	static ValueStorage<?> makeStorage(DataProvider provider, TypeLink typeDef, GenericContext generics){
		Class<?> clazz    =typeDef.getTypeClass(provider.getTypeDb());
		var      primitive=SupportedPrimitive.get(clazz);
		if(primitive.isPresent()){
			return new ValueStorage.Primitive<>(primitive.get());
		}else if(!IOInstance.isManaged(clazz)){
			return new ValueStorage.UnmanagedInstance<>(typeDef, provider);
		}else{
			var struct=Struct.ofUnknown(clazz);
			try{
				return new ValueStorage.FixedInstance<>(generics, provider, FixedContiguousStructPipe.of(struct));
			}catch(MalformedStructLayout ignored){
				return new ValueStorage.ReferencedInstance<>(generics, provider, ContiguousStructPipe.of(struct));
			}
		}
	}
	
	T readNew(ContentReader src) throws IOException;
	void write(RandomIO dest, T src) throws IOException;
	
	long inlineSize();
	
	<I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt);
	
	RuntimeType<T> getType();
}
