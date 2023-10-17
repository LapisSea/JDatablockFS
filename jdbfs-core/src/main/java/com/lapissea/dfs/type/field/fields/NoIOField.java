package com.lapissea.dfs.type.field.fields;

import com.lapissea.dfs.io.IO;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;

public non-sealed class NoIOField<Inst extends IOInstance<Inst>, ValueType> extends IOField<Inst, ValueType> implements IO.DisabledIO<Inst>{
	
	public NoIOField(FieldAccessor<Inst> accessor, SizeDescriptor<Inst> sizeDescriptor){
		super(accessor, sizeDescriptor);
	}
}
