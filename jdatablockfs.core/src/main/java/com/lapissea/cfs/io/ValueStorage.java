package com.lapissea.cfs.io;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.*;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NoIOField;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public sealed interface ValueStorage<T>{
	
	sealed interface InstanceBased<T extends IOInstance<T>> extends ValueStorage<T>{
		void readSingle(ContentReader src, T dest, IOField<T, ?> field) throws IOException;
	}
	
	private static <I extends IOInstance<I>, T extends IOInstance<T>> SizeDescriptor<I> makeSizeDescriptor(DataProvider provider, FieldAccessor<I> accessor, StructPipe<T> pipe){
		SizeDescriptor<I> desc;
		
		var pDesc=pipe.getSizeDescriptor();
		var ws   =pDesc.getWordSpace();
		if(pDesc.hasFixed()){
			desc=SizeDescriptor.Fixed.of(ws, pDesc.requireFixed(ws));
		}else{
			desc=SizeDescriptor.Unknown.of(
				ws,
				pDesc.getMin(ws),
				pDesc.getMax(ws),
				(ioPool, prov, value)->pipe.calcUnknownSize(provider, (T)accessor.get(ioPool, value), ws)
			);
		}
		return desc;
	}
	
	final class Instance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		public Instance(GenericContext ctx, DataProvider provider, StructPipe<T> pipe){
			this.ctx=ctx;
			this.provider=provider;
			this.pipe=pipe;
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
			return -1;
		}
		
		@Override
		public BasicSizeDescriptor<T, ?> getSizeDescriptor(){
			return pipe.getSizeDescriptor();
		}
		public StructPipe<T> getPipe(){
			return pipe;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new NoIOField<>(accessor, makeSizeDescriptor(provider, accessor, pipe));
		}
		
		@Override
		public RuntimeType<T> getType(){
			return pipe.getType();
		}
		
		@Override
		public void readSingle(ContentReader src, T dest, IOField<T, ?> field) throws IOException{
			pipe.readSingleField(pipe.makeIOPool(), provider, src, field, dest, ctx);
		}
	}
	
	final class FixedInstance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private final GenericContext         ctx;
		private final DataProvider           provider;
		private final BaseFixedStructPipe<T> pipe;
		private final long                   size;
		
		public FixedInstance(GenericContext ctx, DataProvider provider, BaseFixedStructPipe<T> pipe){
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
		
		public BaseFixedStructPipe<T> getPipe(){
			return pipe;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new NoIOField<>(accessor, SizeDescriptor.Fixed.of(size));
		}
		
		@Override
		public Struct<T> getType(){
			return pipe.getType();
		}
		
		@Override
		public void readSingle(ContentReader src, T dest, IOField<T, ?> field) throws IOException{
			pipe.readSingleField(pipe.makeIOPool(), provider, src, field, dest, ctx);
		}
	}
	
	final class FixedReferencedInstance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private static final long SIZE=Reference.FIXED_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		public FixedReferencedInstance(GenericContext ctx, DataProvider provider, StructPipe<T> pipe){
			this.ctx=ctx;
			this.provider=provider;
			this.pipe=pipe;
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref=Reference.FIXED_PIPE.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, pipe, ctx);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			var ref=dest.remaining()==0?new Reference():Reference.FIXED_PIPE.readNew(provider, dest, null);
			if(ref.isNull()){
				var ticket=AllocateTicket.withData(pipe, provider, src);
				if(dest instanceof ChunkChainIO io){
					ticket=ticket.withPositionMagnet(io.calcGlobalPos());
				}
				var ch=ticket.submit(provider);
				Reference.FIXED_PIPE.write(provider, dest, ch.getPtr().makeReference());
				return;
			}
			
			ref.write(provider, true, pipe, src);
		}
		
		@Override
		public long inlineSize(){
			return SIZE;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new RefField.NoIO<>(accessor, Reference.FIXED_PIPE.getFixedDescriptor()){
				@Override
				public void setReference(I instance, Reference newRef){
					try(var io=ioAt.get()){
						Reference.FIXED_PIPE.write(provider, io, newRef);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public Reference getReference(I instance){
					try(var io=ioAt.get()){
						return Reference.FIXED_PIPE.readNew(provider, io, null);
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
		
		@Override
		public void readSingle(ContentReader src, T dest, IOField<T, ?> field) throws IOException{
			var ref=Reference.FIXED_PIPE.readNew(provider, src, null);
			ref.requireNonNull();
			ref.io(provider, io->pipe.readSingleField(pipe.makeIOPool(), provider, io, field, dest, ctx));
		}
	}
	
	final class UnmanagedInstance<T extends IOInstance.Unmanaged<T>> implements ValueStorage<T>{
		
		private static final long SIZE=Reference.FIXED_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		
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
			var ref=Reference.FIXED_PIPE.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			
			return struct.make(provider, ref, type);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			Reference.FIXED_PIPE.write(provider, dest, src.getReference());
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
				case CHAR -> src.readChar2();
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
			return new NoIOField<>(accessor, SizeDescriptor.Fixed.of(size));
		}
		@SuppressWarnings("unchecked")
		@Override
		public RuntimeType<T> getType(){
			return (RuntimeType<T>)type;
		}
	}
	
	final class InlineString implements ValueStorage<String>{
		
		private static final RuntimeType<String> TYPE=RuntimeType.of(String.class);
		
		private final DataProvider provider;
		
		public InlineString(DataProvider provider){
			this.provider=provider;
		}
		
		@Override
		public String readNew(ContentReader src) throws IOException{
			return AutoText.PIPE.readNew(provider, src, null).getData();
		}
		@Override
		public void write(RandomIO dest, String src) throws IOException{
			AutoText.PIPE.write(provider, dest, new AutoText(src));
		}
		
		@Override
		public long inlineSize(){
			return -1;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, String> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			var d=AutoText.PIPE.getSizeDescriptor();
			return new NoIOField<>(accessor, SizeDescriptor.Unknown.of(d.getWordSpace(), d.getMin(), d.getMax(), (ioPool, prov, value)->{
				var str=(String)accessor.get(ioPool, value);
				if(str==null) return 0;
				return AutoText.PIPE.getSizeDescriptor().calcUnknown(null, prov, new AutoText(str), d.getWordSpace());
			}));
		}
		@Override
		public RuntimeType<String> getType(){
			return TYPE;
		}
	}
	
	final class FixedReferenceString implements ValueStorage<String>{
		
		private static final long SIZE=Reference.FIXED_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		
		private static final RuntimeType<String> TYPE=RuntimeType.of(String.class);
		
		private final DataProvider provider;
		
		public FixedReferenceString(DataProvider provider){
			this.provider=provider;
		}
		
		@Override
		public String readNew(ContentReader src) throws IOException{
			var ref=Reference.FIXED_PIPE.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, AutoText.PIPE, null).getData();
		}
		
		@Override
		public void write(RandomIO dest, String src) throws IOException{
			var ref=dest.remaining()==0?new Reference():Reference.FIXED_PIPE.readNew(provider, dest, null);
			if(ref.isNull()){
				var ticket=AllocateTicket.withData(AutoText.PIPE, provider, new AutoText(src));
				if(dest instanceof ChunkChainIO io){
					ticket=ticket.withPositionMagnet(io.calcGlobalPos());
				}
				var ch=ticket.submit(provider);
				Reference.FIXED_PIPE.write(provider, dest, ch.getPtr().makeReference());
				return;
			}
			ref.write(provider, true, AutoText.PIPE, new AutoText(src));
		}
		
		@Override
		public long inlineSize(){
			return SIZE;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, String> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new NoIOField<>(accessor, Reference.FIXED_PIPE.getFixedDescriptor());
		}
		
		@Override
		public RuntimeType<String> getType(){
			return TYPE;
		}
	}
	
	sealed interface StorageRule{
		record Default() implements StorageRule{}
		
		record FixedOnly() implements StorageRule{}
		
		record VariableFixed(IOField.VaryingSizeProvider provider) implements StorageRule{}
	}
	
	static ValueStorage<?> makeStorage(DataProvider provider, TypeLink typeDef, GenericContext generics, StorageRule rule){
		Class<?> clazz=typeDef.getTypeClass(provider.getTypeDb());
		{
			var primitive=SupportedPrimitive.get(clazz);
			if(primitive.isPresent()){
				return new Primitive<>(primitive.get());
			}
		}
		
		if(clazz==String.class){
			return switch(rule){
				case StorageRule.Default ignored -> new InlineString(provider);
				case StorageRule.FixedOnly ignored -> new FixedReferenceString(provider);
				case StorageRule.VariableFixed ignored -> new FixedReferenceString(provider);
			};
		}
		
		if(!IOInstance.isManaged(clazz)){
			return new UnmanagedInstance<>(typeDef, provider);
		}else{
			var struct=Struct.ofUnknown(clazz);
			return switch(rule){
				case StorageRule.Default ignored -> new Instance<>(generics, provider, StandardStructPipe.of(struct));
				case StorageRule.FixedOnly ignored -> {
					try{
						yield new FixedInstance<>(generics, provider, FixedStructPipe.of(struct, STATE_DONE));
					}catch(UnsupportedStructLayout ignored1){
						yield new FixedReferencedInstance<>(generics, provider, StandardStructPipe.of(struct));
					}
				}
				case StorageRule.VariableFixed conf -> makeVarying(provider, generics, conf, struct);
			};
		}
	}
	
	private static <T extends IOInstance<T>> ValueStorage<T> makeVarying(DataProvider provider, GenericContext generics, StorageRule.VariableFixed rule, Struct<T> struct){
		try{
			var fixed=FixedStructPipe.of(struct, STATE_DONE);
			
			LogUtil.println(fixed.getSpecificFields());
			
			var varying=new FixedVaryingStructPipe<>(struct, true, rule.provider);

//			if(varying.getConfig().isEmpty()){
//				return new FixedInstance<>(generics, provider, fixed);
//			}
			
			return new FixedInstance<>(generics, provider, varying);
		}catch(UnsupportedStructLayout ignored1){
			LogUtil.printlnEr(struct+" variable fixed not implemented");
			return new FixedReferencedInstance<>(generics, provider, StandardStructPipe.of(struct));
		}
	}
	
	T readNew(ContentReader src) throws IOException;
	void write(RandomIO dest, T src) throws IOException;
	
	long inlineSize();
	
	<I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt);
	
	RuntimeType<T> getType();
	
	default BasicSizeDescriptor<T, ?> getSizeDescriptor(){
		return BasicSizeDescriptor.IFixed.Basic.of(WordSpace.BYTE, inlineSize());
	}
}
