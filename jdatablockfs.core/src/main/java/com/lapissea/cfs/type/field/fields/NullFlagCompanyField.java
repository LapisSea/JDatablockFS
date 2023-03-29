package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;

import java.util.List;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class NullFlagCompanyField<T extends IOInstance<T>, Type> extends IOField<T, Type>{
	
	private IOFieldPrimitive.FBoolean<T> isNull;
	
	protected NullFlagCompanyField(FieldAccessor<T> field){
		super(field);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		if(nullable()){
			isNull = fields.requireExactBoolean(IOFieldTools.makeNullFlagName(getAccessor()));
		}
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		
		if(!nullable()) return null;
		
		return List.of(new ValueGeneratorInfo<>(isNull, new ValueGenerator<T, Boolean>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				var isNullRec     = get(ioPool, instance) == null;
				var writtenIsNull = isNull.getValue(ioPool, instance);
				return writtenIsNull != isNullRec;
			}
			@Override
			public Boolean generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				return get(ioPool, instance) == null;
			}
		}));
	}
	
	protected final boolean getIsNull(VarPool<T> ioPool, T instance){
		if(DEBUG_VALIDATION){
			if(!nullable()) throw new RuntimeException("Checking if null on a non nullable field");
		}
		
		return isNull.getValue(ioPool, instance);
	}
	
}
