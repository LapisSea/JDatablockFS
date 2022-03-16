package com.lapissea.cfs.type;

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
import java.util.Objects;
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
		
		protected Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){
			this(provider, reference, typeDef);
			check.ensureValid(typeDef);
		}
		
		public Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef){
			this.provider=Objects.requireNonNull(provider);
			this.reference=reference.requireNonNull();
			this.typeDef=typeDef;
		}
		
		@NotNull
		public Stream<IOField<SELF, ?>> listDynamicUnmanagedFields(){
			return Stream.of();
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
				assert Arrays.equals(oldData, newData):"Data changed when moving reference! This is invalid behaviour";
			}
			
			reference=newRef;
		}
		
		public void free() throws IOException{}
		
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
	
	private final Struct<SELF>      thisStruct;
	private final Struct.Pool<SELF> virtualFields;
	
	@SuppressWarnings("unchecked")
	public IOInstance(){
		this.thisStruct=Struct.of((Class<SELF>)getClass());
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	public IOInstance(Struct<SELF> thisStruct){
		if(DEBUG_VALIDATION){
			if(!thisStruct.getType().equals(getClass())){
				throw new IllegalArgumentException(thisStruct+" is not "+getClass());
			}
		}
		this.thisStruct=thisStruct;
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	
	
	public Struct<SELF> getThisStruct(){
		return thisStruct;
	}
	
	private Struct.Pool<SELF> getVirtualPool(){return virtualFields;}
	
	@SuppressWarnings("unchecked")
	protected final SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(getThisStruct().allocVirtualVarPool(IO), self(), false);
	}
	public String toShortString(){
		return getThisStruct().instanceToString(getThisStruct().allocVirtualVarPool(IO), self(), true);
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
		for(var field : thisStruct.getFields()){
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
	
	public static boolean isManaged(TypeLink type){
		return isManaged(type.getTypeClass(null));
	}
	
	public static boolean isManaged(Class<?> type){
		var isInstance =UtilL.instanceOf(type, IOInstance.class);
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
				
				if(UtilL.instanceOf(typ.componentType(), IOInstance.class)){
					var iArr=(IOInstance<?>[])arr;
					for(int i=0;i<iArr.length;i++){
						var el=iArr[i];
						iArr[i]=el.clone();
					}
				}
				
				arrField.set(null, c, arr);
				continue;
			}
			
			if(!UtilL.instanceOf(typ, IOInstance.class)) continue;
			if(UtilL.instanceOf(typ, IOInstance.Unmanaged.class)) continue;
			var instField=(IOField<SELF, IOInstance<?>>)field;
			
			var val=instField.get(null, (SELF)this);
			if(val==null) continue;
			
			val=val.clone();
			
			instField.set(null, c, val);
		}
		
		return c;
	}
}
