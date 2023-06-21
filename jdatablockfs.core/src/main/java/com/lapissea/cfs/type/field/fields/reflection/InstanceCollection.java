package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.CollectionAddapter;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public class InstanceCollection{
	
	public static class InlineField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends NullFlagCompanyField<T, CollectionType>{
		
		private final CollectionAddapter<ElementType, CollectionType> dataAdapter;
		
		private IOField<T, Integer> collectionSize;
		
		@SuppressWarnings({"rawtypes"})
		public InlineField(FieldAccessor<T> accessor, Class<? extends CollectionAddapter> dataAdapterType){
			super(accessor);
			dataAdapter = makeAddapter(accessor, dataAdapterType);
			
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
				for(var instance : dataAdapter.getAsCollection(arr)){
					sum += elIo.calcByteSize(prov, instance);
				}
				return sum;
			}));
		}
		
		@Override
		public void init(FieldSet<T> fields){
			super.init(fields);
			collectionSize = fields.requireExact(int.class, IOFieldTools.makeCollectionLenName(getAccessor()));
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
			var data = dataAdapter.read(size, provider, src, genericContext);
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
			dataAdapter.skipData(size, provider, src, genericContext);
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			var val = get(ioPool, instance);
			if(val == null || dataAdapter.getSize(get(ioPool, instance)) == 0){
				return Optional.empty();
			}
			return Optional.of(
				doShort?
				Utils.toShortString(val) :
				TextUtil.toString(val)
			);
		}
	}
	
	public static class ReferenceField<T extends IOInstance<T>, ElementType extends IOInstance<ElementType>, CollectionType>
		extends RefField.ReferenceCompanion<T, CollectionType>{
		
		private final CollectionAddapter<ElementType, CollectionType> dataAdapter;
		
		private final ObjectPipe<CollectionType, Void> refPipe;
		
		@SuppressWarnings({"unchecked", "rawtypes"})
		public ReferenceField(FieldAccessor<T> accessor, Class<? extends CollectionAddapter> dataAdapterType){
			super(accessor, SizeDescriptor.Fixed.empty());
			dataAdapter = makeAddapter(accessor, dataAdapterType);
			
			refPipe = new ObjectPipe<>(){
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
									for(ElementType e : dataAdapter.getAsCollection(instance)){
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
				
				@Override
				public Void makeIOPool(){
					return null;
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
			
			set(ioPool, instance, ref.read(provider, refPipe, genericContext));
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
				if(getNullability() == IONullability.Mode.NOT_NULL){
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
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			var val = get(ioPool, instance);
			if(val == null || dataAdapter.getSize(get(ioPool, instance)) == 0){
				return Optional.empty();
			}
			return Optional.of(
				doShort?
				Utils.toShortString(val) :
				TextUtil.toString(val)
			);
		}
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <E extends IOInstance<E>, C> CollectionAddapter<E, C> makeAddapter(FieldAccessor<?> accessor, Class<? extends CollectionAddapter> addapterType){
		var collectionType = accessor.getGenericType(null);
		var type           = CollectionAddapter.getComponentType(addapterType, collectionType);
		
		var impl = new CollectionAddapter.ElementIOImpl.PipeImpl<>((Class<E>)type);
		return CollectionAddapter.newAddapter(addapterType, impl);
	}
}
