package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;
import java.util.OptionalLong;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends IOField.Bit<T, E>{
	
	private final EnumUniverse<E> enumUniverse;
	
	public IOFieldEnum(IFieldAccessor<T> field){
		super(field);
		enumUniverse=EnumUniverse.getUnknown(field.getType());
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
	public boolean instancesEqual(T inst1, T inst2){
		return get(inst1)==get(inst2);
	}
}
