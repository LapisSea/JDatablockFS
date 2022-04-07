package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends IOField.Bit<T, E>{
	
	private final EnumUniverse<E>   enumUniverse;
	private final SizeDescriptor<T> sizeDescriptor;
	
	public IOFieldEnum(FieldAccessor<T> field){
		super(field);
		
		enumUniverse=EnumUniverse.getUnknown(field.getType());
		sizeDescriptor=SizeDescriptor.Fixed.of(WordSpace.BIT, enumUniverse.getBitSize(nullable()));
		
		if(getNullability()==DEFAULT_IF_NULL&&enumUniverse.isEmpty()){
			throw new MalformedStructLayout(DEFAULT_IF_NULL+" is not supported for empty enums");
		}
	}
	
	@Override
	public E get(Struct.Pool<T> ioPool, T instance){
		E e=super.get(ioPool, instance);
		if(e==null&&getNullability()==DEFAULT_IF_NULL) e=enumUniverse.get(0);
		return e;
	}
	@Override
	public void set(Struct.Pool<T> ioPool, T instance, E value){
		super.set(ioPool, instance, switch(getNullability()){
			case NULLABLE, DEFAULT_IF_NULL -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	public void writeBits(Struct.Pool<T> ioPool, BitWriter<?> dest, T instance) throws IOException{
		dest.writeEnum(enumUniverse, get(ioPool, instance), nullable());
	}
	
	@Override
	public void readBits(Struct.Pool<T> ioPool, BitReader src, T instance) throws IOException{
		set(ioPool, instance, src.readEnum(enumUniverse, nullable()));
	}
	
	@Override
	public void skipReadBits(BitReader src, T instance) throws IOException{
		src.skipEnum(enumUniverse, nullable());
	}
	
	@Override
	public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
		return get(ioPool1, inst1)==get(ioPool2, inst2);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	
}
