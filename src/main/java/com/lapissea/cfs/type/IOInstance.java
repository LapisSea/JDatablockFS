package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public abstract class IOInstance<SELF extends IOInstance<SELF>> implements Cloneable{
	
	public abstract static class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance<SELF> implements DataProvider.Holder{
		
		private final DataProvider provider;
		private       Reference    reference;
		private final TypeLink     typeDef;
		
		private StructPipe<SELF> pipe;
		
		protected final boolean readOnly;
		
		protected Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){
			this(provider, reference, typeDef);
			check.ensureValid(typeDef);
		}
		
		public Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef){
			this.provider=Objects.requireNonNull(provider);
			this.reference=reference.requireNonNull();
			this.typeDef=typeDef;
			readOnly=getDataProvider().isReadOnly();
		}
		
		@NotNull
		protected Stream<IOField<SELF, ?>> listDynamicUnmanagedFields(){
			return Stream.of();
		}
		
		@NotNull
		public final Stream<IOField<SELF, ?>> listUnmanagedFields(){
			var s =getThisStruct();
			var fs=s.getUnmanagedStaticFields().stream();
			if(!s.isOverridingDynamicUnmanaged()){
				return fs;
			}
			return Stream.concat(listDynamicUnmanagedFields(), fs);
		}
		
		public TypeLink getTypeDef(){
			return typeDef;
		}
		
		public GenericContext getGenerics(){
			return getThisStruct().describeGenerics(typeDef);
		}
		
		@Override
		public DataProvider getDataProvider(){
			return provider;
		}
		
		protected boolean isSelfDataEmpty() throws IOException{
			try(var io=selfIO()){
				return io.getSize()==0;
			}
		}
		
		public void notifyReferenceMovement(Reference newRef){
			newRef.requireNonNull();
			
			if(DEBUG_VALIDATION){
				byte[] oldData, newData;
				try(var oldIo=reference.io(this);
				    var newIo=newRef.io(this)
				){
					oldData=oldIo.readRemaining();
					newData=newIo.readRemaining();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				assert Arrays.equals(oldData, newData):"Data changed when moving reference! This is invalid behaviour\n"+
				                                       Arrays.toString(oldData)+"\n"+
				                                       Arrays.toString(newData);
			}
			
			reference=newRef;
		}
		
		public Struct.Unmanaged<SELF> getThisStruct(){
			return (Struct.Unmanaged<SELF>)super.getThisStruct();
		}
		
		public void free() throws IOException{
			Set<Chunk> chunks=new HashSet<>();
			
			new MemoryWalker(self()).walk(true, ref->{
				if(ref.isNull()){
					return;
				}
				ref.getPtr().dereference(getDataProvider()).streamNext().forEach(chunks::add);
			});
			
			getDataProvider()
				.getMemoryManager()
				.free(chunks);
		}
		
		protected RandomIO selfIO() throws IOException{
			return reference.io(provider);
		}
		
		protected StructPipe<SELF> newPipe(){
			return ContiguousStructPipe.of(getThisStruct());
		}
		
		public StructPipe<SELF> getPipe(){
			if(pipe==null) pipe=newPipe();
			return pipe;
		}
		
		protected void writeManagedFields() throws IOException{
			if(readOnly){
				throw new UnsupportedOperationException();
			}
			try(var io=selfIO()){
				getPipe().write(provider, io, self());
			}
		}
		protected void readManagedFields() throws IOException{
			try(var io=selfIO()){
				getPipe().read(provider, io, self(), getGenerics());
			}
		}
		
		protected void readManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				getPipe().readSingleField(getPipe().makeIOPool(), provider, io, field, self(), getGenerics());
			}
		}
		
		protected void writeManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				getPipe().writeSingleField(provider, io, field, self());
			}
		}
		
		protected long calcInstanceSize(WordSpace wordSpace){
			var siz=getPipe().getSizeDescriptor();
			var f  =siz.getFixed(wordSpace);
			if(f.isPresent()) return f.getAsLong();
			return siz.calcUnknown(getPipe().makeIOPool(), getDataProvider(), self(), wordSpace);
		}
		
		public Reference getReference(){
			return reference;
		}
		
		protected void allocateNulls() throws IOException{
			allocateNulls(getDataProvider());
		}
	}
	
	private Struct<SELF>      thisStruct;
	private Struct.Pool<SELF> virtualFields;
	
	public IOInstance(){}
	
	public IOInstance(Struct<SELF> thisStruct){
		if(DEBUG_VALIDATION){
			if(!thisStruct.getType().equals(getClass())){
				throw new IllegalArgumentException(thisStruct+" is not "+getClass());
			}
		}
		this.thisStruct=thisStruct;
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	
	@SuppressWarnings("unchecked")
	private void init(){
		if(thisStruct!=null) return;
		
		thisStruct=Struct.of((Class<SELF>)getClass());
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	
	public Struct<SELF> getThisStruct(){
		init();
		return thisStruct;
	}
	
	private Struct.Pool<SELF> getVirtualPool(){
		init();
		return virtualFields;
	}
	
	@SuppressWarnings("unchecked")
	protected final SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(getThisStruct().allocVirtualVarPool(IO), self(), false);
	}
	public String toShortString(){
		return getThisStruct().instanceToString(getThisStruct().allocVirtualVarPool(IO), self(), true);
	}
	
	public String toString(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return getThisStruct().instanceToString(getThisStruct().allocVirtualVarPool(IO), self(), doShort, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		
		if(!(o instanceof IOInstance<?> that)) return false;
		var struct=getThisStruct();
		if(that.getThisStruct()!=struct) return false;
		
		var ioPool1=struct.allocVirtualVarPool(IO);
		var ioPool2=struct.allocVirtualVarPool(IO);
		for(var field : struct.getFields()){
			if(!field.instancesEqual(ioPool1, self(), ioPool2, (SELF)that)) return false;
		}
		
		return true;
	}
	@Override
	public int hashCode(){
		int result=1;
		var ioPool=getThisStruct().allocVirtualVarPool(IO);
		for(var field : getThisStruct().getFields()){
			result=31*result+field.instanceHashCode(ioPool, self());
		}
		return result;
	}
	
	public void allocateNulls(DataProvider provider) throws IOException{
		var pool=getThisStruct().allocVirtualVarPool(IO);
		//noinspection unchecked
		for(var ref : getThisStruct().getFields().byFieldTypeIter((Class<IOField.Ref<SELF, ?>>)(Object)IOField.Ref.class)){
			if(!ref.isNull(pool, self()))
				continue;
			ref.allocate(self(), provider, getGenericContext());
		}
	}
	
	private GenericContext getGenericContext(){
		//TODO: find generic context?
		return null;
	}
	
	public static boolean isInstance(TypeLink type){
		return isInstance(type.getTypeClass(null));
	}
	public static boolean isInstance(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.class);
	}
	
	public static boolean isManaged(TypeLink type){
		return isManaged(type.getTypeClass(null));
	}
	
	public static boolean isUnmanaged(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.Unmanaged.class);
	}
	public static boolean isManaged(Class<?> type){
		var isInstance =isInstance(type);
		var isUnmanaged=UtilL.instanceOf(type, IOInstance.Unmanaged.class);
		return isInstance&&!isUnmanaged;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected SELF clone(){
		if(this instanceof IOInstance.Unmanaged){
			throw new UnsupportedOperationException("Unmanaged objects can not be cloned");
		}
		
		SELF c;
		try{
			c=(SELF)super.clone();
		}catch(CloneNotSupportedException e){
			throw new RuntimeException(e);
		}
		
		for(IOField<SELF, ?> field : getThisStruct().getFields()){
			if(field.getAccessor() instanceof VirtualAccessor acc&&acc.getStoragePool()==IO){
				continue;
			}
			var typ=field.getAccessor().getType();
			if(typ.isArray()){
				var arrField=(IOField<SELF, Object[]>)field;
				
				var arr=arrField.get(null, (SELF)this);
				if(arr==null) continue;
				
				arr=arr.clone();
				
				if(IOInstance.isInstance(typ.componentType())){
					var iArr=(IOInstance<?>[])arr;
					for(int i=0;i<iArr.length;i++){
						var el=iArr[i];
						iArr[i]=el.clone();
					}
				}
				
				arrField.set(null, c, arr);
				continue;
			}
			
			if(!IOInstance.isInstance(typ)) continue;
			if(IOInstance.isUnmanaged(typ)) continue;
			var instField=(IOField<SELF, IOInstance<?>>)field;
			
			var val=instField.get(null, (SELF)this);
			if(val==null) continue;
			
			val=val.clone();
			
			instField.set(null, c, val);
		}
		
		return c;
	}
}
