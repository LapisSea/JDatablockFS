package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.IUtils;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.FixedStructPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public class IOFieldInlineObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends NullFlagCompanyField<CTyp, ValueType>{
	
	private final SizeDescriptor<CTyp>  descriptor;
	private final StructPipe<ValueType> instancePipe;
	private final boolean               fixed;
	
	public IOFieldInlineObject(FieldAccessor<CTyp> accessor){
		this(accessor, false);
	}
	public IOFieldInlineObject(FieldAccessor<CTyp> accessor, boolean fixed){
		super(accessor);
		this.fixed=fixed;
		
		@SuppressWarnings("unchecked")
		var struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		if(fixed){
			instancePipe=FixedStructPipe.of(struct, STATE_DONE);
		}else instancePipe=StandardStructPipe.of(struct);
		
		var desc=instancePipe.getSizeDescriptor();
		
		var fixedSiz=desc.getFixed();
		if(fixedSiz.isPresent()){
			descriptor=SizeDescriptor.Fixed.of(desc.getWordSpace(), fixedSiz.getAsLong());
		}else{
			descriptor=SizeDescriptor.Unknown.of(
				desc.getWordSpace(),
				nullable()?0:desc.getMin(),
				desc.getMax(),
				(ioPool, prov, inst)->{
					var val=get(null, inst);
					if(val==null){
						if(!nullable()) throw new NullPointerException();
						return 0;
					}
					return desc.calcUnknown(instancePipe.makeIOPool(), prov, val, desc.getWordSpace());
				}
			);
		}
	}
	
	@Override
	public ValueType get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, ()->instancePipe.getType().make());
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public IOField<CTyp, ValueType> maxAsFixedSize(VaryingSizeProvider varyingSizeProvider){
		LogUtil.printlnEr("IOFieldInlineObject no varying size impl");
		return new IOFieldInlineObject<>(getAccessor(), true);
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=get(ioPool, instance);
		if(nullable()){
			if(val==null){
				if(fixed){
					IUtils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		instancePipe.write(provider, dest, val);
	}
	
	private ValueType readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			boolean isNull=getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return null;
			}
		}
		
		return instancePipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readNew(ioPool, provider, src, instance, genericContext));
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(descriptor.getFixed(WordSpace.BYTE))){
			return;
		}
		
		if(nullable()){
			boolean isNull=getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.skipExact((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		instancePipe.skip(provider, src, genericContext);
	}
}
