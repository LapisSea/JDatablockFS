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

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.OptionalLong;

public class IOFieldEnumArray<T extends IOInstance<T>, E extends Enum<E>> extends IOField<T, E[]>{
	
	@SuppressWarnings("unused")
	private static final class Usage implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			var raw = Utils.typeToRaw(type);
			if(!raw.isArray()) return false;
			return raw.componentType().isEnum();
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldEnumArray<>(field);
		}
	}
	
	
	private final EnumUniverse<E>          universe;
	private       IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldEnumArray(FieldAccessor<T> accessor){
		super(accessor);
		universe = EnumUniverse.of((Class<E>)accessor.getType().componentType());
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst) -> {
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return byteCount(siz);
			var arr = get(ioPool, inst);
			return byteCount(arr.length);
		}));
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var enums = get(ioPool, instance);
		new BitOutputStream(dest).writeEnums(universe, Arrays.asList(enums)).close();
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		E[] enums;
		try(var s = new BitInputStream(src, (long)universe.bitSize*size)){
			enums = s.readEnums(universe, size);
		}
		set(ioPool, instance, enums);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
	
	private int byteCount(int len){
		return BitUtils.bitsToBytes(universe.bitSize*len);
	}
}
