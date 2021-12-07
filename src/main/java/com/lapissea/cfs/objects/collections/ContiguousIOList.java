package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeLongConsumer;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class ContiguousIOList<T extends IOInstance<T>> extends AbstractUnmanagedIOList<T, ContiguousIOList<T>>{
	
	private static final TypeDefinition.Check TYPE_CHECK=new TypeDefinition.Check(
		ContiguousIOList.class,
		List.of(t->{
			if(!IOInstance.isManaged(t)) throw new ClassCastException("not a managed "+IOInstance.class.getSimpleName());
		})
	);
	
	private final FixedContiguousStructPipe<T> elementPipe;
	
	public ContiguousIOList(DataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef);
		TYPE_CHECK.ensureValid(typeDef);
		var type=(Struct<T>)typeDef.argAsStruct(0);
		type.requireEmptyConstructor();
		this.elementPipe=FixedContiguousStructPipe.of(type);
		
		if(isSelfDataEmpty()){
			writeManagedFields();
		}
		
		//read data needed for proper function such as number of elements
		readManagedFields();
	}
	
	@Override
	protected StructPipe<ContiguousIOList<T>> newPipe(){
		return FixedContiguousStructPipe.of(getThisStruct());
	}
	
	private static <T extends IOInstance<T>> IOField<ContiguousIOList<T>, ?> eField(Type elementType, SizeDescriptor.Fixed<T> desc, long index){
		return new IOField.NoIO<ContiguousIOList<T>, T>(new AbstractFieldAccessor<>(null, "Element["+index+"]"){
			@Override
			public Type getGenericType(GenericContext genericContext){
				return elementType;
			}
			
			@Override
			public T get(Struct.Pool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance){
				return instance.getUnsafe(index);
			}
			@Override
			public void set(Struct.Pool<ContiguousIOList<T>> ioPool, ContiguousIOList<T> instance, Object value){
				try{
					instance.set(index, (T)value);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}, SizeDescriptor.Fixed.of(desc));
	}
	
	@NotNull
	@Override
	public Stream<IOField<ContiguousIOList<T>, ?>> listDynamicUnmanagedFields(){
		var                     typ =getTypeDef().arg(0).generic(getDataProvider().getTypeDb());
		SizeDescriptor.Fixed<T> desc=elementPipe.getFixedDescriptor();
		return LongStream.range(0, size()).mapToObj(index->eField(typ, desc, index));
	}
	
	private long calcElementOffset(long index){
		var headSiz=calcInstanceSize(WordSpace.BYTE);
		var siz    =getElementSize();
		return headSiz+siz*index;
	}
	private long getElementSize(){
		return elementPipe.getFixedDescriptor().get();
	}
	private void writeAt(long index, T value) throws IOException{
		try(var io=selfIO()){
			var pos=calcElementOffset(index);
			io.skipExact(pos);
			
			elementPipe.write(this, io, value);
		}
	}
	private T readAt(long index) throws IOException{
		try(var io=selfIO()){
			var pos=calcElementOffset(index);
			io.skipExact(pos);
			
			return elementPipe.readNew(getDataProvider(), io, getGenerics());
		}
	}
	
	@Override
	public T get(long index) throws IOException{
		checkSize(index);
		return readAt(index);
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		checkSize(index);
		writeAt(index, value);
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		checkSize(index, 1);
		if(index==size()){
			add(value);
			return;
		}
		
		shift(index, ShiftAction.FORWARD_DUP);
		writeAt(index, value);
		deltaSize(1);
	}
	
	@Override
	public void add(T value) throws IOException{
		Objects.requireNonNull(value);
		
		writeAt(size(), value);
		deltaSize(1);
	}
	
	@Override
	public void addAll(Collection<T> values) throws IOException{
		addMany(values.size(), values.iterator()::next);
	}
	
	private void addMany(long count, UnsafeSupplier<T, IOException> source) throws IOException{
		
		try(var io=selfIO()){
			var pos=calcElementOffset(size());
			io.skipExact(pos);
			var elSiz   =getElementSize();
			var totalPos=pos+count*elSiz;
			io.ensureCapacity(totalPos);
			io.setSize(totalPos);
			
			long targetBytes=512;
			long targetCount=Math.min(count, Math.max(1, targetBytes/elSiz));
			
			var targetCap=targetCount*elSiz;
			
			var mem=MemoryData.build().withCapacity((int)targetCap).build();
			try(var buffIo=mem.io()){
				UnsafeLongConsumer<IOException> flush=change->{
					buffIo.setCapacity(buffIo.getPos());
					mem.transferTo(io);
					deltaSize(change);
					buffIo.setPos(0);
				};
				
				long lastI=0;
				long i    =0;
				
				for(long c=0;c<count;c++){
					T value=source.get();
					
					elementPipe.write(this, buffIo, value);
					i++;
					var s=buffIo.getPos();
					if(s>=targetCap){
						var change=i-lastI;
						lastI=i;
						flush.accept(change);
					}
				}
				
				if(buffIo.getPos()>0){
					var change=i-lastI;
					flush.accept(change);
				}
			}
		}
	}
	
	@Override
	public void remove(long index) throws IOException{
		checkSize(index);
		
		shift(index, ShiftAction.SQUASH);
		
		deltaSize(-1);
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(count==0) return;
		if(count<0) throw new IllegalArgumentException("Count must be positive!");
		
		T val=getElementType().requireEmptyConstructor().get();
		
		addMany(count, ()->{
			if(initializer!=null){
				initializer.accept(val);
			}
			return val;
		});
	}
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		deltaSize(-size());
		try(var io=selfIO()){
			io.setCapacity(calcElementOffset(0));
		}
	}
	
	private enum ShiftAction{
		SQUASH, FORWARD_DUP
	}
	
	private void shift(long index, ShiftAction action) throws IOException{
		try(var io=selfIO()){
			var    siz =getElementSize();
			byte[] buff=new byte[Math.toIntExact(siz)];
			
			int dummy=switch(action){
				case SQUASH -> {
					for(long i=index;i<size()-1;i++){
						var nextPos=calcElementOffset(i+1);
						io.setPos(nextPos);
						io.readFully(buff);
						
						var pos=calcElementOffset(i);
						io.setPos(pos);
						io.write(buff);
					}
					
					var lastOff=calcElementOffset(size()-1);
					io.setCapacity(lastOff);
					yield 0;
				}
				case FORWARD_DUP -> {
					var lastOff=calcElementOffset(size()+1);
					io.setCapacity(lastOff);
					
					for(long i=index;i<size();i++){
						
						var pos=calcElementOffset(i);
						io.setPos(pos);
						io.readFully(buff);
						
						var nextPos=calcElementOffset(i+1);
						io.setPos(nextPos);
						io.write(buff);
					}
					yield 0;
				}
			};
		}
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		try(var io=selfIO()){
			var cap=calcElementOffset(capacity);
			io.ensureCapacity(cap);
		}
	}
	
	@Override
	public Struct<T> getElementType(){
		return elementPipe.getType();
	}
	
	@Override
	public void free() throws IOException{
		Set<Chunk> chunks=new HashSet<>();
		var        prov  =getDataProvider();
		
		new MemoryWalker(this).walk(true, ref->{
			if(ref.isNull()) return;
			ref.getPtr().dereference(prov).streamNext().forEach(chunks::add);
		});
		
		prov.getMemoryManager().free(new ArrayList<>(chunks));
	}
	@NotNull
	@Override
	protected String getStringPrefix(){
		return "C";
	}
}
