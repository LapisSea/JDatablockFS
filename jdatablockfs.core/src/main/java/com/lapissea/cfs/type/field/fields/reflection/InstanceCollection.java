package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.GlobalConfig.TYPE_VALIDATION;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class InstanceCollection{
	
	public static class InlineField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends IOField.NullFlagCompany<T, CollectionType>{
		
		private final DataAdapter<T, ElementType, CollectionType> dataAdapter;
		
		private final SizeDescriptor<T>   descriptor;
		private       IOField<T, Integer> collectionSize;
		
		@SuppressWarnings({"unchecked", "rawtypes"})
		public InlineField(FieldAccessor<T> accessor, Class<? extends DataAdapter> dataAdapterType){
			super(accessor);
			try{
				dataAdapter=dataAdapterType.getConstructor(FieldAccessor.class).newInstance(accessor);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
			
			descriptor=SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst)->{
				var arr=get(null, inst);
				if(arr==null) return 0;
				var size=dataAdapter.getSize(arr);
				
				
				var desc=dataAdapter.getValPipe().getSizeDescriptor();
				if(desc.hasFixed()){
					return size*desc.requireFixed(WordSpace.BYTE);
				}
				return dataAdapter.getStream(arr).mapToLong(instance->desc.calcUnknown(instance.getThisStruct().allocVirtualVarPool(IO), prov, instance, WordSpace.BYTE)).sum();
			});
		}
		
		@Override
		public CollectionType get(Struct.Pool<T> ioPool, T instance){
			return getNullable(ioPool, instance);
		}
		
		@Override
		public void init(){
			super.init();
			collectionSize=declaringStruct().getFields().requireExact(int.class, IOFieldTools.makeCollectionLenName(getAccessor()));
		}
		
		@Override
		public SizeDescriptor<T> getSizeDescriptor(){
			return descriptor;
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					return;
				}
			}
			
			dataAdapter.writeData(get(ioPool, instance), provider, dest);
		}
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					set(ioPool, instance, null);
					return;
				}
			}
			
			var data=dataAdapter.readData(collectionSize, ioPool, provider, src, instance, genericContext);
			set(ioPool, instance, data);
		}
		
		@Override
		public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					return;
				}
			}
			
			dataAdapter.skipReadData(collectionSize, ioPool, provider, src, instance, genericContext);
		}
	}
	
	public static class ReferenceField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends IOField.Ref.ReferenceCompanion<T, CollectionType>{
		
		private final DataAdapter<T, ElementType, CollectionType> dataAdapter;
		
		private final ObjectPipe<CollectionType, Void> refPipe;
		
		@SuppressWarnings({"unchecked", "rawtypes"})
		public ReferenceField(FieldAccessor<T> accessor, Class<? extends DataAdapter> dataAdapterType){
			super(accessor);
			try{
				dataAdapter=dataAdapterType.getConstructor(FieldAccessor.class).newInstance(accessor);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
			refPipe=new ObjectPipe<>(){
				@Override
				public void write(DataProvider provider, ContentWriter dest, CollectionType instance) throws IOException{
					var        size  =dataAdapter.getSize(instance);
					NumberSize sizSiz=NumberSize.bySize(size);
					FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, sizSiz);
					sizSiz.write(dest, size);
					dataAdapter.writeData(instance, provider, dest);
				}
				@Override
				public CollectionType read(DataProvider provider, ContentReader src, CollectionType instance, GenericContext genericContext) throws IOException{
					var sizSiz=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
					int size  =(int)sizSiz.read(src);
					return dataAdapter.readData(size, provider, src, genericContext);
				}
				@Override
				public CollectionType readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					var sizSiz=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
					int size  =(int)sizSiz.read(src);
					return dataAdapter.readData(size, provider, src, genericContext);
				}
				@Override
				public BasicSizeDescriptor<CollectionType, Void> getSizeDescriptor(){
					return new BasicSizeDescriptor<>(){
						@Override
						public WordSpace getWordSpace(){
							return WordSpace.BYTE;
						}
						@Override
						public long calcUnknown(Void ioPool, DataProvider provider, CollectionType instance, WordSpace wordSpace){
							var pipe  =dataAdapter.getValPipe();
							var desc  =pipe.getSizeDescriptor();
							var size  =dataAdapter.getSize(instance);
							var sizSiz=NumberSize.bySize(size);
							
							var elementsSize=0L;
							
							if(size>0){
								if(desc.hasFixed()) elementsSize=size*desc.requireFixed(WordSpace.BYTE);
								else{
									for(ElementType e : dataAdapter.getAsIterable(instance)){
										elementsSize+=pipe.calcUnknownSize(provider, e, WordSpace.BYTE);
									}
								}
							}
							
							return mapSize(wordSpace, 1+sizSiz.bytes+elementsSize);
						}
						@Override
						public long getMin(){
							return 1;
						}
						@Override
						public OptionalLong getMax(){
							return OptionalLong.empty();
						}
						@Override
						public OptionalLong getFixed(){
							return OptionalLong.empty();
						}
					};
				}
				
				@Override
				public Void makeIOPool(){
					return null;
				}
			};
		}
		
		@Override
		public CollectionType get(Struct.Pool<T> ioPool, T instance){
			return getNullable(ioPool, instance);
		}
		
		@Override
		protected CollectionType newDefault(){
			return dataAdapter.getNew(dataAdapter.component, 0);
		}
		
		@Override
		protected Reference allocNew(DataProvider provider, CollectionType val) throws IOException{
			var ch=AllocateTicket.withData(refPipe, provider, val).submit(provider);
			return ch.getPtr().makeReference();
		}
		
		@Override
		public SizeDescriptor<T> getSizeDescriptor(){
			return SizeDescriptor.Fixed.empty();
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val=get(ioPool, instance);
			if(val==null&&getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
				val=newDefault();
			}
			var ref=getReference(instance);
			if(val!=null&&(ref==null||ref.isNull())){
				throw new ShouldNeverHappenError();//Generators have not been called if this is true
			}
			
			if(val!=null){
				try(var ignored=provider.getSource().openIOTransaction()){
					try(var io=ref.io(provider)){
						refPipe.write(provider, io, val);
						io.trim();
					}
				}
			}
		}
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var ref=Objects.requireNonNull(getRef(instance));
			if(nullable()){
				if(ref.isNull()){
					set(ioPool, instance, null);
					return;
				}
			}
			
			CollectionType data;
			try(var io=ref.io(provider)){
				data=refPipe.read(provider, io, null, genericContext);
			}
			
			set(ioPool, instance, data);
			
		}
		
		@Override
		public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			//nothing to do. Reference field stores the actual pointer
		}
		
		private void allocAndSet(T instance, DataProvider provider, CollectionType val) throws IOException{
			var ref=allocNew(provider, val);
			setRef(instance, ref);
		}
		@Override
		public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
			var val=newDefault();
			allocAndSet(instance, provider, val);
			set(null, instance, val);
		}
		@Override
		public void setReference(T instance, Reference newRef){
			Objects.requireNonNull(newRef);
			if(newRef.isNull()){
				if(getNullability()==IONullability.Mode.NOT_NULL){
					throw new NullPointerException();
				}
			}
			setRef(instance, newRef);
		}
		@Override
		public ObjectPipe<CollectionType, Void> getReferencedPipe(T instance){
			return refPipe;
		}
	}
	
	public abstract static sealed class DataAdapter<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>{
		
		public static final class ArrayAdapter<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>> extends DataAdapter<T, ElementType, ElementType[]>{
			
			public ArrayAdapter(FieldAccessor<T> accessor){
				super(accessor);
			}
			
			@SuppressWarnings("unchecked")
			@Override
			protected Class<ElementType> getComponentType(Type type){
				var raw =Utils.typeToRaw(type);
				var comp=raw.componentType();
				if(comp==null) throw new ShouldNeverHappenError();
				if(comp.isArray()) throw new MalformedStructLayout(this+" is multi dimensional array: "+type);
				return (Class<ElementType>)comp;
			}
			
			@Override
			protected int getSize(ElementType[] collection){
				return collection.length;
			}
			@Override
			protected Stream<ElementType> getStream(ElementType[] collection){
				return Arrays.stream(collection);
			}
			@Override
			protected Iterable<ElementType> getAsIterable(ElementType[] collection){
				return Arrays.asList(collection);
			}
			@SuppressWarnings("unchecked")
			@Override
			protected ElementType[] getNew(Class<ElementType> componentClass, int size){
				return (ElementType[])Array.newInstance(componentClass, size);
			}
			@Override
			protected void setElement(ElementType[] collection, int index, ElementType element){
				collection[index]=element;
			}
		}
		
		public static final class ListAdapter<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>> extends DataAdapter<T, ElementType, List<ElementType>>{
			
			public ListAdapter(FieldAccessor<T> accessor){
				super(accessor);
			}
			
			@SuppressWarnings("unchecked")
			@Override
			protected Class<ElementType> getComponentType(Type type){
				var parmType=(ParameterizedType)type;
				var comp    =Utils.typeToRaw(parmType.getActualTypeArguments()[0]);
				return (Class<ElementType>)comp;
			}
			
			@Override
			protected int getSize(List<ElementType> collection){
				return collection.size();
			}
			@Override
			protected Stream<ElementType> getStream(List<ElementType> collection){
				return collection.stream();
			}
			@Override
			protected Iterable<ElementType> getAsIterable(List<ElementType> collection){
				return collection;
			}
			@Override
			protected List<ElementType> getNew(Class<ElementType> componentClass, int size){
				ArrayList<ElementType> l=new ArrayList<>();
				l.ensureCapacity(size);
				return l;
			}
			@Override
			protected void setElement(List<ElementType> collection, int index, ElementType element){
				if(index==collection.size()){
					collection.add(element);
					return;
				}
				collection.set(index, element);
			}
		}
		
		private       StructPipe<ElementType> valPipe;
		private final Class<ElementType>      component;
		
		public DataAdapter(FieldAccessor<T> accessor){
			var type=accessor.getGenericType(null);
			component=getComponentType(type);
			if(!IOInstance.isInstance(component)) throw new MalformedStructLayout(this+" is not of type List<IOInstance>: "+type);
			if(IOInstance.isUnmanaged(component)) throw new MalformedStructLayout(this+" element type is unmanaged: "+type);
			
			//preload pipe
			if(TYPE_VALIDATION){
				Runner.compileTask(this::getValPipe);
			}else{
				ContiguousStructPipe.of(component);
			}
			
		}
		
		private StructPipe<ElementType> getValPipe(){
			if(valPipe==null){
				valPipe=ContiguousStructPipe.of(component, STATE_DONE);
			}
			return valPipe;
		}
		
		protected void writeData(CollectionType arr, DataProvider provider, ContentWriter dest) throws IOException{
			var pip=getValPipe();
			
			for(ElementType el : getAsIterable(arr)){
				if(DEBUG_VALIDATION){
					var siz=pip.calcUnknownSize(provider, el, WordSpace.BYTE);
					
					try(var buff=dest.writeTicket(siz).requireExact().submit()){
						pip.write(provider, buff, el);
					}
				}else{
					pip.write(provider, dest, el);
				}
			}
		}
		
		protected CollectionType readData(IOField<T, Integer> collectionSize, Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			int size=collectionSize.get(ioPool, instance);
			return readData(size, provider, src, genericContext);
		}
		protected CollectionType readData(int size, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			
			var pip=getValPipe();
			
			var data=getNew(component, size);
			for(int i=0;i<size;i++){
				setElement(data, i, pip.readNew(provider, src, genericContext));
			}
			return data;
		}
		
		private void skipReadData(IOField<T, Integer> collectionSize, Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			
			var pip=getValPipe();
			
			int size            =collectionSize.get(ioPool, instance);
			var fixedElementSize=pip.getSizeDescriptor().getFixed(WordSpace.BYTE);
			if(fixedElementSize.isPresent()){
				src.skipExact(size*fixedElementSize.getAsLong());
				return;
			}
			ElementType inst=pip.getType().emptyConstructor().get();
			for(int i=0;i<size;i++){
				pip.read(provider, src, inst, genericContext);
			}
		}
		
		protected abstract Class<ElementType> getComponentType(Type type);
		protected abstract int getSize(CollectionType collection);
		protected abstract Stream<ElementType> getStream(CollectionType collection);
		protected abstract Iterable<ElementType> getAsIterable(CollectionType collection);
		protected abstract CollectionType getNew(Class<ElementType> componentClass, int size);
		protected abstract void setElement(CollectionType collection, int index, ElementType element);
	}
}
