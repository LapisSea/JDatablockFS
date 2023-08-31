package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldBooleanArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldByteArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldDynamicInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnumCollection;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldFloatArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldInlineSealedObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldIntArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.cfs.type.field.fields.reflection.InstanceCollection;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldDuration;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldIndexedString;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldInlineString;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldInstant;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalDate;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalDateTime;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldLocalTime;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldStringCollection;

import java.util.List;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract sealed class NullFlagCompanyField<T extends IOInstance<T>, Type> extends IOField<T, Type>
	permits IOFieldBooleanArray, IOFieldByteArray, IOFieldDynamicInlineObject, IOFieldEnumCollection, IOFieldFloatArray, IOFieldInlineObject, IOFieldInlineSealedObject, IOFieldIntArray, InstanceCollection.InlineField, IOFieldDuration, IOFieldIndexedString, IOFieldInlineString, IOFieldInstant, IOFieldLocalDate, IOFieldLocalDateTime, IOFieldLocalTime, IOFieldStringCollection{
	
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
		if(!nullable()) return super.getGenerators();
		
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(isNull, new ValueGenerator<T, Boolean>(){
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
