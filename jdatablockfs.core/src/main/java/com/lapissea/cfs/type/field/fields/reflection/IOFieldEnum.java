package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.BitField;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends BitField<T, E>{
	
	private final EnumUniverse<E>   enumUniverse;
	private final SizeDescriptor<T> sizeDescriptor;
	
	public IOFieldEnum(FieldAccessor<T> field){
		super(field);
		
		enumUniverse=EnumUniverse.getUnknown(field.getType());
		sizeDescriptor=SizeDescriptor.Fixed.of(WordSpace.BIT, enumUniverse.getBitSize(nullable()));
		
		if(getNullability()==DEFAULT_IF_NULL&&enumUniverse.isEmpty()){
			throw new MalformedStruct(DEFAULT_IF_NULL+" is not supported for empty enums");
		}
	}
	
	@Override
	public E get(VarPool<T> ioPool, T instance){
		E e=super.get(ioPool, instance);
		if(e==null){
			switch(getNullability()){
				case NOT_NULL -> throw new FieldIsNullException(this);
				case DEFAULT_IF_NULL -> e=enumUniverse.get(0);
				case NULLABLE -> {}
				case null -> throw new NullPointerException();
			}
		}
		return e;
	}
	@Override
	public void set(VarPool<T> ioPool, T instance, E value){
		super.set(ioPool, instance, switch(getNullability()){
			case NULLABLE, DEFAULT_IF_NULL -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	public void writeBits(VarPool<T> ioPool, BitWriter<?> dest, T instance) throws IOException{
		dest.writeEnum(enumUniverse, get(ioPool, instance), nullable());
	}
	
	@Override
	public void readBits(VarPool<T> ioPool, BitReader src, T instance) throws IOException{
		set(ioPool, instance, src.readEnum(enumUniverse, nullable()));
	}
	
	@Override
	public void skipReadBits(BitReader src, T instance) throws IOException{
		src.skipEnum(enumUniverse, nullable());
	}
	
	@Override
	public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		return get(ioPool1, inst1)==get(ioPool2, inst2);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	
}
