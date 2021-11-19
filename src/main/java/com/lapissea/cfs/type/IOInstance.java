package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.INSTANCE;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public abstract class IOInstance<SELF extends IOInstance<SELF>>{
	
	public abstract static class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance<SELF> implements DataProvider.Holder{
		
		private final DataProvider   provider;
		private final Reference      reference;
		private final TypeDefinition typeDef;
		
		private StructPipe<SELF> pipe;
		
		protected Unmanaged(DataProvider provider, Reference reference, TypeDefinition typeDef, TypeDefinition.Check check){
			this(provider, reference, typeDef);
			check.ensureValid(typeDef);
		}
		
		public Unmanaged(DataProvider provider, Reference reference, TypeDefinition typeDef){
			this.provider=Objects.requireNonNull(provider);
			this.reference=reference.requireNonNull();
			this.typeDef=typeDef;
		}
		
		@NotNull
		public Stream<IOField<SELF, ?>> listDynamicUnmanagedFields(){
			return Stream.of();
		}
		
		public TypeDefinition getTypeDef(){
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
				getPipe().readSingleField(provider, io, field, self(), getGenerics());
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
			return siz.calcUnknown(self(), wordSpace);
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
		return getThisStruct().instanceToString(self(), false);
	}
	public String toShortString(){
		return getThisStruct().instanceToString(self(), true);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		
		if(!(o instanceof IOInstance<?> that)) return false;
		var struct=getThisStruct();
		if(that.getThisStruct()!=struct) return false;
		
		for(var field : struct.getFields()){
			if(!field.instancesEqual(self(), (SELF)that)) return false;
		}
		
		return true;
	}
	@Override
	public int hashCode(){
		int result=1;
		for(var field : thisStruct.getFields()){
			result=31*result+field.instanceHashCode(self());
		}
		return result;
	}
	
	public void allocateNulls(DataProvider provider) throws IOException{
		//noinspection unchecked
		for(var ref : getThisStruct().getFields().byFieldTypeIter((Class<IOField.Ref<SELF, ?>>)(Object)IOField.Ref.class)){
			if(!ref.isNull(self()))
				continue;
			ref.allocate(self(), provider, getGenericContext());
		}
	}
	
	private GenericContext getGenericContext(){
		//TODO: find generic context?
		return null;
	}
	
	public static boolean isManaged(TypeDefinition type){
		return isManaged(type.getTypeClass());
	}
	
	public static boolean isManaged(Class<?> type){
		var isInstance =UtilL.instanceOf(type, IOInstance.class);
		var isUnmanaged=UtilL.instanceOf(type, IOInstance.Unmanaged.class);
		return isInstance&&!isUnmanaged;
	}
}
