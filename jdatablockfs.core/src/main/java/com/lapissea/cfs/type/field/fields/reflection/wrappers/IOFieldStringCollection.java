package com.lapissea.cfs.type.field.fields.reflection.wrappers;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.CollectionAddapter;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public final class IOFieldStringCollection<T extends IOInstance<T>, CollectionType> extends IOField<T, CollectionType>{
	
	@SuppressWarnings("unused")
	private static final class UsageArr extends FieldUsage.InstanceOf<String[]>{
		public UsageArr(){ super(String[].class); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldStringCollection<>(field, CollectionAddapter.OfArray.class);
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
		public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldStringCollection<>(field, CollectionAddapter.OfList.class);
		}
	}
	
	
	private static final class StringIO implements CollectionAddapter.ElementIOImpl<String>{
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
	
	private final CollectionAddapter<String, CollectionType> addapter;
	
	private IOFieldPrimitive.FInt<T> collectionSize;
	
	public IOFieldStringCollection(FieldAccessor<T> accessor, Class<? extends CollectionAddapter> dataAdapterType){
		super(accessor);
		addapter = makeAddapter(accessor, dataAdapterType);
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var col = get(ioPool, inst);
			if(col == null) return 0;
			long sum = 0;
			for(String s : addapter.getAsCollection(col)){
				sum += StringIO.INSTANCE.calcByteSize(prov, s);
			}
			return sum;
		}));
	}
	
	@SuppressWarnings({"rawtypes"})
	private static <C> CollectionAddapter<String, C> makeAddapter(FieldAccessor<?> accessor, Class<? extends CollectionAddapter> addapterType){
		var collectionType = accessor.getGenericType(null);
		var type           = CollectionAddapter.getComponentType(addapterType, collectionType);
		if(type != String.class) throw new IllegalArgumentException(collectionType + " is not a collection of strings");
		
		return CollectionAddapter.newAddapter(addapterType, StringIO.INSTANCE);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		collectionSize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		addapter.write(get(ioPool, instance), provider, dest);
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = collectionSize.getValue(ioPool, instance);
		set(ioPool, instance, addapter.read(size, provider, src, genericContext));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = collectionSize.getValue(ioPool, instance);
		addapter.skipData(size, provider, src, genericContext);
	}
}
