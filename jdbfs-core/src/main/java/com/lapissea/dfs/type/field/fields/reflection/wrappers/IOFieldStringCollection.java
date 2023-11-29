package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.CollectionAdapter;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

public final class IOFieldStringCollection<T extends IOInstance<T>, CollectionType> extends NullFlagCompanyField<T, CollectionType>{
	
	@SuppressWarnings("unused")
	private static final class UsageArr extends FieldUsage.InstanceOf<String[]>{
		public UsageArr(){ super(String[].class, Set.of(IOFieldStringCollection.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field){
			return new IOFieldStringCollection<>(field, CollectionAdapter.OfArray.class);
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
	private static final class UsageList implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			if(!(type instanceof ParameterizedType parmType)) return false;
			if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
			var args = parmType.getActualTypeArguments();
			return Utils.typeToRaw(args[0]) == String.class;
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field){
			return new IOFieldStringCollection<>(field, CollectionAdapter.OfList.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldStringCollection.class); }
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	
	private static final class StringIO implements CollectionAdapter.ElementIOImpl<String>{
		private static final StringIO INSTANCE = new StringIO();
		
		@Override
		public Class<String> componentType(){
			return String.class;
		}
		@Override
		public long calcByteSize(DataProvider provider, String element){
			return AutoText.PIPE.calcUnknownSize(provider, new AutoText(element), WordSpace.BYTE);
		}
		@Override
		public OptionalLong getFixedByteSize(){
			return OptionalLong.empty();
		}
		@Override
		public void write(DataProvider provider, ContentWriter dest, String element) throws IOException{
			AutoText.PIPE.write(provider, dest, new AutoText(element));
		}
		@Override
		public String read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return AutoText.PIPE.readNew(provider, src, genericContext).getData();
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			AutoText.PIPE.skip(provider, src, genericContext);
		}
	}
	
	private final CollectionAdapter<String, CollectionType> adapter;
	
	private IOFieldPrimitive.FInt<T> collectionSize;
	
	public IOFieldStringCollection(FieldAccessor<T> accessor, Class<? extends CollectionAdapter> dataAdapterType){
		super(accessor);
		adapter = makeAdapter(accessor, dataAdapterType);
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var col = get(ioPool, inst);
			if(col == null) return 0;
			long sum = 0;
			for(String s : adapter.asListView(col)){
				sum += StringIO.INSTANCE.calcByteSize(prov, s);
			}
			return sum;
		}));
	}
	
	@SuppressWarnings({"rawtypes"})
	private static <C> CollectionAdapter<String, C> makeAdapter(FieldAccessor<?> accessor, Class<? extends CollectionAdapter> adapterType){
		var collectionType = accessor.getGenericType(null);
		var type           = CollectionAdapter.getComponentType(adapterType, collectionType);
		if(type != String.class) throw new IllegalArgumentException(collectionType + " is not a collection of strings");
		
		return CollectionAdapter.newAdapter(adapterType, StringIO.INSTANCE);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		collectionSize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		if(nullable() && getIsNull(ioPool, instance)) return;
		adapter.write(get(ioPool, instance), provider, dest);
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)){
				set(ioPool, instance, null);
				return;
			}
		}
		
		int size = collectionSize.getValue(ioPool, instance);
		set(ioPool, instance, adapter.read(size, provider, src, genericContext));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = collectionSize.getValue(ioPool, instance);
		adapter.skipData(size, provider, src, genericContext);
	}
}
