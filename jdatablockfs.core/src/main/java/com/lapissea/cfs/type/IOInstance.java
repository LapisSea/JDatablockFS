package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.chunk.MemoryOperations;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.NotImplementedException;
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
public sealed interface IOInstance<SELF extends IOInstance<SELF>> extends Cloneable{
	
	abstract non-sealed class Managed<SELF extends Managed<SELF>> implements IOInstance<SELF>{
		
		private Struct<SELF>      thisStruct;
		private Struct.Pool<SELF> virtualFields;
		
		public Managed(){}
		
		public Managed(Struct<SELF> thisStruct){
			if(DEBUG_VALIDATION) checkStruct(thisStruct);
			this.thisStruct=thisStruct;
			virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
		}
		
		private void checkStruct(Struct<SELF> thisStruct){
			if(!thisStruct.getType().equals(getClass())){
				throw new IllegalArgumentException(thisStruct+" is not "+getClass());
			}
		}
		
		@SuppressWarnings("unchecked")
		private void init(){
			thisStruct=Struct.of((Class<SELF>)getClass());
			virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
		}
		
		@Override
		public Struct<SELF> getThisStruct(){
			if(thisStruct==null) init();
			return thisStruct;
		}
		
		private Struct.Pool<SELF> getVirtualPool(){
			if(thisStruct==null) init();
			return virtualFields;
		}
		
		@SuppressWarnings("unchecked")
		protected final SELF self(){return (SELF)this;}
		
		@Override
		public String toString(){
			return getThisStruct().instanceToString(self(), false);
		}
		@Override
		public String toShortString(){
			return getThisStruct().instanceToString(self(), true);
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
		
		@Override
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
		
		
		@SuppressWarnings("unchecked")
		@Override
		public SELF clone(){
			SELF c;
			try{
				c=(SELF)super.clone();
			}catch(CloneNotSupportedException e){
				throw new RuntimeException(e);
			}
			
			for(IOField<SELF, ?> field : getThisStruct().getFields()){
				if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG)){
					continue;
				}
				var acc=field.getAccessor();
				if(acc instanceof VirtualAccessor<?> vAcc&&vAcc.getStoragePool()==IO){
					continue;
				}
				var typ=acc.getType();
				if(typ.isArray()){
					var arrField=(IOField<SELF, Object[]>)field;
					
					var arr=arrField.get(null, (SELF)this);
					if(arr==null) continue;
					
					arr=arr.clone();
					
					if(isInstance(typ.componentType())){
						var iArr=(IOInstance<?>[])arr;
						for(int i=0;i<iArr.length;i++){
							var el=iArr[i];
							iArr[i]=el.clone();
						}
					}
					
					arrField.set(null, c, arr);
					continue;
				}
				
				if(!isInstance(typ)) continue;
				if(isUnmanaged(typ)) continue;
				var instField=(IOField<SELF, IOInstance<?>>)field;
				
				var val=instField.get(null, (SELF)this);
				if(val==null) continue;
				
				val=val.clone();
				
				instField.set(null, c, val);
			}
			
			return c;
		}
	}
	
	abstract class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance.Managed<SELF> implements DataProvider.Holder{
		
		private final DataProvider   provider;
		private       Reference      reference;
		private final TypeLink       typeDef;
		private       GenericContext genericCtx;
		
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
		
		public CommandSet.CmdReader getUnmanagedReferenceWalkCommands(){
			if(getThisStruct().isOverridingDynamicUnmanaged()){
				throw new NotImplementedException(getThisStruct()+" has dynamic fields! Please implement walk commands!");
			}else{
				throw new UnsupportedOperationException();
			}
		}
		
		public final TypeLink getTypeDef(){
			return typeDef;
		}
		
		
		public final GenericContext getGenerics(){
			if(genericCtx==null){
				genericCtx=getThisStruct().describeGenerics(typeDef);
			}else if(genericCtx instanceof GenericContext.Deferred d&&d.actualData()!=null){
				genericCtx=d.actualData();
			}
			return genericCtx;
		}
		
		@Override
		public final DataProvider getDataProvider(){
			return provider;
		}
		
		protected final boolean isSelfDataEmpty() throws IOException{
			try(var io=selfIO()){
				return io.getSize()==0;
			}
		}
		
		public final void notifyReferenceMovement(Reference newRef){
			newRef.requireNonNull();
			
			if(DEBUG_VALIDATION) ensureDataIntegrity(newRef);
			
			reference=newRef;
		}
		
		private void ensureDataIntegrity(Reference newRef){
			byte[] oldData, newData;
			try(var oldIo=reference.io(this);
			    var newIo=newRef.io(this)
			){
				oldData=oldIo.readRemaining();
				newData=newIo.readRemaining();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			if(!Arrays.equals(oldData, newData)){
				throw new IllegalStateException(
					"Data changed when moving reference! This is invalid behaviour\n"+
					Arrays.toString(oldData)+"\n"+
					Arrays.toString(newData)
				);
			}
		}
		
		@Override
		public Struct.Unmanaged<SELF> getThisStruct(){
			return (Struct.Unmanaged<SELF>)super.getThisStruct();
		}
		
		private boolean freed;
		
		public boolean isFreed(){
			return freed;
		}
		protected void notifyFreed(){
			freed=true;
		}
		
		public void free() throws IOException{
			MemoryOperations.freeSelfAndReferenced(self());
			notifyFreed();
		}
		
		protected final RandomIO selfIO() throws IOException{
			return reference.io(provider);
		}
		
		protected StructPipe<SELF> newPipe(){
			return ContiguousStructPipe.of(getThisStruct());
		}
		
		public final StructPipe<SELF> getPipe(){
			if(pipe==null) pipe=newPipe();
			return pipe;
		}
		
		protected final void writeManagedFields() throws IOException{
			if(readOnly){
				throw new UnsupportedOperationException();
			}
			try(var io=selfIO()){
				getPipe().write(provider, io, self());
			}
		}
		protected final void readManagedFields() throws IOException{
			try(var io=selfIO()){
				getPipe().read(provider, io, self(), getGenerics());
			}
		}
		
		protected final void readManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				getPipe().readSingleField(getPipe().makeIOPool(), provider, io, field, self(), getGenerics());
			}
		}
		
		protected final void writeManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				getPipe().writeSingleField(provider, io, field, self());
			}
		}
		
		protected final long calcInstanceSize(WordSpace wordSpace){
			var siz=getPipe().getSizeDescriptor();
			var f  =siz.getFixed(wordSpace);
			if(f.isPresent()) return f.getAsLong();
			return siz.calcUnknown(getPipe().makeIOPool(), getDataProvider(), self(), wordSpace);
		}
		
		public final Reference getReference(){
			return reference;
		}
		
		protected final void allocateNulls() throws IOException{
			allocateNulls(getDataProvider());
		}
		
		@Override
		public SELF clone() throws UnsupportedOperationException{
			throw new UnsupportedOperationException("Unmanaged objects can not be cloned");
		}
	}
	
	
	Struct<SELF> getThisStruct();
	
	void allocateNulls(DataProvider provider) throws IOException;
	
	default String toShortString(){
		return getThisStruct().instanceToString(self(), true);
	}
	
	default String toString(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return getThisStruct().instanceToString(self(), doShort, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	SELF clone();
	
	@SuppressWarnings("unchecked")
	private SELF self(){return (SELF)this;}
	
	static boolean isInstance(TypeLink type){
		return isInstance(type.getTypeClass(null));
	}
	static boolean isInstance(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.class);
	}
	
	static boolean isManaged(TypeLink type){
		return isManaged(type.getTypeClass(null));
	}
	
	static boolean isUnmanaged(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.Unmanaged.class);
	}
	static boolean isManaged(Class<?> type){
		var isInstance =isInstance(type);
		var isUnmanaged=UtilL.instanceOf(type, IOInstance.Unmanaged.class);
		return isInstance&&!isUnmanaged;
	}
}
