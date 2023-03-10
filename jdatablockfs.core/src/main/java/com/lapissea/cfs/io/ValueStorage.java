package com.lapissea.cfs.io;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.BaseFixedStructPipe;
import com.lapissea.cfs.io.instancepipe.FieldDependency;
import com.lapissea.cfs.io.instancepipe.FixedStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.NewObj;
import com.lapissea.cfs.type.RuntimeType;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NoIOField;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static com.lapissea.cfs.io.instancepipe.StructPipe.STATE_IO_FIELD;

public sealed interface ValueStorage<T>{
	
	sealed interface InstanceBased<T extends IOInstance<T>> extends ValueStorage<T>{
		void readSelective(ContentReader src, T dest, FieldDependency.Ticket<T> depTicket) throws IOException;
		T readNewSelective(ContentReader src, FieldDependency.Ticket<T> depTicket, boolean strictHolder) throws IOException;
		FieldDependency.Ticket<T> depTicket(IOField<T, ?> field);
		FieldDependency.Ticket<T> depTicket(FieldSet<T> fields);
		FieldDependency.Ticket<T> depTicket(Set<String> names);
	}
	
	private static <I extends IOInstance<I>, T extends IOInstance<T>> SizeDescriptor<I> makeSizeDescriptor(DataProvider provider, FieldAccessor<I> accessor, StructPipe<T> pipe){
		SizeDescriptor<I> desc;
		
		var pDesc = pipe.getSizeDescriptor();
		var ws    = pDesc.getWordSpace();
		if(pDesc.hasFixed()){
			desc = SizeDescriptor.Fixed.of(ws, pDesc.requireFixed(ws));
		}else{
			desc = SizeDescriptor.Unknown.of(
				ws,
				pDesc.getMin(ws),
				pDesc.getMax(ws),
				(ioPool, prov, value) -> pipe.calcUnknownSize(provider, (T)accessor.get(ioPool, value), ws)
			);
		}
		return desc;
	}
	
	final class Instance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		public Instance(GenericContext ctx, DataProvider provider, StructPipe<T> pipe){
			this.ctx = ctx;
			this.provider = provider;
			this.pipe = pipe;
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
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
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
		public void readSelective(ContentReader src, T dest, FieldDependency.Ticket<T> depTicket) throws IOException{
			pipe.readDeps(pipe.makeIOPool(), provider, src, depTicket, dest, ctx);
		}
		@Override
		public T readNewSelective(ContentReader src, FieldDependency.Ticket<T> depTicket, boolean strictHolder) throws IOException{
			return pipe.readNewSelective(provider, src, depTicket, ctx, strictHolder);
		}
		
		@Override
		public FieldDependency.Ticket<T> depTicket(IOField<T, ?> field){
			return pipe.getFieldDependency().getDeps(field);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(FieldSet<T> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(Set<String> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
	}
	
	final class FixedInstance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private final GenericContext         ctx;
		private final DataProvider           provider;
		private final BaseFixedStructPipe<T> pipe;
		private final long                   size;
		
		public FixedInstance(GenericContext ctx, DataProvider provider, BaseFixedStructPipe<T> pipe){
			this.ctx = ctx;
			this.provider = provider;
			this.pipe = pipe;
			size = pipe.getFixedDescriptor().get(WordSpace.BYTE);
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
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
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
		public void readSelective(ContentReader src, T dest, FieldDependency.Ticket<T> depTicket) throws IOException{
			pipe.readDeps(pipe.makeIOPool(), provider, src, depTicket, dest, ctx);
		}
		@Override
		public T readNewSelective(ContentReader src, FieldDependency.Ticket<T> depTicket, boolean strictHolder) throws IOException{
			return pipe.readNewSelective(provider, src, depTicket, ctx, strictHolder);
		}
		
		@Override
		public FieldDependency.Ticket<T> depTicket(IOField<T, ?> field){
			return pipe.getFieldDependency().getDeps(field);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(FieldSet<T> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(Set<String> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
	}
	
	
	private static void writeNew(RandomIO dest, AllocateTicket ticket, DataProvider provider, BaseFixedStructPipe<Reference> refPipe) throws IOException{
		if(dest instanceof ChunkChainIO io){
			ticket = ticket.withPositionMagnet(io.calcGlobalPos());
		}
		var ch = ticket.submit(provider);
		try{
			refPipe.write(provider, dest, ch.getPtr().makeReference());
		}catch(Throwable e){
			provider.getMemoryManager().free(ch);
			throw e;
		}
	}
	
	final class FixedReferencedInstance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>{
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		private final BaseFixedStructPipe<Reference> refPipe;
		private final long                           size;
		
		public FixedReferencedInstance(GenericContext ctx, DataProvider provider, BaseFixedStructPipe<Reference> refPipe, StructPipe<T> pipe){
			this.ctx = ctx;
			this.provider = provider;
			this.pipe = pipe;
			this.refPipe = refPipe;
			
			size = refPipe.getFixedDescriptor().get(WordSpace.BYTE);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref = refPipe.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, pipe, ctx);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			var pos = dest.getPos();
			var ref = dest.remaining() == 0? new Reference() : refPipe.readNew(provider, dest, null);
			if(ref.isNull()){
				dest.setPos(pos);
				writeNew(dest, AllocateTicket.withData(pipe, provider, src), provider, refPipe);
			}else{
				ref.write(provider, true, pipe, src);
			}
		}
		@Override
		public void notifyRemoval(ContentReader io) throws IOException{
			var ref = refPipe.readNew(provider, io, null);
			if(ref.isNull()){
				return;
			}
			provider.getMemoryManager().freeChains(List.of(ref.getPtr()));
		}
		@Override
		public boolean needsRemoval(){
			return true;
		}
		
		@Override
		public long inlineSize(){
			return size;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new RefField.NoIO<>(accessor, refPipe.getFixedDescriptor()){
				@Override
				public void setReference(I instance, Reference newRef){
					try(var io = ioAt.get()){
						refPipe.write(provider, io, newRef);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				@Override
				public Reference getReference(I instance){
					try(var io = ioAt.get()){
						return refPipe.readNew(provider, io, null);
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
		public void readSelective(ContentReader src, T dest, FieldDependency.Ticket<T> depTicket) throws IOException{
			var ref = refPipe.readNew(provider, src, null);
			ref.requireNonNull();
			ref.io(provider, io -> pipe.readDeps(pipe.makeIOPool(), provider, io, depTicket, dest, ctx));
		}
		@Override
		public T readNewSelective(ContentReader src, FieldDependency.Ticket<T> depTicket, boolean strictHolder) throws IOException{
			var ref = refPipe.readNew(provider, src, null);
			ref.requireNonNull();
			return ref.ioMap(provider, io -> pipe.readNewSelective(provider, io, depTicket, ctx, strictHolder));
		}
		
		@Override
		public FieldDependency.Ticket<T> depTicket(IOField<T, ?> field){
			return pipe.getFieldDependency().getDeps(field);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(FieldSet<T> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
		@Override
		public FieldDependency.Ticket<T> depTicket(Set<String> ioFields){
			return pipe.getFieldDependency().getDeps(ioFields);
		}
	}
	
	final class UnmanagedInstance<T extends IOInstance.Unmanaged<T>> implements ValueStorage<T>{
		
		private final TypeLink            type;
		private final DataProvider        provider;
		private final Struct.Unmanaged<T> struct;
		
		private final BaseFixedStructPipe<Reference> refPipe;
		private final long                           size;
		
		@SuppressWarnings("unchecked")
		public UnmanagedInstance(TypeLink type, DataProvider provider, BaseFixedStructPipe<Reference> refPipe){
			this.type = type;
			this.provider = provider;
			this.refPipe = refPipe;
			size = refPipe.getFixedDescriptor().get(WordSpace.BYTE);
			struct = (Struct.Unmanaged<T>)Struct.Unmanaged.ofUnknown(type.getTypeClass(provider.getTypeDb()));
		}
		
		public BaseFixedStructPipe<Reference> getRefPipe(){
			return refPipe;
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref = refPipe.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			
			return struct.make(provider, ref, type);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			refPipe.write(provider, dest, src == null? new Reference() : src.getReference());
		}
		@Override
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
		}
		
		@Override
		public long inlineSize(){
			return size;
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
			this.type = type;
			size = type.maxSize.requireFixed(WordSpace.BYTE);
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
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
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
		
		private static final RuntimeType<String> TYPE = RuntimeType.of(String.class);
		
		private final DataProvider provider;
		
		public InlineString(DataProvider provider){
			this.provider = provider;
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
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
		}
		
		@Override
		public long inlineSize(){
			return -1;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, String> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			var d = AutoText.PIPE.getSizeDescriptor();
			return new NoIOField<>(accessor, SizeDescriptor.Unknown.of(d.getWordSpace(), d.getMin(), d.getMax(), (ioPool, prov, value) -> {
				var str = (String)accessor.get(ioPool, value);
				if(str == null) return 0;
				return AutoText.PIPE.getSizeDescriptor().calcUnknown(null, prov, new AutoText(str), d.getWordSpace());
			}));
		}
		@Override
		public RuntimeType<String> getType(){
			return TYPE;
		}
	}
	
	final class FixedReferenceString implements ValueStorage<String>{
		
		
		private static final RuntimeType<String> TYPE = RuntimeType.of(String.class);
		
		private final DataProvider provider;
		
		private final BaseFixedStructPipe<Reference> refPipe;
		private final long                           size;
		
		public FixedReferenceString(DataProvider provider, BaseFixedStructPipe<Reference> refPipe){
			this.provider = provider;
			this.refPipe = refPipe;
			size = refPipe.getFixedDescriptor().get(WordSpace.BYTE);
		}
		
		@Override
		public String readNew(ContentReader src) throws IOException{
			var ref = refPipe.readNew(provider, src, null);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, AutoText.PIPE, null).getData();
		}
		
		@Override
		public void write(RandomIO dest, String src) throws IOException{
			var ref = dest.remaining() == 0? new Reference() : refPipe.readNew(provider, dest, null);
			if(ref.isNull()){
				writeNew(dest, AllocateTicket.withData(AutoText.PIPE, provider, new AutoText(src)), provider, refPipe);
			}else{
				ref.write(provider, true, AutoText.PIPE, new AutoText(src));
			}
		}
		@Override
		public void notifyRemoval(ContentReader io) throws IOException{
			var ref = refPipe.readNew(provider, io, null);
			if(ref.isNull()){
				return;
			}
			provider.getMemoryManager().freeChains(List.of(ref.getPtr()));
		}
		@Override
		public boolean needsRemoval(){
			return true;
		}
		
		@Override
		public long inlineSize(){
			return size;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, String> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new NoIOField<>(accessor, refPipe.getFixedDescriptor());
		}
		
		@Override
		public RuntimeType<String> getType(){
			return TYPE;
		}
	}
	
	final class UnknownIDObject implements ValueStorage<Object>{
		
		private static final StandardStructPipe<Reference> REF_PIPE = StandardStructPipe.of(Reference.class);
		
		private final DataProvider   provider;
		private final GenericContext generics;
		
		public UnknownIDObject(DataProvider provider, GenericContext generics){
			this.provider = provider;
			this.generics = generics;
		}
		
		@Override
		public BasicSizeDescriptor<Object, ?> getSizeDescriptor(){
			return BasicSizeDescriptor.Unknown.of(WordSpace.BYTE, 1, OptionalLong.empty(), (pool, provider, src) -> {
				int id;
				try{
					id = provider.getTypeDb().toID(src, false).val();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				
				long size = 1;
				
				size += NumberSize.bySize(id).bytes;
				if(src == null) return size;
				
				if(src instanceof String str){
					size += AutoText.PIPE.getSizeDescriptor().calcUnknown(null, provider, new AutoText(str), WordSpace.BYTE);
					return size;
				}
				
				var type = src.getClass();
				
				var p = SupportedPrimitive.get(type);
				if(p.isPresent()){
					return size + switch(p.get()){
						case DOUBLE -> 8;
						case FLOAT -> 4;
						case CHAR, SHORT -> 2;
						case LONG -> 1 + NumberSize.bySizeSigned((long)src).bytes;
						case INT -> 1 + NumberSize.bySizeSigned((int)src).bytes;
						case BYTE, BOOLEAN -> 1;
					};
				}
				
				if(type.isEnum()){
					return size + EnumUniverse.ofUnknown(type).numSize(false).bytes;
				}
				
				if(src instanceof IOInstance<?> inst){
					if(inst instanceof IOInstance.Unmanaged<?> unm){
						return size + REF_PIPE.calcUnknownSize(provider, unm.getReference(), WordSpace.BYTE);
					}
					
					//noinspection unchecked
					return size + StandardStructPipe.of(inst.getClass()).calcUnknownSize(provider, inst, WordSpace.BYTE);
				}
				
				throw new NotImplementedException("Unknown type: " + type);
			});
		}
		
		@Override
		public Object readNew(ContentReader src) throws IOException{
			var id = src.readUnsignedInt4Dynamic();
			if(id == 0) return null;
			
			var link = provider.getTypeDb().fromID(id);
			var type = link.getTypeClass(provider.getTypeDb());
			
			var p = SupportedPrimitive.get(type).map(pr -> switch(pr){
				case DOUBLE -> src.readFloat8();
				case FLOAT -> src.readFloat4();
				case CHAR -> src.readChar2();
				case LONG -> src.readInt8Dynamic();
				case INT -> src.readInt4Dynamic();
				case SHORT -> src.readInt2();
				case BYTE -> src.readInt1();
				case BOOLEAN -> src.readBoolean();
			});
			if(p.isPresent()) return p.get();
			
			if(type == String.class){
				return AutoText.PIPE.readNew(provider, src, null).getData();
			}
			
			if(type.isEnum()){
				var u = EnumUniverse.ofUnknown(type);
				return FlagReader.readSingle(src, u);
			}
			
			if(IOInstance.isInstance(type)){
				if(IOInstance.isUnmanaged(type)){
					var s   = Struct.Unmanaged.ofUnknown(type);
					var ref = REF_PIPE.readNew(provider, src, null);
					return s.make(provider, ref, link);
				}
				
				var s    = Struct.ofUnknown(type);
				var pipe = StandardStructPipe.of(s);
				return pipe.readNew(provider, src, generics);
			}
			
			throw new NotImplementedException("Unknown type: " + type);
		}
		@Override
		public void write(RandomIO dest, Object src) throws IOException{
			var id = provider.getTypeDb().toID(src);
			dest.writeUnsignedInt4Dynamic(id);
			if(src == null) return;
			
			if(src instanceof String str){
				AutoText.PIPE.write(provider, dest, new AutoText(str));
				return;
			}
			
			var type = src.getClass();
			
			var p = SupportedPrimitive.get(type);
			if(p.isPresent()){
				switch(p.get()){
					case DOUBLE -> dest.writeFloat8((double)src);
					case FLOAT -> dest.writeFloat4((float)src);
					case CHAR -> dest.writeChar2((char)src);
					case LONG -> dest.writeInt8Dynamic((long)src);
					case INT -> dest.writeInt4Dynamic((int)src);
					case SHORT -> dest.writeInt2((short)src);
					case BYTE -> dest.writeInt1((byte)src);
					case BOOLEAN -> dest.writeBoolean((boolean)src);
				}
				return;
			}
			
			if(type.isEnum()){
				EnumUniverse uni = EnumUniverse.ofUnknown(type);
				FlagWriter.writeSingle(dest, uni, (Enum)src);
				return;
			}
			
			if(src instanceof IOInstance<?> inst){
				if(inst instanceof IOInstance.Unmanaged<?> unm){
					REF_PIPE.write(provider, dest, unm.getReference());
					return;
				}
				
				//noinspection unchecked
				StandardStructPipe.of(inst.getClass()).write(provider, dest, inst);
				return;
			}
			
			throw new NotImplementedException("Unknown type: " + type);
		}
		@Override
		public void notifyRemoval(ContentReader io){ }
		@Override
		public boolean needsRemoval(){
			return false;
		}
		
		@Override
		public long inlineSize(){
			return -1;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, Object> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			var d = AutoText.PIPE.getSizeDescriptor();
			return new NoIOField<>(accessor, SizeDescriptor.Unknown.of(d.getWordSpace(), d.getMin(), d.getMax(), (ioPool, prov, value) -> {
				var str = (String)accessor.get(ioPool, value);
				if(str == null) return 0;
				return AutoText.PIPE.getSizeDescriptor().calcUnknown(null, prov, new AutoText(str), d.getWordSpace());
			}));
		}
		
		@Override
		public RuntimeType<Object> getType(){
			return new RuntimeType<>(){
				@Override
				public boolean getCanHavePointers(){
					return true;
				}
				@Override
				public NewObj<Object> emptyConstructor(){
					throw new UnsupportedOperationException();
				}
				@Override
				public Class<Object> getType(){
					return Object.class;
				}
			};
		}
	}
	
	sealed interface StorageRule{
		record Default() implements StorageRule{ }
		
		record FixedOnly() implements StorageRule{ }
		
		record VariableFixed(VaryingSize.Provider provider) implements StorageRule{ }
	}
	
	static ValueStorage<?> makeStorage(DataProvider provider, TypeLink typeDef, GenericContext generics, StorageRule rule){
		Class<?> clazz = typeDef.getTypeClass(provider.getTypeDb());
		if(clazz == Object.class){
			return new UnknownIDObject(provider, generics);
		}
		{
			var primitive = SupportedPrimitive.get(clazz);
			if(primitive.isPresent()){
				return new Primitive<>(primitive.get());
			}
		}
		
		if(clazz == String.class){
			return switch(rule){
				case StorageRule.Default ignored -> new InlineString(provider);
				case StorageRule.FixedOnly ignored -> new FixedReferenceString(provider, Reference.fixedPipe());
				case StorageRule.VariableFixed conf -> new FixedReferenceString(provider, FixedVaryingStructPipe.tryVarying(Reference.STRUCT, conf.provider));
			};
		}
		
		if(!IOInstance.isManaged(clazz)){
			return switch(rule){
				case StorageRule.Default ignored -> new UnmanagedInstance<>(typeDef, provider, Reference.fixedPipe());//TODO: implement standard reference unmanaged
				case StorageRule.FixedOnly ignored -> new UnmanagedInstance<>(typeDef, provider, Reference.fixedPipe());
				case StorageRule.VariableFixed conf -> new UnmanagedInstance<>(typeDef, provider, FixedVaryingStructPipe.tryVarying(Reference.STRUCT, conf.provider));
			};
		}else{
			var struct = Struct.ofUnknown(clazz);
			return switch(rule){
				case StorageRule.Default ignored -> new Instance<>(generics, provider, StandardStructPipe.of(struct));
				case StorageRule.FixedOnly ignored -> {
					try{
						yield new FixedInstance<>(generics, provider, FixedStructPipe.of(struct, STATE_IO_FIELD));
					}catch(UnsupportedStructLayout ignored1){
						yield new FixedReferencedInstance<>(generics, provider, Reference.fixedPipe(), StandardStructPipe.of(struct));
					}
				}
				case StorageRule.VariableFixed conf -> {
					int id = conf.provider.mark();
					try{
						yield new FixedInstance<>(generics, provider, FixedVaryingStructPipe.tryVarying(struct, conf.provider));
					}catch(UnsupportedStructLayout ignored){
						conf.provider.reset(id);
						yield new FixedReferencedInstance<>(generics, provider, FixedVaryingStructPipe.tryVarying(Reference.STRUCT, conf.provider), StandardStructPipe.of(struct));
					}
				}
			};
		}
	}
	
	T readNew(ContentReader src) throws IOException;
	void write(RandomIO dest, T src) throws IOException;
	
	void notifyRemoval(ContentReader io) throws IOException;
	boolean needsRemoval();
	
	long inlineSize();
	
	<I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt);
	
	RuntimeType<T> getType();
	
	default BasicSizeDescriptor<T, ?> getSizeDescriptor(){
		return BasicSizeDescriptor.IFixed.Basic.of(WordSpace.BYTE, inlineSize());
	}
}
