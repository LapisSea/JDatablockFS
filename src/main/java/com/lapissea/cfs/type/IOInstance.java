package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.TriConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.*;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public abstract class IOInstance<SELF extends IOInstance<SELF>>{
	
	public abstract static class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance<SELF> implements ChunkDataProvider.Holder{
		
		private final ChunkDataProvider provider;
		private final Reference         reference;
		private final TypeDefinition    typeDef;
		
		private StructPipe<SELF> pipe;
		
		public Unmanaged(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef, TypeDefinition.Check check){
			this(provider, reference, typeDef);
			check.ensureValid(typeDef);
		}
		
		public Unmanaged(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef){
			this.provider=Objects.requireNonNull(provider);
			this.reference=reference.requireNonNull();
			this.typeDef=typeDef;
		}
		
		public abstract Stream<IOField<SELF, ?>> listUnmanagedFields();
		
		public TypeDefinition getTypeDef(){
			return typeDef;
		}
		
		public GenericContext getGenerics(){
			return getThisStruct().describeGenerics(typeDef);
		}
		
		@Override
		public ChunkDataProvider getChunkProvider(){
			return provider;
		}
		
		protected RandomIO selfIO() throws IOException{
			return reference.io(provider);
		}
		
		public StructPipe<SELF> getPipe(){
			if(pipe==null) pipe=ContiguousStructPipe.of(getThisStruct());
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
		
		protected long calcSize(){
			var siz=getPipe().getSizeDescriptor();
			var f  =siz.getFixed();
			if(f.isPresent()) return f.getAsLong();
			return siz.calcUnknown(self());
		}
		
		public Reference getReference(){
			return reference;
		}
	}
	
	private final Struct<SELF> thisStruct;
	private final Object[]     virtualFields;
	
	@SuppressWarnings("unchecked")
	public IOInstance(){
		this.thisStruct=Struct.of((Class<SELF>)getClass());
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	public IOInstance(Struct<SELF> thisStruct){
		this.thisStruct=thisStruct;
		virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
	}
	
	
	public Struct<SELF> getThisStruct(){
		return thisStruct;
	}
	
	private void protectAccessor(VirtualAccessor<SELF> accessor){
		if(DEBUG_VALIDATION){
			if(accessor.getDeclaringStruct()!=getThisStruct()){
				throw new IllegalArgumentException(accessor.getDeclaringStruct()+" != "+getThisStruct());
			}
		}
	}
	
	private static <T extends IOInstance<T>> BiFunction<IOInstance<T>, VirtualAccessor<T>, Object> getVirtualRef() {return IOInstance::getVirtual;}
	private static <T extends IOInstance<T>> TriConsumer<IOInstance<T>, VirtualAccessor<T>, Object> setVirtualRef(){return IOInstance::setVirtual;}
	
	private Object getVirtual(VirtualAccessor<SELF> accessor){
		protectAccessor(accessor);
		int index=accessor.getAccessIndex();
		return virtualFields[index];
	}
	private void setVirtual(VirtualAccessor<SELF> accessor, Object value){
		protectAccessor(accessor);
		int index=accessor.getAccessIndex();
		virtualFields[index]=value;
	}
	
	@SuppressWarnings("unchecked")
	protected final SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(self(), false);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		
		if(!(o instanceof IOInstance<?> that)) return false;
		var struct=getThisStruct();
		if(that.getThisStruct()!=struct) return false;
		
		for(var field : struct.getFields()){
			if(!field.instancesEqual(self(), self())) return false;
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
	
	public boolean allocateNulls(ChunkDataProvider provider) throws IOException{
		boolean dirty=false;
		for(IOField<SELF, ?> f : getThisStruct().getFields()){
			if(!(f instanceof IOField.Ref)) continue;
			
			//noinspection unchecked
			IOField.Ref<SELF, ?> selfRef=(IOField.Ref<SELF, ?>)f;
			if(selfRef.get(self())!=null) continue;
			
			selfRef.allocate(self(), provider, getGenericContext());
			dirty=true;
		}
		return dirty;
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
