package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.CollectionAdapter;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public class InstanceCollection{
	
	@SuppressWarnings("unused")
	private static final class UsageArr implements IOField.FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			var raw = Utils.typeToRaw(type);
			if(!raw.isArray()) return false;
			return IOInstance.isManaged(raw.componentType());
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			if(field.hasAnnotation(IOValue.Reference.class)){
				return new InstanceCollection.ReferenceField<>(field, CollectionAdapter.OfArray.class);
			}
			return new InstanceCollection.InlineField<>(field, CollectionAdapter.OfArray.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(InstanceCollection.ReferenceField.class, InstanceCollection.InlineField.class);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	@SuppressWarnings("unused")
	private static final class UsageList implements IOField.FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			if(!(type instanceof ParameterizedType parmType)) return false;
			if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
			var args     = parmType.getActualTypeArguments();
			var listType = Objects.requireNonNull(IOType.of(args[0])).getTypeClass(null);
			return IOInstance.isManaged(listType) ||
			       SealedUtil.getSealedUniverse(listType, false).flatMap(SealedUtil.SealedInstanceUniverse::ofUnknown).isPresent();
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			if(field.hasAnnotation(IOValue.Reference.class)){
				return new InstanceCollection.ReferenceField<>(field, CollectionAdapter.OfList.class);
			}
			return new InstanceCollection.InlineField<>(field, CollectionAdapter.OfList.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(InstanceCollection.ReferenceField.class, InstanceCollection.InlineField.class);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	public static final class InlineField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends NullFlagCompanyField<T, CollectionType>
		implements CollectionAdapter.CollectionContainer<ElementType, CollectionType>{
		
		private final CollectionAdapter<ElementType, CollectionType> dataAdapter;
		
		private IOField<T, Integer> collectionSize;
		
		@SuppressWarnings({"rawtypes"})
		public InlineField(FieldAccessor<T> accessor, Class<? extends CollectionAdapter> dataAdapterType){
			super(accessor);
			dataAdapter = makeAdapter(accessor, dataAdapterType);
			
			initSizeDescriptor(SizeDescriptor.Unknown.of(WordSpace.BYTE, 0, OptionalLong.empty(), (ioPool, prov, inst) -> {
				var arr = get(null, inst);
				if(arr == null) return 0;
				var size = dataAdapter.getSize(arr);
				
				
				var fixed = dataAdapter.getElementIO().getFixedByteSize();
				if(fixed.isPresent()){
					return size*fixed.getAsLong();
				}
				
				var  elIo = dataAdapter.getElementIO();
				long sum  = 0;
				for(var instance : dataAdapter.asListView(arr)){
					sum += elIo.calcByteSize(prov, instance);
				}
				return sum;
			}));
		}
		
		@Override
		public void init(FieldSet<T> fields){
			super.init(fields);
			collectionSize = fields.requireExact(int.class, FieldNames.collectionLen(getAccessor()));
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					return;
				}
			}
			
			dataAdapter.write(get(ioPool, instance), provider, dest);
		}
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					set(ioPool, instance, null);
					return;
				}
			}
			
			int size = collectionSize.get(ioPool, instance);
			var data = dataAdapter.read(size, provider, src, makeContext(genericContext));
			set(ioPool, instance, data);
		}
		
		@Override
		public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					return;
				}
			}
			
			int size = collectionSize.get(ioPool, instance);
			dataAdapter.skipData(size, provider, src, makeContext(genericContext));
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
			var val = get(ioPool, instance);
			if(val == null || dataAdapter.getSize(get(ioPool, instance)) == 0){
				return Optional.empty();
			}
			return Optional.of(
				settings.doShort()?
				Utils.toShortString(val) :
				TextUtil.toString(val)
			);
		}
		@Override
		public CollectionAdapter<ElementType, CollectionType> getCollectionAddapter(){ return dataAdapter; }
	}
	
	public static final class ReferenceField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends RefField.ReferenceCompanion<T, CollectionType>
		implements CollectionAdapter.CollectionContainer<ElementType, CollectionType>{
		
		private final CollectionAdapter<ElementType, CollectionType> dataAdapter;
		
		private final ObjectPipe<CollectionType, Void> refPipe;
		
		@SuppressWarnings({"rawtypes"})
		public ReferenceField(FieldAccessor<T> accessor, Class<? extends CollectionAdapter> dataAdapterType){
			super(accessor, SizeDescriptor.Fixed.empty());
			dataAdapter = makeAdapter(accessor, dataAdapterType);
			
			refPipe = new ObjectPipe.NoPool<>(){
				@Override
				public void write(DataProvider provider, ContentWriter dest, CollectionType instance) throws IOException{
					var        size   = dataAdapter.getSize(instance);
					NumberSize sizSiz = NumberSize.bySize(size);
					FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, sizSiz);
					sizSiz.write(dest, size);
					dataAdapter.write(instance, provider, dest);
				}
				@Override
				public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					var size = readSiz(src);
					dataAdapter.skipData(size, provider, src, genericContext);
				}
				
				private int readSiz(ContentReader src) throws IOException{
					var sizSiz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
					return (int)sizSiz.read(src);
				}
				@Override
				public CollectionType readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
					int size = readSiz(src);
					return dataAdapter.read(size, provider, src, genericContext);
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
							var size   = dataAdapter.getSize(instance);
							var sizSiz = NumberSize.bySize(size);
							
							var elementsSize = 0L;
							
							if(size>0){
								var io    = dataAdapter.getElementIO();
								var fixed = io.getFixedByteSize();
								if(fixed.isPresent()) elementsSize = size*fixed.getAsLong();
								else{
									for(ElementType e : dataAdapter.asListView(instance)){
										elementsSize += io.calcByteSize(provider, e);
									}
								}
							}
							
							return mapSize(wordSpace, 1 + sizSiz.bytes + elementsSize);
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
			};
		}
		
		@Override
		protected CollectionType newDefault(){
			return dataAdapter.makeNew(0);
		}
		
		@Override
		protected Reference allocNew(DataProvider provider, CollectionType val) throws IOException{
			var ch = AllocateTicket.withData(refPipe, provider, val).submit(provider);
			return ch.getPtr().makeReference();
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val = get(ioPool, instance);
			if(val == null && getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
				val = newDefault();
			}
			var ref = getReference(instance);
			if(val != null && (ref == null || ref.isNull())){
				throw new ShouldNeverHappenError();//Generators have not been called if this is true
			}
			
			if(val != null){
				ref.writeAtomic(provider, true, refPipe, val);
			}
		}
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var ref = Objects.requireNonNull(getRef(instance));
			if(nullable()){
				if(ref.isNull()){
					set(ioPool, instance, null);
					return;
				}
			}
			
			set(ioPool, instance, ref.read(provider, refPipe, makeContext(genericContext)));
		}
		
		@Override
		public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext){
			//Nothing to do. Reference field stores the actual pointer
		}
		
		private void allocAndSet(T instance, DataProvider provider, CollectionType val) throws IOException{
			var ref = allocNew(provider, val);
			setRef(instance, ref);
		}
		@Override
		public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
			var val = newDefault();
			allocAndSet(instance, provider, val);
			set(null, instance, val);
		}
		@Override
		public void setReference(T instance, Reference newRef){
			Objects.requireNonNull(newRef);
			if(newRef.isNull()){
				if(isNonNullable()){
					throw new NullPointerException();
				}
			}
			setRef(instance, newRef);
		}
		@Override
		public ObjectPipe<CollectionType, Void> getReferencedPipe(T instance){
			return refPipe;
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
			var val = get(ioPool, instance);
			if(val == null || dataAdapter.getSize(get(ioPool, instance)) == 0){
				return Optional.empty();
			}
			return Optional.of(
				settings.doShort()?
				Utils.toShortString(val) :
				TextUtil.toString(val)
			);
		}
		@Override
		public CollectionAdapter<ElementType, CollectionType> getCollectionAddapter(){ return dataAdapter; }
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <E extends IOInstance<E>, C> CollectionAdapter<E, C> makeAdapter(FieldAccessor<?> accessor, Class<? extends CollectionAdapter> adapterType){
		var collectionType = accessor.getGenericType(null);
		var type           = CollectionAdapter.getComponentType(adapterType, collectionType);
		
		CollectionAdapter.ElementIOImpl<E> impl;
		
		var universe = SealedUtil.getSealedUniverse(type, false).flatMap(SealedUtil.SealedInstanceUniverse::ofUnknown);
		if(universe.isPresent()){
			impl = new CollectionAdapter.ElementIOImpl.SealedTypeImpl<>((SealedUtil.SealedInstanceUniverse<E>)universe.get());
		}else{
			impl = new CollectionAdapter.ElementIOImpl.PipeImpl<>((Class<E>)type);
		}
		
		return CollectionAdapter.newAdapter(adapterType, impl);
	}
}
