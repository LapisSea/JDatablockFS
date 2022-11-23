package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

public class IOFieldEnumList<T extends IOInstance<T>, E extends Enum<E>> extends IOField<T, List<E>>{
	
	private final EnumUniverse<E>          universe;
	private final SizeDescriptor<T>        descriptor;
	private       IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldEnumList(FieldAccessor<T> accessor){
		super(accessor);
		var gt  =accessor.getGenericType(null);
		var etyp=((ParameterizedType)gt).getActualTypeArguments()[0];
		universe=EnumUniverse.of((Class<E>)etyp);
		
		descriptor=SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst)->{
			var siz=arraySize.getValue(ioPool, inst);
			if(siz>0) return byteCount(siz);
			var arr=get(ioPool, inst);
			return byteCount(arr.size());
		});
	}
	
	@Override
	public void init(){
		super.init();
		arraySize=declaringStruct().getFields().requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var enums=get(ioPool, instance);
		new BitOutputStream(dest).writeEnums(universe, enums).close();
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size=arraySize.getValue(ioPool, instance);
		E[] enums;
		try(var s=new BitInputStream(src, (long)universe.bitSize*size)){
			enums=s.readEnums(universe, size);
		}
		set(ioPool, instance, Arrays.asList(enums));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size=arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
	
	private int byteCount(int len){
		return Utils.bitToByte(universe.bitSize*len);
	}
}