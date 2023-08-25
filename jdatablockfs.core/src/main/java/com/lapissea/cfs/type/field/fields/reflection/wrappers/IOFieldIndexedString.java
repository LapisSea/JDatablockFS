package com.lapissea.cfs.type.field.fields.reflection.wrappers;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOIndexed;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import static com.lapissea.cfs.objects.NumberSize.INT;
import static com.lapissea.cfs.objects.NumberSize.VOID;

public final class IOFieldIndexedString<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, String>{
	
	private final boolean                                     forceFixed;
	private final VaryingSize                                 maxSize;
	private       BiFunction<VarPool<CTyp>, CTyp, NumberSize> dynamicSize;
	
	public IOFieldIndexedString(FieldAccessor<CTyp> accessor, VaryingSize maxSize){
		super(accessor);
		assert accessor.hasAnnotation(IOIndexed.class);
		
		this.forceFixed = maxSize != null;
		this.maxSize = maxSize != null? maxSize : new VaryingSize(INT, -1);
	}
	
	private NumberSize getSafeSize(VarPool<CTyp> ioPool, CTyp instance, NumberSize neededNum){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.safeSize(neededNum);
	}
	private NumberSize getSize(VarPool<CTyp> ioPool, CTyp instance){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.size;
	}
	
	@Override
	public void init(FieldSet<CTyp> ioFields){
		super.init(ioFields);
		
		var fieldOpt = forceFixed? Optional.<IOField<CTyp, NumberSize>>empty() : IOFieldTools.getDynamicSize(getAccessor());
		if(fieldOpt.isPresent()){
			var numSizeField = fieldOpt.get();
			dynamicSize = (ioPool, instance) -> {
				var size = numSizeField.get(ioPool, instance);
				if(size.greaterThan(INT)){
					throw new IllegalStateException(size + " is not an allowed size at " + this + " with dynamic size " + numSizeField);
				}
				return size;
			};
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				VOID,
				Optional.of(NumberSize.INT),
				numSizeField.getAccessor())
			);
		}else{
			initSizeDescriptor(SizeDescriptor.Fixed.of(maxSize.size.bytes));
		}
	}
	
	@Override
	public IOField<CTyp, String> maxAsFixedSize(VaryingSize.Provider varProvider){
		String uid  = sizeDescriptorSafe() instanceof SizeDescriptor.UnknownNum<CTyp> num? num.getAccessor().getName() : null;
		var    size = varProvider.provide(INT, uid, false);
		if(forceFixed && maxSize == size) return this;
		return new IOFieldIndexedString<>(getAccessor(), size);
	}
	
	@Override
	public String get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> "");
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, String value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable() && getIsNull(ioPool, instance)) return;
		var val = get(ioPool, instance);
		if(val == null && !nullable()){
			throw new NullPointerException();
		}
		var id   = provider.getDataPool().toId(String.class, val, true);
		var size = getSafeSize(ioPool, instance, NumberSize.bySize(id));
		size.writeInt(dest, id);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		String text;
		if(nullable() && getIsNull(ioPool, instance)) text = null;
		else{
			var size = getSize(ioPool, instance);
			var id   = size.readInt(src);
			text = provider.getDataPool().fromId(String.class, id);
		}
		
		set(ioPool, instance, text);
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)) return;
		}
		AutoText.PIPE.skip(provider, src, genericContext);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
}
