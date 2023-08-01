package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.CollectionAddapter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

public final class IOFieldEnumCollection<T extends IOInstance<T>, E extends Enum<E>, ColType> extends IOField<T, ColType>{
	
	@SuppressWarnings("unused")
	private static final class UsageList implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			if(!(type instanceof ParameterizedType parmType)) return false;
			if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
			var args = parmType.getActualTypeArguments();
			return Utils.typeToRaw(args[0]).isEnum();
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			return new IOFieldEnumCollection<>(field, CollectionAddapter.OfList.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldEnumCollection.class); }
	}
	
	@SuppressWarnings("unused")
	private static final class UsageArray implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			var raw = Utils.typeToRaw(type);
			if(!raw.isArray()) return false;
			return raw.componentType().isEnum();
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			return new IOFieldEnumCollection<>(field, CollectionAddapter.OfArray.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldEnumCollection.class); }
	}
	
	private static final class EnumIO<E extends Enum<E>> implements CollectionAddapter.ElementIOImpl<E>{
		
		private final EnumUniverse<E> uni;
		private EnumIO(EnumUniverse<E> uni){
			this.uni = uni;
		}
		
		@Override
		public Class<E> componentType(){
			return uni.type;
		}
		@Override
		public long calcByteSize(DataProvider provider, E element){ throw new UnsupportedOperationException(); }
		@Override
		public OptionalLong getFixedByteSize(){ throw new UnsupportedOperationException(); }
		@Override
		public void write(DataProvider provider, ContentWriter dest, E element){ throw new UnsupportedOperationException(); }
		@Override
		public E read(DataProvider provider, ContentReader src, GenericContext genericContext){ throw new UnsupportedOperationException(); }
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext){ throw new UnsupportedOperationException(); }
	}
	
	private final EnumUniverse<E>                universe;
	private final CollectionAddapter<E, ColType> addapter;
	private       IOFieldPrimitive.FInt<T>       collectionSize;
	
	public IOFieldEnumCollection(FieldAccessor<T> accessor, Class<? extends CollectionAddapter> addapterType){
		super(accessor);
		addapter = makeAddapter(accessor, addapterType);
		var gt   = accessor.getGenericType(null);
		var etyp = ((ParameterizedType)gt).getActualTypeArguments()[0];
		universe = EnumUniverse.of((Class<E>)etyp);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var siz = collectionSize.getValue(ioPool, inst);
			if(siz>0) return byteCount(siz);
			var col = get(ioPool, inst);
			return byteCount(addapter.getSize(col));
		}));
	}
	
	@SuppressWarnings({"rawtypes"})
	private static <E extends Enum<E>, C> CollectionAddapter<E, C> makeAddapter(FieldAccessor<?> accessor, Class<? extends CollectionAddapter> addapterType){
		var collectionType = accessor.getGenericType(null);
		var type           = CollectionAddapter.getComponentType(addapterType, collectionType);
		var uni            = EnumUniverse.<E>ofUnknown(type);
		return CollectionAddapter.newAddapter(addapterType, new EnumIO<>(uni));
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		collectionSize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var enums = get(ioPool, instance);
		new BitOutputStream(dest).writeEnums(universe, addapter.asListView(enums)).close();
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = collectionSize.getValue(ioPool, instance);
		E[] tmp;
		try(var s = new BitInputStream(src, (long)universe.bitSize*size)){
			tmp = s.readEnums(universe, size);
		}
		ColType enums = addapter.asCollection(tmp);
		
		set(ioPool, instance, enums);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = collectionSize.getValue(ioPool, instance);
		src.skipExact(byteCount(size));
	}
	
	private int byteCount(int len){
		return BitUtils.bitsToBytes(universe.bitSize*len);
	}
}
