package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Objects;

public class IOFieldInlineObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends IOField.NullFlagCompany<CTyp, ValueType>{
	
	private final SizeDescriptor<CTyp>  descriptor;
	private final StructPipe<ValueType> instancePipe;
	private final boolean               fixed;
	
	public IOFieldInlineObject(FieldAccessor<CTyp> accessor){
		this(accessor, false);
	}
	public IOFieldInlineObject(FieldAccessor<CTyp> accessor, boolean fixed){
		super(accessor);
		this.fixed=fixed;
		
		var struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		instancePipe=fixed?FixedContiguousStructPipe.of(struct):ContiguousStructPipe.of(struct);
		
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
					return desc.calcUnknown(instancePipe.makeIOPool(), prov, val);
				}
			);
		}
	}
	
	@Override
	public ValueType get(Struct.Pool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, ()->instancePipe.getType().requireEmptyConstructor().get());
	}
	
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public IOField<CTyp, ValueType> implMaxAsFixedSize(){
		return new IOFieldInlineObject<>(getAccessor(), true);
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=get(ioPool, instance);
		if(nullable()){
			if(val==null){
				if(fixed){
					Utils.zeroFill(dest::write, (int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return;
			}
		}
		instancePipe.write(provider, dest, val);
	}
	
	private ValueType readNew(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			boolean isNull=getIsNull(ioPool, instance);
			if(isNull){
				if(fixed){
					src.readInts1((int)getSizeDescriptor().requireFixed(WordSpace.BYTE));
				}
				return null;
			}
		}
		
		return instancePipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readNew(ioPool, provider, src, instance, genericContext));
	}
	
	@Override
	public void skipRead(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(descriptor.getFixed(WordSpace.BYTE))){
			return;
		}
		
		readNew(ioPool, provider, src, instance, genericContext);
	}
}
