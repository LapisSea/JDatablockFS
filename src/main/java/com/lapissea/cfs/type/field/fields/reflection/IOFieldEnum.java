package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;

public class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends IOField.Bit<T, E>{
	
	private final EnumUniverse<E>    enumUniverse;
	private final SizeDescriptor<T>  sizeDescriptor;
	private final boolean            nullable;
	private final IONullability.Mode mode;
	
	public IOFieldEnum(IFieldAccessor<T> field){
		super(field);
		
		mode=IOFieldTools.getNullability(field);
		nullable=switch(mode){
			case NULLABLE -> true;
			case NOT_NULL, DEFAULT_IF_NULL -> false;
		};
		
		enumUniverse=EnumUniverse.getUnknown(field.getType());
		sizeDescriptor=new SizeDescriptor.Fixed<>(WordSpace.BIT, enumUniverse.getBitSize(nullable));
		
		if(mode==DEFAULT_IF_NULL&&enumUniverse.isEmpty()){
			throw new MalformedStructLayout(DEFAULT_IF_NULL+" is not supported for empty enums");
		}
	}
	
	@Override
	public E get(T instance){
		E e=super.get(instance);
		if(e==null&&mode==DEFAULT_IF_NULL) e=enumUniverse.get(0);
		return e;
	}
	@Override
	public void set(T instance, E value){
		super.set(instance, switch(mode){
			case NULLABLE, DEFAULT_IF_NULL -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	public void writeBits(BitWriter<?> dest, T instance) throws IOException{
		dest.writeEnum(enumUniverse, get(instance), nullable);
	}
	
	@Override
	public void readBits(BitReader src, T instance) throws IOException{
		set(instance, src.readEnum(enumUniverse, nullable));
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
