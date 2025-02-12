package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.exceptions.TypeIOFail;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.ValueStorage;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.instancepipe.FieldDependency;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.BasicFieldAccessor;
import com.lapissea.dfs.type.field.access.TypeFlag;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnmanagedValueInfo;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NoIOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.dfs.utils.iterableplus.IterablePPSource;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.StoragePool.IO;

public class IONode<T> extends IOInstance.Unmanaged<IONode<T>> implements IterablePPSource<IONode<T>>{
	static{ allowFullAccess(MethodHandles.lookup()); }
	
	private static final class Info{
		@SuppressWarnings("unchecked")
		private static final Struct<IONode<Object>> STRUCT               = (Struct<IONode<Object>>)(Object)Struct.of(IONode.class, Struct.STATE_FIELD_MAKE);
		private static final IOField<?, NumberSize> NEXT_SIZE_FIELD      = STRUCT.getFields().requireExact(NumberSize.class, "nextSize");
		private static final int                    NEXT_SIZE_FIELD_SIZE = (int)getNextSizeField().getSizeDescriptor().requireMax(WordSpace.BYTE);
	}
	
	private static final class LinkedValueIterator<T> implements IOIterator.Iter<T>{
		
		private final Iter<IONode<T>> nodes;
		
		private LinkedValueIterator(IONode<T> node){
			nodes = node.iterator();
		}
		
		@Override
		public boolean hasNext(){
			return nodes.hasNext();
		}
		
		@Override
		public T ioNext() throws IOException{
			var node = nodes.ioNext();
			return node.getValue();
		}
		@Override
		public void ioRemove() throws IOException{
			nodes.ioRemove();
		}
	}
	
	@IOUnmanagedValueInfo
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T> IOUnmanagedValueInfo.Data<IONode<T>> info(){
		return () -> {
			var valueAccessor = new BasicFieldAccessor<IONode<T>>(null, "value", List.of(
				Annotations.make(IOValue.Generic.class),
				Annotations.makeNullability(IONullability.Mode.NULLABLE)
			)){
				@Override
				public Type getGenericType(GenericContext genericContext){
					return Object.class;
				}
				@Override
				public int getTypeID(){
					return TypeFlag.ID_OBJECT;
				}
				@Override
				public boolean genericTypeHasArgs(){ return false; }
				@Override
				public T get(VarPool<IONode<T>> ioPool, IONode<T> instance){
					try{
						return instance.getValue();
					}catch(IOException e){
						throw UtilL.uncheckedThrow(e);
					}
				}
				@Override
				public void set(VarPool<IONode<T>> ioPool, IONode<T> instance, Object value){
					try{
						if(value != null){
							var arg = IOType.getArg(instance.getTypeDef(), 0);
							if(!UtilL.instanceOfObj(value, arg.getTypeClass(instance.getDataProvider().getTypeDb()))){
								throw new ClassCastException(arg + " not compatible with " + value);
							}
						}
						
						instance.setValue((T)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public boolean isReadOnly(){ return false; }
			};
			
			SizeDescriptor<IONode<T>> valDesc = SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst) -> {
				var val = valueAccessor.get(ioPool, inst);
				if(val == null) return 0;
				var siz = inst.valueStorage.inlineSize();
				if(inst.valueStorage instanceof ValueStorage.Instance iStor){
					try{
						return iStor.getPipe().calcUnknownSize(prov, (IOInstance)inst.getValue(), WordSpace.BYTE);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				return siz;
			});
			
			var nextAccessor = new BasicFieldAccessor<IONode<T>>(null, "next", List.of(
				Annotations.makeNullability(IONullability.Mode.NULLABLE)
			)){
				@Override
				public Type getGenericType(GenericContext genericContext){
					if(genericContext == null) return IONode.class;
					throw new NotImplementedException();
				}
				@Override
				public Class<?> getType(){
					return IONode.class;
				}
				@Override
				public int getTypeID(){
					return TypeFlag.ID_OBJECT;
				}
				@Override
				public boolean genericTypeHasArgs(){
					return true;
				}
				@Override
				public Object get(VarPool<IONode<T>> ioPool, IONode<T> instance){
					try{
						return instance.getNext();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public void set(VarPool<IONode<T>> ioPool, IONode<T> instance, Object value){
					try{
						instance.setNext((IONode<T>)value);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public boolean isReadOnly(){ return false; }
			};
			
			var next = new RefField.NoIO<IONode<T>, IONode<T>>(nextAccessor, SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, NumberSize.LARGEST.optionalBytesLong, (ioPool, prov, node) -> node.nextSize.bytes)){
				@Override
				protected Set<TypeFlag> computeTypeFlags(){
					return Iters.of(TypeFlag.IO_INSTANCE).nonNulls().toModSet();
				}
				@Override
				public void setReference(IONode<T> instance, Reference newRef){
					if(newRef.getOffset() != 0) throw new NotImplementedException();
					try{
						instance.setNextRaw(newRef.getPtr());
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				@Override
				public Reference getReference(IONode<T> instance){
					ChunkPointer next;
					try{
						next = instance.getNextPtr();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					return next.isNull()? Reference.NULL : next.makeReference();
				}
				@Override
				public StructPipe<IONode<T>> getReferencedPipe(IONode<T> instance){
					return instance.getPipe();
				}
			};
			next.initLateData(FieldSet.of(List.of(getNextSizeField())));
			
			
			return Iters.of(
				next,
				new NoIOField<>(valueAccessor, valDesc)
			);
		};
	}
	
	private static final TypeCheck NODE_TYPE_CHECK = new TypeCheck(
		IONode.class,
		(type, db) -> { }
	);
	
	private static NumberSize calcOptimalNextSize(DataProvider provider) throws IOException{
		return NumberSize.bySize(provider.getSource().getIOSize()).next();
	}
	
	private static <T> NumberSize getNextSize(IONode<T> next, DataProvider provider) throws IOException{
		if(next != null) return NumberSize.bySize(next.getPointer());
		else return calcOptimalNextSize(provider);
	}
	private static Chunk allocateNodeChunk(DataProvider provider, OptionalLong positionMagnet, NumberSize nextSize, long bytes) throws IOException{
		int nextBytes = nextSize.bytes;
		return AllocateTicket.bytes(bytes).withDataPopulated((p, io) -> {
			try(var writer = new BitOutputStream(io)){
				writer.writeEnum(NumberSize.FLAG_INFO, nextSize);
			}
			io.writeWord(0, nextBytes);
		}).withPositionMagnet(positionMagnet).submit(provider);
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static <T> IONode<T> allocValNode(T value, IONode<T> next, BasicSizeDescriptor<T, ?> sizeDescriptor, IOType nodeType, DataProvider provider, OptionalLong positionMagnet) throws IOException{
		var nextSize = getNextSize(next, provider);
		
		var bytes = 1 + nextSize.bytes + switch(sizeDescriptor){
			case SizeDescriptor.Fixed<?> fixed -> fixed.get(WordSpace.BYTE);
			case SizeDescriptor.Unknown unknown ->
				unknown.calcUnknown(((IOInstance<?>)value).getThisStruct().allocVirtualVarPool(IO), provider, value, WordSpace.BYTE);
			case BasicSizeDescriptor<T, ?> basic -> basic.calcUnknown(null, provider, value, WordSpace.BYTE);
		};
		
		Chunk chunk = allocateNodeChunk(provider, positionMagnet, nextSize, bytes);
		try(var ignored = provider.getSource().openIOTransaction()){
			return new IONode<>(provider, chunk, nodeType, value, next);
		}
	}
	
	public static <T> IONode<T> allocValNode(RandomIO valueBytes, IONode<T> next, IOType nodeType, DataProvider provider, OptionalLong positionMagnet) throws IOException{
		var nextSize = getNextSize(next, provider);
		
		var bytes = 1 + nextSize.bytes + valueBytes.remaining();
		
		try(var ignored = provider.getSource().openIOTransaction()){
			var chunk = allocateNodeChunk(provider, positionMagnet, nextSize, bytes);
			var node  = new IONode<>(provider, chunk, nodeType, null, next);
			node.ensureNextSpace();
			try(var io = node.getValueDataIO()){
				valueBytes.transferTo(io);
			}
			return node;
		}
	}
	
	private final ValueStorage<T> valueStorage;
	
	@IOValue
	private NumberSize nextSize;
	
	public IONode(DataProvider provider, Chunk identity, IOType typeDef, T val, IONode<T> next) throws IOException{
		this(provider, identity, typeDef);
		
		var newSiz = calcOptimalNextSize(provider);
		if(newSiz.greaterThan(nextSize)){
			var nextPtr = getNextPtr();
			nextSize = newSiz;
			writeManagedFields();
			writeNextPtr(nextPtr);
		}
		
		if(next != null) setNext(next);
		if(val != null) setValue(val);
	}
	
	@SuppressWarnings("unchecked")
	public IONode(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super((Struct<IONode<T>>)(Object)Info.STRUCT, provider, identity, typeDef, NODE_TYPE_CHECK);
		
		var magnetProvider = provider.withRouter(t -> t.withPositionMagnet(t.positionMagnet().orElse(getPointer().getValue())));
		
		//noinspection unchecked
		valueStorage = (ValueStorage<T>)ValueStorage.makeStorage(magnetProvider, IOType.getArg(typeDef, 0), getGenerics().argAsContext("T"), new ValueStorage.StorageRule.Default());
		
		try{
			readManagedFields();
		}catch(EOFException eof){
			if(isSelfDataEmpty() && !readOnly){
				nextSize = calcOptimalNextSize(provider);
				writeManagedFields();
			}else{
				throw eof;
			}
		}
	}

//	@Override
//	public boolean equals(Object o){
//		if(this == o) return true;
//		if(!(o instanceof IONode<?> that)) return false;
//
//		if(!this.getPointer().equals(that.getPointer())){
//			return false;
//		}
//		if(!this.getTypeDef().equals(that.getTypeDef())){
//			return false;
//		}
//
//		try{
//			if(!this.getNextPtr().equals(that.getNextPtr())){
//				return false;
//			}
//			if(this.hasValue() != that.hasValue()){
//				return false;
//			}
//			var v1 = this.getValue();
//			var v2 = that.getValue();
//			return Objects.equals(v1, v2);
//		}catch(IOException e){
//			throw new RuntimeException(e);
//		}
//	}
	
	@Override
	public int hashCode(){
		return getPointer().hashCode();
	}
	
	private void ensureNextSpace() throws IOException{
		try(var io = selfIO()){
			ensureNextSpace(io);
		}
	}
	private void ensureNextSpace(RandomIO io) throws IOException{
		var valueStart = valueStart();
		var skipped    = io.skip(valueStart);
		var toWrite    = valueStart - skipped;
		IOUtils.zeroFill(io, toWrite);
		io.setPos(0);
	}
	
	T getValue() throws IOException{
		return readValue();
	}
	
	@Nullable
	RandomIO getValueDataIO() throws IOException{
		var io = selfIO();
		try{
			var s = io.getSize();
			if(s == 0) return null;
			if(s<Info.NEXT_SIZE_FIELD_SIZE) return null;
			if(DEBUG_VALIDATION){
				if(!getPipe().getSpecificFields().getFirst().equals(getNextSizeField())){
					throw new ShouldNeverHappenError();
				}
			}
			updateNextSize(io);

//			if(DEBUG_VALIDATION){
//				requireNonFreed();
//			}
			
			long toSkip  = nextSize.bytes;
			long skipped = 0;
			while(skipped<toSkip){
				long remaining    = toSkip - skipped;
				var  skippedChunk = io.skip(remaining);
				if(skippedChunk == 0){
					return null;
				}
				skipped += skippedChunk;
			}
			return io;
		}catch(Throwable e){
			io.close();
			throw e;
		}
	}
	
	private T readValue() throws IOException{
		try(var io = getValueDataIO()){
			if(io == null) return null;
			if(io.remaining() == 0){
				return null;
			}
			return valueStorage.readNew(io);
		}catch(TypeIOFail e){
			throw new TypeIOFail("Failed reading " + getTypeDef().toShortString(), null, e);
		}catch(IOException e){
			throw readFail(e);
		}
	}
	
	private IOException readFail(IOException e) throws IOException{
		StringBuilder sb = new StringBuilder();
		sb.append("@ ").append(this.getPointer().makeReference().addOffset(valueStart()).infoString(getDataProvider())).append(": ");
		var cause = e.getCause();
		while(cause != null && cause.getClass() == IOException.class && cause.getLocalizedMessage() != null){
			sb.append(e.getLocalizedMessage()).append(": ");
			cause = cause.getCause();
		}
		if(cause != null){
			sb.append(cause.getClass().getSimpleName());
			var msg = cause.getLocalizedMessage();
			if(msg != null) sb.append(": ").append(msg);
		}
		return new IOException(sb.toString(), e);
	}
	
	private void requireNonFreed() throws IOException{
		var ch    = this.getPointer();
		var frees = getDataProvider().getMemoryManager().getFreeChunks();
		if(frees.contains(ch)){
			throw new RuntimeException(frees + " " + ch);
		}
	}
	
	boolean hasValue() throws IOException{
		try(var io = selfIO()){
			if(io.remaining()<Info.NEXT_SIZE_FIELD_SIZE){
				return false;
			}
			io.skipExact(Info.NEXT_SIZE_FIELD_SIZE);
			if(io.remaining()<nextSize.bytes){
				return false;
			}
			io.skipExact(nextSize.bytes);
			if(DEBUG_VALIDATION){
				if(valueStart() != io.getPos()) throw new AssertionError();
			}
			return io.remaining() != 0;
		}
	}
	
	void setValue(T value) throws IOException{
		if(readOnly){
			throw new UnsupportedOperationException();
		}
		try(var io = selfIO()){
			ensureNextSpace(io);
			io.skipExact(valueStart());
			if(value != null){
				valueStorage.write(io, value);
			}
			io.trim();
		}
		if(DEBUG_VALIDATION) checkSetValue(value);
	}
	private void checkSetValue(T value) throws IOException{
		T v = getValue();
		if(!Objects.equals(value, v)){
			throw new AssertionError("\n" + value + " != \n" + v);
		}
	}
	
	private ChunkPointer getNextPtr() throws IOException{
		return readNextPtr();
	}
	private ChunkPointer readNextPtr() throws IOException{
		try(var io = selfIO()){
			updateNextSize(io);
			if(io.remaining() == 0){
				return ChunkPointer.NULL;
			}
			return ChunkPointer.read(nextSize, io);
		}
	}
	
	private byte nextSizeRaw;
	private void updateNextSize(ChunkChainIO io) throws IOException{
		byte newData;
		newData = io.readInt1();
		if(nextSizeRaw == newData) return;
		nextSizeRaw = newData;
		try(var f = new FlagReader(nextSizeRaw&0xFF, NumberSize.BYTE)){
			nextSize = f.readEnum(NumberSize.FLAG_INFO);
		}
	}
	
	IONode<T> getNext() throws IOException{
		return readNext();
	}
	
	public boolean hasNext() throws IOException{
		return !getNextPtr().isNull();
	}
	
	private IONode<T> readNext() throws IOException{
		var ptr = getNextPtr();
		if(ptr.isNull()) return null;
		
		var prov = getDataProvider();
		return new IONode<>(prov, ptr.dereference(prov), getTypeDef());
	}
	
	@SuppressWarnings("unchecked")
	private static <T> IOField<IONode<T>, NumberSize> getNextSizeField(){
		return (IOField<IONode<T>, NumberSize>)Info.NEXT_SIZE_FIELD;
	}
	
	private int valueStart(){
		return Info.NEXT_SIZE_FIELD_SIZE + nextSize.bytes;
	}
	
	public void setNext(IONode<T> next) throws IOException{
		ChunkPointer ptr;
		if(next == null) ptr = ChunkPointer.NULL;
		else ptr = next.getPointer();
		
		setNextRaw(ptr);
	}
	private void setNextRaw(ChunkPointer ptr) throws IOException{
		var oldPtr = getNextPtr();
		if(oldPtr.equals(ptr)){
			return;
		}
		
		assert !ptr.equals(getPointer()) : "Can not set next to self! " + getPointer() + " -> " + ptr;
		
		writeNextPtr(ptr);
	}
	
	private void writeNextPtr(ChunkPointer ptr) throws IOException{
		var newSiz = NumberSize.bySize(ptr);
		if(newSiz.greaterThan(nextSize)){
			var val  = getValue();
			var grow = newSiz.bytes - nextSize.bytes;
			nextSize = newSiz;
			selfIO(io -> io.ensureCapacity(io.getCapacity() + grow));
			try(var ignored = getDataProvider().getSource().openIOTransaction()){
				writeManagedFields();
				setValue(val);
			}
		}
		
		try(var io = selfIO()){
			io.skipExact(Info.NEXT_SIZE_FIELD_SIZE);
			nextSize.write(io, ptr);
		}
	}
	
	public IONode<T> getLast() throws IOException{
		var node = this;
		while(true){
			var next = node.getNext();
			if(next == null) return node;
			node = next;
		}
	}
	
	@Override
	public String toShortString(){
		if(isFreed()){
			return "{FREED}";
		}
		
		String  val;
		boolean valCorrupted = false;
		try{
			val = Utils.toShortString(getValue());
		}catch(Throwable e){
			val = IOFieldTools.corruptedGet(e);
			valCorrupted = true;
		}
		var result = new StringBuilder().append("{").append(Utils.toShortString(val));
		try{
			var next = getNextPtr();
			if(!next.isNull()){
				result.append(" -> ").append(next);
			}
		}catch(Throwable e){
			if(!valCorrupted){
				result.append(" -> ").append(IOFieldTools.corruptedGet(e));
			}
		}
		
		return result.append("}").toString();
	}
	@Override
	public String toString(){
		return this.getClass().getSimpleName() + toShortString();
	}
	
	private static final class NodeIterator<T> implements IOIterator.Iter<IONode<T>>{
		
		private IONode<T>   node;
		private IOException e;
		
		private NodeIterator(IONode<T> node){
			this.node = node;
		}
		
		@Override
		public boolean hasNext(){
			return node != null;
		}
		
		@Override
		public IONode<T> ioNext() throws IOException{
			
			if(e != null){
				throw e;
			}
			
			IONode<T> next;
			try{
				next = node.getNext();
			}catch(IOException e){
				this.e = e;
				next = null;
			}
			
			var current = node;
			node = next;
			return current;
		}
	}
	@Override
	public final IOIterator.Iter<IONode<T>> iterator(){
		return new NodeIterator<>(this);
	}
	public final IOIterator.Iter<T> valueIterator(){
		return new LinkedValueIterator<>(this);
	}
	
	public record ValResult<T>(boolean empty, T val){
		public static final ValResult<?> EMPTY = new ValResult<>(true, null);
	}
	public static <T> ValResult<T> emptyValResult(){
		//noinspection unchecked
		return (ValResult<T>)ValResult.EMPTY;
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public ValResult<T> readValueField(IOField<?, ?> field) throws IOException{
		return (ValResult<T>)readValueSelective(((ValueStorage.InstanceBased)valueStorage).depTicket(field));
	}
	
	public <TI extends IOInstance<TI>> ValResult<TI> readValueSelective(FieldDependency.Ticket<TI> depTicket, boolean strictHolder) throws IOException{
		if(!hasValue()) return emptyValResult();
		
		//noinspection unchecked
		var based = (ValueStorage.InstanceBased<TI>)valueStorage;
		try(var io = selfIO()){
			io.skipExact(valueStart());
			var       res = based.readNewSelective(io, depTicket, strictHolder);
			Class<TI> typ = based.getType().getType();
			typ.cast(res);
			return new ValResult<>(false, res);
		}
	}
	
	public <TI extends IOInstance<TI>> ValResult<TI> readValueSelective(FieldDependency.Ticket<TI> depTicket) throws IOException{
		if(!hasValue()) return emptyValResult();
		//noinspection unchecked
		var based = (ValueStorage.InstanceBased<TI>)valueStorage;
		try(var io = selfIO()){
			io.skipExact(valueStart());
			var       res = based.readNewSelective(io, depTicket, false);
			Class<TI> typ = based.getType().getType();
			typ.cast(res);
			return new ValResult<>(false, res);
		}
	}
	
	@Override
	public OptionalInt tryGetSize(){
		boolean next;
		try{
			next = hasNext();
		}catch(IOException e){
			next = true;
		}
		if(next){
			return OptionalInt.empty();
		}
		return OptionalInt.of(1);
	}
}
