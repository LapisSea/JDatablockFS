package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldDuration;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldInlineString;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldInstant;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldLocalDate;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldLocalDateTime;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldLocalTime;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldStringCollection;
import com.lapissea.dfs.utils.IOUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public abstract sealed class IOFieldWrapper<CTyp extends IOInstance<CTyp>, ValueType> extends NullFlagCompanyField<CTyp, ValueType>
	permits IOFieldDuration, IOFieldInlineString, IOFieldInstant, IOFieldLocalDate, IOFieldLocalDateTime, IOFieldLocalTime, IOFieldStringCollection{
	
	private final Supplier<ValueType> defaultValue = this::defaultValue;
	private       int                 fixedSize;
	private       boolean             fixed;
	
	public IOFieldWrapper(FieldAccessor<CTyp> accessor){ super(accessor); }
	
	@Override
	public void init(FieldSet<CTyp> ioFields){
		super.init(ioFields);
		var fSiz = getSizeDescriptor().getFixed(WordSpace.BYTE);
		fixed = fSiz.isPresent();
		fixedSize = Math.toIntExact(fSiz.orElse(0));
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	protected final boolean isFixed(){ return fixed; }
	
	protected abstract ValueType defaultValue();
	
	@Override
	public final ValueType get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, defaultValue);
	}
	@Override
	public final boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public final void set(VarPool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public final void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = get(ioPool, instance);
		if(val == null){
			if(!nullable()) throw new FieldIsNull(this);
			if(fixed){
				IOUtils.zeroFill(dest, fixedSize);
			}
			return;
		}
		writeValue(provider, dest, val);
	}
	
	private ValueType readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.skipExact(fixedSize);
				}
				return null;
			}
		}
		
		return readValue(ioPool, provider, src, instance, genericContext);
	}
	
	@Override
	public final void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var value = readNew(ioPool, provider, src, instance, genericContext);
		set(ioPool, instance, value);
	}
	
	@Override
	public final void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(fixed){
			src.skipExact(fixedSize);
			return;
		}
		
		if(nullable() && getIsNull(ioPool, instance)){
			return;
		}
		
		skipValue(ioPool, provider, src, instance, genericContext);
	}
	
	protected abstract void writeValue(DataProvider provider, ContentWriter dest, ValueType value) throws IOException;
	protected abstract ValueType readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException;
	protected abstract void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException;
}
