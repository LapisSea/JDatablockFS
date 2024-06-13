package com.lapissea.dfs.io;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.SealedUtil.SealedInstanceUniverse;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.exceptions.UnsupportedStructLayout;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.instancepipe.BaseFixedStructPipe;
import com.lapissea.dfs.io.instancepipe.FieldDependency;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.TypedReference;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.RuntimeType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.compilation.WrapperStructs;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.fields.NoIOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.DynamicSupport;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.io.instancepipe.StructPipe.STATE_IO_FIELD;

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
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var ptrs = new ArrayList<ChunkPointer>();
			
			var startPos = io.getPos();
			var root     = readNew(io);
			
			var rootRef = new Reference(ChunkPointer.of(69), 420);
			var dirty   = FixedInstance.structWalk(provider, ptrs, dereferenceWrite, pipe, root, rootRef);
			if(dirty){
				write(io.setPos(startPos), root);
			}
			return ptrs;
		}
		@Override
		public boolean needsRemoval(){
			return pipe.getType().getCanHavePointers();
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
		
		private static final Reference DUMMY = new Reference(ChunkPointer.of(Long.MAX_VALUE), 420);
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var ptrs = new ArrayList<ChunkPointer>();
			
			var startPos = io.getPos();
			var root     = readNew(io);
			
			var dirty = structWalk(provider, ptrs, dereferenceWrite, pipe, root, DUMMY);
			if(dirty){
				write(io.setPos(startPos), root);
			}
			return ptrs;
		}
		
		private static <T extends IOInstance<T>> boolean structWalk(
			DataProvider provider, ArrayList<ChunkPointer> ptrs,
			boolean dereferenceWrite, StructPipe<T> pipe, T root, Reference rootRef
		) throws IOException{
			var rec = new MemoryWalker.PointerRecord(){
				boolean dirty;
				@Override
				public <IO extends IOInstance<IO>> int log(Reference instanceReference, IO instance, RefField<IO, ?> field, Reference valueReference) throws IOException{
					if(rootRef == valueReference) throw new ShouldNeverHappenError();
					if(!valueReference.isNull()){
						ptrs.add(valueReference.getPtr());
						
						if(dereferenceWrite && instance == root && field.nullable()){
							field.set(null, instance, null);
							field.setReference(instance, new Reference());
							dirty = true;
						}
					}
					return MemoryWalker.CONTINUE;
				}
				@Override
				public <IO extends IOInstance<IO>> int logChunkPointer(Reference instanceReference, IO instance, IOField<IO, ChunkPointer> field, ChunkPointer value){
					return MemoryWalker.CONTINUE;
				}
			};
			new MemoryWalker(provider, root, rootRef, pipe, false, rec).walk();
			return rec.dirty;
		}
		
		@Override
		public boolean needsRemoval(){
			return pipe.getType().getCanHavePointers();
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
	
	final class FixedReferenceInstance<T extends IOInstance<T>> implements ValueStorage.InstanceBased<T>, RefStorage<Reference>{
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		private final StructPipe<T>  pipe;
		
		private final BaseFixedStructPipe<Reference> refPipe;
		private final long                           size;
		
		public FixedReferenceInstance(GenericContext ctx, DataProvider provider, BaseFixedStructPipe<Reference> refPipe, StructPipe<T> pipe){
			this.ctx = ctx;
			this.provider = provider;
			this.pipe = pipe;
			this.refPipe = refPipe;
			
			size = refPipe.getFixedDescriptor().get(WordSpace.BYTE);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref = readInline(src);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, pipe, ctx);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			var pos = dest.getPos();
			var ref = dest.remaining() == 0? new Reference() : readInline(dest);
			if(ref.isNull()){
				dest.setPos(pos);
				dest.ensureCapacity(pos + refPipe.getFixedDescriptor().get(WordSpace.BYTE));
				writeNew(dest, AllocateTicket.withData(pipe, provider, src), provider, refPipe);
			}else{
				ref.write(provider, true, pipe, src);
			}
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var startPos = io.getPos();
			var ref      = readInline(io);
			if(ref.isNull()){
				return List.of();
			}
			
			if(dereferenceWrite){
				refPipe.write(provider, io.setPos(startPos), new Reference());
			}
			
			if(pipe.getType().getCanHavePointers()){
				var ptrs = new ArrayList<ChunkPointer>(4);
				ptrs.add(ref.getPtr());
				FixedInstance.structWalk(provider, ptrs, false, pipe, ref.readNew(provider, pipe, ctx), ref);
				return ptrs;
			}
			
			return List.of(ref.getPtr());
		}
		@Override
		public boolean needsRemoval(){
			return true;
		}
		
		@Override
		public Reference readInline(ContentReader io) throws IOException{
			return refPipe.readNew(provider, io, null);
		}
		
		@Override
		public void writeInline(RandomIO dest, Reference src) throws IOException{
			refPipe.write(provider, dest, src);
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
						writeInline(io, newRef);
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
					}
				}
				
				@Override
				public Reference getReference(I instance){
					try(var io = ioAt.get()){
						return readInline(io);
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
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
			var ref = readInline(src);
			ref.requireNonNull();
			ref.io(provider, io -> pipe.readDeps(pipe.makeIOPool(), provider, io, depTicket, dest, ctx));
		}
		@Override
		public T readNewSelective(ContentReader src, FieldDependency.Ticket<T> depTicket, boolean strictHolder) throws IOException{
			var ref = readInline(src);
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
	
	final class UnmanagedInstance<T extends IOInstance.Unmanaged<T>> implements ValueStorage<T>, RefStorage<Reference>{
		
		private final IOType              type;
		private final DataProvider        provider;
		private final Struct.Unmanaged<T> struct;
		
		private final ObjectPipe<ChunkPointer, Void> ptrPipe;
		private final long                           size;
		
		@SuppressWarnings("unchecked")
		public UnmanagedInstance(IOType type, DataProvider provider, ObjectPipe<ChunkPointer, Void> ptrPipe){
			this.type = type;
			this.provider = provider;
			this.ptrPipe = ptrPipe;
			size = ptrPipe.getSizeDescriptor().getFixed(WordSpace.BYTE).orElse(-1);
			struct = (Struct.Unmanaged<T>)Struct.Unmanaged.ofUnknown(type.getTypeClass(provider.getTypeDb()));
		}
		
		public ObjectPipe<ChunkPointer, Void> getPtrPipe(){
			return ptrPipe;
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref = readInlinePtr(src);
			if(ref.isNull()){
				return null;
			}
			
			return struct.make(provider, ref.dereference(provider), type);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			writeInline(dest, src == null? new Reference() : src.getPointer().makeReference());
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite){ return List.of(); }
		@Override
		public boolean needsRemoval(){
			return false;
		}
		
		@Override
		public Reference readInline(ContentReader src) throws IOException{
			return readInlinePtr(src).makeReference();
		}
		private ChunkPointer readInlinePtr(ContentReader src) throws IOException{
			return ptrPipe.readNew(provider, src, null);
		}
		@Override
		public void writeInline(RandomIO dest, Reference src) throws IOException{
			writeInlinePtr(dest, src.asJustPointer());
		}
		public void writeInlinePtr(RandomIO dest, ChunkPointer src) throws IOException{
			ptrPipe.write(provider, dest, src);
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
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite){ return List.of(); }
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
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite){ return List.of(); }
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
	
	final class FixedReferenceString implements ValueStorage<String>, RefStorage<Reference>{
		
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
			var ref = readInline(src);
			if(ref.isNull()){
				return null;
			}
			return ref.readNew(provider, AutoText.PIPE, null).getData();
		}
		
		@Override
		public void write(RandomIO dest, String src) throws IOException{
			var ref = dest.remaining() == 0? new Reference() : readInline(dest);
			if(ref.isNull()){
				//TODO: create mechanism for assumed fixed field first, then if growth needed switch to explicit next size
				var d = new AutoText(src);
				var t = AllocateTicket.bytes(AutoText.PIPE.calcUnknownSize(provider, d, WordSpace.BYTE))
				                      .withDataPopulated(AutoText.PIPE, d);
				writeNew(dest, t, provider, refPipe);
			}else{
				ref.write(provider, true, AutoText.PIPE, new AutoText(src));
			}
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var startPos = io.getPos();
			var ref      = readInline(io);
			if(ref.isNull()){
				return List.of();
			}
			if(dereferenceWrite){
				this.writeInline(io.setPos(startPos), new Reference());
			}
			return List.of(ref.getPtr());
		}
		@Override
		public boolean needsRemoval(){
			return true;
		}
		
		@Override
		public Reference readInline(ContentReader io) throws IOException{
			return refPipe.readNew(provider, io, null);
		}
		@Override
		public void writeInline(RandomIO io, Reference src) throws IOException{
			refPipe.write(provider, io, src);
		}
		@Override
		public long inlineSize(){
			return size;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, String> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new RefField.NoIOObj<>(accessor, refPipe.getFixedDescriptor()){
				@Override
				public void setReference(I instance, Reference newRef) throws IOException{
					try(var io = ioAt.get()){
						FixedReferenceString.this.writeInline(io, newRef);
					}
				}
				
				@Override
				public Reference getReference(I instance) throws IOException{
					try(var io = ioAt.get()){
						return readInline(io);
					}
				}
				@Override
				public ObjectPipe<String, ?> getReferencedPipe(I instance){ return AutoText.STR_PIPE; }
			};
		}
		
		@Override
		public RuntimeType<String> getType(){
			return TYPE;
		}
	}
	
	final class UnknownIDObject implements ValueStorage<Object>{
		
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
					id = provider.getTypeDb().objToID(src, false).val();
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
				
				return 1 + NumberSize.bySize(id).bytes + DynamicSupport.calcSize(provider, src);
			});
		}
		
		@Override
		public Object readNew(ContentReader src) throws IOException{
			var id = src.readUnsignedInt4Dynamic();
			if(id == 0) return null;
			var link = provider.getTypeDb().fromID(id);
			return DynamicSupport.readTyp(link, provider, src, generics);
		}
		@Override
		public void write(RandomIO dest, Object src) throws IOException{
			var db  = provider.getTypeDb();
			var idV = db.objToID(src, false);
			int id;
			if(!idV.stored()){
				dest.ensureCapacity(dest.getPos() + NumberSize.bySize(idV.val()).bytes + 1);
				id = db.objToID(src);
			}else id = idV.val();
			dest.writeUnsignedInt4Dynamic(id);
			if(src == null) return;
			
			DynamicSupport.writeValue(provider, dest, src);
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite){ return List.of(); }
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
			return new RuntimeType.Lambda<>(true, Object.class, null);
		}
	}
	
	final class UnknownIDReference<T> implements ValueStorage<T>, RefStorage<TypedReference>{
		
		private final DataProvider               provider;
		private final GenericContext             generics;
		private final StructPipe<TypedReference> refPipe;
		
		private final long inlineSize;
		
		public UnknownIDReference(DataProvider provider, GenericContext generics, StructPipe<TypedReference> refPipe){
			this.provider = provider;
			this.generics = generics;
			this.refPipe = refPipe;
			inlineSize = refPipe.getSizeDescriptor().getFixed(WordSpace.BYTE).orElse(-1);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var ref  = readInline(src);
			var type = ref.getType(provider.getTypeDb());
			try(var io = ref.getRef().io(provider)){
				//noinspection unchecked
				return (T)DynamicSupport.readTyp(type, provider, io, generics);
			}
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			var id     = provider.getTypeDb().objToID(src);
			var orgPos = dest.getPos();
			var refId  = dest.remaining() == 0? new TypedReference() : readInline(dest);
			if(refId.isNull()){
				var size = DynamicSupport.calcSize(provider, src);
				
				var ticket = AllocateTicket.bytes(size)
				                           .withDataPopulated((u, io) -> DynamicSupport.writeValue(provider, io, src));
				if(dest instanceof ChunkChainIO io){
					ticket = ticket.withPositionMagnet(io.calcGlobalPos());
				}
				var ch = ticket.submit(provider);
				try{
					writeInline(dest.setPos(orgPos), new TypedReference(ch.getPtr().makeReference(), id));
				}catch(Throwable e){
					provider.getMemoryManager().free(ch);
					throw e;
				}
			}else{
				if(refId.getId() != id){
					refId = new TypedReference(refId.getRef(), id);
					writeInline(dest.setPos(orgPos), refId);
				}
				try(var io = refId.getRef().io(provider)){
					DynamicSupport.writeValue(provider, io, src);
				}
			}
		}
		
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var startPos = io.getPos();
			var refId    = readInline(io);
			if(refId.isNull()){
				return List.of();
			}
			if(dereferenceWrite){
				writeInline(io.setPos(startPos), new TypedReference());
			}
			
			var type = refId.getType(provider.getTypeDb());
			var raw  = type.getTypeClass(provider.getTypeDb());
			
			if(IOInstance.isInstance(raw)){
				var struct = Struct.ofUnknown(raw);
				if(struct.getCanHavePointers()){
					var ref  = refId.getRef();
					var ptrs = new ArrayList<ChunkPointer>(4);
					ptrs.add(ref.getPtr());
					IOInstance val;
					try(var refIo = ref.io(provider)){
						val = (IOInstance)DynamicSupport.readTyp(type, provider, refIo, generics);
					}
					FixedInstance.structWalk(provider, ptrs, false, (StructPipe)StandardStructPipe.of(struct), val, ref);
					return ptrs;
				}
			}
			return List.of(refId.getRef().getPtr());
		}
		@Override
		public boolean needsRemoval(){
			return true;
		}
		
		@Override
		public TypedReference readInline(ContentReader src) throws IOException{
			return refPipe.readNew(provider, src, null);
		}
		@Override
		public void writeInline(RandomIO dest, TypedReference src) throws IOException{
			refPipe.write(provider, dest, src);
		}
		
		@Override
		public long inlineSize(){
			return inlineSize;
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new RefField.NoIOObj<>(accessor, (SizeDescriptor<I>)refPipe.getSizeDescriptor()){
				@Override
				public void setReference(I instance, Reference newRef) throws IOException{
					try(var io = ioAt.get()){
						var pos   = io.getPos();
						var refId = readInline(io);
						io.setPos(pos);
						writeInline(io, refId.withRef(newRef));
					}
				}
				@Override
				public Reference getReference(I instance) throws IOException{
					return readRef().getRef();
				}
				@SuppressWarnings("unchecked")
				@Override
				public ObjectPipe<T, ?> getReferencedPipe(I instance) throws IOException{
					var refId = readRef();
					var type  = refId.getType(provider.getTypeDb());
					var raw   = type.getTypeClass(provider.getTypeDb());
					if(IOInstance.isInstance(raw)){
						return (ObjectPipe<T, ?>)StandardStructPipe.of(Struct.ofUnknown(raw));
					}
					if(raw == String.class){
						return (ObjectPipe<T, ?>)AutoText.STR_PIPE;
					}
					throw new NotImplementedException();//TODO: What to do with the rest?
				}
				private TypedReference readRef() throws IOException{
					TypedReference refId;
					try(var io = ioAt.get()){
						refId = readInline(io);
					}
					return refId;
				}
			};
		}
		
		@Override
		public RuntimeType<T> getType(){
			//noinspection unchecked
			return new RuntimeType.Lambda<>(true, (Class<T>)Object.class, null);
		}
	}
	
	final class SealedInstance<T extends IOInstance<T>> implements ValueStorage<T>{
		
		private final GenericContext            ctx;
		private final DataProvider              provider;
		private final SealedInstanceUniverse<T> universe;
		private final boolean                   canHavePointers;
		private final SizeDescriptor<T>         sizeDescriptor;
		private final RuntimeType<T>            type;
		
		public SealedInstance(GenericContext ctx, DataProvider provider, SealedInstanceUniverse<T> universe){
			this.ctx = ctx;
			this.provider = provider;
			this.universe = universe;
			sizeDescriptor = universe.makeSizeDescriptor(true, false, (pool, inst) -> inst);
			canHavePointers = universe.calcCanHavePointers();
			
			type = new RuntimeType.Lambda<>(canHavePointers, universe.root(), null);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var id   = src.readUnsignedInt4Dynamic();
			var type = provider.getTypeDb().fromID(universe.root(), id);
			var pipe = universe.pipeMap().get(type);
			return pipe.readNew(provider, src, ctx);
		}
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			@SuppressWarnings("unchecked")
			var type = (Class<T>)src.getClass();
			var id   = provider.getTypeDb().toID(universe.root(), type, true);
			var pipe = universe.pipeMap().get(type);
			dest.writeUnsignedInt4Dynamic(id);
			pipe.write(provider, dest, src);
		}
		
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var ptrs = new ArrayList<ChunkPointer>();
			
			var startPos = io.getPos();
			
			var id   = io.readUnsignedInt4Dynamic();
			var type = provider.getTypeDb().fromID(universe.root(), id);
			var pipe = universe.pipeMap().get(type);
			if(!pipe.getType().getCanHavePointers()){
				return List.of();
			}
			var root = pipe.readNew(provider, io, ctx);
			
			var rootRef = new Reference(ChunkPointer.of(69), 420);
			var dirty   = FixedInstance.structWalk(provider, ptrs, dereferenceWrite, pipe, root, rootRef);
			if(dirty){
				write(io.setPos(startPos), root);
			}
			return ptrs;
		}
		@Override
		public boolean needsRemoval(){
			return canHavePointers;
		}
		
		public boolean getCanHavePointers(){
			return canHavePointers;
		}
		
		@Override
		public long inlineSize(){
			return sizeDescriptor.getFixed(WordSpace.BYTE).orElse(-1);
		}
		
		@Override
		public BasicSizeDescriptor<T, ?> getSizeDescriptor(){
			return sizeDescriptor;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			return new NoIOField<>(accessor, universe.makeSizeDescriptor(false, false, (pool, inst) -> (T)accessor.get(pool, inst)));
		}
		
		@Override
		public RuntimeType<T> getType(){
			return type;
		}
	}
	
	final class FixedReferenceSealedInstance<T extends IOInstance<T>> implements ValueStorage<T>, RefStorage<TypedReference>{
		
		private final GenericContext ctx;
		private final DataProvider   provider;
		
		private final BaseFixedStructPipe<TypedReference> refPipe;
		private final long                                size;
		private final SealedInstanceUniverse<T>           universe;
		private final RuntimeType<T>                      type;
		
		public FixedReferenceSealedInstance(
			GenericContext ctx, DataProvider provider,
			BaseFixedStructPipe<TypedReference> refPipe, SealedInstanceUniverse<T> universe
		){
			this.ctx = ctx;
			this.provider = provider;
			this.refPipe = refPipe;
			size = refPipe.getFixedDescriptor().get(WordSpace.BYTE);
			this.universe = universe;
			
			type = new RuntimeType.Lambda<>(true, universe.root(), null);
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			var refId = readInline(src);
			if(refId.isNull()){
				return null;
			}
			var ref  = refId.getRef();
			var type = refId.getType(provider.getTypeDb(), universe.root());
			var pipe = universe.pipeMap().get(type);
			return ref.readNew(provider, pipe, ctx);
		}
		
		
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			//noinspection unchecked
			var type = (Class<T>)src.getClass();
			var pipe = universe.pipeMap().get(type);
			var id   = provider.getTypeDb().toID(universe.root(), type, true);
			
			var orgPos = dest.getPos();
			var refId  = dest.remaining() == 0? new TypedReference() : readInline(dest);
			if(refId.isNull()){
				var ticket = AllocateTicket.withData(pipe, provider, src);
				if(dest instanceof ChunkChainIO io){
					ticket = ticket.withPositionMagnet(io.calcGlobalPos());
				}
				var ch = ticket.submit(provider);
				try{
					writeInline(dest, new TypedReference(ch.getPtr().makeReference(), id));
				}catch(Throwable e){
					provider.getMemoryManager().free(ch);
					throw e;
				}
			}else{
				if(refId.getId() != id){
					refId = new TypedReference(refId.getRef(), id);
					writeInline(dest.setPos(orgPos), refId);
				}
				refId.getRef().write(provider, true, pipe, src);
			}
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException{
			var startPos = io.getPos();
			var refId    = readInline(io);
			if(refId.isNull()){
				return List.of();
			}
			if(dereferenceWrite){
				writeInline(io.setPos(startPos), new TypedReference());
			}
			var type = refId.getType(provider.getTypeDb(), universe.root());
			var pipe = universe.pipeMap().get(type);
			if(pipe.getType().getCanHavePointers()){
				var ref  = refId.getRef();
				var ptrs = new ArrayList<ChunkPointer>(4);
				ptrs.add(ref.getPtr());
				FixedInstance.structWalk(provider, ptrs, false, pipe, ref.readNew(provider, pipe, ctx), ref);
				return ptrs;
			}
			return List.of(refId.getRef().getPtr());
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
				public void setReference(I instance, Reference newRef) throws IOException{
					try(var io = ioAt.get()){
						var pos   = io.getPos();
						var refId = readInline(io);
						io.setPos(pos);
						writeInline(io, refId.withRef(newRef));
					}
				}
				@Override
				public Reference getReference(I instance) throws IOException{
					return readRef().getRef();
				}
				@Override
				public StructPipe<T> getReferencedPipe(I instance) throws IOException{
					var refId = readRef();
					var type  = refId.getType(provider.getTypeDb(), universe.root());
					return universe.pipeMap().get(type);
				}
				private TypedReference readRef() throws IOException{
					TypedReference refId;
					try(var io = ioAt.get()){
						refId = readInline(io);
					}
					return refId;
				}
			};
		}
		
		@Override
		public RuntimeType<T> getType(){
			return type;
		}
		
		@Override
		public TypedReference readInline(ContentReader src) throws IOException{
			return refPipe.readNew(provider, src, null);
		}
		@Override
		public void writeInline(RandomIO dest, TypedReference src) throws IOException{
			refPipe.write(provider, dest, src);
		}
	}
	
	final class InlineWrapped<T> implements ValueStorage<T>{
		
		private final RuntimeType<T> type;
		
		private final DataProvider provider;
		
		private final StandardStructPipe<WrapperStructs.Wrapper<T>> pipe;
		private final Function<T, WrapperStructs.Wrapper<T>>        ctor;
		
		public InlineWrapped(DataProvider provider, Class<T> type){
			this.provider = provider;
			this.type = RuntimeType.of(type);
			var res = WrapperStructs.getWrapperStruct(type);
			if(res == null) throw new IllegalArgumentException(type.getTypeName() + " is not a wrapper");
			pipe = StandardStructPipe.of(res.struct());
			ctor = res.constructor();
		}
		
		@Override
		public T readNew(ContentReader src) throws IOException{
			return pipe.readNew(provider, src, null).get();
		}
		@Override
		public void write(RandomIO dest, T src) throws IOException{
			pipe.write(provider, dest, ctor.apply(src));
		}
		@Override
		public List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite){ return List.of(); }
		
		@Override
		public boolean needsRemoval(){
			return false;
		}
		
		@Override
		public long inlineSize(){
			return -1;
		}
		
		@Override
		public <I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt){
			var d = pipe.getSizeDescriptor();
			return new NoIOField<>(accessor, SizeDescriptor.Unknown.of(d.getWordSpace(), d.getMin(), d.getMax(), (ioPool, prov, value) -> {
				var str = type.getType().cast(accessor.get(ioPool, value));
				if(str == null) return 0;
				return pipe.getSizeDescriptor().calcUnknown(null, prov, ctor.apply(str), d.getWordSpace());
			}));
		}
		@Override
		public RuntimeType<T> getType(){
			return type;
		}
	}
	
	sealed interface StorageRule{
		record Default() implements StorageRule{ }
		
		record FixedOnly() implements StorageRule{ }
		
		record VariableFixed(VaryingSize.Provider provider) implements StorageRule{ }
	}
	
	static ValueStorage<?> makeStorage(DataProvider provider, IOType typeDef, GenericContext generics, StorageRule rule){
		Class<?> clazz = typeDef.getTypeClass(provider.getTypeDb());
		if(DEBUG_VALIDATION){
			assert UtilL.instanceOf(clazz, generics.owner)
				: clazz + " != " + generics.owner;
		}
		if(clazz == Object.class){
			return switch(rule){
				case StorageRule.Default ignored -> new UnknownIDObject(provider, generics);
				case StorageRule.FixedOnly ignored -> new UnknownIDReference<>(provider, generics, FixedStructPipe.of(TypedReference.class));
				case StorageRule.VariableFixed conf -> {
					var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(TypedReference.class), conf.provider);
					yield new UnknownIDReference<>(provider, generics, pipe);
				}
			};
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
				case StorageRule.VariableFixed conf -> {
					var pipe = FixedVaryingStructPipe.tryVarying(Reference.STRUCT, conf.provider);
					yield new FixedReferenceString(provider, pipe);
				}
			};
		}
		
		if(FieldCompiler.getWrapperTypes().contains(clazz)){
			return switch(rule){
				case StorageRule.Default ignored -> new InlineWrapped<>(provider, clazz);
				case StorageRule.FixedOnly ignored -> throw new NotImplementedException();//TODO
				case StorageRule.VariableFixed conf -> throw new NotImplementedException();//TODO
			};
		}
		
		if(isSealedCached(clazz)){
			//noinspection rawtypes
			Optional<SealedInstanceUniverse> oUniverse =
				SealedUtil.getSealedUniverse(clazz, false).flatMap(SealedInstanceUniverse::ofUnknown);
			if(oUniverse.isPresent()){
				SealedInstanceUniverse<?> universe = oUniverse.get();
				return switch(rule){
					case StorageRule.Default ignored -> new SealedInstance<>(generics, provider, universe);
					case StorageRule.FixedOnly ignored -> {
						var pipe = FixedStructPipe.of(TypedReference.STRUCT);
						yield new FixedReferenceSealedInstance<>(generics, provider, pipe, universe);
					}
					case StorageRule.VariableFixed conf -> {
						var pipe = FixedVaryingStructPipe.tryVarying(TypedReference.STRUCT, conf.provider);
						yield new FixedReferenceSealedInstance<>(generics, provider, pipe, universe);
					}
				};
			}
		}
		
		if(!IOInstance.isInstance(clazz)){
			throw new IllegalArgumentException(clazz.getTypeName() + " is not an IOInstance");
		}
		
		if(!IOInstance.isManaged(clazz)){
			return switch(rule){
				case StorageRule.Default ignored -> new UnmanagedInstance<>(typeDef, provider, ChunkPointer.DYN_PIPE);
				case StorageRule.FixedOnly ignored -> new UnmanagedInstance<>(typeDef, provider, ChunkPointer.FIXED_PIPES.get(NumberSize.LARGEST));
				case StorageRule.VariableFixed conf -> {
					var pipe = ChunkPointer.varSizePipe(conf.provider);
					yield new UnmanagedInstance<>(typeDef, provider, pipe);
				}
			};
		}else{
			var struct = Struct.ofUnknown(clazz);
			return switch(rule){
				case StorageRule.Default ignored -> new Instance<>(generics, provider, StandardStructPipe.of(struct));
				case StorageRule.FixedOnly ignored -> {
					try{
						yield new FixedInstance<>(generics, provider, FixedStructPipe.of(struct, STATE_IO_FIELD));
					}catch(UnsupportedStructLayout ignored1){
						yield new FixedReferenceInstance<>(generics, provider, Reference.fixedPipe(), StandardStructPipe.of(struct));
					}
				}
				case StorageRule.VariableFixed conf -> {
					int id = conf.provider.mark();
					try{
						yield new FixedInstance<>(generics, provider, FixedVaryingStructPipe.tryVarying(struct, conf.provider));
					}catch(UnsupportedStructLayout ignored){
						conf.provider.reset(id);
						var valPipe = StandardStructPipe.of(struct);
						var refPipe = FixedVaryingStructPipe.tryVarying(Reference.STRUCT, conf.provider);
						yield new FixedReferenceInstance<>(generics, provider, refPipe, valPipe);
					}
				}
			};
		}
	}
	
	interface RefStorage<RefT>{
		@SuppressWarnings("unchecked")
		static <T, RefT> RefStorage<RefT> of(ValueStorage<T> storage){
			if(storage instanceof ValueStorage.RefStorage<?> s){
				return (RefStorage<RefT>)s;
			}
			return new RefStorage<>(){
				@Override
				public RefT readInline(ContentReader src) throws IOException{
					return (RefT)storage.readNew(src);
				}
				@Override
				public void writeInline(RandomIO dest, RefT src) throws IOException{
					storage.write(dest, (T)src);
				}
				@Override
				public long inlineSize(){
					return storage.inlineSize();
				}
			};
		}
		
		RefT readInline(ContentReader src) throws IOException;
		void writeInline(RandomIO dest, RefT src) throws IOException;
		long inlineSize();
	}
	
	T readNew(ContentReader src) throws IOException;
	void write(RandomIO dest, T src) throws IOException;
	
	List<ChunkPointer> notifyRemoval(RandomIO io, boolean dereferenceWrite) throws IOException;
	boolean needsRemoval();
	
	long inlineSize();
	
	<I extends IOInstance<I>> IOField<I, T> field(FieldAccessor<I> accessor, UnsafeSupplier<RandomIO, IOException> ioAt);
	
	RuntimeType<T> getType();
	
	default BasicSizeDescriptor<T, ?> getSizeDescriptor(){
		return BasicSizeDescriptor.IFixed.Basic.of(WordSpace.BYTE, inlineSize());
	}
}
