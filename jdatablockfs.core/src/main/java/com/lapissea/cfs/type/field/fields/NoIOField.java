package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.io.IO;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

public class NoIOField<Inst extends IOInstance<Inst>, ValueType> extends IOField<Inst, ValueType> implements IO.DisabledIO<Inst>{
	
	private final SizeDescriptor<Inst> sizeDescriptor;
	
	public NoIOField(FieldAccessor<Inst> accessor, SizeDescriptor<Inst> sizeDescriptor){
		super(accessor);
		this.sizeDescriptor = sizeDescriptor;
	}
	
	@Override
	public SizeDescriptor<Inst> getSizeDescriptor(){
		return sizeDescriptor;
	}
}
