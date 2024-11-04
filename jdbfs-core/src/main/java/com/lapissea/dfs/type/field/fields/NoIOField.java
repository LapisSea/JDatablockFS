package com.lapissea.dfs.type.field.fields;

import com.lapissea.dfs.io.IO;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;

import java.util.Objects;
import java.util.Set;

public non-sealed class NoIOField<Inst extends IOInstance<Inst>, ValueType> extends IOField<Inst, ValueType> implements IO.DisabledIO<Inst>{
	
	public NoIOField(@NotNull FieldAccessor<Inst> accessor, SizeDescriptor<Inst> sizeDescriptor){
		super(Objects.requireNonNull(accessor), sizeDescriptor);
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Iters.of(
			IOFieldTools.isGeneric(this)? TypeFlag.DYNAMIC : null,
			IOInstance.isInstanceOrSealed(getType())? TypeFlag.IO_INSTANCE : null,
			getType().isEnum() || SupportedPrimitive.isAny(getType())? TypeFlag.PRIMITIVE_OR_ENUM : null
		).nonNulls().toModSet();
	}
}
