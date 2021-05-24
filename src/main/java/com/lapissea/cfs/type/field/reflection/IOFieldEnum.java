package com.lapissea.cfs.type.field.reflection;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends IOField.Bit<T, E>{
	
	private final EnumUniverse<E> enumUniverse;
	private final Field           field;
	
	public IOFieldEnum(Field field){
		enumUniverse=EnumUniverse.getUnknown(field.getType());
		this.field=field;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public E get(T instance){
		try{
			return (E)field.get(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void set(T instance, E value){
		try{
			field.set(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	
	@Override
	public long calcSize(T instance){
		return enumUniverse.bitSize;
	}
	@Override
	public OptionalLong getFixedSize(){
		return OptionalLong.of(enumUniverse.bitSize);
	}
	
	@Override
	public void writeBits(BitWriter<?> dest, T instance) throws IOException{
		dest.writeEnum(enumUniverse, get(instance), false);
	}
	
	@Override
	public void readBits(BitReader src, T instance) throws IOException{
		set(instance, src.readEnum(enumUniverse, false));
	}
	
	@Override
	public String getName(){
		return field.getName();
	}
}
