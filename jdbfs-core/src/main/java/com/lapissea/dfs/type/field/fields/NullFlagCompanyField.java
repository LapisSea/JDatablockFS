package com.lapissea.dfs.type.field.fields;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.fields.reflection.*;
import com.lapissea.jorth.CodeArg;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.util.List;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract sealed class NullFlagCompanyField<T extends IOInstance<T>, Type> extends IOField<T, Type>
	permits IOFieldBooleanArray, IOFieldByteArray, IOFieldByteBuffer, IOFieldDirectType, IOFieldDynamicInlineObject, IOFieldEnumCollection, IOFieldFloatArray, IOFieldInlineObject, IOFieldInlineSealedObject, IOFieldIntArray, IOFieldIntegerArray, IOFieldWrapper, InstanceCollection.InlineField{
	
	protected IOFieldPrimitive.FBoolean<T> isNull;
	
	protected NullFlagCompanyField(FieldAccessor<T> accessor){
		super(accessor);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		if(nullable()){
			isNull = fields.requireExactBoolean(FieldNames.nullFlag(getAccessor()));
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
			badCallCheck();
		}
		return isNull.getValue(ioPool, instance);
	}
	private void badCallCheck(){
		if(!nullable()) throw new RuntimeException("Checking if null on a non nullable field");
	}
	
	public void injectReadFieldNullable(CodeBlock body, SpecializedGenerator.AccessMap accessMap, CodeArg skip, CodeArg readParsed) throws MalformedJorth, UnsupportedCodeGenType{
		accessMap.set(getAccessor(), body, bod -> {
			if(nullable()){
				accessMap.get(isNull, body);
				body.ifTrue(b -> {
					if(skip != null){
						skip.accept(b);
					}
					b.nullVal(getType());
				}).elseRun(readParsed);
			}else{
				readParsed.accept(bod);
			}
		});
	}
}
