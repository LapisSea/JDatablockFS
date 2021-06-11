package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends IOField.Bit<T, E>{
	
	private final EnumUniverse<E>   enumUniverse;
	private final SizeDescriptor<T> sizeDescriptor;
	
	public IOFieldEnum(IFieldAccessor<T> field){
		super(field);
		enumUniverse=EnumUniverse.getUnknown(field.getType());
		sizeDescriptor=new SizeDescriptor.Fixed<>(WordSpace.BIT, enumUniverse.bitSize);
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
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
}
